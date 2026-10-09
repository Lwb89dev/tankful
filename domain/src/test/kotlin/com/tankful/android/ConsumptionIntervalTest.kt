package com.tankful.android

import com.tankful.android.consumption.Confidence
import com.tankful.android.consumption.IntervalResult
import com.tankful.android.consumption.InvalidIntervalReason
import com.tankful.android.consumption.OfficialComparison
import com.tankful.android.consumption.OfficialFigure
import com.tankful.android.consumption.VehicleSpecification
import com.tankful.android.consumption.belowTwentyPercentOfOfficial
import com.tankful.android.consumption.canonicalRate
import com.tankful.android.consumption.compareOfficial
import com.tankful.android.consumption.computeIntervals
import com.tankful.android.consumption.displayKgPer100Km
import com.tankful.android.consumption.displayKmPerLitre
import com.tankful.android.consumption.displayLitresPer100Km
import com.tankful.android.consumption.intervalId
import com.tankful.android.consumption.summarize
import com.tankful.android.model.EconomyPreference
import com.tankful.android.model.FuelKind
import com.tankful.android.model.MeasureKind
import com.tankful.android.model.Powertrain
import com.tankful.android.model.TestCycle
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ConsumptionIntervalTest {
    @Test
    fun workedIntervalDisplaysSixPointSevenAndFourteenPointNine() {
        val fills = listOf(
            fill("a", 0L, 50_000_000L, 40_000L, full = true),
            fill("p", 1L, 50_300_000L, 15_000L, full = false),
            fill("b", 2L, 50_650_000L, 28_500L, full = true),
        )
        val interval = computeIntervals(fills).filterIsInstance<IntervalResult.Valid>().single()
        assertEquals(43_500L, interval.fuel.amount)
        assertEquals(650_000L, interval.distance.metres)
        assertEquals(BigDecimal("6.7"), displayLitresPer100Km(interval.fuel.amount, interval.distance.metres))
        assertEquals(BigDecimal("14.9"), displayKmPerLitre(interval.fuel.amount, interval.distance.metres))
        assertFalse(interval.fuel.amount == 40_000L + 43_500L)
    }

    @Test
    fun openPartialMissingAndNonPositive() {
        val openOnly = computeIntervals(listOf(fill("a", 0L, 1_000L, 10_000L, full = true)))
        assertTrue(openOnly.single() is IntervalResult.Open)
        val partial = computeIntervals(listOf(fill("p", 0L, 1_000L, 10_000L, full = false)))
        assertTrue(partial.isEmpty())
        val missing = computeIntervals(
            listOf(
                fill("a", 0L, null, 10_000L, full = true),
                fill("b", 1L, 5_000L, 10_000L, full = true),
            ),
        )
        val invalid = missing.filterIsInstance<IntervalResult.Invalid>().single()
        assertEquals(InvalidIntervalReason.MISSING_ODOMETER, invalid.reason)
        val backwards = computeIntervals(
            listOf(
                fill("a", 0L, 5_000L, 10_000L, full = true),
                fill("b", 1L, 5_000L, 10_000L, full = true),
            ),
        )
        assertEquals(
            InvalidIntervalReason.NON_POSITIVE_DISTANCE,
            backwards.filterIsInstance<IntervalResult.Invalid>().single().reason,
        )
        val empty = computeIntervals(
            listOf(
                fill("a", 0L, 0L, 10_000L, full = true),
                fill("b", 1L, 100_000L, 0L, full = true),
            ),
        )
        assertEquals(
            InvalidIntervalReason.NON_POSITIVE_FUEL,
            empty.filterIsInstance<IntervalResult.Invalid>().single().reason,
        )
    }

    @Test
    fun fuelsStaySeparateAndOdometerRowsDoNotOpenIntervals() {
        val fills = listOf(
            fill("a", 0L, 0L, 40_000L, full = true, fuel = FuelKind.PETROL),
            fill("lpg", 1L, 10_000L, 5_000L, full = false, fuel = FuelKind.LPG),
            fill("b", 2L, 100_000L, 8_000L, full = true, fuel = FuelKind.PETROL),
        )
        val results = computeIntervals(fills)
        val petrol = results.filterIsInstance<IntervalResult.Valid>().single()
        assertEquals(8_000L, petrol.fuel.amount)
        assertTrue(results.none { it is IntervalResult.Valid && false })
        val readingsOnly = emptyList<com.tankful.android.consumption.SeriesFill>()
        assertTrue(computeIntervals(readingsOnly).isEmpty())
    }

    @Test
    fun meterReplacementAndAggregateRatio() {
        val replaced = computeIntervals(
            listOf(
                fill("a", 0L, 0L, 10_000L, full = true, epoch = "e1"),
                fill("b", 1L, 50_000L, 5_000L, full = true, epoch = "e2"),
            ),
        )
        assertEquals(
            InvalidIntervalReason.METER_REPLACED,
            replaced.filterIsInstance<IntervalResult.Invalid>().single().reason,
        )
        val chained = computeIntervals(
            listOf(
                fill("a", 0L, 0L, 1L, full = true),
                fill("b", 1L, 100_000L, 10_000L, full = true),
                fill("c", 2L, 300_000L, 10_000L, full = true),
            ),
        )
        val summary = summarize(chained, emptySet())
        val average = summary.average!!
        assertEquals(20_000L, average.fuel.amount)
        assertEquals(300_000L, average.distance.metres)
        val meanOfRates = BigDecimal("7.5")
        val ratio = displayLitresPer100Km(average.fuel.amount, average.distance.metres)
        assertEquals(BigDecimal("6.7"), ratio)
        assertTrue(ratio.compareTo(meanOfRates) != 0)
    }

    @Test
    fun shortAndLowConfidenceStayInTheAverage() {
        val fills = listOf(
            fill("a", 0L, 0L, 1L, full = true),
            fill("b", 1L, 50_000L, 5_000L, full = true),
            fill("c", 2L, 150_000L, 1_200L, full = true),
        )
        val summary = summarize(computeIntervals(fills), emptySet())
        assertEquals(6_200L, summary.average!!.fuel.amount)
        assertNull(summary.best)
        assertEquals(1, summary.lowConfidenceCount)
        val low = computeIntervals(fills).filterIsInstance<IntervalResult.Valid>()
            .single { it.confidence == Confidence.LOW }
        val confirmed = summarize(computeIntervals(fills), setOf(intervalId(low.openingRefuelId, low.closingRefuelId)))
        assertEquals(low.closingRefuelId, confirmed.best?.closingRefuelId)
    }

    @Test
    fun bestIsLowestFuelPerDistanceEvenIfPreferenceIsKmPerLitre() {
        val fills = listOf(
            fill("a", 0L, 0L, 1L, full = true),
            fill("thirsty", 1L, 100_000L, 8_000L, full = true),
            fill("lean", 2L, 200_000L, 6_100L, full = true),
        )
        val preference = EconomyPreference.KM_PER_L
        assertEquals(EconomyPreference.KM_PER_L, preference)
        val summary = summarize(computeIntervals(fills), emptySet())
        assertEquals("lean", summary.best?.closingRefuelId)
        assertEquals("thirsty", summary.worst?.closingRefuelId)
    }

    @Test
    fun fortyLitresIsNotLowViaTheTwentyPercentClause() {
        val official = BigDecimal("5.0")
        val observed = BigDecimal("40")
        assertFalse(belowTwentyPercentOfOfficial(observed, official))
        assertEquals(Confidence.LOW, com.tankful.android.consumption.confidenceFor(observed, official))
    }

    @Test
    fun phevWeightedAndOfficialZeroDoNotDivide() {
        val observed = com.tankful.android.consumption.AggregateRate(
            Quantity.of(QuantityUnit.MILLILITRE, 6_100L),
            com.tankful.android.quantity.DistanceMetres.ofMetres(100_000L),
        )
        val weighted = spec(MeasureKind.WEIGHTED, 52L)
        val zero = spec(MeasureKind.LIQUID, 0L)
        assertTrue(compareOfficial(FuelKind.PETROL, observed, weighted, Powertrain.PHEV) is OfficialComparison.NotComparable)
        assertTrue(compareOfficial(FuelKind.PETROL, observed, zero, Powertrain.ICE) is OfficialComparison.NotComparable)
    }

    @Test
    fun cngIsMassAndOneMetreFlipsTheDecimal() {
        val fills = listOf(
            fill("a", 0L, 0L, 1L, full = true, fuel = FuelKind.CNG, unit = QuantityUnit.GRAM),
            fill("b", 1L, 100_000L, 5_000L, full = true, fuel = FuelKind.CNG, unit = QuantityUnit.GRAM),
        )
        val interval = computeIntervals(fills).filterIsInstance<IntervalResult.Valid>().single()
        assertEquals(QuantityUnit.GRAM, interval.fuel.unit)
        assertEquals(BigDecimal("5.0"), displayKgPer100Km(interval.fuel.amount, interval.distance.metres))
        assertEquals(BigDecimal("10.1"), displayLitresPer100Km(10_050L, 100_000L))
        assertEquals(BigDecimal("10.0"), displayLitresPer100Km(10_050L, 100_001L))
        val rate = canonicalRate(Quantity.of(QuantityUnit.MILLILITRE, 10_050L), 100_000L)
        assertTrue(rate > BigDecimal("10"))
    }

    private fun spec(measure: MeasureKind, unscaled: Long): VehicleSpecification {
        return VehicleSpecification(
            id = "s",
            vehicleId = "v",
            fuelKind = FuelKind.PETROL,
            combined = OfficialFigure(EconomyPreference.L_PER_100KM, 1, unscaled),
            testCycle = TestCycle.WLTP,
            measureKind = measure,
        )
    }

    private fun fill(
        id: String,
        time: Long,
        metres: Long?,
        quantity: Long,
        full: Boolean,
        fuel: FuelKind = FuelKind.PETROL,
        unit: QuantityUnit = QuantityUnit.MILLILITRE,
        epoch: String = "e",
    ) = com.tankful.android.consumption.SeriesFill(
        id = id,
        vehicleId = "v",
        fuelKind = fuel,
        timestampEpochMs = time,
        odometerMetres = metres,
        epochId = epoch,
        quantity = Quantity.of(unit, quantity),
        fullTank = full,
    )
}
