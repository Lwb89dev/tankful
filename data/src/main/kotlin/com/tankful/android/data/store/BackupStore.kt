package com.tankful.android.data.store

import com.tankful.android.catalog.SYSTEM_CATEGORIES
import com.tankful.android.data.db.CategoryEntity
import com.tankful.android.data.db.TankfulDatabase
import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import com.tankful.android.json.toCanonicalString
import com.tankful.android.ledger.JudgementRow
import com.tankful.android.ledger.Logbook
import com.tankful.android.model.EXPORT_SCHEMA
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.SETTINGS_ENTITY_ID
import com.tankful.android.money.MAJOR_CURRENCIES
import com.tankful.android.quantity.MEASURE_IMPERIAL
import com.tankful.android.quantity.MEASURE_METRIC
import com.tankful.android.sync.RELAY_LIST_CAP
import com.tankful.android.sync.relayUrlAllowed
import com.tankful.android.transfer.AttachmentMeta
import com.tankful.android.transfer.DeletedRef
import com.tankful.android.transfer.ExpenseContent
import com.tankful.android.transfer.ExportDocument
import com.tankful.android.transfer.ImportPreview
import com.tankful.android.transfer.ImportRowKind
import com.tankful.android.transfer.IntervalJudgementContent
import com.tankful.android.transfer.LocalHead
import com.tankful.android.transfer.MaintenanceContent
import com.tankful.android.transfer.RefuelContent
import com.tankful.android.transfer.SettingsContent
import com.tankful.android.transfer.VehicleContent
import com.tankful.android.transfer.attachmentFile
import com.tankful.android.transfer.encodeExport
import com.tankful.android.transfer.judgementContentHash
import com.tankful.android.transfer.maintenanceContentOf
import com.tankful.android.transfer.previewImport
import com.tankful.android.transfer.refuelContentOf
import com.tankful.android.transfer.restoreExpense
import com.tankful.android.transfer.restoreMaintenance
import com.tankful.android.transfer.restoreRefuel
import com.tankful.android.transfer.standaloneExpenseContent
import com.tankful.android.transfer.userSettings
import com.tankful.android.transfer.vehicleContentOf
import com.tankful.android.transfer.zipBackup
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

enum class ImportChoice { KEEP, REPLACE, SKIP }

