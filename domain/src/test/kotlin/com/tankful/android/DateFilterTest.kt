package com.tankful.android

import com.tankful.android.time.InsightRange
import com.tankful.android.time.insightWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DateFilterTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun currentMonthLastMonthAndLastThirtyDaysDiffer() {
        val now = Instant.parse("2026-03-01T04:30:00Z")
        val current = insightWindow(InsightRange.CURRENT_MONTH, now, newYork)
        val last = insightWindow(InsightRange.LAST_MONTH, now, newYork)
        val trailing = insightWindow(InsightRange.LAST_30_DAYS, now, newYork)
        assertEquals(Instant.parse("2026-02-01T05:00:00Z"), current.start)
        assertEquals(Instant.parse("2026-03-01T05:00:00Z"), current.end)
        assertEquals(current.start, last.end)
        assertNotEquals(last.start, trailing.start)
        assertTrue(current.contains(Instant.parse("2026-02-15T15:00:00Z")))
        assertFalse(current.contains(Instant.parse("2026-03-01T05:00:00Z")))
    }

    @Test
    fun springForwardCustomDayIsHalfOpenLocalMidnights() {
        val day = LocalDate.of(2026, 3, 8)
        val window = insightWindow(
            InsightRange.CUSTOM,
            Instant.parse("2026-03-08T15:00:00Z"),
            newYork,
            customStart = day,
            customEndInclusive = day,
        )
        val start = checkNotNull(window.start)
        val end = checkNotNull(window.end)
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), start)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), end)
        assertEquals(Duration.ofHours(23), Duration.between(start, end))
        assertTrue(window.contains(Instant.parse("2026-03-08T07:30:00Z")))
        assertFalse(window.contains(end))
    }
}
