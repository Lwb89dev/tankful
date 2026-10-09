package com.tankful.android.ui

import com.tankful.android.consumption.AggregateRate
import com.tankful.android.consumption.ComparisonDirection
import com.tankful.android.consumption.IntervalResult
import com.tankful.android.consumption.OfficialFigure
import com.tankful.android.consumption.SeriesFill
import com.tankful.android.consumption.VehicleSpecification
import com.tankful.android.consumption.compareOfficial
import com.tankful.android.consumption.computeIntervals
import com.tankful.android.consumption.displayKgPer100Km
import com.tankful.android.consumption.displayKmPerMajor
import com.tankful.android.consumption.displayLitresPer100Km
import com.tankful.android.consumption.intervalsClosedIn
import com.tankful.android.consumption.observedFuelPerDistance
import com.tankful.android.consumption.summarize
import com.tankful.android.data.db.SpecificationEntity
import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.RefuelRow
import com.tankful.android.model.EconomyPreference
import com.tankful.android.model.FuelKind
import com.tankful.android.model.MeasureKind
import com.tankful.android.model.Powertrain
import com.tankful.android.model.TestCycle
import com.tankful.android.money.costPer100KmMinor
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.money.sumByCurrency
import com.tankful.android.odometer.SpanPoint
import com.tankful.android.odometer.odometerSpanMetres
import com.tankful.android.quantity.distancePerMoney
import com.tankful.android.quantity.displayOneDecimal
import com.tankful.android.quantity.odometerText
import com.tankful.android.time.InsightRange
import com.tankful.android.time.insightWindow
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

data class DashboardUi(
    val name: String,
    val hero: String,
    val secondary: String?,
    val liquidOnly: Boolean,
    val showOpen: Boolean,
    val direction: ComparisonDirection?,
    val deltaText: String?,
    val monthSpend: String,
    val spanValue: String,
    val spendPerDistance: String,
    val ownership: String?,
    val emptyGarage: Boolean,
)

data class ChartPoint(
    val label: String,
    val text: String,
    val hollow: Boolean,
    val position: Float,
    val atMillis: Long = 0L,
)

data class InsightUi(
    val hero: String,
    val points: List<ChartPoint>,
    val spend: List<ChartPoint>,
    val best: String?,
    val worst: String?,
    val low: Int,
    val direction: ComparisonDirection?,
    val deltaText: String?,
    val secondaryPoints: List<ChartPoint> = emptyList(),
    val secondaryFuel: String? = null,
    val rateUnit: String = "",
)

data class GarageLine(
    val id: String,
    val name: String,
    val make: String,
    val model: String,
    val fuel: String,
    val secondaryFuel: String?,
    val odometer: String,
    val consumption: String,
    val secondary: String?,
    val spend: String,
    val titledByNick: Boolean,
)

fun startDestination(liveVehicles: Int): String {
    if (liveVehicles == 0) return "first-run"
    return "home"
}

fun projectDashboard(
    book: Logbook,
    specs: List<SpecificationEntity>,
    now: Instant,
    zone: ZoneId,
): DashboardUi {
    val vehicle = selected(book)
    if (vehicle == null) return blankDashboard()
    val fuel = fuelOrPetrol(vehicle.primaryFuel)
    val summary = summarize(intervalsFor(book, vehicle.id, fuel), confirmed(book))
    val hero = heroText(summary.average, fuel, vehicle.economyPreference)
    val secondaryFuel = vehicle.secondaryFuel?.let { fuelOrNull(it) }
    val secondary = secondaryFuel?.let { other ->
        val otherSummary = summarize(intervalsFor(book, vehicle.id, other), confirmed(book))
        otherSummary.average?.let { rate -> "${other.name} ${rateText(rate, other)}" }
    }
    val spec = specs.mapNotNull { toSpec(it) }.firstOrNull { it.vehicleId == vehicle.id && it.fuelKind == fuel && it.active }
    val powertrain = powertrainOf(vehicle.powertrain)
    val comparison = compareOfficial(fuel, summary.average, spec, powertrain)
    val comparable = comparison as? com.tankful.android.consumption.OfficialComparison.Comparable
    val month = monthCards(book, vehicle.id, now, zone)
    return DashboardUi(
        name = vehicleTitle(vehicle),
        hero = hero,
        secondary = secondary,
        liquidOnly = powertrain == Powertrain.PHEV,
        showOpen = summary.open != null && summary.average == null,
        direction = comparable?.direction,
        deltaText = comparable?.let { "${signed(it.delta.toBigDecimal())} · ${signed(it.percent.toBigDecimal())}%" },
        monthSpend = month.spend,
        spanValue = month.span,
        spendPerDistance = month.perDistance,
        ownership = ownership(book, vehicle.id, vehicle.purchasePriceMinor, vehicle.purchaseCurrency),
        emptyGarage = false,
    )
}

