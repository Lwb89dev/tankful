package com.tankful.android.sync

import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

private const val MIN_PLAINTEXT = 1
private const val MAX_PLAINTEXT = 16_384
private const val MIN_PAYLOAD_CHARS = 132
private const val MAX_PAYLOAD_CHARS = 24_000

/** NIP-44 version 2 payload: base64, version byte 2, long enough to hold nonce and mac. */
fun nip44Wire(payload: String): Boolean {
    if (payload.length < MIN_PAYLOAD_CHARS || payload.length > MAX_PAYLOAD_CHARS) return false
    if (payload[0] == '#') return false
    val data = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return false
    if (data.size < 99) return false
    return data[0] == 2.toByte()
}

fun nip44Encrypt(secret: ByteArray, recipientXOnlyHex: String, plaintext: String): String? {
    val nonce = ByteArray(32)
    SecureRandom().nextBytes(nonce)
    return nip44Encrypt(secret, recipientXOnlyHex, plaintext, nonce)
}

internal fun nip44Encrypt(
    secret: ByteArray,
    recipientXOnlyHex: String,
    plaintext: String,
    nonce: ByteArray,
): String? {
    if (nonce.size != 32) return null
    val plain = plaintext.toByteArray(Charsets.UTF_8)
    if (plain.size !in MIN_PLAINTEXT..MAX_PLAINTEXT) return null
    val conversation = conversationKey(secret, recipientXOnlyHex) ?: return null
    try {
        return encrypt(plain, conversation, nonce)
    } finally {
        conversation.fill(0)
    }
}

fun nip44Decrypt(secret: ByteArray, senderXOnlyHex: String, payload: String): String? {
    if (!nip44Wire(payload)) return null
    val conversation = conversationKey(secret, senderXOnlyHex) ?: return null
    try {
        val plain = decrypt(payload, conversation) ?: return null
        return plain.toString(Charsets.UTF_8)
    } finally {
        conversation.fill(0)
    }
}

internal fun conversationKey(secret: ByteArray, xOnlyHex: String): ByteArray? {
    if (secret.size != 32 || xOnlyHex.length != 64) return null
    val xOnly = runCatching { Hex.decode(xOnlyHex) }.getOrNull() ?: return null
    if (xOnly.size != 32) return null
    val secp = Secp256k1.get()
    if (!secp.secKeyVerify(secret)) return null
    val even = ByteArray(33)
    even[0] = 2
    xOnly.copyInto(even, 1)
    val sharedX = runCatching {
        val point = secp.pubkeyParse(even)
        val multiplied = secp.pubKeyTweakMul(point, secret)
        secp.pubKeyCompress(multiplied).copyOfRange(1, 33)
    }.getOrNull() ?: return null
    return hkdfExtract(sharedX, "nip44-v2".toByteArray(Charsets.UTF_8))
}

private fun encrypt(plain: ByteArray, conversation: ByteArray, nonce: ByteArray): String? {
    val keys = messageKeys(conversation, nonce) ?: return null
    try {
        val padded = pad(plain) ?: return null
        val ciphertext = chaCha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = hmac(keys.hmacKey, nonce, ciphertext)
        val payload = ByteArray(1 + nonce.size + ciphertext.size + mac.size)
        payload[0] = 2
        nonce.copyInto(payload, 1)
        ciphertext.copyInto(payload, 1 + nonce.size)
        mac.copyInto(payload, 1 + nonce.size + ciphertext.size)
        return Base64.getEncoder().encodeToString(payload)
    } finally {
        keys.wipe()
    }
}

private fun decrypt(payload: String, conversation: ByteArray): ByteArray? {
    val data = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
    if (data.size < 99 || data[0] != 2.toByte()) return null
    val nonce = data.copyOfRange(1, 33)
    val mac = data.copyOfRange(data.size - 32, data.size)
    val ciphertext = data.copyOfRange(33, data.size - 32)
    val keys = messageKeys(conversation, nonce) ?: return null
    try {
        val calculated = hmac(keys.hmacKey, nonce, ciphertext)
        if (!equalTime(calculated, mac)) return null
        val padded = chaCha20(keys.chachaKey, keys.chachaNonce, ciphertext)
        return unpad(padded)
    } finally {
        keys.wipe()
    }
}

