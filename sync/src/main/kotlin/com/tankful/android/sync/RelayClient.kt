package com.tankful.android.sync

import com.tankful.android.json.canonicalJson
import com.tankful.android.model.TANKFUL_SNAPSHOT_KIND
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
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

private const val FRAME_CAP = 1_000_000
private const val MESSAGE_CAP = 300
private const val CLIENT_FRAME_CAP = 262_144
private const val HEADER_CAP = 4_096
private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

@Singleton
class RelayClient @Inject constructor(
    private val http: OkHttpClient,
) {
    suspend fun publish(url: String, allowCleartext: Set<String>, eventJson: String): RelayAttempt {
        val eventId = eventText(eventJson, "id") ?: return RelayAttempt(url, false, true)
        val sent = """["EVENT",$eventJson]"""
        val messages = exchange(url, allowCleartext, sent) { text -> relayAccepted(text, eventId) }
        val ack = messages.any { line -> relayAccepted(line, eventId) }
        return RelayAttempt(url, ack, messages.isEmpty())
    }

    suspend fun fetch(url: String, allowCleartext: Set<String>, request: PageRequest, author: String): List<RelayEvent> {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(TANKFUL_SNAPSHOT_KIND)) }
            putJsonArray("authors") { add(JsonPrimitive(author)) }
            put("limit", request.limit.coerceIn(1, 200))
            put("since", request.since)
            val until = request.until
            if (until != null) put("until", until)
        }
        val req = """["REQ","tankful",$filter]"""
        val messages = exchange(url, allowCleartext, req) { text -> relayPageEnded(text, "tankful") }
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
                    if (text.length > FRAME_CAP || messages.size >= MESSAGE_CAP) {
                        webSocket.close(1009, null)
                        if (cont.isActive) cont.resume(messages.toList())
                        return
                    }
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

internal fun relayAccepted(line: String, eventId: String): Boolean {
    val root = runCatching { canonicalJson.parseToJsonElement(line) }.getOrNull() as? JsonArray ?: return false
    if (root.size < 3) return false
    if (root[0].jsonPrimitive.contentOrNull != "OK") return false
    if (root[1].jsonPrimitive.contentOrNull != eventId) return false
    val flag = root[2]
    return flag is JsonPrimitive && flag.booleanOrNull == true
}

internal fun relayPageEnded(line: String, subscription: String): Boolean {
    val root = runCatching { canonicalJson.parseToJsonElement(line) }.getOrNull() as? JsonArray ?: return false
    if (root.size < 2) return false
    if (root[0].jsonPrimitive.contentOrNull != "EOSE") return false
    return root[1].jsonPrimitive.contentOrNull == subscription
}

private fun parseEvent(line: String): RelayEvent? {
    val root = runCatching { canonicalJson.parseToJsonElement(line) }.getOrNull() as? JsonArray ?: return null
    if (root.size < 3) return null
    if (root[0].jsonPrimitive.contentOrNull != "EVENT") return null
    val event = runCatching { root[2].jsonObject }.getOrNull() ?: return null
    val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val created = event["created_at"]?.jsonPrimitive?.longOrNull ?: return null
    val author = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null
    val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return null
    if (content.length > FRAME_CAP) return null
    return RelayEvent(id, created, author, content)
}

internal fun rawWebSocket(url: String, payload: String, stop: (String) -> Boolean): List<String> {
    val frame = clientFrame(payload) ?: return emptyList()
    val uri = java.net.URI(url)
    val host = uri.host ?: return emptyList()
    if (host.isBlank() || uri.userInfo != null) return emptyList()
    val port = if (uri.port == -1) 80 else uri.port
    val socket = Socket()
    socket.connect(java.net.InetSocketAddress(host, port), 15_000)
    socket.soTimeout = 15_000
    socket.use { open ->
        val output = open.getOutputStream()
        val key = Base64.getEncoder().encodeToString(java.security.SecureRandom().generateSeed(16))
        val path = uri.rawPath.ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val handshake = "GET $path$query HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(handshake.toByteArray(Charsets.US_ASCII))
        output.write(frame)
        output.flush()
        val input = open.getInputStream()
        val header = readHeader(input) ?: return emptyList()
        if (!header.contains(" 101 ")) return emptyList()
        if (!headerAccepts(header, key)) return emptyList()
        val messages = mutableListOf<String>()
        while (messages.size < MESSAGE_CAP) {
            val text = readText(input) ?: break
            messages += text
            if (stop(text)) break
        }
        return messages
    }
}

internal fun headerAccepts(header: String, key: String): Boolean {
    val expect = websocketAccept(key)
    return header.lineSequence().any { line ->
        val parts = line.split(":", limit = 2)
        parts.size == 2 && parts[0].trim().equals("Sec-WebSocket-Accept", ignoreCase = true) && parts[1].trim() == expect
    }
}

internal fun websocketAccept(key: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(digest)
}

internal fun clientFrame(text: String): ByteArray? {
    val data = text.toByteArray(Charsets.UTF_8)
    if (data.size > CLIENT_FRAME_CAP) return null
    val mask = java.security.SecureRandom().generateSeed(4)
    val header = ArrayList<Byte>(14)
    header += 0x81.toByte()
    when {
        data.size < 126 -> header += (0x80 or data.size).toByte()
        data.size <= 65535 -> {
            header += (0x80 or 126).toByte()
            header += (data.size shr 8).toByte()
            header += (data.size and 0xff).toByte()
        }
        else -> {
            header += (0x80 or 127).toByte()
            val size = data.size.toLong()
            for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                header += ((size ushr shift) and 0xff).toByte()
            }
        }
    }
    header += mask.toList()
    val masked = ByteArray(data.size) { index -> (data[index].toInt() xor mask[index % 4].toInt()).toByte() }
    return header.toByteArray() + masked
}

private fun readHeader(input: InputStream): String? {
    val bytes = ArrayList<Byte>(256)
    while (bytes.size < HEADER_CAP) {
        val value = input.read()
        if (value < 0) return null
        bytes += value.toByte()
        val size = bytes.size
        if (size < 4) continue
        val end = bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte()
        if (!end || bytes[size - 2] != '\r'.code.toByte() || bytes[size - 1] != '\n'.code.toByte()) continue
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }
    return null
}

private fun readText(input: InputStream): String? {
    val first = input.read()
    val second = input.read()
    if (first < 0 || second < 0) return null
    if (first and 0x80 == 0 || first and 0x0f != 0x1) return null
    if (second and 0x80 != 0) return null
    var length = second and 0x7f
    if (length == 127) return null
    if (length == 126) {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) return null
        length = (hi shl 8) or lo
    }
    if (length > FRAME_CAP) return null
    val body = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val count = input.read(body, filled, length - filled)
        if (count < 0) return null
        filled += count
    }
    return body.toString(Charsets.UTF_8)
}
