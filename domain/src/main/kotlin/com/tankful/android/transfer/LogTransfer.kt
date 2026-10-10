package com.tankful.android.transfer

import com.tankful.android.catalog.SYSTEM_CATEGORIES
import com.tankful.android.catalog.SYSTEM_SERVICE_TYPES
import com.tankful.android.json.toCanonicalString
import com.tankful.android.ledger.ExpenseRow
import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.MaintenanceRow
import com.tankful.android.ledger.ReadingRow
import com.tankful.android.ledger.RefuelRow
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.model.EnergyKind
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.model.NOTE_MAX
import com.tankful.android.model.ODOMETER_HUGE_METRES
import com.tankful.android.model.OdometerSource
import com.tankful.android.model.SETTINGS_ENTITY_ID
import com.tankful.android.model.STATION_MAX
import com.tankful.android.model.TAG_MAX_COUNT
import com.tankful.android.model.TAG_MAX_LENGTH
import com.tankful.android.money.Money
import com.tankful.android.money.UnitPrice
import com.tankful.android.money.amountsDisagree
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.money.deriveUnitPriceUnscaled
import com.tankful.android.quantity.MEASURE_IMPERIAL
import com.tankful.android.quantity.MEASURE_METRIC
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import com.tankful.android.sync.RELAY_LIST_CAP
import com.tankful.android.sync.relayUrlAllowed
import java.time.Instant

private val SYSTEM_CATEGORY_IDS = SYSTEM_CATEGORIES.map { it.id }.toSet()
private val SERVICE_IDS = SYSTEM_SERVICE_TYPES.map { it.id }.toSet()

fun vehicleContentOf(row: VehicleRow): VehicleContent {
    return VehicleContent(
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
        tagIds = row.tagIds.sorted(),
        nickname = row.nickname,
    )
}

fun refuelContentOf(row: RefuelRow, metres: Long?): RefuelContent {
    return RefuelContent(
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
        tagIds = row.tagIds.sorted(),
        stationName = row.stationName,
        note = row.note,
        unitPriceScale = row.unitPrice.scale,
        unitPriceUnscaled = row.unitPrice.unscaledAmount,
        unitPriceDerived = row.unitPrice.derived,
        odometerMetres = metres,
    )
}

fun standaloneExpenseContent(row: ExpenseRow): ExpenseContent? {
    if (row.origin != ExpenseOrigin.STANDALONE) return null
    return ExpenseContent(
        id = row.id,
        vehicleId = row.vehicleId,
        categoryId = row.categoryId,
        timestamp = Instant.ofEpochMilli(row.timestampEpochMs).toCanonicalString(),
        totalCurrency = row.total.currency,
        totalMinor = row.total.minorUnits,
        origin = row.origin.name,
        note = row.note,
        originId = null,
        tagIds = row.tagIds.sorted(),
    )
}

fun maintenanceContentOf(row: MaintenanceRow, metres: Long?): MaintenanceContent {
    return MaintenanceContent(
        id = row.id,
        vehicleId = row.vehicleId,
        timestamp = Instant.ofEpochMilli(row.timestampEpochMs).toCanonicalString(),
        totalCurrency = row.total.currency,
        totalMinor = row.total.minorUnits,
        categoryId = row.categoryId,
        serviceTypeId = row.serviceTypeId,
        note = row.note,
        labourMinor = row.labour?.minorUnits,
        partsMinor = row.parts?.minorUnits,
        odometerMetres = metres,
        expenseId = row.expenseId,
        tagIds = row.tagIds.sorted(),
    )
}

fun userSettings(
    currency: String,
    relays: List<String>,
    leadDays: Int,
    theme: String,
    measure: String,
): SettingsContent {
    val urls = relays.map { it.trim() }.filter { relayUrlAllowed(it) }.distinct().take(RELAY_LIST_CAP)
    return SettingsContent(
        id = SETTINGS_ENTITY_ID,
        defaultCurrency = currency,
        relayUrls = urls,
        defaultLeadTimeDays = leadDays.coerceIn(0, 365),
        theme = theme.takeIf { it == "SYSTEM" || it == "LIGHT" || it == "DARK" },
        measure = measure.takeIf { it == MEASURE_METRIC || it == MEASURE_IMPERIAL },
    )
}

