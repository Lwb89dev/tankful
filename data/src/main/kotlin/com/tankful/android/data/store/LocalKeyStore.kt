package com.tankful.android.data.store

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted nsec bytes in the app files directory.
 * Backup rules already exclude files. The bytes are not exported, synced, or logged.
 */
@Singleton
class LocalKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val seal = KeystoreSeal(ALIAS, File(context.filesDir, "signer-secret.bin"))

    fun save(secret: ByteArray) = seal.save(secret)

    fun load(): ByteArray? = seal.load()

    fun clear() = seal.clear()

    private companion object {
        const val ALIAS = "tankful-signer-secret"
    }
}
