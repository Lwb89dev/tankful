package com.tankful.android

import com.tankful.android.model.FuelKind
import com.tankful.android.model.QuickFuelChoice
import com.tankful.android.model.pairedSecondary
import com.tankful.android.providers.OsmStation
import com.tankful.android.providers.parseOverpass
import com.tankful.android.providers.stationAddress
import com.tankful.android.providers.STATION_QUERY_MAX_METRES
import com.tankful.android.providers.stationOnMap
import com.tankful.android.providers.visibleSearchRadiusMetres
import com.tankful.android.providers.PostedPrice
import com.tankful.android.providers.fuelsOnCard
import com.tankful.android.providers.mimitCsvUrls
import com.tankful.android.providers.osmFuelKind
import com.tankful.android.providers.readMimitBoard
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationPriceTest {
    @Test
    fun petrolPairsWithLpgOrMethaneOnly() {
        assertEquals(FuelKind.LPG, pairedSecondary(QuickFuelChoice.PETROL, QuickFuelChoice.LPG))
        assertEquals(FuelKind.CNG, pairedSecondary(QuickFuelChoice.PETROL, QuickFuelChoice.CNG))
        assertNull(pairedSecondary(QuickFuelChoice.DIESEL, QuickFuelChoice.LPG))
        assertNull(pairedSecondary(QuickFuelChoice.PETROL, QuickFuelChoice.DIESEL))
    }

    @Test
    fun osmKeysCoverTheSixFuels() {
        assertEquals(FuelKind.PETROL, osmFuelKind("fuel:octane_95"))
        assertEquals(FuelKind.DIESEL, osmFuelKind("fuel:diesel"))
        assertEquals(FuelKind.LPG, osmFuelKind("fuel:lpg"))
        assertEquals(FuelKind.CNG, osmFuelKind("fuel:cng"))
        assertEquals(FuelKind.ELECTRIC, osmFuelKind("fuel:electricity"))
        assertEquals(FuelKind.HYDROGEN, osmFuelKind("fuel:hydrogen"))
        assertNull(osmFuelKind("fuel:adblue"))
    }

    @Test
    fun ministryPricesMatchANearbyPumpAndSkipAFarOne() {
        val board = readMimitBoard(PRICES, STATIONS, LocalDate.parse("2026-10-08"))
        val near = board.pricesAt(45.001, 9.001).associateBy { it.fuel }
        assertEquals("1.899", near[FuelKind.PETROL]?.euros)
        assertEquals("1.799", near[FuelKind.DIESEL]?.euros)
        assertEquals("0.799", near[FuelKind.LPG]?.euros)
        assertEquals("1.499", near[FuelKind.CNG]?.euros)
        assertEquals("0.650", near[FuelKind.ELECTRIC]?.euros)
        assertEquals("13.000", near[FuelKind.HYDROGEN]?.euros)
        assertEquals("2026-10-08", near[FuelKind.PETROL]?.asOf)
        assertTrue(board.pricesAt(46.0, 9.0).isEmpty())
    }

    @Test
    fun extractionPreambleDoesNotHideTheHeader() {
        val prices = "Estrazione del 2026-10-07\n" + PRICES.trim()
        val stations = "Estrazione del 2026-10-07\n" + STATIONS.trim()
        val board = readMimitBoard(prices, stations, LocalDate.parse("2026-10-08"))
        val petrol = board.pricesAt(45.001, 9.001).first { it.fuel == FuelKind.PETROL }
        assertEquals("1.899", petrol.euros)
    }

    @Test
    fun relativeMinistryLinksStayOnHttps() {
        val page = """
            <a href="/images/exportCSV/prezzo_alle_8.csv">prices</a>
            <a href="/images/exportCSV/anagrafica_impianti_attivi.csv">stations</a>
            <a href="http://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv">cleartext</a>
        """.trimIndent()
        val pageUrl = "https://www.mimit.gov.it/it/open-data/elenco-dataset/carburanti-prezzi-praticati-e-anagrafica-degli-impianti"
        val urls = mimitCsvUrls(page, pageUrl)
        assertEquals("https://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv", urls?.prices)
        assertEquals("https://www.mimit.gov.it/images/exportCSV/anagrafica_impianti_attivi.csv", urls?.stations)
    }

    @Test
    fun postedPricesShowWhenTheMapHasNoFuelTags() {
        val station = OsmStation("node", 1L, "Pompa", 45.0, 9.0, emptySet(), true)
        val prices = listOf(PostedPrice(FuelKind.PETROL, "1.899", "2026-10-08"))
        assertEquals(listOf(FuelKind.PETROL), fuelsOnCard(station, prices))
        val tagged = station.copy(fuelsSold = setOf("fuel:diesel"), fuelUnknown = false)
        assertEquals(listOf(FuelKind.DIESEL), fuelsOnCard(tagged, emptyList()))
    }

    @Test
    fun stationCardKeepsTheStreetWhenOpenStreetMapHasOne() {
        assertEquals("Via Bologna 12, 44122 Ferrara", stationAddress("  ", "Via Bologna", "12", "44122", "Ferrara"))
        assertEquals("Piazza Trento", stationAddress("Piazza Trento", "Via Bologna", "12", null, null))
        val json = """
            {"elements":[{"type":"node","id":7,"lat":44.8,"lon":11.6,"tags":{"amenity":"fuel","name":"Eni","addr:street":"Via Bologna","addr:housenumber":"12","addr:city":"Ferrara"}}]}
        """.trimIndent()
        assertEquals("Via Bologna 12, Ferrara", parseOverpass(json).single().address)
    }

    @Test
    fun stationsWithoutCoordinatesStayOffTheMap() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"tags":{"amenity":"fuel","name":"Lost"}},
              {"type":"node","id":2,"lat":0,"lon":0,"tags":{"amenity":"fuel","name":"Ocean"}},
              {"type":"node","id":3,"lat":44.8,"lon":11.6,"tags":{"amenity":"fuel","name":"Eni"}}
            ]}
        """.trimIndent()
        val station = parseOverpass(json).single()
        assertEquals("Eni", station.name)
        assertEquals(44.8, station.latitude, 0.0001)
        assertEquals(11.6, station.longitude, 0.0001)
        assertTrue(stationOnMap(44.8, 11.6))
        assertTrue(!stationOnMap(0.0, 0.0))
    }

    @Test
    fun aZoomedOutMapSearchesAsFarAsTheScreen() {
        assertEquals(8_000, visibleSearchRadiusMetres(45.0, 9.0, 45.0, 9.0))
        val province = visibleSearchRadiusMetres(45.0, 9.0, 45.5, 9.0)
        assertTrue(province > 15_000)
        assertTrue(province < STATION_QUERY_MAX_METRES)
        assertEquals(STATION_QUERY_MAX_METRES, visibleSearchRadiusMetres(45.0, 9.0, -45.0, 9.0))
    }
}

private const val STATIONS = """
idImpianto|Latitudine|Longitudine|Nome Impianto
9|45.00000|9.00000|Pompa
"""

private const val PRICES = """
idImpianto|descCarburante|prezzo|isSelf|dtComu
9|Benzina|1.899|1|2026-10-08
9|Gasolio|1.799|1|2026-10-08
9|GPL|0.799|1|2026-10-08
9|Metano|1.499|1|2026-10-08
9|Elettricità|0.650|1|2026-10-08
9|Idrogeno|13.000|0|2026-10-08
"""
