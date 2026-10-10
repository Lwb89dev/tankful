package com.tankful.android.data.db

private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/** True when the file starts with the unencrypted SQLite header. */
internal fun sqlitePlaintext(header: ByteArray): Boolean {
    if (header.size < SQLITE_MAGIC.size) return false
    return header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)
}
