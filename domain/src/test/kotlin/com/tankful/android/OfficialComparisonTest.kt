package com.tankful.android

import com.tankful.android.consumption.AggregateRate
import com.tankful.android.consumption.ComparisonDirection
import com.tankful.android.consumption.OfficialComparison
import com.tankful.android.consumption.OfficialFigure
import com.tankful.android.consumption.VehicleSpecification
import com.tankful.android.consumption.compareOfficial
import com.tankful.android.consumption.displayKmPerMajor
import com.tankful.android.model.EconomyPreference
import com.tankful.android.model.FuelKind
import com.tankful.android.model.MeasureKind
import com.tankful.android.model.Powertrain
import com.tankful.android.model.TestCycle
import com.tankful.android.quantity.DistanceMetres
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class OfficialComparisonTest {
    @Test
    fun fivePointTwoVersusSixPointOneStaysPositiveInKmPerLitre() {
        val preference = EconomyPreference.KM_PER_L
        assertEquals(EconomyPreference.KM_PER_L, preference)
        val result = compareOfficial(
            FuelKind.PETROL,
            observed(6_100L, 100_000L),
            spec(FuelKind.PETROL, 52L, TestCycle.WLTP, MeasureKind.LIQUID),
            Powertrain.ICE,
        ) as OfficialComparison.Comparable
        assertEquals(9L, result.delta.unscaled)
        assertEquals(173L, result.percent.unscaled)
        assertEquals(ComparisonDirection.ABOVE, result.direction)
        assertEquals(BigDecimal("19.2"), displayKmPerMajor(BigDecimal("5.2")))
        assertEquals(BigDecimal("16.4"), displayKmPerMajor(BigDecimal("6.1")))
    }

    @Test
    fun mismatchedFuelMpgeAndUrbanOnlyAreNotComparable() {
        val observed = observed(6_100L, 100_000L)
        val lpg = compareOfficial(
            FuelKind.PETROL,
            observed,
            spec(FuelKind.LPG, 52L, TestCycle.WLTP, MeasureKind.LIQUID),
            Powertrain.ICE,
        )
        assertTrue(lpg is OfficialComparison.NotComparable)
        val epa = spec(FuelKind.PETROL, 280L, TestCycle.EPA, MeasureKind.LIQUID)
        val compared = compareOfficial(FuelKind.PETROL, observed, epa, Powertrain.ICE)
            as OfficialComparison.Comparable
        assertEquals(TestCycle.EPA, compared.cycle)
        assertTrue(compared.cycle != TestCycle.WLTP)
        val mpge = compareOfficial(
            FuelKind.PETROL,
            observed,
            spec(FuelKind.PETROL, 100L, TestCycle.EPA, MeasureKind.MPGE),
            Powertrain.ICE,
        )
        assertTrue(mpge is OfficialComparison.NotComparable)
        val urbanOnly = VehicleSpecification(
            id = "u",
            vehicleId = "v",
            fuelKind = FuelKind.PETROL,
            combined = null,
            testCycle = TestCycle.WLTP,
            measureKind = MeasureKind.LIQUID,
        )
        assertTrue(compareOfficial(FuelKind.PETROL, observed, urbanOnly, Powertrain.ICE) is OfficialComparison.NotComparable)
    }

    private fun observed(millilitres: Long, metres: Long): AggregateRate {
        return AggregateRate(
            Quantity.of(QuantityUnit.MILLILITRE, millilitres),
            DistanceMetres.ofMetres(metres),
        )
    }

    private fun spec(
        fuel: FuelKind,
        unscaled: Long,
        cycle: TestCycle,
        measure: MeasureKind,
    ): VehicleSpecification {
        return VehicleSpecification(
            id = "s",
            vehicleId = "v",
            fuelKind = fuel,
            combined = OfficialFigure(EconomyPreference.L_PER_100KM, 1, unscaled),
            testCycle = cycle,
            measureKind = measure,
        )
    }
}
