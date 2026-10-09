package com.tankful.android.odometer

import com.tankful.android.model.ConfirmReason
import com.tankful.android.model.ODOMETER_HUGE_METRES
import com.tankful.android.model.ODOMETER_JUMP_CONFIRM_METRES
import com.tankful.android.model.OdometerReason
import com.tankful.android.model.RejectReason
import java.time.Duration
import java.time.Instant

private const val SKEW_OK_MS: Long = 5L * 60L * 1000L
private const val FUTURE_REJECT_MS: Long = 24L * 60L * 60L * 1000L
private const val JUMP_WINDOW_MS: Long = 36L * 60L * 60L * 1000L

data class StoredReading(
    val id: String,
    val timestamp: Instant,
    val metres: Long,
    val epochId: String,
)

data class OdometerDraft(
    val id: String,
    val timestamp: Instant,
    val metres: Long,
)

sealed interface OdometerDecision {
    data class Accept(val epochId: String, val reason: OdometerReason?) : OdometerDecision

    data class NeedsConfirmation(val reasons: List<ConfirmReason>) : OdometerDecision

    data class Rejected(val reasons: List<RejectReason>) : OdometerDecision
}

fun evaluateOdometer(
    draft: OdometerDraft,
    existing: List<StoredReading>,
    now: Instant,
    acknowledgements: Set<ConfirmReason>,
    chosenReason: OdometerReason?,
    newEpochId: String,
): OdometerDecision {
    if (draft.metres < 0L) return OdometerDecision.Rejected(listOf(RejectReason.NEGATIVE_ODOMETER))
    val aheadMs = Duration.between(now, draft.timestamp).toMillis()
    if (aheadMs > FUTURE_REJECT_MS) {
        return OdometerDecision.Rejected(listOf(RejectReason.FUTURE_BEYOND_24H))
    }
    val ordered = existing.filter { it.id != draft.id }.sortedWith(READING_ORDER)
    val previous = ordered.lastOrNull { readingBefore(it, draft) }
    val next = ordered.firstOrNull { readingAfter(it, draft) }
    val joinedEpoch = previous?.epochId ?: newEpochId
    val confirms = linkedSetOf<ConfirmReason>()
    if (aheadMs > SKEW_OK_MS && ConfirmReason.CLOCK_SKEW !in acknowledgements) {
        confirms += ConfirmReason.CLOCK_SKEW
    }
    if (draft.metres > ODOMETER_HUGE_METRES && ConfirmReason.ODOMETER_HUGE !in acknowledgements) {
        confirms += ConfirmReason.ODOMETER_HUGE
    }
    val orderProblem = orderProblem(draft, previous, next, joinedEpoch, chosenReason)
    if (orderProblem && chosenReason == null) confirms += ConfirmReason.ODOMETER_ORDER
    if (chosenReason != OdometerReason.REPLACEMENT) {
        val jump = jumpProblem(draft, previous)
        if (jump && ConfirmReason.ODOMETER_JUMP !in acknowledgements) {
            confirms += ConfirmReason.ODOMETER_JUMP
        }
    }
    if (confirms.isNotEmpty()) return OdometerDecision.NeedsConfirmation(confirms.toList())
    val epoch = if (chosenReason == OdometerReason.REPLACEMENT) newEpochId else joinedEpoch
    return OdometerDecision.Accept(epoch, chosenReason)
}

private val READING_ORDER = compareBy<StoredReading>({ it.timestamp }, { it.id })

private fun readingBefore(reading: StoredReading, draft: OdometerDraft): Boolean {
    if (reading.timestamp < draft.timestamp) return true
    return reading.timestamp == draft.timestamp && reading.id < draft.id
}

private fun readingAfter(reading: StoredReading, draft: OdometerDraft): Boolean {
    if (reading.timestamp > draft.timestamp) return true
    return reading.timestamp == draft.timestamp && reading.id > draft.id
}

private fun orderProblem(
    draft: OdometerDraft,
    previous: StoredReading?,
    next: StoredReading?,
    joinedEpoch: String,
    chosenReason: OdometerReason?,
): Boolean {
    if (chosenReason == OdometerReason.REPLACEMENT || chosenReason == OdometerReason.CORRECTION) {
        return false
    }
    if (previous != null && draft.metres < previous.metres) return true
    if (next != null && next.epochId == joinedEpoch && draft.metres > next.metres) return true
    return false
}

private fun jumpProblem(draft: OdometerDraft, previous: StoredReading?): Boolean {
    if (previous == null) return false
    val gap = Duration.between(previous.timestamp, draft.timestamp).toMillis()
    if (gap <= 0L || gap > JUMP_WINDOW_MS) return false
    return draft.metres - previous.metres > ODOMETER_JUMP_CONFIRM_METRES
}

data class SpanPoint(
    val timestamp: Instant,
    val id: String,
    val metres: Long,
    val epochId: String,
)

/** Last minus first, only when both ends share an epoch and the metres rise. */
fun odometerSpanMetres(points: List<SpanPoint>): Long? {
    val ordered = points.sortedWith(compareBy({ it.timestamp }, { it.id }))
    val first = ordered.firstOrNull() ?: return null
    val last = ordered.lastOrNull() ?: return null
    if (first.id == last.id) return null
    if (first.epochId != last.epochId) return null
    if (last.metres <= first.metres) return null
    return last.metres - first.metres
}
