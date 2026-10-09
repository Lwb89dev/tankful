package com.tankful.android.ledger

import com.tankful.android.catalog.SYSTEM_CATEGORIES
import com.tankful.android.json.sha256Hex
import com.tankful.android.json.toCanonicalString
import com.tankful.android.model.ConfirmReason
import com.tankful.android.model.EnergyKind
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.model.OdometerSource
import com.tankful.android.model.RejectReason
import com.tankful.android.model.SaveOutcome
import com.tankful.android.model.SNAPSHOT_MAX_BYTES
import com.tankful.android.money.Money
import com.tankful.android.money.UnitPrice
import com.tankful.android.money.amountsDisagree
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.money.deriveUnitPriceUnscaled
import com.tankful.android.money.largeMoneyThreshold
import com.tankful.android.money.minorUnitsFromUnitPrice
import com.tankful.android.odometer.OdometerDecision
import com.tankful.android.odometer.OdometerDraft
import com.tankful.android.odometer.StoredReading
import com.tankful.android.odometer.evaluateOdometer
import com.tankful.android.quantity.LARGE_LIQUID_ML
import com.tankful.android.quantity.LARGE_MASS_G
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import java.time.Instant

data class VehicleRow(
    val id: String,
    val createdAt: Instant,
    val manufacturer: String,
    val model: String,
    val modelYear: Int,
    val purchaseDate: String? = null,
    val deletedAt: Long? = null,
    val powertrain: String = "ICE",
    val primaryFuel: String = "PETROL",
    val secondaryFuel: String? = null,
    val plate: String? = null,
    val vin: String? = null,
    val purchasePriceMinor: Long? = null,
    val purchaseCurrency: String? = null,
    val generation: String? = null,
    val engine: String? = null,
    val powerUnscaled: Long? = null,
    val powerScale: Int? = null,
    val powerUnit: String? = null,
    val transmission: String? = null,
    val drivetrain: String? = null,
    val registrationYear: Int? = null,
    val distanceUnit: String = "KM_WHOLE",
    val economyPreference: String = "L_PER_100KM",
    val notes: String? = null,
    val imageAttachmentId: String? = null,
    val lastFuelKind: String? = null,
    val lastStationName: String? = null,
    val lastFullTank: Boolean? = null,
    val revision: Long = 1L,
    val installId: String? = null,
    val contentHash: String? = null,
    val tagIds: List<String> = emptyList(),
    val nickname: String? = null,
)

data class ExpenseRow(
    val id: String,
    val vehicleId: String,
    val categoryId: String,
    val timestampEpochMs: Long,
    val total: Money,
    val note: String?,
    val origin: ExpenseOrigin,
    val originId: String?,
    val deletedAt: Long? = null,
    val revision: Long = 1L,
    val installId: String? = null,
    val contentHash: String? = null,
    val tagIds: List<String> = emptyList(),
)

data class ReadingRow(
    val id: String,
    val vehicleId: String,
    val timestamp: Instant,
    val metres: Long,
    val epochId: String,
    val source: OdometerSource,
    val sourceEntityId: String?,
    val deletedAt: Long? = null,
    val entryUnit: String = "KM_WHOLE",
    val entryUnscaled: Long = 0L,
    val entryScale: Int = 0,
    val reason: String? = null,
    val note: String? = null,
    val revision: Long = 1L,
    val installId: String? = null,
    val contentHash: String? = null,
)

data class RefuelRow(
    val id: String,
    val vehicleId: String,
    val timestampEpochMs: Long,
    val quantity: Quantity,
    val fuelKind: FuelKind,
    val energyKind: EnergyKind,
    val unitPrice: UnitPrice,
    val total: Money,
    val amountsDisagreed: Boolean,
    val fullTank: Boolean,
    val stationName: String?,
    val note: String?,
    val tagIds: List<String>,
    val odometerReadingId: String?,
    val expenseId: String,
    val deletedAt: Long? = null,
    val revision: Long = 1L,
    val stationOsmType: String? = null,
    val stationOsmId: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val installId: String? = null,
    val contentHash: String? = null,
)

data class MaintenanceItemRow(
    val id: String,
    val maintenanceId: String,
    val description: String,
    val deletedAt: Long? = null,
    val costMinor: Long? = null,
    val costCurrency: String? = null,
    val quantityText: String? = null,
    val revision: Long = 1L,
    val installId: String? = null,
    val contentHash: String? = null,
)

