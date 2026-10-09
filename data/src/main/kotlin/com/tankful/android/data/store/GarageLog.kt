package com.tankful.android.data.store

import androidx.room.withTransaction
import com.tankful.android.data.db.EntityTagEntity
import com.tankful.android.data.db.SpecificationEntity
import com.tankful.android.data.db.TankfulDatabase
import com.tankful.android.ledger.Logbook
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose

@Singleton
class GarageLog @Inject constructor(
    private val database: TankfulDatabase,
    private val device: DeviceSettings,
) {
    private val gate = Mutex()

    private class Written<T>(val value: T, val selected: String?)

    fun observe(): Flow<Logbook> {
        val tables = arrayOf(
            "vehicles",
            "refuels",
            "expenses",
            "odometer_readings",
            "maintenance_records",
            "interval_judgements",
        )
        return callbackFlow {
            val observer = object : androidx.room.InvalidationTracker.Observer(tables) {
                override fun onInvalidated(tables: Set<String>) {
                    trySend(Unit)
                }
            }
            database.invalidationTracker.addObserver(observer)
            trySend(Unit)
            awaitClose { database.invalidationTracker.removeObserver(observer) }
        }.map { load() }.flowOn(Dispatchers.IO)
    }

    suspend fun read(): Logbook = withContext(Dispatchers.IO) { load() }

    suspend fun <T> edit(spec: SpecificationEntity? = null, block: (Logbook) -> T): T = gate.withLock {
        withContext(Dispatchers.IO) { commitEdit(spec, block) }
    }

    private suspend fun <T> commitEdit(spec: SpecificationEntity?, block: (Logbook) -> T): T {
        val sync = device.syncEnabled.first()
        val installId = if (sync) device.ensureInstallId() else ""
        val written = database.withTransaction { applyEdit(spec, block, sync, installId) }
        device.setSelectedVehicleId(written.selected)
        return written.value
    }

    private suspend fun <T> applyEdit(
        spec: SpecificationEntity?,
        block: (Logbook) -> T,
        sync: Boolean,
        installId: String,
    ): Written<T> {
        val book = load()
        val result = block(book)
        val pending = stampLogbook(book, sync, installId, Instant.now())
        persist(book)
        for (row in pending) database.sync().upsertOutbox(row)
        writeActiveSpec(spec)
        return Written(result, book.selectedVehicleId)
    }

    private suspend fun writeActiveSpec(spec: SpecificationEntity?) {
        if (spec == null) return
        val dao = database.catalog()
        if (spec.isActive && spec.deletedAt == null) deactivateTwins(spec)
        dao.upsertSpecification(spec)
    }

    private suspend fun deactivateTwins(spec: SpecificationEntity) {
        val dao = database.catalog()
        for (other in dao.specificationsNow()) {
            if (!replacesActiveSpec(other, spec)) continue
            dao.upsertSpecification(other.copy(isActive = false))
        }
    }

    private fun replacesActiveSpec(existing: SpecificationEntity, spec: SpecificationEntity): Boolean {
        if (existing.id == spec.id) return false
        if (existing.vehicleId != spec.vehicleId) return false
        if (existing.fuelKind != spec.fuelKind) return false
        if (!existing.isActive || existing.deletedAt != null) return false
        return true
    }

    suspend fun search(raw: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cleaned = raw.replace("\"", " ").trim()
        if (cleaned.isEmpty()) return@withContext emptyList()
        val tokens = cleaned.split(Regex("\\s+"))
        val match = tokens.joinToString(" ") { token -> "$token*" }
        val hits = queryHits(
            "SELECT entity_type, entity_id FROM timeline_fts WHERE timeline_fts MATCH ? LIMIT 50",
            arrayOf(match),
        )
        if (hits.isNotEmpty() || tokens.isEmpty()) return@withContext hits
        val like = tokens.joinToString(" AND ") { "body LIKE ?" }
        val args = tokens.map { "%$it%" }.toTypedArray()
        queryHits(
            "SELECT entity_type, entity_id FROM timeline_fts WHERE $like LIMIT 50",
            args,
        )
    }

    private fun queryHits(sql: String, args: Array<String>): List<Pair<String, String>> {
        val hits = mutableListOf<Pair<String, String>>()
        try {
            database.openHelper.writableDatabase.query(sql, args).use { cursor ->
                while (cursor.moveToNext()) hits += cursor.getString(0) to cursor.getString(1)
            }
        } catch (ignored: Exception) {
            return emptyList()
        }
        return hits
    }

    private suspend fun load(): Logbook {
        val dao = database.ledger()
        val grouped = dao.entityTags().groupBy { it.entityType to it.entityId }
        fun links(type: String, id: String): List<String> {
            return grouped[type to id].orEmpty().map { it.tagId }.sorted()
        }
        val book = Logbook()
        for (row in dao.vehicles()) book.vehicles[row.id] = row.toRow(links("vehicle", row.id))
        for (row in dao.expenses()) book.expenses[row.id] = row.toRow(links("expense", row.id))
        for (row in dao.readings()) book.readings[row.id] = row.toRow()
        for (row in dao.refuels()) book.refuels[row.id] = row.toRow(links("refuel", row.id))
        for (row in dao.maintenance()) book.maintenance[row.id] = row.toRow(links("maintenance", row.id))
        for (row in dao.maintenanceItems()) book.maintenanceItems[row.id] = row.toRow()
        for (row in dao.judgements()) book.judgements[row.id] = row.toRow()
        book.selectedVehicleId = device.peekSelected()
        return book
    }

    private suspend fun persist(book: Logbook) {
        val dao = database.ledger()
        val vehicles = book.vehicles.values.map { it.toEntity() }
        val readings = book.readings.values.map { it.toEntity() }
        val expenses = book.expenses.values.map { it.toEntity() }
        val refuels = book.refuels.values.map { it.toEntity() }
        val maintenance = book.maintenance.values.map { it.toEntity() }
        val items = book.maintenanceItems.values.map { it.toEntity() }
        val judgements = book.judgements.values.map { it.toEntity() }
        if (vehicles.isNotEmpty()) dao.upsertVehicles(vehicles)
        if (readings.isNotEmpty()) dao.upsertReadings(readings)
        if (expenses.isNotEmpty()) dao.upsertExpenses(expenses)
        if (refuels.isNotEmpty()) dao.upsertRefuels(refuels)
        if (maintenance.isNotEmpty()) dao.upsertMaintenance(maintenance)
        if (items.isNotEmpty()) dao.upsertItems(items)
        if (judgements.isNotEmpty()) dao.upsertJudgements(judgements)
        val known = database.catalog().tagsNow().map { it.id }.toSet()
        val links = mutableListOf<EntityTagEntity>()
        fun add(type: String, id: String, tagIds: List<String>) {
            for (tagId in tagIds.distinct().sorted()) {
                if (tagId in known) links += EntityTagEntity(type, id, tagId)
            }
        }
        for (row in book.vehicles.values) add("vehicle", row.id, row.tagIds)
        for (row in book.expenses.values) add("expense", row.id, row.tagIds)
        for (row in book.refuels.values) add("refuel", row.id, row.tagIds)
        for (row in book.maintenance.values) add("maintenance", row.id, row.tagIds)
        dao.clearTags()
        if (links.isNotEmpty()) dao.insertTags(links)
        writeFts(book, searchText())
    }

    private suspend fun searchText(): SearchText {
        val tags = database.catalog().tagsNow().associate { it.id to it.displayName }
        val categories = database.catalog().categoriesNow().associate { it.id to it.displayName }
        val providers = database.catalog().providersNow().associate { it.id to it.name }
        val services = database.catalog().serviceTypesNow().associate { it.id to it.displayName }
        return SearchText(tags, categories, providers, services)
    }

    private fun writeFts(book: Logbook, text: SearchText) {
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM timeline_fts")
        for (row in book.vehicles.values) {
            if (row.deletedAt != null) continue
            insertFts(db, "vehicle", row.id, listOf(row.notes, text.tagsOf(row.tagIds)))
        }
        for (row in book.refuels.values) {
            if (row.deletedAt != null) continue
            insertFts(db, "refuel", row.id, listOf(row.note, row.stationName, text.tagsOf(row.tagIds)))
        }
        for (row in book.expenses.values) {
            val standalone = row.origin == com.tankful.android.model.ExpenseOrigin.STANDALONE
            if (!standalone || row.deletedAt != null) continue
            val category = text.categories[row.categoryId]
            insertFts(db, "expense", row.id, listOf(row.note, category, text.tagsOf(row.tagIds)))
        }
        for (row in book.maintenance.values) {
            if (row.deletedAt != null) continue
            val provider = row.providerId?.let { text.providers[it] }
            val service = text.services[row.serviceTypeId]
            val category = text.categories[row.categoryId]
            insertFts(db, "maintenance", row.id, listOf(row.note, provider, service, category, text.tagsOf(row.tagIds)))
        }
        for (row in book.readings.values) {
            val manual = row.source == com.tankful.android.model.OdometerSource.MANUAL && row.deletedAt == null
            if (manual) insertFts(db, "odometer", row.id, listOf(row.note))
        }
    }
}

private data class SearchText(
    val tags: Map<String, String>,
    val categories: Map<String, String>,
    val providers: Map<String, String>,
    val services: Map<String, String>,
) {
    fun tagsOf(ids: List<String>): String {
        return ids.mapNotNull { tags[it] }.joinToString(" ")
    }
}

private fun insertFts(
    db: androidx.sqlite.db.SupportSQLiteDatabase,
    type: String,
    id: String,
    parts: List<String?>,
) {
    val body = parts.mapNotNull { part -> part?.trim()?.takeIf { it.isNotEmpty() } }.joinToString(" ")
    if (body.isBlank()) return
    db.execSQL(
        "INSERT INTO timeline_fts (entity_type, entity_id, body) VALUES (?, ?, ?)",
        arrayOf(type, id, body),
    )
}
