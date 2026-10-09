package com.tankful.android.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class InsightRange {
    CURRENT_MONTH,
    LAST_MONTH,
    LAST_30_DAYS,
    LAST_90_DAYS,
    CURRENT_YEAR,
    PREVIOUS_YEAR,
    ALL_TIME,
    CUSTOM,
}

data class HalfOpenInstant(val start: Instant?, val end: Instant?) {
    fun contains(instant: Instant): Boolean {
        val starts = start
        if (starts != null && instant < starts) return false
        val ends = end
        if (ends != null && instant >= ends) return false
        return true
    }
}

fun insightWindow(
    range: InsightRange,
    now: Instant,
    zone: ZoneId,
    customStart: LocalDate? = null,
    customEndInclusive: LocalDate? = null,
): HalfOpenInstant {
    val zoned = now.atZone(zone)
    return when (range) {
        InsightRange.CURRENT_MONTH -> monthWindow(zoned, 0)
        InsightRange.LAST_MONTH -> monthWindow(zoned, -1)
        InsightRange.LAST_30_DAYS -> trailing(now, 30)
        InsightRange.LAST_90_DAYS -> trailing(now, 90)
        InsightRange.CURRENT_YEAR -> yearWindow(zoned, 0)
        InsightRange.PREVIOUS_YEAR -> yearWindow(zoned, -1)
        InsightRange.ALL_TIME -> HalfOpenInstant(null, null)
        InsightRange.CUSTOM -> customWindow(zone, customStart, customEndInclusive)
    }
}

private fun monthWindow(zoned: ZonedDateTime, monthOffset: Long): HalfOpenInstant {
    val first = zoned.toLocalDate().withDayOfMonth(1).plusMonths(monthOffset)
    val start = first.atStartOfDay(zoned.zone).toInstant()
    val end = first.plusMonths(1).atStartOfDay(zoned.zone).toInstant()
    return HalfOpenInstant(start, end)
}

private fun yearWindow(zoned: ZonedDateTime, yearOffset: Long): HalfOpenInstant {
    val first = zoned.toLocalDate().withDayOfYear(1).plusYears(yearOffset)
    val start = first.atStartOfDay(zoned.zone).toInstant()
    val end = first.plusYears(1).atStartOfDay(zoned.zone).toInstant()
    return HalfOpenInstant(start, end)
}

private fun trailing(now: Instant, days: Long): HalfOpenInstant {
    return HalfOpenInstant(now.minus(Duration.ofDays(days)), now)
}

private fun customWindow(
    zone: ZoneId,
    customStart: LocalDate?,
    customEndInclusive: LocalDate?,
): HalfOpenInstant {
    require(customStart != null && customEndInclusive != null)
    val start = customStart.atStartOfDay(zone).toInstant()
    val end = customEndInclusive.plusDays(1).atStartOfDay(zone).toInstant()
    return HalfOpenInstant(start, end)
}
