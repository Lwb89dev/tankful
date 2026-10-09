package com.tankful.android.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
    private val file = File(context.filesDir, "signer-secret.bin")

    fun save(secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = cipher.iv
        val body = cipher.doFinal(secret)
        file.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(body)
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun aesKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "tankful-signer-secret"
    }
}