data class MaintenanceRow(
    val id: String,
    val vehicleId: String,
    val total: Money,
    val labour: Money?,
    val parts: Money?,
    val amountsDisagreed: Boolean,
    val expenseId: String,
    val odometerReadingId: String?,
    val note: String?,
    val deletedAt: Long? = null,
    val revision: Long = 1L,
    val timestampEpochMs: Long = 0L,
    val serviceTypeId: String = "general_service",
    val providerId: String? = null,
    val categoryId: String = "maintenance.scheduled_service",
    val nextDate: String? = null,
    val nextOdometerMetres: Long? = null,
    val installId: String? = null,
    val contentHash: String? = null,
    val tagIds: List<String> = emptyList(),
)

data class JudgementRow(
    val id: String,
    val openingRefuelId: String,
    val closingRefuelId: String,
    val confirmed: Boolean,
    val deletedAt: Long? = null,
    val revision: Long = 1L,
    val installId: String? = null,
    val contentHash: String? = null,
)

data class RefuelDraft(
    val id: String,
    val vehicleId: String,
    val timestamp: Instant,
    val quantity: Quantity,
    val fuelKind: FuelKind,
    val energyKind: EnergyKind = EnergyKind.LIQUID,
    val currency: String,
    val unitPriceUnscaled: Long?,
    val totalMinor: Long?,
    val fullTank: Boolean,
    val stationName: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val odometer: OdometerDraft? = null,
    val newEpochId: String = "epoch-$id",
    val stationOsmType: String? = null,
    val stationOsmId: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

sealed interface TimelineRow {
    val id: String

    data class RefuelItem(override val id: String) : TimelineRow
    data class ManualOdometerItem(override val id: String) : TimelineRow
    data class ExpenseItem(override val id: String) : TimelineRow
    data class MaintenanceItem(override val id: String) : TimelineRow
}

class Logbook {
    val vehicles = linkedMapOf<String, VehicleRow>()
    val expenses = linkedMapOf<String, ExpenseRow>()
    val readings = linkedMapOf<String, ReadingRow>()
    val refuels = linkedMapOf<String, RefuelRow>()
    val maintenance = linkedMapOf<String, MaintenanceRow>()
    val maintenanceItems = linkedMapOf<String, MaintenanceItemRow>()
    val judgements = linkedMapOf<String, JudgementRow>()
    var selectedVehicleId: String? = null

    fun addVehicle(row: VehicleRow) {
        vehicles[row.id] = row
        if (selectedVehicleId == null) selectedVehicleId = row.id
    }

    fun deleteVehicle(id: String) {
        val current = vehicles[id] ?: return
        vehicles[id] = current.copy(deletedAt = current.createdAt.toEpochMilli())
        if (selectedVehicleId == id) {
            selectedVehicleId = replacementVehicleId(vehicles.values, id, id)
        }
    }

    fun saveRefuel(
        draft: RefuelDraft,
        acknowledgements: Set<ConfirmReason>,
        now: Instant,
        chosenOdometerReason: com.tankful.android.model.OdometerReason? = null,
    ): SaveOutcome<RefuelRow> {
        val rejected = validateRefuel(draft)
        if (rejected.isNotEmpty()) return SaveOutcome.Rejected(rejected)
        val priced = resolveRefuelMoney(draft, acknowledgements)
        if (priced is MoneyPlan.Stop) return priced.outcome
        val money = priced as MoneyPlan.Ready
        val readingPlan = planReading(draft, readings.values, acknowledgements, now, chosenOdometerReason)
        if (readingPlan is ReadingPlan.Stop) return readingPlan.outcome
        val existing = refuels[draft.id]
        val revision = (existing?.revision ?: 0L) + 1L
        val expenseId = existing?.expenseId ?: "expense-${draft.id}"
        val readingId = (readingPlan as ReadingPlan.Ready).readingId
        val row = RefuelRow(
            id = draft.id,
            vehicleId = draft.vehicleId,
            timestampEpochMs = draft.timestamp.toEpochMilli(),
            quantity = draft.quantity,
            fuelKind = draft.fuelKind,
            energyKind = EnergyKind.LIQUID,
            unitPrice = money.unitPrice,
            total = money.total,
            amountsDisagreed = money.amountsDisagreed,
            fullTank = draft.fullTank,
            stationName = draft.stationName,
            note = draft.note,
            tagIds = draft.tagIds.sorted(),
            odometerReadingId = readingId,
            expenseId = expenseId,
            revision = revision,
            stationOsmType = draft.stationOsmType,
            stationOsmId = draft.stationOsmId,
            latitude = draft.latitude,
            longitude = draft.longitude,
            installId = existing?.installId,
            contentHash = existing?.contentHash,
        )
        val payload = "${row.id}|${row.total.minorUnits}|${row.note.orEmpty()}|${row.tagIds}"
        if (payload.toByteArray(Charsets.UTF_8).size >= SNAPSHOT_MAX_BYTES) {
            return SaveOutcome.Rejected(listOf(RejectReason.PAYLOAD_TOO_LARGE))
        }
        val expense = ExpenseRow(
            id = expenseId,
            vehicleId = draft.vehicleId,
            categoryId = "fuel",
            timestampEpochMs = row.timestampEpochMs,
            total = money.total,
            note = draft.note,
            origin = ExpenseOrigin.REFUEL,
            originId = draft.id,
            revision = revision,
        )
        refuels[row.id] = row
        expenses[expense.id] = expense
        val readyReading = readingPlan.reading
        if (readyReading != null) readings[readyReading.id] = readyReading
        return SaveOutcome.Saved(row)
    }

    fun deleteRefuel(id: String): SaveOutcome<Unit> {
        val row = refuels[id] ?: return SaveOutcome.Rejected(listOf(RejectReason.MISSING_VEHICLE))
        refuels[id] = row.copy(deletedAt = row.timestampEpochMs)
        val expense = expenses[row.expenseId]
        if (expense != null) expenses[row.expenseId] = expense.copy(deletedAt = row.timestampEpochMs)
        val readingId = row.odometerReadingId
        val reading = readingId?.let { readings[it] }
        if (reading != null && reading.source == OdometerSource.REFUEL) {
            readings[reading.id] = reading.copy(deletedAt = row.timestampEpochMs)
        }
        for ((judgementId, judgement) in judgements) {
            val cites = judgement.openingRefuelId == id || judgement.closingRefuelId == id
            if (cites) judgements[judgementId] = judgement.copy(deletedAt = row.timestampEpochMs)
        }
        return SaveOutcome.Saved(Unit)
    }

    fun saveStandaloneExpense(row: ExpenseRow): SaveOutcome<ExpenseRow> {
        if (row.origin != ExpenseOrigin.STANDALONE || row.originId != null) {
            return SaveOutcome.Rejected(listOf(RejectReason.MISSING_IDENTITY))
        }
        if (row.total.minorUnits < 0L) return SaveOutcome.Rejected(listOf(RejectReason.NEGATIVE_MONEY))
        expenses[row.id] = row
        return SaveOutcome.Saved(row)
    }

    fun saveMaintenance(
        row: MaintenanceRow,
        items: List<MaintenanceItemRow>,
        acknowledgements: Set<ConfirmReason>,
    ): SaveOutcome<MaintenanceRow> {
        val labour = row.labour
        val parts = row.parts
        if (labour != null && parts != null) {
            val sum = labour.minorUnits + parts.minorUnits
            if (amountsDisagree(sum, row.total.minorUnits) &&
                ConfirmReason.LABOUR_PARTS_DISAGREE !in acknowledgements
            ) {
                return SaveOutcome.NeedsConfirmation(listOf(ConfirmReason.LABOUR_PARTS_DISAGREE))
            }
        }
        val disagreed = labour != null && parts != null &&
            amountsDisagree(labour.minorUnits + parts.minorUnits, row.total.minorUnits)
        val stored = row.copy(amountsDisagreed = disagreed)
        val expense = ExpenseRow(
            id = stored.expenseId,
            vehicleId = stored.vehicleId,
            categoryId = stored.categoryId,
            timestampEpochMs = stored.timestampEpochMs,
            total = stored.total,
            note = stored.note,
            origin = ExpenseOrigin.MAINTENANCE,
            originId = stored.id,
        )
        maintenance[stored.id] = stored
        expenses[expense.id] = expense
        for (item in items) maintenanceItems[item.id] = item
        return SaveOutcome.Saved(stored)
    }

    fun timeline(): List<TimelineRow> {
        val rows = mutableListOf<TimelineRow>()
        for (refuel in refuels.values) {
            if (refuel.deletedAt == null) rows += TimelineRow.RefuelItem(refuel.id)
        }
        for (reading in readings.values) {
            val manual = reading.source == OdometerSource.MANUAL && reading.deletedAt == null
            if (manual) rows += TimelineRow.ManualOdometerItem(reading.id)
        }
        for (expense in expenses.values) {
            val standalone = expense.origin == ExpenseOrigin.STANDALONE && expense.deletedAt == null
            if (standalone) rows += TimelineRow.ExpenseItem(expense.id)
        }
        for (record in maintenance.values) {
            if (record.deletedAt == null) rows += TimelineRow.MaintenanceItem(record.id)
        }
        return rows
    }

    fun expenseMinor(currency: String): Long = sumExpenses(currency) { true }

    fun categoryMinor(categoryId: String, currency: String): Long {
        return sumExpenses(currency) { it.categoryId == categoryId }
    }

    fun refuelMinor(currency: String): Long {
        var sum = 0L
        for (refuel in refuels.values) {
            if (refuel.deletedAt != null) continue
            if (refuel.total.currency != currency) continue
            sum += refuel.total.minorUnits
        }
        return sum
    }

    private fun sumExpenses(currency: String, include: (ExpenseRow) -> Boolean): Long {
        var sum = 0L
        for (expense in expenses.values) {
            if (expense.deletedAt != null || !include(expense)) continue
            if (expense.total.currency != currency) continue
            sum += expense.total.minorUnits
        }
        return sum
    }

    fun systemCategoryIdsInExport(): Set<String> = emptySet()

    fun knownSystemCategoryIds(): Set<String> = SYSTEM_CATEGORIES.map { it.id }.toSet()
}

fun replacementVehicleId(
    vehicles: Collection<VehicleRow>,
    deletedId: String,
    selectedId: String?,
): String? {
    if (selectedId != deletedId) return selectedId
    return vehicles
        .filter { it.deletedAt == null && it.id != deletedId }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))
        .firstOrNull()
        ?.id
}

