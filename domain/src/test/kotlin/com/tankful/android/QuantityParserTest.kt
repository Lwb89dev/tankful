package com.tankful.android

import com.tankful.android.quantity.millilitresFromEntry
import com.tankful.android.quantity.parseUnitPriceUnscaled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class QuantityParserTest {
    @Test
    fun dotAndCommaAreScaleThreeInBothLocales() {
        for (locale in listOf(Locale.US, Locale.ITALY)) {
            assertEquals(1749L, parseUnitPriceUnscaled("1.749", locale))
            assertEquals(1749L, parseUnitPriceUnscaled("1,749", locale))
        }
    }

    @Test
    fun mixedSeparatorsAndFourthDecimalAreRejected() {
        assertNull(parseUnitPriceUnscaled("1,749.0", Locale.US))
        assertNull(parseUnitPriceUnscaled("1.7491", Locale.ITALY))
        assertNull(millilitresFromEntry("1.7491", Locale.US))
    }

    @Test
    fun litresBecomeMillilitres() {
        assertEquals(42_700L, millilitresFromEntry("42.70", Locale.ITALY))
        assertEquals(42_700L, millilitresFromEntry("42,70", Locale.US))
        assertEquals(42_000L, millilitresFromEntry("42.", Locale.ITALY))
        assertEquals(42_000L, millilitresFromEntry("42,", Locale.US))
    }
}
