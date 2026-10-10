package com.tankful.android.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteHeaderTest {
    @Test
    fun recognisesAPlainSqliteFile() {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertTrue(sqlitePlaintext(magic))
        assertFalse(sqlitePlaintext(ByteArray(16) { 1 }))
        assertFalse(sqlitePlaintext(ByteArray(0)))
    }
}
