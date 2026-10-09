package com.tankful.android.consumption

import com.tankful.android.model.EconomyPreference
import com.tankful.android.model.FuelKind
import com.tankful.android.model.MeasureKind
import com.tankful.android.model.Powertrain
import com.tankful.android.model.TestCycle
import com.tankful.android.money.TankfulRounding
import com.tankful.android.quantity.DistanceMetres
import com.tankful.android.quantity.FuelUnits
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import com.tankful.android.quantity.displayOneDecimal
import java.math.BigDecimal

enum class Confidence { HIGH, LOW }

enum class InvalidIntervalReason {
    MISSING_ODOMETER,
    METER_REPLACED,
    NON_POSITIVE_DISTANCE,
    NON_POSITIVE_FUEL,
}

sealed interface IntervalResult {
    data class Valid(
        val openingRefuelId: String,
        val closingRefuelId: String,
        val fuel: Quantity,
        val distance: DistanceMetres,
        val confidence: Confidence,
    ) : IntervalResult

    data class Open(val openingRefuelId: String) : IntervalResult

    data class Invalid(
        val reason: InvalidIntervalReason,
        val openingRefuelId: String,
        val closingRefuelId: String,
    ) : IntervalResult
}

data class AggregateRate(val fuel: Quantity, val distance: DistanceMetres)

data class ConsumptionSummary(
    val average: AggregateRate?,
    val best: IntervalResult.Valid?,
    val worst: IntervalResult.Valid?,
    val lowConfidenceCount: Int,
    val open: IntervalResult.Open?,
)

data class SeriesFill(
    val id: String,
    val vehicleId: String,
    val fuelKind: FuelKind,
    val timestampEpochMs: Long,
    val odometerMetres: Long?,
    val epochId: String?,
    val quantity: Quantity,
    val fullTank: Boolean,
    val tombstoned: Boolean = false,
)

const val BEST_WORST_MIN_METRES: Long = 100_000L

private val LOW_FLOOR = BigDecimal("1.5")
private val LOW_CEILING = BigDecimal("30")
private val TWENTY_PERCENT = BigDecimal("0.20")

fun intervalId(openingRefuelId: String, closingRefuelId: String): String {
    return "$openingRefuelId:$closingRefuelId"
}

fun canonicalRate(fuel: Quantity, metres: Long): BigDecimal {
    require(metres > 0L)
    return BigDecimal.valueOf(fuel.amount)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(metres), 16, TankfulRounding.mode)
}

fun belowTwentyPercentOfOfficial(rate: BigDecimal, official: BigDecimal): Boolean {
    return rate.compareTo(official.multiply(TWENTY_PERCENT)) < 0
}

fun confidenceFor(rate: BigDecimal, officialFuelPerDistance: BigDecimal?): Confidence {
    if (rate.compareTo(LOW_FLOOR) < 0 || rate.compareTo(LOW_CEILING) > 0) {
        return Confidence.LOW
    }
    if (officialFuelPerDistance != null && belowTwentyPercentOfOfficial(rate, officialFuelPerDistance)) {
        return Confidence.LOW
    }
    return Confidence.HIGH
}

fun computeIntervals(
    fills: List<SeriesFill>,
    officialByFuel: Map<FuelKind, BigDecimal?> = emptyMap(),
): List<IntervalResult> {
    val live = fills.filter { !it.tombstoned }
    val grouped = live.groupBy { it.vehicleId to it.fuelKind }
    val keys = grouped.keys.sortedWith(compareBy({ it.first }, { it.second.name }))
    val results = mutableListOf<IntervalResult>()
    for (key in keys) {
        val series = grouped.getValue(key).sortedWith(FILL_ORDER)
        results += scanSeries(series, officialByFuel[key.second])
    }
    return results
}

private val FILL_ORDER = Comparator<SeriesFill> { left, right ->
    val byTime = left.timestampEpochMs.compareTo(right.timestampEpochMs)
    if (byTime != 0) return@Comparator byTime
    val byNull = nullRank(left.odometerMetres) - nullRank(right.odometerMetres)
    if (byNull != 0) return@Comparator byNull
    val byMetres = (left.odometerMetres ?: 0L).compareTo(right.odometerMetres ?: 0L)
    if (byMetres != 0) return@Comparator byMetres
    left.id.compareTo(right.id)
}

