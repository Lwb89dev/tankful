package com.tankful.android.sync

import com.tankful.android.json.canonicalJson
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class NostrProfile(val name: String, val picture: String?)

fun readNostrProfile(json: String): NostrProfile? {
    val obj = profileObject(json) ?: return null
    val display = textOf(obj, "display_name")
    val name = textOf(obj, "name")
    val chosen = display.ifBlank { name }.take(80)
    if (chosen.isBlank()) return null
    return NostrProfile(chosen, httpsPicture(textOf(obj, "picture")))
}

fun rawPictureField(json: String): String {
    val obj = profileObject(json) ?: return ""
    return textOf(obj, "picture")
}

private fun profileObject(json: String): JsonObject? {
    return runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull()
}

fun httpsPicture(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    if (url.length !in 12..2048 || !url.startsWith("https://", ignoreCase = true)) return null
    if (url.any { it.isWhitespace() || it == '@' }) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.userInfo != null || uri.host.isNullOrBlank()) return null
    return url
}

private fun textOf(obj: JsonObject, name: String): String {
    return runCatching { obj[name]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim().orEmpty()
}
