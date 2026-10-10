package com.tankful.android.sync

import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun nip01Id(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
    val tagJson = JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) })
    val body = JsonArray(
        listOf(
            JsonPrimitive(0),
            JsonPrimitive(pubkey),
            JsonPrimitive(createdAt),
            JsonPrimitive(kind),
            tagJson,
            JsonPrimitive(content),
        ),
    )
    return sha256Hex(canonicalJson.encodeToString(JsonElement.serializer(), body))
}

/** Local BIP340 check. A thrown verifier is a failed signature, never a pass. */
fun verifySignedEvent(json: String): Boolean {
    val event = runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return false
    val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return false
    val pubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return false
    val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: return false
    val kind = event["kind"]?.jsonPrimitive?.longOrNull?.toInt() ?: return false
    val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return false
    val sig = event["sig"]?.jsonPrimitive?.contentOrNull ?: return false
    val tags = event["tags"]?.jsonArray?.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } } ?: return false
    val computed = nip01Id(pubkey, createdAt, kind, tags, content)
    if (!computed.equals(id, ignoreCase = true)) return false
    return runCatching {
        Secp256k1.get().verifySchnorr(Hex.decode(sig), Hex.decode(id), Hex.decode(pubkey))
    }.getOrDefault(false)
}

fun eventText(json: String, field: String): String? {
    val event = runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    return event[field]?.jsonPrimitive?.contentOrNull
}

fun eventKind(json: String): Int? {
    val event = runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    return event["kind"]?.jsonPrimitive?.longOrNull?.toInt()
}

/** Schnorr-sign an unsigned NIP-01 event. The secret is not copied into the result. */
fun signLocalEvent(secret: ByteArray, unsigned: String?): String? {
    if (secret.size != 32 || unsigned.isNullOrBlank()) return null
    val event = runCatching { canonicalJson.parseToJsonElement(unsigned).jsonObject }.getOrNull() ?: return null
    val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return null
    if (id.length != 64) return null
    val aux = ByteArray(32)
    java.security.SecureRandom().nextBytes(aux)
    val sig = try {
        Secp256k1.get().signSchnorr(Hex.decode(id), secret, aux)
    } catch (ignored: Exception) {
        return null
    } finally {
        aux.fill(0)
    }
    val signed = linkedMapOf<String, JsonElement>()
    for (name in listOf("id", "pubkey", "created_at", "kind", "tags", "content")) {
        signed[name] = event[name] ?: return null
    }
    signed["sig"] = JsonPrimitive(Hex.encode(sig))
    return canonicalJson.encodeToString(JsonObject(signed))
}