private sealed interface MoneyPlan {
    data class Ready(val unitPrice: UnitPrice, val total: Money, val amountsDisagreed: Boolean) : MoneyPlan
    data class Stop(val outcome: SaveOutcome<Nothing>) : MoneyPlan
}

private sealed interface ReadingPlan {
    data class Ready(val reading: ReadingRow?, val readingId: String?) : ReadingPlan
    data class Stop(val outcome: SaveOutcome<Nothing>) : ReadingPlan
}

private fun validateRefuel(draft: RefuelDraft): List<RejectReason> {
    val reasons = mutableListOf<RejectReason>()
    if (draft.vehicleId.isBlank()) reasons += RejectReason.MISSING_VEHICLE
    if (draft.quantity.amount <= 0L) reasons += RejectReason.NEGATIVE_OR_ZERO_QUANTITY
    if (draft.energyKind == EnergyKind.ELECTRIC) reasons += RejectReason.ELECTRIC_ENERGY
    val note = draft.note
    if (note != null && note.length > com.tankful.android.model.NOTE_MAX) {
        reasons += RejectReason.NOTE_TOO_LONG
    }
    if (draft.tagIds.size > com.tankful.android.model.TAG_MAX_COUNT) reasons += RejectReason.TOO_MANY_TAGS
    if (draft.tagIds.any { it.length > com.tankful.android.model.TAG_MAX_LENGTH }) {
        reasons += RejectReason.TAG_TOO_LONG
    }
    val station = draft.stationName
    if (station != null && station.length > com.tankful.android.model.STATION_MAX) {
        reasons += RejectReason.STATION_TOO_LONG
    }
    return reasons
}