private fun nullRank(metres: Long?): Int = if (metres == null) 1 else 0

private fun scanSeries(sorted: List<SeriesFill>, official: BigDecimal?): List<IntervalResult> {
    val results = mutableListOf<IntervalResult>()
    var open: SeriesFill? = null
    for (fill in sorted) {
        if (!fill.fullTank) continue
        val opening = open
        if (opening == null) {
            open = fill
            continue
        }
        results += closeInterval(opening, fill, sorted, official)
        open = fill
    }
    val leftover = open
    if (leftover != null) results += IntervalResult.Open(leftover.id)
    return results
}

private fun closeInterval(
    opening: SeriesFill,
    closing: SeriesFill,
    sorted: List<SeriesFill>,
    official: BigDecimal?,
): IntervalResult {
    val fuel = fuelBetween(opening, closing, sorted)
    if (opening.odometerMetres == null || closing.odometerMetres == null) {
        return IntervalResult.Invalid(
            InvalidIntervalReason.MISSING_ODOMETER,
            opening.id,
            closing.id,
        )
    }
    if (opening.epochId != closing.epochId) {
        return IntervalResult.Invalid(
            InvalidIntervalReason.METER_REPLACED,
            opening.id,
            closing.id,
        )
    }
    val metres = closing.odometerMetres - opening.odometerMetres
    if (metres <= 0L) {
        return IntervalResult.Invalid(
            InvalidIntervalReason.NON_POSITIVE_DISTANCE,
            opening.id,
            closing.id,
        )
    }
    if (fuel.amount <= 0L) {
        return IntervalResult.Invalid(
            InvalidIntervalReason.NON_POSITIVE_FUEL,
            opening.id,
            closing.id,
        )
    }
    val rate = canonicalRate(fuel, metres)
    return IntervalResult.Valid(
        openingRefuelId = opening.id,
        closingRefuelId = closing.id,
        fuel = fuel,
        distance = DistanceMetres.ofMetres(metres),
        confidence = confidenceFor(rate, official),
    )
}

private fun fuelBetween(opening: SeriesFill, closing: SeriesFill, sorted: List<SeriesFill>): Quantity {
    val start = sorted.indexOf(opening)
    val end = sorted.indexOf(closing)
    var sum = 0L
    val unit = closing.quantity.unit
    var index = start + 1
    while (index <= end) {
        val quantity = sorted[index].quantity
        require(quantity.unit == unit)
        sum += quantity.amount
        index += 1
    }
    return Quantity.of(unit, sum)
}

fun summarize(intervals: List<IntervalResult>, confirmedPairIds: Set<String>): ConsumptionSummary {
    val valid = intervals.filterIsInstance<IntervalResult.Valid>()
    val open = intervals.filterIsInstance<IntervalResult.Open>().lastOrNull()
    val lowCount = valid.count { it.confidence == Confidence.LOW }
    if (valid.isEmpty()) {
        return ConsumptionSummary(null, null, null, lowCount, open)
    }
    val unit = valid.first().fuel.unit
    var fuelSum = 0L
    var distanceSum = 0L
    for (interval in valid) {
        require(interval.fuel.unit == unit)
        fuelSum += interval.fuel.amount
        distanceSum += interval.distance.metres
    }
    val average = AggregateRate(Quantity.of(unit, fuelSum), DistanceMetres.ofMetres(distanceSum))
    val eligible = valid.filter { eligibleForBestWorst(it, confirmedPairIds) }
    return ConsumptionSummary(
        average = average,
        best = eligible.minWithOrNull(::rateOrder),
        worst = eligible.maxWithOrNull(::rateOrder),
        lowConfidenceCount = lowCount,
        open = open,
    )
}

fun eligibleForBestWorst(interval: IntervalResult.Valid, confirmedPairIds: Set<String>): Boolean {
    if (interval.distance.metres < BEST_WORST_MIN_METRES) return false
    if (interval.confidence == Confidence.HIGH) return true
    val pair = intervalId(interval.openingRefuelId, interval.closingRefuelId)
    return pair in confirmedPairIds
}

