package com.tankful.android.data.store

import com.tankful.android.data.db.TankfulDatabase
import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import com.tankful.android.json.toCanonicalString
import com.tankful.android.ledger.ExpenseRow
import com.tankful.android.ledger.JudgementRow
import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.RefuelRow
import com.tankful.android.model.EXPORT_SCHEMA
import com.tankful.android.model.EnergyKind
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.model.SETTINGS_ENTITY_ID
import com.tankful.android.money.Money
import com.tankful.android.money.UnitPrice
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.money.deriveUnitPriceUnscaled
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import com.tankful.android.transfer.AttachmentMeta
import com.tankful.android.transfer.DeletedRef
import com.tankful.android.transfer.ExportDocument
import com.tankful.android.transfer.ImportPreview
import com.tankful.android.transfer.ImportRowKind
import com.tankful.android.transfer.IntervalJudgementContent
import com.tankful.android.transfer.LocalHead
import com.tankful.android.transfer.RefuelContent
import com.tankful.android.transfer.SettingsContent
import com.tankful.android.transfer.VehicleContent
import com.tankful.android.transfer.encodeExport
import com.tankful.android.transfer.judgementContentHash
import com.tankful.android.transfer.previewImport
import com.tankful.android.transfer.zipBackup
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

enum class ImportChoice { KEEP, REPLACE, SKIP }