private class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray) {
    fun wipe() {
        chachaKey.fill(0)
        chachaNonce.fill(0)
        hmacKey.fill(0)
    }
}

private fun messageKeys(conversation: ByteArray, nonce: ByteArray): MessageKeys? {
    if (conversation.size != 32 || nonce.size != 32) return null
    val expanded = hkdfExpand(conversation, nonce, 76)
    return MessageKeys(
        expanded.copyOfRange(0, 32),
        expanded.copyOfRange(32, 44),
        expanded.copyOfRange(44, 76),
    )
}

private fun pad(plain: ByteArray): ByteArray? {
    val length = plain.size
    if (length !in MIN_PLAINTEXT..MAX_PLAINTEXT) return null
    val prefix = 2
    val out = ByteArray(prefix + paddedLength(length))
    out[0] = (length ushr 8).toByte()
    out[1] = (length and 0xff).toByte()
    plain.copyInto(out, prefix)
    return out
}

private fun unpad(padded: ByteArray): ByteArray? {
    if (padded.size < 34) return null
    val first = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
    if (first == 0) return null
    if (first !in MIN_PLAINTEXT..MAX_PLAINTEXT) return null
    if (padded.size != 2 + paddedLength(first)) return null
    return padded.copyOfRange(2, 2 + first)
}

internal fun paddedLength(unpadded: Int): Int {
    if (unpadded <= 32) return 32
    val log = 31 - Integer.numberOfLeadingZeros(unpadded - 1)
    val nextPower = 1 shl (log + 1)
    val chunk = if (nextPower <= 256) 32 else nextPower / 8
    return chunk * (((unpadded - 1) / chunk) + 1)
}

private fun hkdfExtract(ikm: ByteArray, salt: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    return mac.doFinal(ikm)
}

private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        mac.reset()
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        val block = mac.doFinal()
        val count = minOf(block.size, length - offset)
        block.copyInto(out, offset, 0, count)
        previous = block
        offset += count
        counter += 1
    }
    return out
}

private fun hmac(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(nonce)
    mac.update(ciphertext)
    return mac.doFinal()
}

private fun equalTime(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var diff = 0
    for (index in left.indices) diff = diff or (left[index].toInt() xor right[index].toInt())
    return diff == 0
}

/** ChaCha20, IETF nonce, counter starts at 0. RFC 8439. */
private fun chaCha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    var offset = 0
    var counter = 0
    while (offset < data.size) {
        val block = chaChaBlock(key, nonce, counter)
        val count = minOf(64, data.size - offset)
        for (index in 0 until count) out[offset + index] = data[offset + index] xor block[index]
        offset += count
        counter += 1
    }
    return out
}

private fun chaChaBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
    val state = IntArray(16)
    state[0] = 0x61707865
    state[1] = 0x3320646e
    state[2] = 0x79622d32
    state[3] = 0x6b206574
    for (index in 0 until 8) state[4 + index] = little(key, index * 4)
    state[12] = counter
    state[13] = little(nonce, 0)
    state[14] = little(nonce, 4)
    state[15] = little(nonce, 8)
    val working = state.copyOf()
    repeat(10) {
        quarter(working, 0, 4, 8, 12)
        quarter(working, 1, 5, 9, 13)
        quarter(working, 2, 6, 10, 14)
        quarter(working, 3, 7, 11, 15)
        quarter(working, 0, 5, 10, 15)
        quarter(working, 1, 6, 11, 12)
        quarter(working, 2, 7, 8, 13)
        quarter(working, 3, 4, 9, 14)
    }
    val out = ByteArray(64)
    for (index in working.indices) {
        val word = working[index] + state[index]
        out[index * 4] = word.toByte()
        out[index * 4 + 1] = (word ushr 8).toByte()
        out[index * 4 + 2] = (word ushr 16).toByte()
        out[index * 4 + 3] = (word ushr 24).toByte()
    }
    return out
}

private fun quarter(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
    state[a] += state[b]
    state[d] = rotl(state[d] xor state[a], 16)
    state[c] += state[d]
    state[b] = rotl(state[b] xor state[c], 12)
    state[a] += state[b]
    state[d] = rotl(state[d] xor state[a], 8)
    state[c] += state[d]
    state[b] = rotl(state[b] xor state[c], 7)
}

private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

private fun little(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