private fun rateOrder(left: IntervalResult.Valid, right: IntervalResult.Valid): Int {
    val leftSide = BigDecimal.valueOf(left.fuel.amount).multiply(BigDecimal.valueOf(right.distance.metres))
    val rightSide = BigDecimal.valueOf(right.fuel.amount).multiply(BigDecimal.valueOf(left.distance.metres))
    return leftSide.compareTo(rightSide)
}

fun displayLitresPer100Km(millilitres: Long, metres: Long): BigDecimal {
    val quantity = Quantity.of(QuantityUnit.MILLILITRE, millilitres)
    return displayOneDecimal(FuelUnits.litresPer100km(quantity, DistanceMetres.ofMetres(metres)))
}

fun displayKmPerLitre(millilitres: Long, metres: Long): BigDecimal {
    val quantity = Quantity.of(QuantityUnit.MILLILITRE, millilitres)
    return displayOneDecimal(FuelUnits.kmPerLitre(quantity, DistanceMetres.ofMetres(metres)))
}

fun displayKgPer100Km(grams: Long, metres: Long): BigDecimal {
    val quantity = Quantity.of(QuantityUnit.GRAM, grams)
    return displayOneDecimal(FuelUnits.kgPer100km(quantity, DistanceMetres.ofMetres(metres)))
}

data class ScaledValue(val scale: Int, val unscaled: Long) {
    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(unscaled).movePointLeft(scale)
}

fun scaledOneDecimal(value: BigDecimal): ScaledValue {
    val rounded = value.setScale(1, TankfulRounding.mode)
    return ScaledValue(1, rounded.movePointRight(1).longValueExact())
}

enum class ComparisonDirection { ABOVE, BELOW, MATCH }

sealed interface OfficialComparison {
    data class Comparable(
        val cycle: TestCycle,
        val official: ScaledValue,
        val observed: ScaledValue,
        val delta: ScaledValue,
        val percent: ScaledValue,
        val direction: ComparisonDirection,
    ) : OfficialComparison

    data class NotComparable(val reason: String) : OfficialComparison
}

data class OfficialFigure(
    val unit: EconomyPreference,
    val scale: Int,
    val unscaled: Long,
)

data class VehicleSpecification(
    val id: String,
    val vehicleId: String,
    val fuelKind: FuelKind,
    val combined: OfficialFigure?,
    val testCycle: TestCycle,
    val measureKind: MeasureKind,
    val active: Boolean = true,
)

fun toFuelPerDistance(fuel: FuelKind, figure: OfficialFigure): BigDecimal? {
    if (fuel == FuelKind.ELECTRIC || fuel == FuelKind.HYDROGEN) return null
    val number = BigDecimal.valueOf(figure.unscaled).movePointLeft(figure.scale)
    val liquid = fuel != FuelKind.CNG
    return when (figure.unit) {
        EconomyPreference.L_PER_100KM -> if (liquid) number else null
        EconomyPreference.KM_PER_L -> invertToPerHundred(number, liquid)
        EconomyPreference.KG_PER_100KM -> if (!liquid) number else null
        EconomyPreference.KM_PER_KG -> invertToPerHundred(number, !liquid)
        EconomyPreference.US_MPG -> if (liquid) mpgToPerHundred(number, FuelUnits.usGallonLitres) else null
        EconomyPreference.UK_MPG -> if (liquid) mpgToPerHundred(number, FuelUnits.ukGallonLitres) else null
    }
}

private fun invertToPerHundred(perMajor: BigDecimal, applicable: Boolean): BigDecimal? {
    if (!applicable || perMajor.signum() == 0) return if (applicable) BigDecimal.ZERO else null
    return BigDecimal("100").divide(perMajor, 16, TankfulRounding.mode)
}

private fun mpgToPerHundred(mpg: BigDecimal, gallonLitres: BigDecimal): BigDecimal? {
    if (mpg.signum() == 0) return BigDecimal.ZERO
    val kmPerLitre = mpg.multiply(FuelUnits.mileKilometres).divide(gallonLitres, 16, TankfulRounding.mode)
    return BigDecimal("100").divide(kmPerLitre, 16, TankfulRounding.mode)
}

