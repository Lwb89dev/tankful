package com.tankful.android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val manufacturer: String,
    val model: String,
    @ColumnInfo(name = "model_year") val modelYear: Int,
    val powertrain: String,
    @ColumnInfo(name = "primary_fuel") val primaryFuel: String,
    @ColumnInfo(name = "secondary_fuel") val secondaryFuel: String?,
    val plate: String?,
    val vin: String?,
    @ColumnInfo(name = "purchase_date") val purchaseDate: String?,
    @ColumnInfo(name = "purchase_price_minor") val purchasePriceMinor: Long?,
    @ColumnInfo(name = "purchase_currency") val purchaseCurrency: String?,
    val generation: String?,
    val engine: String?,
    @ColumnInfo(name = "power_unscaled") val powerUnscaled: Long?,
    @ColumnInfo(name = "power_scale") val powerScale: Int?,
    @ColumnInfo(name = "power_unit") val powerUnit: String?,
    val transmission: String?,
    val drivetrain: String?,
    @ColumnInfo(name = "registration_year") val registrationYear: Int?,
    @ColumnInfo(name = "distance_unit") val distanceUnit: String,
    @ColumnInfo(name = "economy_preference") val economyPreference: String,
    val notes: String?,
    @ColumnInfo(name = "image_attachment_id") val imageAttachmentId: String?,
    @ColumnInfo(name = "last_fuel_kind") val lastFuelKind: String?,
    @ColumnInfo(name = "last_station_name") val lastStationName: String?,
    @ColumnInfo(name = "last_full_tank") val lastFullTank: Boolean?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
    val nickname: String? = null,
)

@Entity(
    tableName = "vehicle_specifications",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("vehicle_id")],
)
data class SpecificationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    @ColumnInfo(name = "fuel_kind") val fuelKind: String,
    @ColumnInfo(name = "combined_unit") val combinedUnit: String?,
    @ColumnInfo(name = "combined_scale") val combinedScale: Int?,
    @ColumnInfo(name = "combined_unscaled") val combinedUnscaled: Long?,
    @ColumnInfo(name = "city_unit") val cityUnit: String?,
    @ColumnInfo(name = "city_scale") val cityScale: Int?,
    @ColumnInfo(name = "city_unscaled") val cityUnscaled: Long?,
    @ColumnInfo(name = "highway_unit") val highwayUnit: String?,
    @ColumnInfo(name = "highway_scale") val highwayScale: Int?,
    @ColumnInfo(name = "highway_unscaled") val highwayUnscaled: Long?,
    @ColumnInfo(name = "urban_unit") val urbanUnit: String?,
    @ColumnInfo(name = "urban_scale") val urbanScale: Int?,
    @ColumnInfo(name = "urban_unscaled") val urbanUnscaled: Long?,
    @ColumnInfo(name = "extra_urban_unit") val extraUrbanUnit: String?,
    @ColumnInfo(name = "extra_urban_scale") val extraUrbanScale: Int?,
    @ColumnInfo(name = "extra_urban_unscaled") val extraUrbanUnscaled: Long?,
    @ColumnInfo(name = "co2_scale") val co2Scale: Int?,
    @ColumnInfo(name = "co2_unscaled") val co2Unscaled: Long?,
    @ColumnInfo(name = "co2_grams_per_mile") val co2GramsPerMile: Boolean?,
    @ColumnInfo(name = "test_cycle") val testCycle: String,
    @ColumnInfo(name = "measure_kind") val measureKind: String,
    val source: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    @ColumnInfo(name = "retrieved_at") val retrievedAt: Long?,
    @ColumnInfo(name = "retrieval_protocol") val retrievalProtocol: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "odometer_readings",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("vehicle_id", "timestamp")],
)
data class OdometerReadingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val timestamp: Long,
    val metres: Long,
    @ColumnInfo(name = "entry_unit") val entryUnit: String,
    @ColumnInfo(name = "entry_unscaled") val entryUnscaled: Long,
    @ColumnInfo(name = "entry_scale") val entryScale: Int,
    val source: String,
    @ColumnInfo(name = "source_entity_id") val sourceEntityId: String?,
    @ColumnInfo(name = "epoch_id") val epochId: String,
    val reason: String?,
    val note: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("vehicle_id", "timestamp"), Index("category_id")],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val timestamp: Long,
    @ColumnInfo(name = "total_currency") val totalCurrency: String,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    val note: String?,
    val origin: String,
    @ColumnInfo(name = "origin_id") val originId: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "refuels",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = OdometerReadingEntity::class,
            parentColumns = ["id"],
            childColumns = ["odometer_reading_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("vehicle_id", "timestamp"),
        Index(value = ["expense_id"], unique = true),
        Index(value = ["odometer_reading_id"], unique = true),
    ],
)
data class RefuelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val timestamp: Long,
    @ColumnInfo(name = "quantity_amount") val quantityAmount: Long,
    @ColumnInfo(name = "quantity_unit") val quantityUnit: String,
    @ColumnInfo(name = "fuel_kind") val fuelKind: String,
    @ColumnInfo(name = "energy_kind") val energyKind: String,
    @ColumnInfo(name = "unit_price_currency") val unitPriceCurrency: String,
    @ColumnInfo(name = "unit_price_scale") val unitPriceScale: Int,
    @ColumnInfo(name = "unit_price_unscaled") val unitPriceUnscaled: Long,
    @ColumnInfo(name = "unit_price_derived") val unitPriceDerived: Boolean,
    @ColumnInfo(name = "total_currency") val totalCurrency: String,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "amounts_disagreed") val amountsDisagreed: Boolean,
    @ColumnInfo(name = "full_tank") val fullTank: Boolean,
    @ColumnInfo(name = "station_name") val stationName: String?,
    @ColumnInfo(name = "station_osm_type") val stationOsmType: String?,
    @ColumnInfo(name = "station_osm_id") val stationOsmId: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val note: String?,
    @ColumnInfo(name = "odometer_reading_id") val odometerReadingId: String?,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_group") val parentGroup: String,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "entity_tags",
    primaryKeys = ["entity_type", "entity_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("tag_id")],
)
data class EntityTagEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