fun projectInsights(
    book: Logbook,
    specs: List<SpecificationEntity>,
    range: InsightRange,
    now: Instant,
    zone: ZoneId,
    imperial: Boolean,
): InsightUi {
    val vehicle = selected(book) ?: return blankInsights()
    val fuel = fuelOrPetrol(vehicle.primaryFuel)
    val window = insightWindow(range, now, zone)
    val fills = fills(book, vehicle.id)
    val all = intervalsFor(book, vehicle.id, fuel)
    val closing = fills.associate { it.id to it.timestampEpochMs }
    val start = window.start?.toEpochMilli()
    val end = window.end?.toEpochMilli()
    val closed = intervalsClosedIn(all, closing, start, end)
    val summary = summarize(closed, confirmed(book))
    val points = consumptionPoints(closed, fills, fuel, zone)
    val secondaryKind = vehicle.secondaryFuel?.let { fuelOrNull(it) }
    val secondaryPoints = secondaryKind?.let { kind ->
        val other = intervalsClosedIn(intervalsFor(book, vehicle.id, kind), closing, start, end)
        consumptionPoints(other, fills, kind, zone)
    }.orEmpty()
    val spec = specs.mapNotNull { toSpec(it) }.firstOrNull { it.vehicleId == vehicle.id && it.fuelKind == fuel && it.active }
    val comparison = compareOfficial(fuel, summary.average, spec, powertrainOf(vehicle.powertrain))
    val comparable = comparison as? com.tankful.android.consumption.OfficialComparison.Comparable
    return InsightUi(
        hero = heroText(summary.average, fuel, vehicle.economyPreference),
        points = points,
        spend = spendPoints(book, vehicle.id, window, zone, imperial),
        best = summary.best?.let { rateText(AggregateRate(it.fuel, it.distance), fuel) },
        worst = summary.worst?.let { rateText(AggregateRate(it.fuel, it.distance), fuel) },
        low = summary.lowConfidenceCount,
        direction = comparable?.direction,
        deltaText = comparable?.let { "${signed(it.delta.toBigDecimal())} · ${signed(it.percent.toBigDecimal())}%" },
        secondaryPoints = secondaryPoints,
        secondaryFuel = secondaryKind?.name,
        rateUnit = rateUnitOf(fuel.name),
    )
}

internal fun rateUnitOf(fuelName: String): String {
    if (fuelName == FuelKind.CNG.name) return "kg/100 km"
    if (fuelName == FuelKind.ELECTRIC.name || fuelName == FuelKind.HYDROGEN.name) return ""
    return "L/100 km"
}

private fun consumptionPoints(
    closed: List<IntervalResult>,
    fills: List<SeriesFill>,
    fuel: FuelKind,
    zone: ZoneId,
): List<ChartPoint> {
    val closing = fills.associate { it.id to it.timestampEpochMs }
    return closed.filterIsInstance<IntervalResult.Valid>().map { interval ->
        val at = closing[interval.closingRefuelId] ?: 0L
        val text = rateText(AggregateRate(interval.fuel, interval.distance), fuel)
        val hollow = interval.confidence == com.tankful.android.consumption.Confidence.LOW
        ChartPoint(chartDate(at, zone), text, hollow, ratePosition(interval), at)
    }.sortedBy { it.atMillis }
}