@Singleton
class BackupStore @Inject constructor(
    private val database: TankfulDatabase,
    private val log: GarageLog,
    private val device: DeviceSettings,
) {
    suspend fun exportDocument(appVersion: String): ExportDocument {
        val book = log.read()
        val catalog = database.catalog()
        val vehicles = book.vehicles.values.filter { it.deletedAt == null }.map { vehicleContentOf(it) }
        val refuels = book.refuels.values.filter { it.deletedAt == null }.map { row ->
            refuelContentOf(row, liveMetres(book, row.odometerReadingId))
        }
        val expenses = book.expenses.values.mapNotNull { row ->
            if (row.deletedAt != null) null else standaloneExpenseContent(row)
        }
        val maintenance = book.maintenance.values.filter { it.deletedAt == null }.map { row ->
            maintenanceContentOf(row, liveMetres(book, row.odometerReadingId))
        }
        val judgements = book.judgements.values.filter { it.deletedAt == null }.map { row ->
            IntervalJudgementContent(row.id, row.openingRefuelId, row.closingRefuelId, row.confirmed)
        }
        val settingsRow = catalog.settings(SETTINGS_ENTITY_ID)
        val settings = settingsRow?.let { row ->
            userSettings(
                row.defaultCurrency,
                decodeRelays(row.relayUrlsJson),
                row.defaultLeadTimeDays,
                device.theme.first(),
                device.measure.first(),
            )
        }
        val deleted = mutableListOf<DeletedRef>()
        for (row in book.vehicles.values) if (row.deletedAt != null) deleted += DeletedRef("vehicle", row.id)
        for (row in book.refuels.values) if (row.deletedAt != null) deleted += DeletedRef("refuel", row.id)
        for (row in book.expenses.values) {
            if (row.origin == ExpenseOrigin.STANDALONE && row.deletedAt != null) deleted += DeletedRef("expense", row.id)
        }
        for (row in book.maintenance.values) if (row.deletedAt != null) deleted += DeletedRef("maintenance", row.id)
        for (row in book.judgements.values) if (row.deletedAt != null) deleted += DeletedRef("interval_judgement", row.id)
        val customCategories = catalog.categoriesNow()
            .filter { !it.isSystem && it.deletedAt == null }
            .map { row -> jsonId(row.id, "displayName" to row.displayName, "parentGroup" to row.parentGroup) }
        return ExportDocument(
            schema = EXPORT_SCHEMA,
            exportedAt = Instant.now().toCanonicalString(),
            appVersion = appVersion,
            vehicles = vehicles,
            refuels = refuels,
            expenses = expenses,
            categories = customCategories,
            maintenanceRecords = maintenance,
            intervalJudgements = judgements,
            attachments = catalog.attachments().map { row ->
                AttachmentMeta(row.id, row.relativePath, row.filename, row.mime)
            },
            settings = settings,
            deleted = deleted,
        )
    }

    suspend fun exportJson(appVersion: String): String = encodeExport(exportDocument(appVersion))

    suspend fun exportZip(appVersion: String, filesDir: File): ByteArray {
        val document = exportDocument(appVersion)
        val files = linkedMapOf<String, ByteArray>()
        for (attachment in document.attachments) {
            val file = attachmentFile(filesDir, attachment.relativePath) ?: continue
            if (file.isFile) files[attachment.filename] = file.readBytes()
        }
        return zipBackup(encodeExport(document), files)
    }

    suspend fun preview(json: String): ImportPreview {
        val book = log.read()
        val local = linkedMapOf<String, LocalHead>()
        for (row in book.vehicles.values) {
            local[row.id] = LocalHead(row.contentHash ?: "", row.deletedAt != null)
        }
        for (row in book.refuels.values) {
            local[row.id] = LocalHead(row.contentHash ?: "", row.deletedAt != null)
        }
        for (row in book.judgements.values) {
            val content = IntervalJudgementContent(
                row.id,
                row.openingRefuelId,
                row.closingRefuelId,
                row.confirmed,
            )
            local[row.id] = LocalHead(row.contentHash ?: judgementContentHash(content), row.deletedAt != null)
        }
        for (row in book.expenses.values) {
            if (row.origin != ExpenseOrigin.STANDALONE) continue
            local[row.id] = LocalHead(row.contentHash ?: "", row.deletedAt != null)
        }
        for (row in book.maintenance.values) {
            local[row.id] = LocalHead(row.contentHash ?: "", row.deletedAt != null)
        }
        val stored = database.catalog().settings(SETTINGS_ENTITY_ID)
        if (stored != null) local[stored.id] = LocalHead(stored.contentHash ?: "", false)
        return previewImport(json, local)
    }

    suspend fun apply(json: String, choices: Map<String, ImportChoice>): ImportPreview {
        val preview = preview(json)
        if (preview.refused != null) return preview
        val document = canonicalJson.decodeFromString(ExportDocument.serializer(), json)
        restoreCategories(document.categories)
        val allowed = database.catalog().categoriesNow().map { it.id }.toSet()
        val kinds = preview.rows.associate { it.id to it.kind }
        log.edit { book -> writeDocument(book, document, kinds, choices, allowed) }
        restoreSettings(document.settings, choices[document.settings?.id])
        return preview
    }

    private fun writeDocument(
        book: Logbook,
        document: ExportDocument,
        kinds: Map<String, ImportRowKind>,
        choices: Map<String, ImportChoice>,
        allowed: Set<String>,
    ) {
        for (vehicle in document.vehicles) {
            if (!accept(kinds[vehicle.id], choices[vehicle.id])) continue
            book.addVehicle(vehicle.toRow(book.vehicles[vehicle.id]))
        }
        for (refuel in document.refuels) writeRefuel(book, refuel, kinds, choices)
        for (expense in document.expenses) writeExpense(book, expense, kinds, choices, allowed)
        for (record in document.maintenanceRecords) writeMaintenance(book, record, kinds, choices, allowed)
        for (judgement in document.intervalJudgements) writeJudgement(book, judgement, kinds, choices)
        for (deleted in document.deleted) {
            if (kinds[deleted.id] == ImportRowKind.STAY_DELETED) tombstone(book, deleted.id)
        }
    }

    private fun writeRefuel(
        book: Logbook,
        decoded: RefuelContent,
        kinds: Map<String, ImportRowKind>,
        choices: Map<String, ImportChoice>,
    ) {
        if (!accept(kinds[decoded.id], choices[decoded.id])) return
        val existing = book.refuels[decoded.id]
        val revision = (existing?.revision ?: 0L) + 1L
        book.restoreRefuel(decoded, revision, existing?.installId, null)
    }

    private fun writeExpense(
        book: Logbook,
        decoded: ExpenseContent,
        kinds: Map<String, ImportRowKind>,
        choices: Map<String, ImportChoice>,
        allowed: Set<String>,
    ) {
        if (!accept(kinds[decoded.id], choices[decoded.id])) return
        val existing = book.expenses[decoded.id]
        val revision = (existing?.revision ?: 0L) + 1L
        book.restoreExpense(decoded, allowed, revision, existing?.installId, null)
    }

    private fun writeMaintenance(
        book: Logbook,
        decoded: MaintenanceContent,
        kinds: Map<String, ImportRowKind>,
        choices: Map<String, ImportChoice>,
        allowed: Set<String>,
    ) {
        if (!accept(kinds[decoded.id], choices[decoded.id])) return
        val existing = book.maintenance[decoded.id]
        val revision = (existing?.revision ?: 0L) + 1L
        book.restoreMaintenance(decoded, allowed, revision, existing?.installId, null)
    }

    private fun writeJudgement(
        book: Logbook,
        decoded: IntervalJudgementContent,
        kinds: Map<String, ImportRowKind>,
        choices: Map<String, ImportChoice>,
    ) {
        if (!accept(kinds[decoded.id], choices[decoded.id])) return
        val existing = book.judgements[decoded.id]
        book.judgements[decoded.id] = JudgementRow(
            id = decoded.id,
            openingRefuelId = decoded.openingRefuelId,
            closingRefuelId = decoded.closingRefuelId,
            confirmed = decoded.confirmed,
            deletedAt = null,
            revision = (existing?.revision ?: 0L) + 1L,
            contentHash = null,
        )
    }

    private suspend fun restoreCategories(categories: List<JsonObject>) {
        val system = SYSTEM_CATEGORIES.map { it.id }.toSet()
        val rows = categories.mapNotNull { customCategory(it, system) }
        if (rows.isEmpty()) return
        database.catalog().upsertCategories(rows)
    }

    private suspend fun restoreSettings(content: SettingsContent?, choice: ImportChoice?) {
        if (content == null || content.id != SETTINGS_ENTITY_ID || choice == ImportChoice.SKIP) return
        val current = database.catalog().settings(SETTINGS_ENTITY_ID) ?: return
        val currency = content.defaultCurrency.takeIf { it in MAJOR_CURRENCIES } ?: current.defaultCurrency
        val relays = content.relayUrls.map { it.trim() }.filter { relayUrlAllowed(it) }.distinct().take(RELAY_LIST_CAP)
        val lead = content.defaultLeadTimeDays.coerceIn(0, 365)
        database.catalog().upsertSettings(
            current.copy(
                defaultCurrency = currency,
                relayUrlsJson = canonicalJson.encodeToString(relays),
                defaultLeadTimeDays = lead,
            ),
        )
        val theme = content.theme
        if (theme == "SYSTEM" || theme == "LIGHT" || theme == "DARK") device.setTheme(theme)
        val measure = content.measure
        if (measure == MEASURE_METRIC || measure == MEASURE_IMPERIAL) device.setMeasure(measure)
    }

    private fun tombstone(book: Logbook, id: String) {
        val vehicle = book.vehicles[id]
        if (vehicle != null && vehicle.deletedAt == null) book.deleteVehicle(id)
        val refuel = book.refuels[id]
        if (refuel != null && refuel.deletedAt == null) book.deleteRefuel(id)
        val expense = book.expenses[id]
        if (expense != null && expense.origin == ExpenseOrigin.STANDALONE && expense.deletedAt == null) {
            book.expenses[id] = expense.copy(deletedAt = expense.timestampEpochMs)
        }
        val record = book.maintenance[id]
        if (record != null && record.deletedAt == null) tombstoneMaintenance(book, record)
        val judgement = book.judgements[id]
        if (judgement != null) book.judgements[id] = judgement.copy(deletedAt = judgement.deletedAt ?: 0L)
    }
}

