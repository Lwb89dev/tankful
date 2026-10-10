package com.tankful.android.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import com.tankful.android.data.store.LocalKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SignerCall(val mapping: Nip55Mapping, val value: String?, val packageName: String?)

const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

@Singleton
class Nip55Signer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localKeys: LocalKeyStore,
) {
    suspend fun publicKey(rememberedPackage: String?): SignerCall = call(rememberedPackage, "GET_PUBLIC_KEY", null, null)

    suspend fun sign(rememberedPackage: String?, eventJson: String): SignerCall {
        return call(rememberedPackage, "SIGN_EVENT", eventJson, null)
    }

    suspend fun encrypt(rememberedPackage: String?, plaintext: String, pubkey: String): SignerCall {
        return call(rememberedPackage, "NIP44_ENCRYPT", plaintext, arrayOf(pubkey))
    }

    suspend fun decrypt(rememberedPackage: String?, ciphertext: String, pubkey: String): SignerCall {
        return call(rememberedPackage, "NIP44_DECRYPT", ciphertext, arrayOf(pubkey))
    }

    private suspend fun call(
        rememberedPackage: String?,
        method: String,
        selection: String?,
        args: Array<String>?,
    ): SignerCall = withContext(Dispatchers.IO) {
        if (rememberedPackage == LOCAL_NSEC_SIGNER) return@withContext localCall(method, selection, args)
        val packageName = if (rememberedPackage == AMBER_PACKAGE) AMBER_PACKAGE else findSigner()
        if (packageName == null) return@withContext SignerCall(Nip55Mapping(SignerFailure.NOT_INSTALLED, false), null, null)
        try {
            withTimeout(60_000) { query(packageName, method, selection, args) }
        } catch (timeout: TimeoutCancellationException) {
            SignerCall(mapNip55(0, false, true, true), null, packageName)
        }
    }

    private fun localCall(method: String, selection: String?, args: Array<String>?): SignerCall {
        val secret = localKeys.load() ?: return SignerCall(mapNip55(0, false, true, false), null, LOCAL_NSEC_SIGNER)
        try {
            val value = localValue(method, selection, args, secret)
            val code = if (value != null) ANDROID_RESULT_OK else 0
            return SignerCall(mapNip55(code, false, true, false), value, LOCAL_NSEC_SIGNER)
        } finally {
            secret.fill(0)
        }
    }

    private fun localValue(method: String, selection: String?, args: Array<String>?, secret: ByteArray): String? {
        val pubkey = args?.firstOrNull().orEmpty()
        return when (method) {
            "GET_PUBLIC_KEY" -> xOnlyPubkeyHex(secret)
            "NIP44_ENCRYPT" -> nip44Encrypt(secret, pubkey, selection.orEmpty())
            "NIP44_DECRYPT" -> nip44Decrypt(secret, pubkey, selection.orEmpty())
            "SIGN_EVENT" -> signLocalEvent(secret, selection)
            else -> null
        }
    }

    private fun query(packageName: String, method: String, selection: String?, args: Array<String>?): SignerCall {
        if (packageName != AMBER_PACKAGE) return SignerCall(mapNip55(0, false, true, false), null, null)
        val uri = Uri.parse("content://$packageName.$method")
        val cursor = context.contentResolver.query(uri, null, selection, args, null, CancellationSignal())
        if (cursor == null) return SignerCall(mapNip55(0, false, true, false), null, packageName)
        cursor.use { rows ->
            if (!rows.moveToFirst()) return SignerCall(mapNip55(0, false, true, false), null, packageName)
            val rejected = column(rows, "rejected") == "true" || column(rows, "rejected") == "1"
            val value = column(rows, "result") ?: column(rows, "signature") ?: column(rows, "event")
            val code = if (value != null) ANDROID_RESULT_OK else 0
            return SignerCall(mapNip55(code, rejected, true, false), value, packageName)
        }
    }

    fun amberInstalled(): Boolean = installed(AMBER_PACKAGE)

    fun publicKeyIntent(): Intent? {
        if (!installed(AMBER_PACKAGE)) return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        intent.setPackage(AMBER_PACKAGE)
        intent.putExtra("type", "get_public_key")
        return intent
    }

    private fun findSigner(): String? = if (installed(AMBER_PACKAGE)) AMBER_PACKAGE else null

    private fun installed(packageName: String): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }
}

private fun column(cursor: android.database.Cursor, name: String): String? {
    val index = cursor.getColumnIndex(name)
    if (index < 0 || cursor.isNull(index)) return null
    return cursor.getString(index)
}
