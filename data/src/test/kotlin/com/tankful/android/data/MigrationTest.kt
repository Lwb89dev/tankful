package com.tankful.android.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.tankful.android.data.db.MIGRATION_1_2
import com.tankful.android.data.db.MIGRATION_2_3
import com.tankful.android.data.db.MIGRATION_3_4
import com.tankful.android.data.db.MIGRATION_4_5
import com.tankful.android.data.db.TankfulDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun freshInstallHasPartialIndexesAndOneActiveSpecPerFuel() {
        val database = TankfulDatabase.create(context, null)
        val names = indexNames(database.openHelper.writableDatabase)
        assertTrue(names.contains("index_specs_one_active_fuel"))
        assertTrue(names.contains("index_readings_source_entity"))
        assertTrue(names.contains("index_expenses_origin_entity"))
        assertTrue(names.contains("index_conflicts_one_open"))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO vehicles (id, created_at, manufacturer, model, model_year, powertrain, primary_fuel, distance_unit, economy_preference, revision) VALUES ('v', 1, 'A', 'B', 2020, 'ICE', 'PETROL', 'KM_WHOLE', 'L_PER_100KM', 1)",
        )
        insertSpec(sqlite, "s1", "PETROL", active = 1, deletedAt = null)
        insertSpec(sqlite, "s2", "LPG", active = 1, deletedAt = null)
        insertSpec(sqlite, "s-old", "PETROL", active = 0, deletedAt = null)
        try {
            insertSpec(sqlite, "s3", "PETROL", active = 1, deletedAt = null)
            fail("second active petrol spec")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            val message = expected.message.orEmpty()
            assertTrue(message.contains("UNIQUE constraint failed"))
            assertTrue(message.contains("vehicle_id"))
        }
        database.close()
    }

    @Test
    fun nicknameColumnIsAdded() {
        context.deleteDatabase("nick.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("nick.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE vehicles (id TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val opened = helper.writableDatabase
        MIGRATION_4_5.migrate(opened)
        opened.execSQL("INSERT INTO vehicles (id, created_at, nickname) VALUES ('v', 1, 'Rex')")
        val nickname = opened.query("SELECT nickname FROM vehicles WHERE id = 'v'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("Rex", nickname)
        helper.close()
    }

    @Test
    fun upgradesKeepTheRefuelAndRequireStationCache() {
        context.deleteDatabase("legacy.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("legacy.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createVersion1(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val opened = helper.writableDatabase
        opened.execSQL(
            "INSERT INTO vehicles (id, created_at, manufacturer, model, model_year, powertrain, primary_fuel, distance_unit, economy_preference, revision) VALUES ('v', 1, 'A', 'B', 2020, 'ICE', 'PETROL', 'KM_WHOLE', 'L_PER_100KM', 1)",
        )
        opened.execSQL(
            "INSERT INTO categories (id, parent_group, is_system, sort_order, display_name, revision) VALUES ('fuel', 'fuel', 1, 0, 'fuel', 1)",
        )
        opened.execSQL(
            "INSERT INTO expenses (id, vehicle_id, category_id, timestamp, total_currency, total_minor, origin, origin_id, revision) VALUES ('e', 'v', 'fuel', 2, 'EUR', 7468, 'REFUEL', 'r', 1)",
        )
        opened.execSQL(
            """
            INSERT INTO refuels (
                id, vehicle_id, timestamp, quantity_amount, quantity_unit, fuel_kind, energy_kind,
                unit_price_currency, unit_price_scale, unit_price_unscaled, unit_price_derived,
                total_currency, total_minor, amounts_disagreed, full_tank, expense_id, revision
            ) VALUES (
                'r', 'v', 2, 42700, 'MILLILITRE', 'PETROL', 'LIQUID',
                'EUR', 3, 1749, 0,
                'EUR', 7468, 0, 1, 'e', 1
            )
            """.trimIndent(),
        )
        MIGRATION_1_2.migrate(opened)
        MIGRATION_2_3.migrate(opened)
        MIGRATION_3_4.migrate(opened)
        val refuel = opened.query("SELECT total_minor FROM refuels WHERE id = 'r'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        assertEquals(7468L, refuel)
        assertTrue(tableExists(opened, "timeline_fts"))
        assertTrue(tableExists(opened, "station_cache"))
        assertTrue(tableExists(opened, "outbox"))
        assertTrue(tableExists(opened, "published_content_events"))
        opened.close()

        val bare = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("nostation.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        ).writableDatabase
        try {
            MIGRATION_3_4.migrate(bare)
            fail("missing station_cache")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        } finally {
            bare.close()
        }
    }

}

private fun insertSpec(
    db: SupportSQLiteDatabase,
    id: String,
    fuel: String,
    active: Int,
    deletedAt: Long?,
) {
    db.execSQL(
        """
        INSERT INTO vehicle_specifications (
            id, vehicle_id, fuel_kind, test_cycle, measure_kind, source, is_active, deleted_at, revision
        ) VALUES (?, 'v', ?, 'WLTP', 'LIQUID', 'MANUAL', ?, ?, 1)
        """.trimIndent(),
        arrayOf<Any?>(id, fuel, active, deletedAt),
    )
}

private fun indexNames(db: SupportSQLiteDatabase): Set<String> {
    val names = mutableSetOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
        while (cursor.moveToNext()) names += cursor.getString(0)
    }
    return names
}

private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean {
    return db.query(
        "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }
}

private fun createVersion1(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE vehicles (
            id TEXT NOT NULL PRIMARY KEY,
            created_at INTEGER NOT NULL,
            manufacturer TEXT NOT NULL,
            model TEXT NOT NULL,
            model_year INTEGER NOT NULL,
            powertrain TEXT NOT NULL,
            primary_fuel TEXT NOT NULL,
            secondary_fuel TEXT,
            plate TEXT,
            vin TEXT,
            purchase_date TEXT,
            purchase_price_minor INTEGER,
            purchase_currency TEXT,
            generation TEXT,
            engine TEXT,
            power_unscaled INTEGER,
            power_scale INTEGER,
            power_unit TEXT,
            transmission TEXT,
            drivetrain TEXT,
            registration_year INTEGER,
            distance_unit TEXT NOT NULL,
            economy_preference TEXT NOT NULL,
            notes TEXT,
            image_attachment_id TEXT,
            last_fuel_kind TEXT,
            last_station_name TEXT,
            last_full_tank INTEGER,
            deleted_at INTEGER,
            revision INTEGER NOT NULL,
            install_id TEXT,
            content_hash TEXT
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE categories (
            id TEXT NOT NULL PRIMARY KEY,
            parent_group TEXT NOT NULL,
            is_system INTEGER NOT NULL,
            sort_order INTEGER NOT NULL,
            display_name TEXT NOT NULL,
            deleted_at INTEGER,
            revision INTEGER NOT NULL,
            install_id TEXT,
            content_hash TEXT
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE expenses (
            id TEXT NOT NULL PRIMARY KEY,
            vehicle_id TEXT NOT NULL,
            category_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            total_currency TEXT NOT NULL,
            total_minor INTEGER NOT NULL,
            note TEXT,
            origin TEXT NOT NULL,
            origin_id TEXT,
            deleted_at INTEGER,
            revision INTEGER NOT NULL,
            install_id TEXT,
            content_hash TEXT
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE refuels (
            id TEXT NOT NULL PRIMARY KEY,
            vehicle_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            quantity_amount INTEGER NOT NULL,
            quantity_unit TEXT NOT NULL,
            fuel_kind TEXT NOT NULL,
            energy_kind TEXT NOT NULL,
            unit_price_currency TEXT NOT NULL,
            unit_price_scale INTEGER NOT NULL,
            unit_price_unscaled INTEGER NOT NULL,
            unit_price_derived INTEGER NOT NULL,
            total_currency TEXT NOT NULL,
            total_minor INTEGER NOT NULL,
            amounts_disagreed INTEGER NOT NULL,
            full_tank INTEGER NOT NULL,
            station_name TEXT,
            station_osm_type TEXT,
            station_osm_id INTEGER,
            latitude REAL,
            longitude REAL,
            note TEXT,
            odometer_reading_id TEXT,
            expense_id TEXT NOT NULL,
            deleted_at INTEGER,
            revision INTEGER NOT NULL,
            install_id TEXT,
            content_hash TEXT
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE vehicle_specifications (
            id TEXT NOT NULL PRIMARY KEY,
            vehicle_id TEXT NOT NULL,
            fuel_kind TEXT NOT NULL,
            is_active INTEGER NOT NULL,
            deleted_at INTEGER
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE odometer_readings (
            id TEXT NOT NULL PRIMARY KEY,
            source TEXT NOT NULL,
            source_entity_id TEXT
        )
        """.trimIndent(),
    )
}

