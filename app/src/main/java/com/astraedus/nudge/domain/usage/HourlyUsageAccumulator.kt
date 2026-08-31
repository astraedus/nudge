package com.astraedus.nudge.domain.usage

/**
 * Splits foreground spans across the 24 hour-cells of one day — the stats screen's hourly heatmap.
 *
 * It is fed from [ForegroundSpanTracker] like every other screen-time number, which is the point:
 * the heatmap used to run its own `package -> startTime` pairing inside `ScreenTimeProvider`, so
 * it inherited the same defect as the bars above it (several apps "foreground" at once, each open
 * span billed through to now) and could light up hours the phone spent in a pocket.
 *
 * Spans arrive non-overlapping, so no cell can exceed an hour and the day cannot exceed its own
 * length.
 *
 * Pure Kotlin; JVM-tested in `HourlyUsageAccumulatorTest`.
 *
 * @param dayStartMs local midnight of the day being broken down.
 * @param dayEndMs end of the day: the next local midnight, or "now" for today. Time past it is
 *   never credited — for today that is what keeps the heatmap from drawing hours that have not
 *   happened yet.
 */
class HourlyUsageAccumulator(private val dayStartMs: Long, private val dayEndMs: Long) {

    private val hourly = MutableList(HOURS) { 0L }

    /** Adds `[startMs, endMs)`, clipped to the day, to each hour-cell it overlaps. */
    fun add(startMs: Long, endMs: Long) {
        val spanStart = startMs.coerceAtLeast(dayStartMs)
        val spanEnd = endMs.coerceAtMost(dayEndMs)
        if (spanStart >= spanEnd) return

        // A clocks-back day is 25 hours long against 24 cells, so the last cell absorbs the
        // repeated hour rather than the span silently falling off the end of the row.
        val firstHour = hourIndexOf(spanStart)
        val lastHour = hourIndexOf(spanEnd - 1)

        for (hour in firstHour..lastHour) {
            val cellStart = dayStartMs + hour * HOUR_MS
            val cellEnd = if (hour == HOURS - 1) maxOf(spanEnd, cellStart + HOUR_MS) else cellStart + HOUR_MS
            val overlapStart = maxOf(spanStart, cellStart)
            val overlapEnd = minOf(spanEnd, cellEnd)
            if (overlapStart < overlapEnd) hourly[hour] += overlapEnd - overlapStart
        }
    }

    /** One total per hour, index 0..23. */
    fun totals(): List<Long> = hourly.toList()

    private fun hourIndexOf(timestampMs: Long): Int =
        ((timestampMs - dayStartMs) / HOUR_MS).toInt().coerceIn(0, HOURS - 1)

    companion object {
        const val HOURS = 24
        private const val HOUR_MS = 60L * 60L * 1000L

        /** An empty row — no permission, no events, or a day that has not started. */
        fun empty(): List<Long> = List(HOURS) { 0L }
    }
}