private fun chartDate(epochMs: Long, zone: ZoneId): String {
    if (epochMs <= 0L) return "—"
    return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
}

fun projectGarage(book: Logbook, imperial: Boolean): List<GarageLine> {
    val live = book.vehicles.values.filter { it.deletedAt == null }
    val lines = live.map { vehicle ->
        val fuel = fuelOrPetrol(vehicle.primaryFuel)
        val summary = summarize(intervalsFor(book, vehicle.id, fuel), confirmed(book))
        val secondaryFuel = vehicle.secondaryFuel?.let { fuelOrNull(it) }
        val secondary = secondaryFuel?.let { other ->
            summarize(intervalsFor(book, vehicle.id, other), confirmed(book)).average?.let { rate ->
                "${other.name} ${rateText(rate, other)}"
            }
        }
        val spend = sumByCurrency(expensesOf(book, vehicle.id).map { it.total })
        val nick = vehicle.nickname?.trim().orEmpty()
        GarageLine(
            id = vehicle.id,
            name = if (nick.isNotEmpty()) nick else "${vehicle.manufacturer} ${vehicle.model}",
            make = vehicle.manufacturer,
            model = vehicle.model,
            fuel = vehicle.primaryFuel,
            secondaryFuel = vehicle.secondaryFuel,
            odometer = latestOdometer(book, vehicle.id, imperial),
            consumption = heroText(summary.average, fuel, "L_PER_100KM"),
            secondary = secondary,
            spend = formatSums(spend),
            titledByNick = nick.isNotEmpty(),
        )
    }
    val currencies = live.map { it.purchaseCurrency }.distinct()
    val spendCurrencies = book.expenses.values.map { it.total.currency }.distinct()
    if (currencies.size <= 1 && spendCurrencies.size <= 1) {
        return lines.sortedBy { it.spend }
    }
    return lines.sortedBy { it.name }
}

private data class MonthCards(val spend: String, val span: String, val perDistance: String)

private fun monthCards(book: Logbook, vehicleId: String, now: Instant, zone: ZoneId): MonthCards {
    val window = insightWindow(InsightRange.CURRENT_MONTH, now, zone)
    val refuels = book.refuels.values.filter { row ->
        row.vehicleId == vehicleId && row.deletedAt == null && window.contains(Instant.ofEpochMilli(row.timestampEpochMs))
    }
    val spend = sumByCurrency(refuels.map { it.total })
    val points = book.readings.values.filter { reading ->
        reading.vehicleId == vehicleId && reading.deletedAt == null && window.contains(reading.timestamp)
    }.map { SpanPoint(it.timestamp, it.id, it.metres, it.epochId) }
    val span = odometerSpanMetres(points)
    val perDistance = spendPerSpan(spend, span)
    val spanText = if (span == null) "—" else "${displayOneDecimal(BigDecimal(span).movePointLeft(3)).toPlainString()} km"
    return MonthCards(formatSums(spend), spanText, perDistance)
}

private fun spendPerSpan(spend: Map<String, Long>, span: Long?): String {
    if (span == null || spend.size != 1) return "—"
    val entry = spend.entries.single()
    val minor = costPer100KmMinor(entry.value, span)
    return formatMinor(entry.key, minor)
}

private fun ownership(book: Logbook, vehicleId: String, price: Long?, currency: String?): String? {
    val sums = sumByCurrency(expensesOf(book, vehicleId).map { it.total })
    if (price == null || currency == null) return formatSums(sums).ifBlank { null }
    if (sums.isEmpty()) return formatMinor(currency, price)
    if (sums.size == 1 && currency in sums) return formatMinor(currency, price + sums.getValue(currency))
    return formatSums(sums)
}

private fun expensesOf(book: Logbook, vehicleId: String) =
    book.expenses.values.filter { it.vehicleId == vehicleId && it.deletedAt == null }

fun blankInsights(): InsightUi = InsightUi("—", emptyList(), emptyList(), null, null, 0, null, null)