@Entity(tableName = "service_types")
data class ServiceTypeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: String,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(tableName = "service_providers")
data class ServiceProviderEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    val location: String?,
    val contact: String?,
    val note: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "maintenance_records",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("vehicle_id"),
        Index(value = ["expense_id"], unique = true),
        Index(value = ["odometer_reading_id"], unique = true),
    ],
)
data class MaintenanceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val timestamp: Long,
    @ColumnInfo(name = "service_type_id") val serviceTypeId: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "odometer_reading_id") val odometerReadingId: String?,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "labour_currency") val labourCurrency: String?,
    @ColumnInfo(name = "labour_minor") val labourMinor: Long?,
    @ColumnInfo(name = "parts_currency") val partsCurrency: String?,
    @ColumnInfo(name = "parts_minor") val partsMinor: Long?,
    @ColumnInfo(name = "total_currency") val totalCurrency: String,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "amounts_disagreed") val amountsDisagreed: Boolean,
    @ColumnInfo(name = "next_date") val nextDate: String?,
    @ColumnInfo(name = "next_odometer_metres") val nextOdometerMetres: Long?,
    val note: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "maintenance_items",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["maintenance_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("maintenance_id")],
)
data class MaintenanceItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "maintenance_id") val maintenanceId: String,
    val description: String,
    @ColumnInfo(name = "cost_minor") val costMinor: Long?,
    @ColumnInfo(name = "cost_currency") val costCurrency: String?,
    @ColumnInfo(name = "quantity_text") val quantityText: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("vehicle_id")],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val kind: String,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "due_odometer_metres") val dueOdometerMetres: Long?,
    @ColumnInfo(name = "interval_days") val intervalDays: Int?,
    @ColumnInfo(name = "lead_time_days") val leadTimeDays: Int,
    val enabled: Boolean,
    @ColumnInfo(name = "custom_title") val customTitle: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "interval_judgements",
    indices = [Index(value = ["opening_refuel_id", "closing_refuel_id"], unique = true)],
)
data class JudgementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "opening_refuel_id") val openingRefuelId: String,
    @ColumnInfo(name = "closing_refuel_id") val closingRefuelId: String,
    val confirmed: Boolean,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["relative_path"], unique = true)],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val mime: String,
    val filename: String,
    val sha256: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "default_currency") val defaultCurrency: String,
    @ColumnInfo(name = "relay_urls_json") val relayUrlsJson: String,
    @ColumnInfo(name = "default_lead_time_days") val defaultLeadTimeDays: Int,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "install_id") val installId: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
)

@Entity(tableName = "station_cache", primaryKeys = ["osm_type", "osm_id"])
data class StationCacheEntity(
    @ColumnInfo(name = "osm_type") val osmType: String,
    @ColumnInfo(name = "osm_id") val osmId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "centre_lat") val centreLat: Double,
    @ColumnInfo(name = "centre_lon") val centreLon: Double,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["entity_type", "entity_id", "revision"], unique = true)],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val revision: Long,
    val state: String,
    @ColumnInfo(name = "signed_event") val signedEvent: String?,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "outbox_relay_ack", primaryKeys = ["outbox_id", "relay_url"])
data class OutboxRelayAckEntity(
    @ColumnInfo(name = "outbox_id") val outboxId: String,
    @ColumnInfo(name = "relay_url") val relayUrl: String,
    val accepted: Boolean,
)

@Entity(tableName = "applied_events")
data class AppliedEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "published_content_events", primaryKeys = ["relay_url", "event_id"])
data class PublishedContentEventEntity(
    @ColumnInfo(name = "relay_url") val relayUrl: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
)

@Entity(tableName = "quarantine")
data class QuarantineEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "snapshot_fork",
    indices = [Index(value = ["entity_type", "entity_id", "content_hash"], unique = true)],
)
data class SnapshotForkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
)

@Entity(tableName = "pending_snapshots")
data class PendingSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
)

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val state: String,
    @ColumnInfo(name = "local_hash") val localHash: String,
    @ColumnInfo(name = "other_hash") val otherHash: String,
    @ColumnInfo(name = "other_snapshot") val otherSnapshot: String,
)
