package com.tankful.android

import com.tankful.android.money.Money
import com.tankful.android.money.UnitPrice
import com.tankful.android.money.amountsDisagree
import com.tankful.android.money.costPer100KmMinor
import com.tankful.android.money.costPer100MiMinor
import com.tankful.android.money.deriveUnitPriceUnscaled
import com.tankful.android.money.minorUnitsFromUnitPrice
import com.tankful.android.money.sumByCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test
    fun fortyTwoPointSevenLitresAtOnePointSevenFourNine() {
        val cents = minorUnitsFromUnitPrice(42_700L, 1749L, 3, 2)
        assertEquals(7468L, cents)
    }

    @Test
    fun receiptTotalStaysTypedAndDerivedPriceIsFlagged() {
        val derived = deriveUnitPriceUnscaled(7466L, 42_700L, 3, 2)
        assertEquals(1748L, derived)
        val price = UnitPrice.of("EUR", 3, derived, derived = true)
        assertEquals("1.748", price.toPlain())
        assertTrue(price.derived)
        val kept = Money.of("EUR", 7466L)
        assertEquals(7466L, kept.minorUnits)
    }

    @Test
    fun aggregateDoesNotRemultiplyDerivedPrice() {
        val aggregate = sumByCurrency(listOf(Money.of("EUR", 7466L)))
        val remultiplied = minorUnitsFromUnitPrice(42_700L, 1748L, 3, 2)
        assertEquals(7466L, aggregate.getValue("EUR"))
        assertEquals(7464L, remultiplied)
        assertNotEquals(aggregate.getValue("EUR"), remultiplied)
    }

    @Test
    fun twoCentGapNeedsConfirmationAndOneCentDoesNot() {
        assertTrue(amountsDisagree(7468L, 7466L))
        assertFalse(amountsDisagree(7468L, 7467L))
        assertFalse(amountsDisagree(7468L, 7468L))
    }

    @Test
    fun twoCurrenciesStaySeparate() {
        val totals = sumByCurrency(listOf(Money.of("EUR", 100L), Money.of("USD", 50L)))
        assertEquals(mapOf("EUR" to 100L, "USD" to 50L), totals)
    }

    @Test
    fun zeroAllowedAndNegativeRejected() {
        assertEquals(0L, Money.of("EUR", 0L).minorUnits)
        assertThrows { Money.of("EUR", -1L) }
        assertThrows { UnitPrice.of("EUR", 3, -1L, false) }
    }

    @Test
    fun costPerDistanceWorkedExample() {
        assertEquals(1149L, costPer100KmMinor(7468L, 650_000L))
        assertEquals(1849L, costPer100MiMinor(7468L, 650_000L))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected rejection")
    }
}
