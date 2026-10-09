package com.tankful.android

import com.tankful.android.data.catalogue.CatalogueEntry
import com.tankful.android.ui.catalogueNumbers
import com.tankful.android.ui.cataloguePower
import com.tankful.android.ui.metricHorsepower
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogueFormatTest {
    @Test
    fun showsEpaMilesAndTheLiquidEquivalent() {
        val text = catalogueNumbers(sample("US_MPG", 0, 30, 296, perMile = true, fuel = "PETROL"))
        assertEquals("30 mpg · 7.8 L/100 km · 296 g/mi", text)
    }

    @Test
    fun convertsPublishedKilowattsToMetricHorsepower() {
        assertEquals(105, metricHorsepower(77))
        assertEquals(69, metricHorsepower(51))
        assertEquals("77 kW · 105 CV", cataloguePower(77))
    }

    @Test
    fun keepsAnOfficialLitreFigureAsPublished() {
        val text = catalogueNumbers(sample("L_PER_100KM", 1, 78, 182, perMile = false, fuel = "PETROL"))
        assertEquals("7.8 L/100 km · 182 g/km", text)
    }

    private fun sample(
        unit: String,
        scale: Int,
        combined: Long,
        co2: Long,
        perMile: Boolean,
        fuel: String,
    ): CatalogueEntry {
        return CatalogueEntry(
            id = "sample",
            source = "EPA",
            make = "Toyota",
            model = "Corolla",
            variant = "",
            year = 2010,
            fuel = fuel,
            powertrain = "ICE",
            testCycle = "EPA",
            measureKind = "LIQUID",
            combinedUnit = unit,
            combinedScale = scale,
            combinedUnscaled = combined,
            urbanUnscaled = null,
            extraUnscaled = null,
            cityUnscaled = null,
            highwayUnscaled = null,
            co2Unscaled = co2,
            co2GramsPerMile = perMile,
            powerKw = null,
        )
    }
}
