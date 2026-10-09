package com.tankful.android

import com.tankful.android.model.FuelKind
import com.tankful.android.money.MAJOR_CURRENCIES
import com.tankful.android.money.amountsDisagree
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.money.minorUnitsFromUnitPrice
import com.tankful.android.quantity.MEASURE_IMPERIAL
import com.tankful.android.quantity.MEASURE_METRIC
import com.tankful.android.quantity.currencyForLocale
import com.tankful.android.quantity.distancePerMoney
import com.tankful.android.quantity.fillTotalText
import com.tankful.android.quantity.measureForLocale
import com.tankful.android.quantity.odometerMetres
import com.tankful.android.quantity.odometerText
import com.tankful.android.quantity.priceEntryText
import com.tankful.android.quantity.quantityEntryText
import com.tankful.android.quantity.storedFill
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureTest {
    @Test
    fun totalAppearsOnlyWhenQuantityAndPriceAreBothPresent() {
        assertEquals("", fillTotalText("42.70", "", "EUR", Locale.ITALY))
        assertEquals("", fillTotalText("", "1.749", "EUR", Locale.ITALY))
        assertEquals("74.68", fillTotalText("42.70", "1.749", "EUR", Locale.ITALY))
        assertEquals("74.68", fillTotalText("1.749", "42.70", "EUR", Locale.ITALY))
        assertEquals("73.46", fillTotalText("42,", "1.749", "EUR", Locale.ITALY))
        assertEquals("73.46", fillTotalText("42.", "1.749", "EUR", Locale.ITALY))
        assertEquals("42.70", fillTotalText("42.70", "1,", "EUR", Locale.ITALY))
        assertEquals("", fillTotalText(".", "1.749", "EUR", Locale.ITALY))
    }

    @Test
    fun storedTotalMatchesTheTypedProduct() {
        val stored = storedFill("42.70", "1.749", FuelKind.PETROL, "EUR", false, Locale.ITALY)
        assertEquals(42_700L, stored?.smallest)
        assertEquals(7468L, stored?.totalMinor)
        val again = minorUnitsFromUnitPrice(stored!!.smallest, stored.priceUnscaled, 3, 2)
        assertFalse(amountsDisagree(again, stored.totalMinor))
    }

    @Test
    fun gallonsTimesPriceStayWithinOneCent() {
        val stored = storedFill("10", "3.500", FuelKind.LPG, "USD", true, Locale.US)
        assertEquals(3500L, stored?.totalMinor)
        val again = minorUnitsFromUnitPrice(stored!!.smallest, stored.priceUnscaled, 3, currencyMinorScale("USD"))
        assertFalse(amountsDisagree(again, stored.totalMinor))
    }

    @Test
    fun editedFieldsRoundTripToTheSameBill() {
        val metric = storedFill("42.70", "1.749", FuelKind.PETROL, "EUR", false, Locale.ITALY)!!
        val metricQuantity = quantityEntryText(metric.smallest, FuelKind.PETROL, false)
        val metricPrice = priceEntryText(metric.totalMinor, metric.smallest, FuelKind.PETROL, "EUR", false)
        assertEquals("74.68", fillTotalText(metricQuantity, metricPrice, "EUR", Locale.ITALY))
        val gallons = storedFill("10", "3.500", FuelKind.LPG, "USD", true, Locale.US)!!
        val gallonQuantity = quantityEntryText(gallons.smallest, FuelKind.LPG, true)
        val gallonPrice = priceEntryText(gallons.totalMinor, gallons.smallest, FuelKind.LPG, "USD", true)
        val again = storedFill(gallonQuantity, gallonPrice, FuelKind.LPG, "USD", true, Locale.US)!!
        assertFalse(amountsDisagree(again.totalMinor, gallons.totalMinor))
    }

    @Test
    fun phoneLocalePicksMetricOrImperialAndAListedCurrency() {
        assertEquals(MEASURE_METRIC, measureForLocale(Locale.ITALY))
        assertEquals(MEASURE_IMPERIAL, measureForLocale(Locale.US))
        assertEquals("EUR", currencyForLocale(Locale.ITALY))
        assertEquals("USD", currencyForLocale(Locale.US))
        assertEquals(20, MAJOR_CURRENCIES.distinct().size)
    }

    @Test
    fun oneEnteredMileStillReadsAsOneMile() {
        val metres = odometerMetres("1", imperial = true, Locale.US)
        assertEquals("1 mi", metres?.let { odometerText(it, imperial = true) })
        assertEquals("1 km", odometerText(1000L, imperial = false))
    }

    @Test
    fun distancePerSpendUsesTheActiveUnit() {
        assertEquals("10.0 km/EUR", distancePerMoney(10_000L, 100L, "EUR", imperial = false))
        assertTrue(distancePerMoney(0L, 100L, "EUR", imperial = false) == null)
    }
}