fun systemCategoryIds(): Set<String> = SYSTEM_CATEGORY_IDS

fun Logbook.restoreRefuel(
    decoded: RefuelContent,
    revision: Long,
    installId: String?,
    contentHash: String?,
): Boolean {
    if (vehicles[decoded.vehicleId] == null) return false
    val fuel = runCatching { FuelKind.valueOf(decoded.fuelKind) }.getOrNull() ?: return false
    if (fuel == FuelKind.ELECTRIC || fuel == FuelKind.HYDROGEN) return false
    val energy = runCatching { EnergyKind.valueOf(decoded.energyKind) }.getOrDefault(EnergyKind.LIQUID)
    if (energy == EnergyKind.ELECTRIC) return false
    if (decoded.quantityAmount <= 0L) return false
    val stamp = runCatching { Instant.parse(decoded.timestamp).toEpochMilli() }.getOrNull() ?: return false
    val total = runCatching { Money.of(decoded.totalCurrency, decoded.totalMinor) }.getOrNull() ?: return false
    val price = unitPriceOf(decoded, total) ?: return false
    val existing = refuels[decoded.id]
    val expenseId = existing?.expenseId ?: "expense-${decoded.id}"
    val unit = if (fuel == FuelKind.CNG) QuantityUnit.GRAM else QuantityUnit.MILLILITRE
    val readingId = putReading(
        decoded.id,
        existing?.odometerReadingId,
        decoded.vehicleId,
        stamp,
        decoded.odometerMetres,
        OdometerSource.REFUEL,
    )
    expenses[expenseId] = ExpenseRow(
        id = expenseId,
        vehicleId = decoded.vehicleId,
        categoryId = "fuel",
        timestampEpochMs = stamp,
        total = total,
        note = clip(decoded.note, NOTE_MAX),
        origin = ExpenseOrigin.REFUEL,
        originId = decoded.id,
        revision = revision,
        installId = installId,
    )
    refuels[decoded.id] = RefuelRow(
        id = decoded.id,
        vehicleId = decoded.vehicleId,
        timestampEpochMs = stamp,
        quantity = Quantity.of(unit, decoded.quantityAmount),
        fuelKind = fuel,
        energyKind = energy,
        unitPrice = price,
        total = total,
        amountsDisagreed = false,
        fullTank = decoded.fullTank,
        stationName = clip(decoded.stationName, STATION_MAX),
        note = clip(decoded.note, NOTE_MAX),
        tagIds = clipTags(decoded.tagIds),
        odometerReadingId = readingId,
        expenseId = expenseId,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
        deletedAt = null,
    )
    return true
}

fun Logbook.restoreExpense(
    decoded: ExpenseContent,
    allowed: Set<String>,
    revision: Long,
    installId: String?,
    contentHash: String?,
): Boolean {
    if (decoded.origin != ExpenseOrigin.STANDALONE.name || decoded.originId != null) return false
    if (decoded.categoryId !in allowed || vehicles[decoded.vehicleId] == null) return false
    val stamp = runCatching { Instant.parse(decoded.timestamp).toEpochMilli() }.getOrNull() ?: return false
    val total = runCatching { Money.of(decoded.totalCurrency, decoded.totalMinor) }.getOrNull() ?: return false
    expenses[decoded.id] = ExpenseRow(
        id = decoded.id,
        vehicleId = decoded.vehicleId,
        categoryId = decoded.categoryId,
        timestampEpochMs = stamp,
        total = total,
        note = clip(decoded.note, NOTE_MAX),
        origin = ExpenseOrigin.STANDALONE,
        originId = null,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
        tagIds = clipTags(decoded.tagIds),
        deletedAt = null,
    )
    return true
}

