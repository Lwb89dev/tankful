package com.tankful.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tankful.android.catalog.SYSTEM_CATEGORIES
import com.tankful.android.catalog.SYSTEM_SERVICE_TYPES
import com.tankful.android.model.SETTINGS_ENTITY_ID

@Database(
    entities = [
        VehicleEntity::class,
        SpecificationEntity::class,
        OdometerReadingEntity::class,
        ExpenseEntity::class,
        RefuelEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        EntityTagEntity::class,
        ServiceTypeEntity::class,
        ServiceProviderEntity::class,
        MaintenanceEntity::class,
        MaintenanceItemEntity::class,
        ReminderEntity::class,
        JudgementEntity::class,
        AttachmentEntity::class,
        SettingsEntity::class,
        StationCacheEntity::class,
        OutboxEntity::class,
        OutboxRelayAckEntity::class,
        AppliedEventEntity::class,
        PublishedContentEventEntity::class,
        QuarantineEntity::class,
        SnapshotForkEntity::class,
        PendingSnapshotEntity::class,
        ConflictEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TankfulDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao

    abstract fun catalog(): CatalogDao

    abstract fun sync(): SyncDao

    abstract fun stations(): StationDao

    companion object {
        fun create(context: Context, name: String?): TankfulDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, TankfulDatabase::class.java)
            } else {
                val cipher = LogbookCipher.factory(context, name)
                Room.databaseBuilder(context, TankfulDatabase::class.java, name)
                    .openHelperFactory(cipher)
            }
            return builder
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(TankfulCallback)
                .build()
        }
    }
}

object SchemaExtras {
    val PARTIAL_SPEC: String =
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_specs_one_active_fuel
        ON vehicle_specifications(vehicle_id, fuel_kind)
        WHERE is_active = 1 AND deleted_at IS NULL
        """.trimIndent()

    val PARTIAL_READING: String =
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_readings_source_entity
        ON odometer_readings(source, source_entity_id)
        WHERE source_entity_id IS NOT NULL
        """.trimIndent()

    val PARTIAL_EXPENSE: String =
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_origin_entity
        ON expenses(origin, origin_id)
        WHERE origin_id IS NOT NULL
        """.trimIndent()

    val PARTIAL_CONFLICT: String =
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_conflicts_one_open
        ON conflicts(entity_type, entity_id)
        WHERE state = 'OPEN'
        """.trimIndent()

    val FTS: String =
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS timeline_fts USING fts5(
            entity_type UNINDEXED,
            entity_id UNINDEXED,
            body
        )
        """.trimIndent()

    val STATION: String =
        """
        CREATE TABLE IF NOT EXISTS `station_cache` (
            `osm_type` TEXT NOT NULL,
            `osm_id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `latitude` REAL NOT NULL,
            `longitude` REAL NOT NULL,
            `tags_json` TEXT NOT NULL,
            `centre_lat` REAL NOT NULL,
            `centre_lon` REAL NOT NULL,
            `fetched_at` INTEGER NOT NULL,
            PRIMARY KEY(`osm_type`, `osm_id`)
        )
        """.trimIndent()

    val SYNC_TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `outbox` (
            `id` TEXT NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            `revision` INTEGER NOT NULL,
            `state` TEXT NOT NULL,
            `signed_event` TEXT,
            `payload` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_entity_type_entity_id_revision`
        ON `outbox` (`entity_type`, `entity_id`, `revision`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `outbox_relay_ack` (
            `outbox_id` TEXT NOT NULL,
            `relay_url` TEXT NOT NULL,
            `accepted` INTEGER NOT NULL,
            PRIMARY KEY(`outbox_id`, `relay_url`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `applied_events` (
            `event_id` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `published_content_events` (
            `relay_url` TEXT NOT NULL,
            `event_id` TEXT NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            PRIMARY KEY(`relay_url`, `event_id`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `quarantine` (
            `event_id` TEXT NOT NULL,
            `reason` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `snapshot_fork` (
            `id` TEXT NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            `content_hash` TEXT NOT NULL,
            `snapshot_json` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_snapshot_fork_entity_type_entity_id_content_hash`
        ON `snapshot_fork` (`entity_type`, `entity_id`, `content_hash`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `pending_snapshots` (
            `event_id` TEXT NOT NULL,
            `snapshot_json` TEXT NOT NULL,
            PRIMARY KEY(`event_id`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `conflicts` (
            `id` TEXT NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `local_hash` TEXT NOT NULL,
            `other_hash` TEXT NOT NULL,
            `other_snapshot` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )

    fun partialIndexes(db: SupportSQLiteDatabase) {
        db.execSQL(PARTIAL_SPEC)
        db.execSQL(PARTIAL_READING)
        db.execSQL(PARTIAL_EXPENSE)
        db.execSQL(PARTIAL_CONFLICT)
    }

    /** API 26 ships FTS5. Robolectric's SQLite is built without that module. */
    fun createFts(db: SupportSQLiteDatabase) {
        try {
            db.execSQL(FTS)
        } catch (exception: Exception) {
            if (!exception.message.orEmpty().contains("no such module")) throw exception
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS timeline_fts (
                    entity_type TEXT,
                    entity_id TEXT,
                    body TEXT
                )
                """.trimIndent(),
            )
        }
    }

    fun seed(db: SupportSQLiteDatabase) {
        for (category in SYSTEM_CATEGORIES) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO categories
                (id, parent_group, is_system, sort_order, display_name, deleted_at, revision, install_id, content_hash)
                VALUES (?, ?, 1, ?, ?, NULL, 1, NULL, NULL)
                """.trimIndent(),
                arrayOf(category.id, category.parentGroup, category.sortOrder, category.id),
            )
        }
        for (service in SYSTEM_SERVICE_TYPES) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO service_types
                (id, default_category_id, is_system, display_name, deleted_at, revision, install_id, content_hash)
                VALUES (?, ?, 1, ?, NULL, 1, NULL, NULL)
                """.trimIndent(),
                arrayOf(service.id, service.defaultCategoryId, service.id),
            )
        }
        db.execSQL(
            """
            INSERT OR IGNORE INTO settings
            (id, default_currency, relay_urls_json, default_lead_time_days, deleted_at, revision, install_id, content_hash)
            VALUES (?, 'EUR', '[]', 7, NULL, 1, NULL, NULL)
            """.trimIndent(),
            arrayOf(SETTINGS_ENTITY_ID),
        )
    }
}

private object TankfulCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        SchemaExtras.partialIndexes(db)
        SchemaExtras.createFts(db)
        SchemaExtras.seed(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        SchemaExtras.partialIndexes(db)
        SchemaExtras.createFts(db)
        SchemaExtras.seed(db)
    }
}

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SchemaExtras.createFts(db)
    }
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(SchemaExtras.STATION)
    }
}

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val present = db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'station_cache'",
        ).use { cursor -> cursor.moveToFirst() }
        if (!present) error("station_cache is missing")
        for (statement in SchemaExtras.SYNC_TABLES) {
            db.execSQL(statement)
        }
        SchemaExtras.partialIndexes(db)
    }
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vehicles ADD COLUMN nickname TEXT")
    }
}
