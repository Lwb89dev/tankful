package com.tankful.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM vehicles")
    suspend fun vehicles(): List<VehicleEntity>

    @Query("SELECT * FROM expenses")
    suspend fun expenses(): List<ExpenseEntity>

    @Query("SELECT * FROM odometer_readings")
    suspend fun readings(): List<OdometerReadingEntity>

    @Query("SELECT * FROM refuels")
    suspend fun refuels(): List<RefuelEntity>

    @Query("SELECT * FROM maintenance_records")
    suspend fun maintenance(): List<MaintenanceEntity>

    @Query("SELECT * FROM maintenance_items")
    suspend fun maintenanceItems(): List<MaintenanceItemEntity>

    @Query("SELECT * FROM interval_judgements")
    suspend fun judgements(): List<JudgementEntity>

    @Query("SELECT * FROM entity_tags")
    suspend fun entityTags(): List<EntityTagEntity>

    @Upsert
    suspend fun upsertVehicles(rows: List<VehicleEntity>)

    @Upsert
    suspend fun upsertExpenses(rows: List<ExpenseEntity>)

    @Upsert
    suspend fun upsertReadings(rows: List<OdometerReadingEntity>)

    @Upsert
    suspend fun upsertRefuels(rows: List<RefuelEntity>)

    @Upsert
    suspend fun upsertMaintenance(rows: List<MaintenanceEntity>)

    @Upsert
    suspend fun upsertItems(rows: List<MaintenanceItemEntity>)

    @Upsert
    suspend fun upsertJudgements(rows: List<JudgementEntity>)

    @Query("DELETE FROM entity_tags")
    suspend fun clearTags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(rows: List<EntityTagEntity>)

    @Query("SELECT id FROM refuels WHERE id = :id")
    suspend fun refuelId(id: String): String?
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM categories ORDER BY sort_order")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun categoriesNow(): List<CategoryEntity>

    @Upsert
    suspend fun upsertCategories(rows: List<CategoryEntity>)

    @Query("SELECT * FROM tags WHERE deleted_at IS NULL ORDER BY display_name")
    fun tags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun tagsNow(): List<TagEntity>

    @Upsert
    suspend fun upsertTag(row: TagEntity)

    @Query("SELECT * FROM service_types ORDER BY display_name")
    fun serviceTypes(): Flow<List<ServiceTypeEntity>>

    @Upsert
    suspend fun upsertServiceTypes(rows: List<ServiceTypeEntity>)

    @Query("SELECT * FROM service_providers WHERE deleted_at IS NULL ORDER BY name")
    fun providers(): Flow<List<ServiceProviderEntity>>

    @Upsert
    suspend fun upsertProvider(row: ServiceProviderEntity)

    @Query("SELECT * FROM vehicle_specifications WHERE deleted_at IS NULL")
    fun specifications(): Flow<List<SpecificationEntity>>

    @Query("SELECT * FROM vehicle_specifications")
    suspend fun specificationsNow(): List<SpecificationEntity>

    @Upsert
    suspend fun upsertSpecification(row: SpecificationEntity)

    @Query("SELECT * FROM reminders WHERE deleted_at IS NULL")
    fun reminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE enabled = 1 AND deleted_at IS NULL")
    suspend fun enabledReminders(): List<ReminderEntity>

    @Upsert
    suspend fun upsertReminder(row: ReminderEntity)

    @Query("SELECT * FROM attachments WHERE deleted_at IS NULL")
    suspend fun attachments(): List<AttachmentEntity>

    @Upsert
    suspend fun upsertAttachment(row: AttachmentEntity)

    @Query("SELECT * FROM settings WHERE id = :id")
    suspend fun settings(id: String): SettingsEntity?

    @Upsert
    suspend fun upsertSettings(row: SettingsEntity)

    @Query("SELECT * FROM reminders")
    suspend fun remindersNow(): List<ReminderEntity>

    @Query("SELECT * FROM service_providers")
    suspend fun providersNow(): List<ServiceProviderEntity>

    @Query("SELECT * FROM service_types")
    suspend fun serviceTypesNow(): List<ServiceTypeEntity>
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM outbox ORDER BY entity_type, entity_id, revision")
    suspend fun allOutbox(): List<OutboxEntity>

    @Query(
        """
        SELECT * FROM outbox
        WHERE state != 'ACKNOWLEDGED'
        ORDER BY entity_type, entity_id, revision
        """,
    )
    suspend fun pendingOutbox(): List<OutboxEntity>

    @Upsert
    suspend fun upsertOutbox(row: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteOutbox(id: String)

    @Query("SELECT * FROM outbox_relay_ack WHERE outbox_id = :id")
    suspend fun acks(id: String): List<OutboxRelayAckEntity>

    @Upsert
    suspend fun upsertAck(row: OutboxRelayAckEntity)

    @Query("DELETE FROM outbox_relay_ack WHERE outbox_id = :id")
    suspend fun deleteAcks(id: String)

    @Query("SELECT event_id FROM applied_events")
    suspend fun appliedIds(): List<String>

    @Query("SELECT event_id FROM applied_events WHERE event_id = :id")
    suspend fun applied(id: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApplied(row: AppliedEventEntity)

    @Query("SELECT event_id FROM quarantine")
    suspend fun quarantineIds(): List<String>

    @Query("SELECT event_id FROM quarantine WHERE event_id = :id")
    suspend fun quarantined(id: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuarantine(row: QuarantineEntity)

    @Query("SELECT COUNT(*) FROM quarantine")
    fun quarantineCount(): Flow<Int>

    @Upsert
    suspend fun upsertFork(row: SnapshotForkEntity)

    @Query("SELECT * FROM snapshot_fork WHERE entity_type = :type AND entity_id = :entityId")
    suspend fun forks(type: String, entityId: String): List<SnapshotForkEntity>

    @Upsert
    suspend fun upsertPending(row: PendingSnapshotEntity)

    @Query("DELETE FROM pending_snapshots WHERE event_id = :id")
    suspend fun deletePending(id: String)

    @Query("SELECT * FROM pending_snapshots")
    suspend fun pendingSnapshots(): List<PendingSnapshotEntity>

    @Upsert
    suspend fun upsertConflict(row: ConflictEntity)

    @Query("SELECT * FROM conflicts WHERE state = 'OPEN'")
    fun openConflicts(): Flow<List<ConflictEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPublished(row: PublishedContentEventEntity)

    @Query(
        """
        SELECT event_id FROM published_content_events
        WHERE entity_type = :type AND entity_id = :entityId
        """,
    )
    suspend fun contentEventIds(type: String, entityId: String): List<String>
}

@Dao
interface StationDao {
    @Query(
        """
        SELECT * FROM station_cache
        WHERE centre_lat = :lat AND centre_lon = :lon
        """,
    )
    suspend fun around(lat: Double, lon: Double): List<StationCacheEntity>

    @Query("DELETE FROM station_cache WHERE centre_lat = :lat AND centre_lon = :lon")
    suspend fun clearAround(lat: Double, lon: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<StationCacheEntity>)
}
