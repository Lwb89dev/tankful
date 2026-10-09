package com.tankful.android.data

import com.tankful.android.data.catalogue.CatalogueFacet
import com.tankful.android.data.catalogue.CataloguePick
import com.tankful.android.data.catalogue.catalogueChoices
import com.tankful.android.data.catalogue.catalogueQuery
import com.tankful.android.data.catalogue.displacementCc
import com.tankful.android.data.catalogue.foldCatalogue
import com.tankful.android.data.catalogue.groupSpellings
import com.tankful.android.data.catalogue.likePattern
import com.tankful.android.data.catalogue.resolvePick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogueQueryTest {
    @Test
    fun foldsMarksAndKeepsTheYearApartFromTheWords() {
        assertEquals("alfa romeo", foldCatalogue("Alfa Roméo"))
        val query = catalogueQuery("Panda 2024")
        assertEquals(2024, query?.year)
        assertEquals(listOf("panda"), query?.words)
    }

    @Test
    fun groupsSpellingsByTheCommonFormAndSortsThem() {
        val names = groupSpellings(listOf("FORD" to 10, "Ford" to 2, "AUDI" to 3, "Audi" to 8))
        assertEquals(listOf("Audi", "FORD"), names.map { it.label })
        assertEquals(setOf("Audi", "AUDI"), names.first { it.label == "Audi" }.spellings.toSet())
    }

    @Test
    fun rejectsAYearOrASingleLetterOnItsOwn() {
        assertNull(catalogueQuery("2024"))
        assertNull(catalogueQuery("a"))
        assertNull(catalogueQuery("  "))
    }

    @Test
    fun escapesLikeWildcards() {
        assertEquals("%100\\%\\_a\\\\b%", likePattern("100%_a\\b"))
    }

    @Test
    fun readsPublishedDisplacementFromEachSourceShape() {
        assertEquals(1499, displacementCc("1499 cm3 · 63 kW"))
        assertEquals(1600, displacementCc("1.6 L · Manual 5-spd"))
        assertEquals(1800, displacementCc("1.8 L"))
        assertEquals(1250, displacementCc("FIESTA 3P 1250 (60ch)"))
        assertEquals(1750, displacementCc("159 1750 Tbi (200ch)"))
        assertEquals(1600, displacementCc("147 1.6 TS (105ch)"))
        assertNull(displacementCc(""))
        assertNull(displacementCc("Automatic"))
        assertNull(displacementCc("51 kW"))
    }

    @Test
    fun walksYearThenFuelThenCapacityThenPower() {
        val rows = listOf(
            facet(2024, "PETROL", 999, 92),
            facet(2024, "PETROL", 1499, 63),
            facet(2024, "DIESEL", 1499, 70),
            facet(2015, "PETROL", 1250, 44),
        )
        val open = resolvePick(rows, CataloguePick())
        assertEquals(false, open.yearSet)
        val year = resolvePick(rows, open.chooseYear(2024))
        assertEquals(true, year.yearSet)
        assertEquals(false, year.fuelSet)
        assertEquals(listOf("PETROL", "DIESEL"), catalogueChoices(rows, year).fuels)
        val fuel = resolvePick(rows, year.chooseFuel("PETROL"))
        assertEquals(false, fuel.displacementSet)
        assertEquals(listOf(999, 1499), catalogueChoices(rows, fuel).displacementsCc)
        val capacity = resolvePick(rows, fuel.chooseDisplacement(1499))
        assertEquals(true, capacity.ready)
        assertEquals(63, capacity.powerKw)
    }

    @Test
    fun skipsAMissingCapacityAndALonePower() {
        val rows = listOf(facet(2010, "PETROL", null, 77))
        val resolved = resolvePick(rows, CataloguePick())
        assertEquals(true, resolved.ready)
        assertEquals(2010, resolved.year)
        assertEquals("PETROL", resolved.fuel)
        assertNull(resolved.displacementCc)
        assertEquals(77, resolved.powerKw)
    }

    private fun facet(year: Int, fuel: String, capacity: Int?, power: Int?) =
        CatalogueFacet(year, fuel, capacity, power)
}
