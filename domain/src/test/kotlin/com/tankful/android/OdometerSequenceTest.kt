package com.tankful.android

import com.tankful.android.consumption.IntervalResult
import com.tankful.android.consumption.InvalidIntervalReason
import com.tankful.android.consumption.SeriesFill
import com.tankful.android.consumption.computeIntervals
import com.tankful.android.model.ConfirmReason
import com.tankful.android.model.FuelKind
import com.tankful.android.model.OdometerReason
import com.tankful.android.odometer.OdometerDecision
import com.tankful.android.odometer.OdometerDraft
import com.tankful.android.odometer.StoredReading
import com.tankful.android.odometer.SpanPoint
import com.tankful.android.odometer.evaluateOdometer
import com.tankful.android.odometer.odometerSpanMetres
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OdometerSequenceTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")

    @Test
    fun decreaseNeedsAReasonAndCorrectionIsVisibleToIntervals() {
        val previous = StoredReading("old", now.minusSeconds(86_400), 100_000L, "epoch-1")
        val draft = OdometerDraft("new", now, 90_000L)
        val blocked = evaluateOdometer(draft, listOf(previous), now, emptySet(), null, "epoch-2")
        assertTrue(blocked is OdometerDecision.NeedsConfirmation)
        val corrected = evaluateOdometer(
            draft,
            listOf(previous),
            now,
            emptySet(),
            OdometerReason.CORRECTION,
            "epoch-2",
        ) as OdometerDecision.Accept
        assertEquals("epoch-1", corrected.epochId)
        val fills = listOf(
            SeriesFill("a", "v", FuelKind.PETROL, 1L, 100_000L, "epoch-1", litres(10_000), true),
            SeriesFill("b", "v", FuelKind.PETROL, 2L, 90_000L, corrected.epochId, litres(5_000), true),
        )
        val backwards = computeIntervals(fills)
            .filterIsInstance<IntervalResult.Invalid>()
            .single()
        assertEquals(InvalidIntervalReason.NON_POSITIVE_DISTANCE, backwards.reason)
        val seen = computeIntervals(
            listOf(
                SeriesFill("a", "v", FuelKind.PETROL, 1L, 80_000L, "epoch-1", litres(1), true),
                SeriesFill("b", "v", FuelKind.PETROL, 2L, draft.metres, corrected.epochId, litres(4_000), true),
            ),
        ).filterIsInstance<IntervalResult.Valid>().single()
        assertEquals(10_000L, seen.distance.metres)
    }

    @Test
    fun replacementStartsAnEpoch() {
        val previous = StoredReading("old", now.minusSeconds(3_600), 100_000L, "epoch-1")
        val decision = evaluateOdometer(
            OdometerDraft("swap", now, 20L),
            listOf(previous),
            now,
            emptySet(),
            OdometerReason.REPLACEMENT,
            "epoch-new",
        ) as OdometerDecision.Accept
        assertEquals("epoch-new", decision.epochId)
    }

    @Test
    fun jumpInsideThirtySixHoursConfirmsAndTenDaysDoesNot() {
        val close = StoredReading("old", now.minusSeconds(3_600), 0L, "epoch-1")
        val far = StoredReading("old", now.minusSeconds(10L * 24L * 3600L), 0L, "epoch-1")
        val jumped = OdometerDraft("now", now, 2_500_000L)
        val near = evaluateOdometer(jumped, listOf(close), now, emptySet(), null, "epoch-2")
        assertTrue(near is OdometerDecision.NeedsConfirmation)
        val distant = evaluateOdometer(jumped, listOf(far), now, emptySet(), null, "epoch-2")
        assertTrue(distant is OdometerDecision.Accept)
    }

    @Test
    fun futureTimestamps() {
        val soon = OdometerDraft("s", now.plusSeconds(23L * 3600L), 10L)
        val without = evaluateOdometer(soon, emptyList(), now, emptySet(), null, "e")
        assertTrue(without is OdometerDecision.NeedsConfirmation)
        val with = evaluateOdometer(soon, emptyList(), now, setOf(ConfirmReason.CLOCK_SKEW), null, "e")
        assertTrue(with is OdometerDecision.Accept)
        val late = OdometerDraft("l", now.plusSeconds(25L * 3600L), 10L)
        val rejected = evaluateOdometer(late, emptyList(), now, setOf(ConfirmReason.CLOCK_SKEW), null, "e")
        assertTrue(rejected is OdometerDecision.Rejected)
    }

    @Test
    fun historicalInsertThatInvertsTheNextReadingConfirms() {
        val earlier = StoredReading("a", now.minusSeconds(20_000), 10_000L, "epoch-1")
        val later = StoredReading("c", now.minusSeconds(5_000), 12_000L, "epoch-1")
        val between = OdometerDraft("b", now.minusSeconds(10_000), 13_000L)
        val decision = evaluateOdometer(between, listOf(earlier, later), now, emptySet(), null, "e")
        assertTrue(decision is OdometerDecision.NeedsConfirmation)
    }

    @Test
    fun spanStaysInsideOneEpoch() {
        val first = SpanPoint(now, "a", 1_000L, "epoch-1")
        val last = SpanPoint(now.plusSeconds(10), "b", 2_500L, "epoch-1")
        assertEquals(1_500L, odometerSpanMetres(listOf(last, first)))
        val replaced = last.copy(epochId = "epoch-2")
        assertEquals(null, odometerSpanMetres(listOf(first, replaced)))
    }

    private fun litres(amount: Long) = Quantity.of(QuantityUnit.MILLILITRE, amount)
}