private fun liveMetres(book: Logbook, readingId: String?): Long? {
    val reading = readingId?.let { book.readings[it] } ?: return null
    if (reading.deletedAt != null) return null
    return reading.metres
}

private fun accept(kind: ImportRowKind?, choice: ImportChoice?): Boolean {
    if (kind == null || kind == ImportRowKind.MALFORMED || kind == ImportRowKind.AUTO_SKIP) return false
    if (kind == ImportRowKind.STAY_DELETED) return false
    if (kind == ImportRowKind.CONFLICT) return choice == ImportChoice.REPLACE
    return choice != ImportChoice.SKIP
}

private fun tombstoneMaintenance(book: Logbook, record: com.tankful.android.ledger.MaintenanceRow) {
    book.maintenance[record.id] = record.copy(deletedAt = record.timestampEpochMs)
    val expense = book.expenses[record.expenseId]
    if (expense != null && expense.deletedAt == null) {
        book.expenses[record.expenseId] = expense.copy(deletedAt = record.timestampEpochMs)
    }
}

private fun customCategory(obj: JsonObject, system: Set<String>): CategoryEntity? {
    val id = obj.string("id") ?: return null
    if (id in system || id.length !in 1..80 || id.any { it.isWhitespace() }) return null
    val name = obj.string("displayName")?.trim()?.take(80)?.ifBlank { null } ?: return null
    val group = obj.string("parentGroup")?.trim()?.take(40)?.ifBlank { null } ?: "other"
    return CategoryEntity(id, group, false, 100, name, null, 1, null, null)
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun VehicleContent.toRow(existing: com.tankful.android.ledger.VehicleRow?): com.tankful.android.ledger.VehicleRow {
    val created = Instant.parse(createdAt)
    return com.tankful.android.ledger.VehicleRow(
        id = id,
        createdAt = existing?.createdAt ?: created,
        manufacturer = manufacturer,
        model = model,
        modelYear = modelYear,
        purchaseDate = purchaseDate,
        deletedAt = null,
        powertrain = powertrain,
        primaryFuel = primaryFuel,
        secondaryFuel = secondaryFuel,
        plate = plate,
        vin = vin,
        purchasePriceMinor = purchasePriceMinor,
        purchaseCurrency = purchaseCurrency,
        tagIds = tagIds,
        contentHash = sha256Hex(canonicalJson.encodeToString(this)),
        nickname = nickname,
    )
}

private fun decodeRelays(json: String): List<String> {
    val element = canonicalJson.parseToJsonElement(json)
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
}

private fun jsonId(id: String, vararg pairs: Pair<String, String>): JsonObject {
    return buildJsonObject {
        put("id", id)
        for (pair in pairs) put(pair.first, pair.second)
    }
}
