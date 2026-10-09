package com.tankful.android.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tankful.android.providers.DEFAULT_MAP_STYLE_URL
import com.tankful.android.quantity.measureForLocale
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.deviceStore by preferencesDataStore(name = "tankful_device")

@Singleton
class DeviceSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.deviceStore

    val selectedVehicleId: Flow<String?> = store.data.map { it[Keys.SELECTED] }
    val theme: Flow<String> = store.data.map { it[Keys.THEME] ?: "SYSTEM" }
    val installId: Flow<String> = store.data.map { it[Keys.INSTALL] ?: "" }
    val syncEnabled: Flow<Boolean> = store.data.map { it[Keys.SYNC] ?: false }
    val signerPackage: Flow<String?> = store.data.map { it[Keys.SIGNER_PACKAGE] }
    val signerPubkey: Flow<String?> = store.data.map { it[Keys.SIGNER_PUBKEY] }
    val lastMapCamera: Flow<String?> = store.data.map { it[Keys.CAMERA] }
    val mapStyleUrl: Flow<String> = store.data.map { it[Keys.STYLE] ?: DEFAULT_MAP_STYLE_URL }
    val nip09Enabled: Flow<Boolean> = store.data.map { it[Keys.NIP09] ?: false }
    val measure: Flow<String> = store.data.map { it[Keys.MEASURE] ?: measureForLocale(Locale.getDefault()) }

    suspend fun peekSelected(): String? = selectedVehicleId.first()

    suspend fun setSelectedVehicleId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED) else prefs[Keys.SELECTED] = id
        }
    }

    suspend fun setTheme(value: String) {
        store.edit { it[Keys.THEME] = value }
    }

    suspend fun setMeasure(value: String) {
        store.edit { it[Keys.MEASURE] = value }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        store.edit { it[Keys.SYNC] = enabled }
    }

    suspend fun setSigner(packageName: String?, pubkey: String?) {
        store.edit { prefs ->
            if (packageName == null) prefs.remove(Keys.SIGNER_PACKAGE) else prefs[Keys.SIGNER_PACKAGE] = packageName
            if (pubkey == null) prefs.remove(Keys.SIGNER_PUBKEY) else prefs[Keys.SIGNER_PUBKEY] = pubkey
        }
    }

    suspend fun setLastMapCamera(value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(Keys.CAMERA) else prefs[Keys.CAMERA] = value
        }
    }

    suspend fun setMapStyleUrl(value: String) {
        store.edit { it[Keys.STYLE] = value }
    }

    suspend fun setNip09Enabled(enabled: Boolean) {
        store.edit { it[Keys.NIP09] = enabled }
    }

    suspend fun syncWatermarkSeconds(): Long? {
        return store.data.first()[Keys.WATERMARK]?.toLongOrNull()
    }

    suspend fun setSyncWatermarkSeconds(seconds: Long?) {
        store.edit { prefs ->
            if (seconds == null) prefs.remove(Keys.WATERMARK) else prefs[Keys.WATERMARK] = seconds.toString()
        }
    }

    suspend fun notifiedOn(reminderId: String): String? {
        val rows = store.data.first()[Keys.NOTIFIED].orEmpty()
        val prefix = "$reminderId|"
        val match = rows.firstOrNull { it.startsWith(prefix) } ?: return null
        return match.removePrefix(prefix)
    }

    suspend fun markNotified(reminderId: String, localDate: String) {
        store.edit { prefs ->
            val prefix = "$reminderId|"
            val kept = prefs[Keys.NOTIFIED].orEmpty().filterNot { it.startsWith(prefix) }
            prefs[Keys.NOTIFIED] = (kept + "$prefix$localDate").toSet()
        }
    }

    suspend fun ensureInstallId(): String {
        val current = installId.first()
        if (current.isNotBlank()) return current
        val created = UUID.randomUUID().toString()
        store.edit { it[Keys.INSTALL] = created }
        return created
    }

    private object Keys {
        val SELECTED = stringPreferencesKey("selected_vehicle_id")
        val THEME = stringPreferencesKey("theme")
        val INSTALL = stringPreferencesKey("install_id")
        val SYNC = booleanPreferencesKey("sync_enabled")
        val SIGNER_PACKAGE = stringPreferencesKey("signer_package")
        val SIGNER_PUBKEY = stringPreferencesKey("signer_pubkey")
        val CAMERA = stringPreferencesKey("last_map_camera")
        val STYLE = stringPreferencesKey("map_style_url")
        val WATERMARK = stringPreferencesKey("sync_watermark_seconds")
        val NOTIFIED = stringSetPreferencesKey("reminder_notified_on")
        val NIP09 = booleanPreferencesKey("nip09_enabled")
        val MEASURE = stringPreferencesKey("measure_system")
    }
}
