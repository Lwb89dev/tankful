package com.tankful.android

import com.tankful.android.model.DistanceEntryUnit
import com.tankful.android.quantity.DistanceMetres
import com.tankful.android.quantity.FuelUnits
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import com.tankful.android.quantity.displayTwoDecimals
import com.tankful.android.quantity.metresFromEntry
import com.tankful.android.quantity.millilitresFromGallons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class UnitConversionTest {
    @Test
    fun litreIdentitiesAndGallonAndMile() {
        val volume = Quantity.of(QuantityUnit.MILLILITRE, 43_500L)
        val distance = DistanceMetres.ofMetres(650_000L)
        val perHundred = FuelUnits.litresPer100km(volume, distance)
        val perLitre = FuelUnits.kmPerLitre(volume, distance)
        val back = BigDecimal("100").divide(perLitre, 16, com.tankful.android.money.TankfulRounding.mode)
        val tolerance = BigDecimal("0.0000001")
        assertTrue(perHundred.subtract(back).abs().compareTo(tolerance) <= 0)
        val inverted = BigDecimal("100").divide(perHundred, 16, com.tankful.android.money.TankfulRounding.mode)
        assertTrue(perLitre.subtract(inverted).abs().compareTo(tolerance) <= 0)
        assertEquals(37_854L, millilitresFromGallons(10L, 0, unitedStates = true))
        assertEquals(1609L, metresFromEntry(DistanceEntryUnit.MI_WHOLE, 1L, 0))
    }

    @Test
    fun workedIntervalIsThirtyFivePointOneFiveUsMpg() {
        val volume = Quantity.of(QuantityUnit.MILLILITRE, 43_500L)
        val distance = DistanceMetres.ofMetres(650_000L)
        val mpg = displayTwoDecimals(FuelUnits.usMilesPerGallon(volume, distance))
        assertEquals(BigDecimal("35.15"), mpg)
    }
}