@Singleton
class BackupStore @Inject constructor(
    private val database: TankfulDatabase,
    private val log: GarageLog,
) {
    suspend fun exportDocument(appVersion: String): ExportDocument {
        val book = log.read()
        val catalog = database.catalog()
        val vehicles = book.vehicles.values.filter { it.deletedAt == null }.map { row ->
            VehicleContent(
                id = row.id,
                createdAt = row.createdAt.toCanonicalString(),
                manufacturer = row.manufacturer,
                model = row.model,
                modelYear = row.modelYear,
                powertrain = row.powertrain,
                primaryFuel = row.primaryFuel,
                secondaryFuel = row.secondaryFuel,
                plate = row.plate,
                vin = row.vin,
                purchaseDate = row.purchaseDate,
                purchasePriceMinor = row.purchasePriceMinor,
                purchaseCurrency = row.purchaseCurrency,
                tagIds = row.tagIds,
                nickname = row.nickname,
            )
        }
        val refuels = book.refuels.values.filter { it.deletedAt == null }.map { row ->
            RefuelContent(
                id = row.id,
                vehicleId = row.vehicleId,
                timestamp = Instant.ofEpochMilli(row.timestampEpochMs).toCanonicalString(),
                quantityAmount = row.quantity.amount,
                quantityUnit = row.quantity.unit.name,
                fuelKind = row.fuelKind.name,
                energyKind = row.energyKind.name,
                totalCurrency = row.total.currency,
                totalMinor = row.total.minorUnits,
                fullTank = row.fullTank,
                tagIds = row.tagIds,
            )
        }
        val judgements = book.judgements.values.filter { it.deletedAt == null }.map { row ->
            IntervalJudgementContent(row.id, row.openingRefuelId, row.closingRefuelId, row.confirmed)
        }
        val settingsRow = catalog.settings(SETTINGS_ENTITY_ID)
        val settings = settingsRow?.let { row ->
            SettingsContent(
                id = row.id,
                defaultCurrency = row.defaultCurrency,
                relayUrls = decodeRelays(row.relayUrlsJson),
                defaultLeadTimeDays = row.defaultLeadTimeDays,
            )
        }
        val deleted = mutableListOf<DeletedRef>()
        for (row in book.vehicles.values) if (row.deletedAt != null) deleted += DeletedRef("vehicle", row.id)
        for (row in book.refuels.values) if (row.deletedAt != null) deleted += DeletedRef("refuel", row.id)
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
            categories = customCategories,
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
            val file = File(filesDir, attachment.relativePath)
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
        return previewImport(json, local)
    }

    suspend fun apply(json: String, choices: Map<String, ImportChoice>): ImportPreview {
        val preview = preview(json)
        if (preview.refused != null) return preview
        val document = canonicalJson.decodeFromString(ExportDocument.serializer(), json)
        log.edit { book ->
            for (row in preview.rows) applyRow(book, document, row.id, row.kind, choices[row.id])
        }
        return preview
    }

    private fun applyRow(
        book: Logbook,
        document: ExportDocument,
        id: String,
        kind: ImportRowKind,
        choice: ImportChoice?,
    ) {
        if (kind == ImportRowKind.MALFORMED || kind == ImportRowKind.AUTO_SKIP) return
        if (kind == ImportRowKind.CONFLICT && choice != ImportChoice.REPLACE) return
        if (kind == ImportRowKind.INSERT && choice == ImportChoice.SKIP) return
        if (kind == ImportRowKind.STAY_DELETED) {
            tombstone(book, id)
            return
        }
        val vehicle = document.vehicles.firstOrNull { it.id == id }
        if (vehicle != null) {
            applyVehicle(book, vehicle, kind, choice)
            return
        }
        val refuel = document.refuels.firstOrNull { it.id == id }
        if (refuel != null) {
            applyImportedRefuel(book, refuel, kind, choice)
            return
        }
        val judgement = document.intervalJudgements.firstOrNull { it.id == id }
        if (judgement != null) applyImportedJudgement(book, judgement, kind, choice)
    }

    private fun applyVehicle(book: Logbook, vehicle: VehicleContent, kind: ImportRowKind, choice: ImportChoice?) {
        if (kind != ImportRowKind.INSERT && choice != ImportChoice.REPLACE) return
        val existing = book.vehicles[vehicle.id]
        if (kind == ImportRowKind.INSERT && existing != null && existing.deletedAt == null) return
        book.addVehicle(vehicle.toRow(existing))
    }

    private fun applyImportedRefuel(book: Logbook, decoded: RefuelContent, kind: ImportRowKind, choice: ImportChoice?) {
        if (kind != ImportRowKind.INSERT && choice != ImportChoice.REPLACE) return
        val existing = book.refuels[decoded.id]
        if (kind == ImportRowKind.INSERT && existing != null && existing.deletedAt == null) return
        val row = refuelFromImport(existing, decoded) ?: return
        val expenseId = row.expenseId
        val previousExpense = book.expenses[expenseId]
        book.expenses[expenseId] = ExpenseRow(
            id = expenseId,
            vehicleId = row.vehicleId,
            categoryId = "fuel",
            timestampEpochMs = row.timestampEpochMs,
            total = row.total,
            note = row.note,
            origin = ExpenseOrigin.REFUEL,
            originId = row.id,
            revision = (previousExpense?.revision ?: 0L) + 1L,
        )
        book.refuels[row.id] = row
    }

    private fun applyImportedJudgement(
        book: Logbook,
        decoded: IntervalJudgementContent,
        kind: ImportRowKind,
        choice: ImportChoice?,
    ) {
        if (kind != ImportRowKind.INSERT && choice != ImportChoice.REPLACE) return
        val existing = book.judgements[decoded.id]
        if (kind == ImportRowKind.INSERT && existing != null && existing.deletedAt == null) return
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

    private fun tombstone(book: com.tankful.android.ledger.Logbook, id: String) {
        val vehicle = book.vehicles[id]
        if (vehicle != null && vehicle.deletedAt == null) book.deleteVehicle(id)
        val refuel = book.refuels[id]
        if (refuel != null && refuel.deletedAt == null) book.deleteRefuel(id)
        val judgement = book.judgements[id]
        if (judgement != null) {
            book.judgements[id] = judgement.copy(deletedAt = judgement.deletedAt ?: 0L)
        }
    }
}

private fun refuelFromImport(existing: RefuelRow?, decoded: RefuelContent): RefuelRow? {
    if (decoded.quantityAmount <= 0L) return null
    val fuel = runCatching { FuelKind.valueOf(decoded.fuelKind) }.getOrNull() ?: return null
    val stamp = runCatching { Instant.parse(decoded.timestamp).toEpochMilli() }.getOrNull() ?: return null
    val unit = if (fuel == FuelKind.CNG) QuantityUnit.GRAM else QuantityUnit.MILLILITRE
    val quantity = Quantity.of(unit, decoded.quantityAmount)
    val total = runCatching { Money.of(decoded.totalCurrency, decoded.totalMinor) }.getOrNull() ?: return null
    val price = keptPrice(existing, quantity, total) ?: derivedPrice(total.currency, total.minorUnits, decoded.quantityAmount) ?: return null
    val energy = runCatching { EnergyKind.valueOf(decoded.energyKind) }.getOrDefault(EnergyKind.LIQUID)
    if (energy == EnergyKind.ELECTRIC) return null
    if (existing == null) {
        return RefuelRow(
            id = decoded.id,
            vehicleId = decoded.vehicleId,
            timestampEpochMs = stamp,
            quantity = quantity,
            fuelKind = fuel,
            energyKind = energy,
            unitPrice = price,
            total = total,
            amountsDisagreed = false,
            fullTank = decoded.fullTank,
            stationName = null,
            note = null,
            tagIds = decoded.tagIds,
            odometerReadingId = null,
            expenseId = "expense-${decoded.id}",
        )
    }
    return existing.copy(
        vehicleId = decoded.vehicleId,
        timestampEpochMs = stamp,
        quantity = quantity,
        fuelKind = fuel,
        energyKind = energy,
        unitPrice = price,
        total = total,
        fullTank = decoded.fullTank,
        tagIds = decoded.tagIds,
        deletedAt = null,
        revision = existing.revision + 1L,
        contentHash = null,
    )
}

private fun keptPrice(existing: RefuelRow?, quantity: Quantity, total: Money): UnitPrice? {
    if (existing == null) return null
    if (existing.quantity != quantity || existing.total != total) return null
    return existing.unitPrice
}

private fun derivedPrice(currency: String, minor: Long, quantity: Long): UnitPrice? {
    return runCatching {
        val scale = currencyMinorScale(currency)
        val unscaled = deriveUnitPriceUnscaled(minor, quantity, 3, scale)
        UnitPrice.of(currency, 3, unscaled, true)
    }.getOrNull()
}

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
