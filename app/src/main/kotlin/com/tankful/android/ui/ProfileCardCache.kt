package com.tankful.android.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class StoredProfile(
    val pubkey: String,
    val name: String,
    val pictureUrl: String?,
    val pictureBytes: ByteArray?,
)

internal enum class ProfileFetch { SHOW, PICTURE, METADATA }

internal fun profileFetchPlan(stored: StoredProfile?): ProfileFetch {
    if (stored == null) return ProfileFetch.METADATA
    if (!stored.pictureUrl.isNullOrBlank() && stored.pictureBytes == null) return ProfileFetch.PICTURE
    return ProfileFetch.SHOW
}

@Serializable
private data class ProfileCardRecord(
    val pubkey: String,
    val name: String,
    val pictureUrl: String? = null,
)

internal class ProfileCardFiles(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun read(pubkey: String): StoredProfile? {
        val record = decode() ?: return null
        if (record.pubkey != pubkey) return null
        return StoredProfile(record.pubkey, record.name, record.pictureUrl, pictureBytes())
    }

    fun write(pubkey: String, name: String, pictureUrl: String?, pictureBytes: ByteArray?) {
        dir.mkdirs()
        val record = ProfileCardRecord(pubkey, name, pictureUrl)
        cardFile().writeText(json.encodeToString(ProfileCardRecord.serializer(), record))
        val picture = pictureFile()
        if (pictureBytes == null || pictureBytes.isEmpty() || pictureBytes.size > STORED_PICTURE_CAP) {
            picture.delete()
            return
        }
        picture.writeBytes(pictureBytes)
    }

    fun writePicture(pubkey: String, bytes: ByteArray) {
        val record = decode() ?: return
        if (record.pubkey != pubkey || bytes.isEmpty() || bytes.size > STORED_PICTURE_CAP) return
        dir.mkdirs()
        pictureFile().writeBytes(bytes)
    }

    fun clear() {
        cardFile().delete()
        pictureFile().delete()
    }

    private fun decode(): ProfileCardRecord? {
        val file = cardFile()
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ProfileCardRecord.serializer(), file.readText())
        }.getOrNull()
    }

    private fun pictureBytes(): ByteArray? {
        val file = pictureFile()
        if (!file.isFile || file.length() <= 0L || file.length() > STORED_PICTURE_CAP) return null
        return file.readBytes()
    }

    private fun cardFile(): File = File(dir, "card.json")

    private fun pictureFile(): File = File(dir, "picture.bin")
}

private const val STORED_PICTURE_CAP = 2 * 1024 * 1024

@Singleton
class ProfileCardCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val files = ProfileCardFiles(File(context.filesDir, "profile-card"))

    fun read(pubkey: String): StoredProfile? = files.read(pubkey)

    fun write(pubkey: String, name: String, pictureUrl: String?, pictureBytes: ByteArray?) {
        files.write(pubkey, name, pictureUrl, pictureBytes)
    }

    fun writePicture(pubkey: String, bytes: ByteArray) {
        files.writePicture(pubkey, bytes)
    }

    fun clear() {
        files.clear()
    }
}
