package com.tankful.android.data.store

import java.io.File
import java.security.SecureRandom

/**
 * 32 random bytes for the logbook file, sealed by AndroidKeyStore.
 * A file that will not decrypt is left in place. Rotating it would make the database unreadable.
 */
internal class DatabasePassphrase(root: File) {
    private val file = File(root, "db-key.bin")
    private val seal = KeystoreSeal("tankful-db-key", file)

    fun bytes(): ByteArray {
        if (!file.isFile) return create()
        val opened = try {
            seal.load()
        } catch (ignored: Exception) {
            null
        }
        if (opened == null || opened.size != 32) error("logbook key unreadable")
        return opened
    }

    private fun create(): ByteArray {
        val fresh = ByteArray(32)
        SecureRandom().nextBytes(fresh)
        try {
            seal.save(fresh)
        } catch (ignored: Exception) {
            fresh.fill(0)
            error("logbook encryption failed")
        }
        return fresh
    }
}
