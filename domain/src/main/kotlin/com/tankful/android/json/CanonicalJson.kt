package com.tankful.android.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalSerializationApi::class)
val canonicalJson: Json = Json {
    prettyPrint = false
    explicitNulls = false
    encodeDefaults = true
}

private val CANONICAL_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun Instant.toCanonicalString(): String {
    return CANONICAL_TIME.format(truncatedTo(ChronoUnit.MILLIS))
}

fun sha256Hex(utf8: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(utf8.toByteArray(Charsets.UTF_8))
    val builder = StringBuilder(digest.size * 2)
    for (byte in digest) {
        builder.append("%02x".format(byte))
    }
    return builder.toString()
}