private fun latestOdometer(book: Logbook, vehicleId: String, imperial: Boolean): String {
    val reading = book.readings.values
        .filter { it.vehicleId == vehicleId && it.deletedAt == null }
        .maxWithOrNull(compareBy({ it.timestamp }, { it.id }))
    if (reading == null) return "—"
    return odometerText(reading.metres, imperial)
}

private fun spendPoints(
    book: Logbook,
    vehicleId: String,
    window: com.tankful.android.time.HalfOpenInstant,
    zone: ZoneId,
    imperial: Boolean,
): List<ChartPoint> {
    val readings = book.readings.values
        .filter { it.vehicleId == vehicleId && it.deletedAt == null }
        .sortedWith(compareBy({ it.timestamp }, { it.id }))
    val points = mutableListOf<ChartPoint>()
    for (reading in readings) {
        if (!window.contains(reading.timestamp)) continue
        val previous = readings.lastOrNull { earlier(it, reading) }
        if (previous == null) continue
        val span = reading.metres - previous.metres
        val money = spendOfStop(book, vehicleId, reading) ?: continue
        val text = distancePerMoney(span, money.second, money.first, imperial) ?: continue
        val position = text.substringBefore(' ').toFloatOrNull() ?: continue
        val at = reading.timestamp.toEpochMilli()
        val label = reading.timestamp.atZone(zone).toLocalDate().toString()
        points += ChartPoint(label, text, false, position, at)
    }
    return points
}

private fun earlier(candidate: com.tankful.android.ledger.ReadingRow, reading: com.tankful.android.ledger.ReadingRow): Boolean {
    if (candidate.epochId != reading.epochId) return false
    if (candidate.timestamp < reading.timestamp) return true
    return candidate.timestamp == reading.timestamp && candidate.id < reading.id
}

private fun spendOfStop(book: Logbook, vehicleId: String, reading: com.tankful.android.ledger.ReadingRow): Pair<String, Long>? {
    val at = reading.timestamp.toEpochMilli()
    val totals = book.refuels.values.filter { row ->
        row.vehicleId == vehicleId && row.deletedAt == null && kotlin.math.abs(row.timestampEpochMs - at) <= 2_000L
    }.map { it.total }
    val sums = sumByCurrency(totals)
    if (sums.size != 1) return null
    val entry = sums.entries.single()
    return entry.key to entry.value
}

fun blankDashboard(): DashboardUi {
    return DashboardUi("", "—", null, false, false, null, null, "—", "—", "—", null, true)
}

private fun selected(book: Logbook) = book.vehicles.values.firstOrNull { it.id == book.selectedVehicleId && it.deletedAt == null }
    ?: book.vehicles.values.filter { it.deletedAt == null }.minWithOrNull(compareBy({ it.createdAt }, { it.id }))

private fun confirmed(book: Logbook): Set<String> {
    return book.judgements.values.filter { it.confirmed && it.deletedAt == null }.map { it.id }.toSet()
}

private fun fills(book: Logbook, vehicleId: String): List<SeriesFill> {
    return book.refuels.values.filter { it.vehicleId == vehicleId && it.deletedAt == null }.map { refuel ->
        val reading = refuel.odometerReadingId?.let { book.readings[it] }
        SeriesFill(
            id = refuel.id,
            vehicleId = refuel.vehicleId,
            fuelKind = refuel.fuelKind,
            timestampEpochMs = refuel.timestampEpochMs,
            odometerMetres = reading?.metres,
            epochId = reading?.epochId,
            quantity = refuel.quantity,
            fullTank = refuel.fullTank,
        )
    }
}

private fun intervalsFor(book: Logbook, vehicleId: String, fuel: FuelKind): List<IntervalResult> {
    val ids = book.refuels.values.filter { it.vehicleId == vehicleId && it.fuelKind == fuel }.map { it.id }.toSet()
    return computeIntervals(fills(book, vehicleId)).filter { openingId(it) in ids }
}

private fun openingId(interval: IntervalResult): String {
    return when (interval) {
        is IntervalResult.Valid -> interval.openingRefuelId
        is IntervalResult.Open -> interval.openingRefuelId
        is IntervalResult.Invalid -> interval.openingRefuelId
    }
}

