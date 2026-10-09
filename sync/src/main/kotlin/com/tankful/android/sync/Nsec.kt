package com.tankful.android.sync

import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1

/** Package marker for a key typed on this phone. It is not an Amber package name. */
const val LOCAL_NSEC_SIGNER = "local-nsec"

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

/** NIP-19 nsec, or null. The caller wipes the returned bytes after use. */
fun decodeNsec(raw: String): ByteArray? {
    val text = raw.trim().lowercase()
    val sep = text.lastIndexOf('1')
    if (sep < 1 || text.substring(0, sep) != "nsec") return null
    if (text.length > 90) return null
    val data = ArrayList<Int>(text.length - sep)
    for (index in sep + 1 until text.length) {
        val value = BECH32_CHARSET.indexOf(text[index])
        if (value < 0) return null
        data.add(value)
    }
    if (data.size <= 6) return null
    if (polymod(hrpExpand("nsec") + data) != 1) return null
    return convertBits(data.subList(0, data.size - 6))
}

/** BIP340 x-only public key, hex. Null when the secret is not a valid secp256k1 key. */
fun xOnlyPubkeyHex(secret: ByteArray): String? {
    if (secret.size != 32) return null
    return runCatching {
        val secp = Secp256k1.get()
        if (!secp.secKeyVerify(secret)) return null
        val compressed = secp.pubKeyCompress(secp.pubkeyCreate(secret))
        if (compressed.size != 33) return null
        Hex.encode(compressed.copyOfRange(1, 33))
    }.getOrNull()
}

private fun polymod(values: List<Int>): Int {
    var chk = 1
    for (value in values) {
        val top = chk ushr 25
        chk = ((chk and 0x1ffffff) shl 5) xor value
        for (index in BECH32_GEN.indices) {
            if (((top ushr index) and 1) == 1) chk = chk xor BECH32_GEN[index]
        }
    }
    return chk
}

private fun hrpExpand(hrp: String): List<Int> {
    val high = hrp.map { it.code shr 5 }
    val low = hrp.map { it.code and 31 }
    return high + listOf(0) + low
}

private fun convertBits(data: List<Int>): ByteArray? {
    var acc = 0
    var bits = 0
    val out = ArrayList<Byte>(32)
    for (value in data) {
        if (value < 0 || value shr 5 != 0) return null
        acc = (acc shl 5) or value
        bits += 5
        while (bits >= 8) {
            bits -= 8
            out.add(((acc shr bits) and 0xff).toByte())
        }
    }
    if (bits >= 5) return null
    if (((acc shl (8 - bits)) and 0xff) != 0) return null
    if (out.size != 32) return null
    return out.toByteArray()
}
