package com.tankful.android.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM blob in the app files directory. The key never leaves AndroidKeyStore.
 * Format is one length byte, the IV, then the ciphertext and tag.
 */
internal class KeystoreSeal(private val alias: String, private val file: File) {
    fun save(secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = cipher.iv
        val body = cipher.doFinal(secret)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(body)
        }
        if (file.exists() && !file.delete()) error("sealed file")
        if (!tmp.renameTo(file)) error("sealed file")
    }

    fun load(): ByteArray? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val ivLength = bytes[0].toInt() and 0xff
        if (ivLength != 12 || bytes.size < 1 + ivLength + 16) return null
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val body = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    fun clear() {
        if (file.exists()) file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun aesKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(alias, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