private fun heroText(rate: AggregateRate?, fuel: FuelKind, preference: String): String {
    if (rate == null || fuel == FuelKind.ELECTRIC || fuel == FuelKind.HYDROGEN) return "—"
    val perDistance = preference == EconomyPreference.L_PER_100KM.name || preference == EconomyPreference.KG_PER_100KM.name
    if (!perDistance && (preference == EconomyPreference.KM_PER_L.name || preference == EconomyPreference.KM_PER_KG.name)) {
        val unit = if (fuel == FuelKind.CNG) "km/kg" else "km/L"
        return "${displayKmPerMajor(observedFuelPerDistance(rate)).toPlainString()} $unit"
    }
    return rateText(rate, fuel)
}

private fun rateText(rate: AggregateRate, fuel: FuelKind): String {
    if (fuel == FuelKind.CNG) {
        return "${displayKgPer100Km(rate.fuel.amount, rate.distance.metres).toPlainString()} kg/100 km"
    }
    return "${displayLitresPer100Km(rate.fuel.amount, rate.distance.metres).toPlainString()} L/100 km"
}

private fun ratePosition(interval: IntervalResult.Valid): Float {
    val text = if (interval.fuel.unit == com.tankful.android.quantity.QuantityUnit.GRAM) {
        displayKgPer100Km(interval.fuel.amount, interval.distance.metres)
    } else {
        displayLitresPer100Km(interval.fuel.amount, interval.distance.metres)
    }
    return text.toFloat()
}

private fun toSpec(entity: SpecificationEntity): VehicleSpecification? {
    val fuel = fuelOrNull(entity.fuelKind) ?: return null
    val cycle = runCatching { TestCycle.valueOf(entity.testCycle) }.getOrDefault(TestCycle.OTHER)
    val measure = runCatching { MeasureKind.valueOf(entity.measureKind) }.getOrDefault(MeasureKind.UNKNOWN)
    val combined = official(entity)
    return VehicleSpecification(entity.id, entity.vehicleId, fuel, combined, cycle, measure, entity.isActive && entity.deletedAt == null)
}

private fun official(entity: SpecificationEntity): OfficialFigure? {
    val unitName = entity.combinedUnit ?: return null
    val scale = entity.combinedScale ?: return null
    val unscaled = entity.combinedUnscaled ?: return null
    val unit = runCatching { EconomyPreference.valueOf(unitName) }.getOrNull() ?: return null
    return OfficialFigure(unit, scale, unscaled)
}

private fun vehicleTitle(vehicle: com.tankful.android.ledger.VehicleRow): String {
    val nick = vehicle.nickname?.trim().orEmpty()
    if (nick.isNotEmpty()) return nick
    return "${vehicle.manufacturer} ${vehicle.model}"
}

private fun fuelOrPetrol(name: String): FuelKind = fuelOrNull(name) ?: FuelKind.PETROL

private fun fuelOrNull(name: String): FuelKind? = runCatching { FuelKind.valueOf(name) }.getOrNull()

private fun powertrainOf(name: String): Powertrain = runCatching { Powertrain.valueOf(name) }.getOrDefault(Powertrain.ICE)

fun formatMinor(currency: String, minor: Long): String {
    val scale = currencyMinorScale(currency)
    val text = BigDecimal.valueOf(minor).movePointLeft(scale).toPlainString()
    return "$text $currency"
}

fun formatSums(sums: Map<String, Long>): String {
    if (sums.isEmpty()) return "—"
    return sums.entries.joinToString(" · ") { formatMinor(it.key, it.value) }
}

private fun signed(value: BigDecimal): String {
    val plain = value.toPlainString()
    if (value.signum() > 0) return "+$plain"
    return plain
}

fun liveVehicleCount(book: Logbook): Int = book.vehicles.values.count { it.deletedAt == null }

fun refuelLabel(row: RefuelRow): String = formatMinor(row.total.currency, row.total.minorUnits)