fun observedFuelPerDistance(rate: AggregateRate): BigDecimal {
    return canonicalRate(rate.fuel, rate.distance.metres)
}

fun comparableOfficial(
    seriesFuel: FuelKind,
    spec: VehicleSpecification?,
    powertrain: Powertrain,
): BigDecimal? {
    if (spec == null || !spec.active) return null
    if (spec.fuelKind != seriesFuel) return null
    if (spec.measureKind != MeasureKind.LIQUID) return null
    if (powertrain == Powertrain.PHEV && spec.measureKind != MeasureKind.LIQUID) return null
    val combined = spec.combined ?: return null
    val converted = toFuelPerDistance(seriesFuel, combined) ?: return null
    if (converted.signum() <= 0) return null
    return converted
}

fun compareOfficial(
    seriesFuel: FuelKind,
    observed: AggregateRate?,
    spec: VehicleSpecification?,
    powertrain: Powertrain,
): OfficialComparison {
    if (spec == null) return OfficialComparison.NotComparable("no spec")
    if (spec.fuelKind != seriesFuel) return OfficialComparison.NotComparable("fuel mismatch")
    if (observed == null) return OfficialComparison.NotComparable("no observation")
    if (spec.measureKind == MeasureKind.MPGE) return OfficialComparison.NotComparable("mpge")
    if (spec.measureKind != MeasureKind.LIQUID) {
        return OfficialComparison.NotComparable(spec.measureKind.name)
    }
    if (powertrain == Powertrain.PHEV && spec.measureKind != MeasureKind.LIQUID) {
        return OfficialComparison.NotComparable("phev measure")
    }
    val combined = spec.combined ?: return OfficialComparison.NotComparable("no combined")
    val official = toFuelPerDistance(seriesFuel, combined)
        ?: return OfficialComparison.NotComparable("unit")
    if (official.signum() == 0) return OfficialComparison.NotComparable("official zero")
    val observedRate = observedFuelPerDistance(observed)
    val delta = observedRate.subtract(official)
    val percent = delta.multiply(BigDecimal("100")).divide(official, 16, TankfulRounding.mode)
    val direction = when {
        observedRate.compareTo(official) > 0 -> ComparisonDirection.ABOVE
        observedRate.compareTo(official) < 0 -> ComparisonDirection.BELOW
        else -> ComparisonDirection.MATCH
    }
    return OfficialComparison.Comparable(
        cycle = spec.testCycle,
        official = scaledOneDecimal(official),
        observed = scaledOneDecimal(observedRate),
        delta = scaledOneDecimal(delta),
        percent = scaledOneDecimal(percent),
        direction = direction,
    )
}

/** km/L formatting happens after the percent exists. Preference does not change the percent. */
fun displayKmPerMajor(fuelPerDistance: BigDecimal): BigDecimal {
    if (fuelPerDistance.signum() == 0) return BigDecimal.ZERO.setScale(1, TankfulRounding.mode)
    val perMajor = BigDecimal("100").divide(fuelPerDistance, 16, TankfulRounding.mode)
    return displayOneDecimal(perMajor)
}

data class Co2Figure(val scale: Int, val unscaled: Long, val gramsPerMile: Boolean)

sealed interface EstimatedCo2 {
    data object Unavailable : EstimatedCo2
}

fun estimateCo2(): EstimatedCo2 = EstimatedCo2.Unavailable

fun intervalsClosedIn(
    intervals: List<IntervalResult>,
    closingTimestamp: Map<String, Long>,
    startInclusive: Long?,
    endExclusive: Long?,
): List<IntervalResult> {
    return intervals.filter { interval ->
        val closingId = when (interval) {
            is IntervalResult.Valid -> interval.closingRefuelId
            is IntervalResult.Invalid -> interval.closingRefuelId
            is IntervalResult.Open -> return@filter false
        }
        val stamp = closingTimestamp[closingId] ?: return@filter false
        inWindow(stamp, startInclusive, endExclusive)
    }
}

private fun inWindow(stamp: Long, startInclusive: Long?, endExclusive: Long?): Boolean {
    if (startInclusive != null && stamp < startInclusive) return false
    if (endExclusive != null && stamp >= endExclusive) return false
    return true
}
