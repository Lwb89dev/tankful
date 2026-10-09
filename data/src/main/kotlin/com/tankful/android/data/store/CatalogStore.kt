package com.tankful.android.data.store

import com.tankful.android.catalog.SYSTEM_GROUPS
import com.tankful.android.data.db.CategoryEntity
import com.tankful.android.data.db.ReminderEntity
import com.tankful.android.data.db.ServiceProviderEntity
import com.tankful.android.data.db.SettingsEntity
import com.tankful.android.data.db.SpecificationEntity
import com.tankful.android.data.db.TagEntity
import androidx.room.withTransaction
import com.tankful.android.data.db.TankfulDatabase
import com.tankful.android.model.SETTINGS_ENTITY_ID
import com.tankful.android.quantity.currencyForLocale
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Singleton
class CatalogStore @Inject constructor(
    private val database: TankfulDatabase,
) {
    fun categories(): Flow<List<CategoryEntity>> = database.catalog().categories()

    fun tags(): Flow<List<TagEntity>> = database.catalog().tags()

    fun serviceTypes() = database.catalog().serviceTypes()

    fun providers() = database.catalog().providers()

    fun specifications() = database.catalog().specifications()

    fun reminders() = database.catalog().reminders()

    suspend fun enabledReminders(): List<ReminderEntity> = database.catalog().enabledReminders()

    suspend fun addCategory(parentGroup: String, displayName: String): CategoryEntity {
        require(parentGroup in SYSTEM_GROUPS) { "unknown group" }
        val row = CategoryEntity(
            id = UUID.randomUUID().toString(),
            parentGroup = parentGroup,
            isSystem = false,
            sortOrder = 1000,
            displayName = displayName.trim(),
            deletedAt = null,
            revision = 1,
            installId = null,
            contentHash = null,
        )
        database.catalog().upsertCategories(listOf(row))
        return row
    }

    suspend fun ensureTag(displayName: String): TagEntity {
        val trimmed = displayName.trim()
        val normalized = trimmed.lowercase()
        val existing = database.catalog().tagsNow().firstOrNull { tag ->
            tag.deletedAt == null && tag.normalizedName == normalized
        }
        if (existing != null) return existing
        val created = TagEntity(
            id = UUID.randomUUID().toString(),
            displayName = trimmed,
            normalizedName = normalized,
            deletedAt = null,
            revision = 1,
            installId = null,
            contentHash = null,
        )
        database.catalog().upsertTag(created)
        return created
    }

    suspend fun saveProvider(row: ServiceProviderEntity) {
        database.catalog().upsertProvider(row)
    }

    suspend fun saveSpecification(row: SpecificationEntity) {
        database.withTransaction { writeSpecification(row) }
    }

    private suspend fun writeSpecification(row: SpecificationEntity) {
        if (row.isActive && row.deletedAt == null) {
            for (other in activeTwins(row)) {
                database.catalog().upsertSpecification(other.copy(isActive = false))
            }
        }
        database.catalog().upsertSpecification(row)
    }

    private suspend fun activeTwins(row: SpecificationEntity): List<SpecificationEntity> {
        return database.catalog().specificationsNow().filter { sameActiveFuel(it, row) }
    }

    suspend fun saveReminder(row: ReminderEntity) {
        database.catalog().upsertReminder(row)
    }

    suspend fun settings(): SettingsEntity {
        val found = database.catalog().settings(SETTINGS_ENTITY_ID)
        if (found != null) return found
        val created = SettingsEntity(
            SETTINGS_ENTITY_ID,
            currencyForLocale(Locale.getDefault()),
            "[]",
            7,
            null,
            1,
            null,
            null,
        )
        database.catalog().upsertSettings(created)
        return created
    }

    private fun sameActiveFuel(existing: SpecificationEntity, row: SpecificationEntity): Boolean {
        if (existing.id == row.id) return false
        if (existing.vehicleId != row.vehicleId) return false
        if (existing.fuelKind != row.fuelKind) return false
        if (!existing.isActive) return false
        return existing.deletedAt == null
    }

    suspend fun saveSettings(row: SettingsEntity) {
        require(row.id == SETTINGS_ENTITY_ID)
        database.catalog().upsertSettings(row)
    }
}
