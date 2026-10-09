package com.tankful.android.sync

import com.tankful.android.model.TANKFUL_SNAPSHOT_KIND
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

@Singleton
class RelayClient @Inject constructor(
    private val http: OkHttpClient,
) {
    suspend fun publish(url: String, allowCleartext: Set<String>, eventJson: String): RelayAttempt {
        val sent = """["EVENT",$eventJson]"""
        val messages = exchange(url, allowCleartext, sent) { text -> text.contains("\"OK\"") }
        val ack = messages.any { line -> line.contains("\"OK\"") && line.contains("true") }
        val failed = messages.isEmpty()
        return RelayAttempt(url, ack && !failed, failed)
    }

    suspend fun fetch(url: String, allowCleartext: Set<String>, request: PageRequest, author: String): List<RelayEvent> {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(TANKFUL_SNAPSHOT_KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(author)) }
            put("limit", request.limit)
            put("since", request.since)
            val until = request.until
            if (until != null) put("until", until)
        }
        val req = """["REQ","tankful",$filter]"""
        val messages = exchange(url, allowCleartext, req) { text -> text.contains("\"EOSE\"") }
        return messages.mapNotNull { parseEvent(it) }
    }

    private suspend fun exchange(
        url: String,
        allowCleartext: Set<String>,
        payload: String,
        stop: (String) -> Boolean,
    ): List<String> = withContext(Dispatchers.IO) {
        if (url.startsWith("ws://")) {
            if (!rawCleartextSocketAllowed(url, allowCleartext)) return@withContext emptyList()
            return@withContext runCatching { rawWebSocket(url, payload, stop) }.getOrDefault(emptyList())
        }
        if (!url.startsWith("wss://")) return@withContext emptyList()
        withTimeoutOrNull(20_000) { socketMessages(url, payload, stop) }.orEmpty()
    }

    private suspend fun socketMessages(url: String, payload: String, stop: (String) -> Boolean): List<String> {
        return suspendCancellableCoroutine { cont ->
            val messages = mutableListOf<String>()
            val request = Request.Builder().url(url).build()
            val socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages += text
                    if (!stop(text)) return
                    webSocket.close(1000, null)
                    if (cont.isActive) cont.resume(messages.toList())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resume(messages.toList())
                }
            })
            socket.send(payload)
            cont.invokeOnCancellation { socket.cancel() }
        }
    }
}

data class RelayEvent(val id: String, val createdAt: Long, val author: String, val content: String)

private fun parseEvent(line: String): RelayEvent? {
    val root = runCatching { com.tankful.android.json.canonicalJson.parseToJsonElement(line) }.getOrNull() as? JsonArray
    if (root == null || root.size < 3) return null
    if (root[0].jsonPrimitive.contentOrNull != "EVENT") return null
    val event = root[2].jsonObject
    val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val created = event["created_at"]?.jsonPrimitive?.longOrNull ?: return null
    val author = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null
    val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return null
    return RelayEvent(id, created, author, content)
}

internal fun rawWebSocket(url: String, payload: String, stop: (String) -> Boolean): List<String> {
    val uri = java.net.URI(url)
    val port = if (uri.port == -1) 80 else uri.port
    val socket = java.net.Socket()
    socket.connect(java.net.InetSocketAddress(uri.host, port), 15_000)
    socket.soTimeout = 15_000
    socket.use { open ->
        val output = open.getOutputStream()
        val key = java.util.Base64.getEncoder().encodeToString(java.security.SecureRandom().generateSeed(16))
        val path = uri.rawPath.ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val handshake = "GET $path$query HTTP/1.1\r\nHost: ${uri.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(handshake.toByteArray(Charsets.US_ASCII))
        output.write(clientFrame(payload))
        output.flush()
        val input = open.getInputStream()
        val status = readHeader(input)
        if (!status.contains("101")) return emptyList()
        val messages = mutableListOf<String>()
        while (messages.size < 300) {
            val text = readText(input) ?: break
            messages += text
            if (stop(text)) break
        }
        return messages
    }
}

private fun clientFrame(text: String): ByteArray {
    val data = text.toByteArray(Charsets.UTF_8)
    val mask = java.security.SecureRandom().generateSeed(4)
    val header = mutableListOf<Byte>()
    header += 0x81.toByte()
    if (data.size < 126) {
        header += (0x80 or data.size).toByte()
    } else {
        header += (0x80 or 126).toByte()
        header += (data.size shr 8).toByte()
        header += (data.size and 0xff).toByte()
    }
    header += mask.toList()
    val masked = ByteArray(data.size) { index -> (data[index].toInt() xor mask[index % 4].toInt()).toByte() }
    return header.toByteArray() + masked
}

private fun readHeader(input: java.io.InputStream): String {
    val buffer = ByteArray(512)
    val read = input.read(buffer)
    if (read <= 0) return ""
    return buffer.decodeToString(0, read)
}

private fun readText(input: java.io.InputStream): String? {
    val first = input.read()
    val second = input.read()
    if (first < 0 || second < 0) return null
    var length = second and 0x7f
    if (length == 126) {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) return null
        length = (hi shl 8) or lo
    }
    if (length > 1_000_000) return null
    val body = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val count = input.read(body, filled, length - filled)
        if (count < 0) return null
        filled += count
    }
    return body.toString(Charsets.UTF_8)
}
