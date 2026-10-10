package com.tankful.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tankful.android.sync.httpsPicture
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

private const val PICTURE_CAP = 8 * 1024 * 1024

internal fun profileBitmap(client: OkHttpClient, url: String): Bitmap? {
    return decodeProfilePicture(fetchHttps(client, url, 0) ?: return null)
}

internal fun decodeProfilePicture(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > PICTURE_CAP) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val decode = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
}

internal fun encodeProfilePicture(bitmap: Bitmap): ByteArray? {
    val out = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
    val bytes = out.toByteArray()
    if (bytes.isEmpty() || bytes.size > PICTURE_CAP) return null
    return bytes
}

private fun fetchHttps(client: OkHttpClient, url: String, hops: Int): ByteArray? {
    if (hops > 5 || httpsPicture(url) == null) return null
    val limited = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Tankful Android/" + com.tankful.android.BuildConfig.VERSION_NAME)
        .header("Accept", "image/jpeg, image/png, image/webp, image/*;q=0.8")
        .build()
    return runCatching {
        limited.newCall(request).execute().use { response -> readResponse(client, response, hops) }
    }.getOrNull()
}

private fun readResponse(client: OkHttpClient, response: okhttp3.Response, hops: Int): ByteArray? {
    if (response.code in 300..399) {
        val next = response.header("Location") ?: return null
        val absolute = response.request.url.resolve(next)?.toString() ?: return null
        return fetchHttps(client, absolute, hops + 1)
    }
    if (!response.isSuccessful) return null
    val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
    if (type.startsWith("text/")) return null
    val body = response.body ?: return null
    return readCap(body.byteStream(), PICTURE_CAP)
}

private fun readCap(input: InputStream, cap: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buf)
        if (count < 0) return out.toByteArray()
        total += count
        if (total > cap) return null
        out.write(buf, 0, count)
    }
}

private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > 256 || height / sample > 256) sample *= 2
    return sample
}