private fun resolveRefuelMoney(draft: RefuelDraft, acknowledgements: Set<ConfirmReason>): MoneyPlan {
    val scale = currencyMinorScale(draft.currency)
    val enteredPrice = draft.unitPriceUnscaled
    val enteredTotal = draft.totalMinor
    if (enteredPrice == null && enteredTotal == null) {
        return MoneyPlan.Stop(SaveOutcome.Rejected(listOf(RejectReason.MISSING_PRICE)))
    }
    if ((enteredPrice != null && enteredPrice < 0L) || (enteredTotal != null && enteredTotal < 0L)) {
        return MoneyPlan.Stop(SaveOutcome.Rejected(listOf(RejectReason.NEGATIVE_MONEY)))
    }
    val confirms = linkedSetOf<ConfirmReason>()
    val price: UnitPrice
    val total: Money
    val disagreed: Boolean
    if (enteredPrice != null && enteredTotal == null) {
        val minor = minorUnitsFromUnitPrice(draft.quantity.amount, enteredPrice, 3, scale)
        price = UnitPrice.of(draft.currency, 3, enteredPrice, derived = false)
        total = Money.of(draft.currency, minor)
        disagreed = false
    } else if (enteredPrice == null && enteredTotal != null) {
        val derived = deriveUnitPriceUnscaled(enteredTotal, draft.quantity.amount, 3, scale)
        price = UnitPrice.of(draft.currency, 3, derived, derived = true)
        total = Money.of(draft.currency, enteredTotal)
        disagreed = false
    } else {
        val computed = minorUnitsFromUnitPrice(draft.quantity.amount, enteredPrice!!, 3, scale)
        val typed = enteredTotal!!
        disagreed = amountsDisagree(computed, typed)
        if (disagreed && ConfirmReason.AMOUNTS_DISAGREE !in acknowledgements) {
            confirms += ConfirmReason.AMOUNTS_DISAGREE
        }
        price = UnitPrice.of(draft.currency, 3, enteredPrice, derived = false)
        total = Money.of(draft.currency, typed)
    }
    if (total.minorUnits > largeMoneyThreshold(draft.currency) &&
        ConfirmReason.LARGE_MONEY !in acknowledgements
    ) {
        confirms += ConfirmReason.LARGE_MONEY
    }
    val largeQuantity = draft.quantity.unit == QuantityUnit.MILLILITRE &&
        draft.quantity.amount > LARGE_LIQUID_ML ||
        draft.quantity.unit == QuantityUnit.GRAM && draft.quantity.amount > LARGE_MASS_G
    if (largeQuantity && ConfirmReason.LARGE_QUANTITY !in acknowledgements) {
        confirms += ConfirmReason.LARGE_QUANTITY
    }
    if (confirms.isNotEmpty()) {
        return MoneyPlan.Stop(SaveOutcome.NeedsConfirmation(confirms.toList()))
    }
    return MoneyPlan.Ready(price, total, disagreed)
}