fun Logbook.restoreMaintenance(
    decoded: MaintenanceContent,
    allowed: Set<String>,
    revision: Long,
    installId: String?,
    contentHash: String?,
): Boolean {
    if (vehicles[decoded.vehicleId] == null || decoded.categoryId !in allowed) return false
    val stamp = runCatching { Instant.parse(decoded.timestamp).toEpochMilli() }.getOrNull() ?: return false
    val total = runCatching { Money.of(decoded.totalCurrency, decoded.totalMinor) }.getOrNull() ?: return false
    val existing = maintenance[decoded.id]
    val expenseId = decoded.expenseId?.takeIf { it.isNotBlank() } ?: existing?.expenseId ?: "expense-${decoded.id}"
    val service = decoded.serviceTypeId.takeIf { it in SERVICE_IDS } ?: "general_service"
    val labour = moneyPart(decoded.totalCurrency, decoded.labourMinor)
    val parts = moneyPart(decoded.totalCurrency, decoded.partsMinor)
    val disagreed = labour != null && parts != null && amountsDisagree(labour.minorUnits + parts.minorUnits, total.minorUnits)
    val readingId = putReading(
        decoded.id,
        existing?.odometerReadingId,
        decoded.vehicleId,
        stamp,
        decoded.odometerMetres,
        OdometerSource.MAINTENANCE,
    )
    expenses[expenseId] = ExpenseRow(
        id = expenseId,
        vehicleId = decoded.vehicleId,
        categoryId = decoded.categoryId,
        timestampEpochMs = stamp,
        total = total,
        note = clip(decoded.note, NOTE_MAX),
        origin = ExpenseOrigin.MAINTENANCE,
        originId = decoded.id,
        revision = revision,
        installId = installId,
    )
    maintenance[decoded.id] = MaintenanceRow(
        id = decoded.id,
        vehicleId = decoded.vehicleId,
        total = total,
        labour = labour,
        parts = parts,
        amountsDisagreed = disagreed,
        expenseId = expenseId,
        odometerReadingId = readingId,
        note = clip(decoded.note, NOTE_MAX),
        revision = revision,
        timestampEpochMs = stamp,
        serviceTypeId = service,
        providerId = null,
        categoryId = decoded.categoryId,
        installId = installId,
        contentHash = contentHash,
        tagIds = clipTags(decoded.tagIds),
        deletedAt = null,
    )
    return true
}

private fun moneyPart(currency: String, minor: Long?): Money? {
    if (minor == null || minor < 0L) return null
    return runCatching { Money.of(currency, minor) }.getOrNull()
}

private fun unitPriceOf(decoded: RefuelContent, total: Money): UnitPrice? {
    val scale = decoded.unitPriceScale
    val unscaled = decoded.unitPriceUnscaled
    if (scale != null && unscaled != null) {
        return runCatching { UnitPrice.of(total.currency, scale, unscaled, decoded.unitPriceDerived == true) }.getOrNull()
    }
    return runCatching {
        val derived = deriveUnitPriceUnscaled(total.minorUnits, decoded.quantityAmount, 3, currencyMinorScale(total.currency))
        UnitPrice.of(total.currency, 3, derived, true)
    }.getOrNull()
}

private fun Logbook.putReading(
    ownerId: String,
    currentId: String?,
    vehicleId: String,
    stamp: Long,
    metres: Long?,
    source: OdometerSource,
): String? {
    if (metres == null || metres < 0L || metres > ODOMETER_HUGE_METRES) return null
    val id = currentId ?: "reading-$ownerId"
    val existing = readings[id]
    readings[id] = ReadingRow(
        id = id,
        vehicleId = vehicleId,
        timestamp = Instant.ofEpochMilli(stamp),
        metres = metres,
        epochId = existing?.epochId ?: "epoch-$ownerId",
        source = source,
        sourceEntityId = ownerId,
        entryUnit = "KM_WHOLE",
        entryUnscaled = metres / 1000L,
        revision = existing?.revision ?: 1L,
        deletedAt = null,
    )
    return id
}

private fun clip(value: String?, max: Int): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.take(max)
}

private fun clipTags(ids: List<String>): List<String> {
    return ids.map { it.trim() }.filter { it.isNotEmpty() && it.length <= TAG_MAX_LENGTH }.distinct().take(TAG_MAX_COUNT)
}