private fun planReading(
    draft: RefuelDraft,
    readings: Collection<ReadingRow>,
    acknowledgements: Set<ConfirmReason>,
    now: Instant,
    chosenReason: com.tankful.android.model.OdometerReason?,
): ReadingPlan {
    val odometer = draft.odometer ?: return ReadingPlan.Ready(null, null)
    val existing = readings
        .filter { it.vehicleId == draft.vehicleId && it.deletedAt == null && it.id != odometer.id }
        .map { StoredReading(it.id, it.timestamp, it.metres, it.epochId) }
    val decision = evaluateOdometer(
        draft = odometer,
        existing = existing,
        now = now,
        acknowledgements = acknowledgements,
        chosenReason = chosenReason,
        newEpochId = draft.newEpochId,
    )
    return when (decision) {
        is OdometerDecision.Rejected -> ReadingPlan.Stop(SaveOutcome.Rejected(decision.reasons))
        is OdometerDecision.NeedsConfirmation ->
            ReadingPlan.Stop(SaveOutcome.NeedsConfirmation(decision.reasons))
        is OdometerDecision.Accept -> {
            val reading = ReadingRow(
                id = odometer.id,
                vehicleId = draft.vehicleId,
                timestamp = odometer.timestamp,
                metres = odometer.metres,
                epochId = decision.epochId,
                source = OdometerSource.REFUEL,
                sourceEntityId = draft.id,
            )
            ReadingPlan.Ready(reading, reading.id)
        }
    }
}

fun contentHashOf(canonical: String): String = sha256Hex(canonical)

fun canonicalInstant(instant: Instant): String = instant.toCanonicalString()
