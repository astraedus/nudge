package com.astraedus.nudge.domain.usage

/**
 * Turns a stream of foreground events (`ACTIVITY_RESUMED` -> `ACTIVITY_PAUSED`) into per-day,
 * per-app totals, splitting any span that crosses a day boundary across the days it covers.
 *
 * **Why this exists.** The weekly screen-time bars used to come from
 * `queryUsageStats(INTERVAL_DAILY)` — pre-aggregated, stale, midnight-misaligned buckets —
 * while the day drill-down computed its numbers from live `queryEvents` spans. Two sources of
 * truth for one calendar day, and they disagreed in the field: a bar rendered tall and dark for
 * a Wednesday whose drill-down said "0s / No usage recorded". This accumulator is the ONE
 * computation both now come from (see `ScreenTimeProvider.getWeeklyUsage`), so a bar and the
 * numbers under it cannot describe different days.
 *
 * **Fed from one pass.** The caller reads the whole window with a single `queryEvents` call and
 * pushes events through in order; the alternative (one query per day) costs seven binder
 * round-trips on every 30 s stats poll, and — worse — cannot see a span that crosses midnight,
 * because such a span has one endpoint outside each day's own query.
 *
 * **Day boundaries are supplied, not derived.** They come from `TimeTracker.startOfDayDaysBefore`
 * (calendar arithmetic), so a DST day is genuinely 23 or 25 hours wide here. Deriving them from
 * `index * 86_400_000` would slide every boundary in the window an hour off true local midnight.
 *
 * **Pairing is not its job.** Deciding which app is foreground and until when belongs to
 * [ForegroundSpanTracker], which this delegates to; it is what guarantees the spans arriving here
 * do not overlap, and therefore that a day's total can never exceed the day. Accumulating the
 * pairing here as well would be the two-implementations-of-one-question defect again, this time
 * against the hourly heatmap and the Willpower session stats, which read the same events.
 *
 * Pure Kotlin, no Android types: the whole pairing/splitting contract is JVM-tested in
 * `DailyUsageAccumulatorTest`.
 *
 * Not thread-safe. Build one, feed it, [finish] it, discard it.
 *
 * @param dayBoundariesMs ascending epoch millis, one more entry than there are days: bucket `i`
 *   covers `[dayBoundariesMs[i], dayBoundariesMs[i + 1])`.
 */
class DailyUsageAccumulator(private val dayBoundariesMs: List<Long>) {

    init {
        require(dayBoundariesMs.size >= 2) {
            "need at least one day: a boundary list of ${dayBoundariesMs.size} describes no bucket"
        }
        require(dayBoundariesMs.zipWithNext().all { (start, end) -> start < end }) {
            "day boundaries must be strictly ascending, got $dayBoundariesMs"
        }
    }

    private val buckets: List<MutableMap<String, Long>> =
        List(dayBoundariesMs.size - 1) { mutableMapOf() }

    /**
     * Where the raw platform stream goes. `ScreenTimeProvider` feeds this directly, with the same
     * loop it uses for the hourly heatmap and the session stats, so all three read paths agree on
     * which app was foreground when.
     */
    val eventSink = ForegroundSpanTracker(onSpan = ::addSpan)

    /** End of the last span bucketed, so an overlapping one cannot be counted twice. */
    private var lastSpanEndMs = Long.MIN_VALUE

    /** An activity moved to the foreground. See [ForegroundSpanTracker.onResumed]. */
    fun onResumed(packageName: String, timestampMs: Long, className: String? = null) {
        eventSink.onResumed(packageName, className, timestampMs)
    }

    /** An activity moved to the background. See [ForegroundSpanTracker.onPaused]. */
    fun onPaused(packageName: String, timestampMs: Long) {
        eventSink.onPaused(packageName, timestampMs)
    }

    /** An activity became invisible. See [ForegroundSpanTracker.onStopped]. */
    fun onStopped(packageName: String, timestampMs: Long, className: String? = null) {
        eventSink.onStopped(packageName, className, timestampMs)
    }

    /**
     * The screen went off, the keyguard came up, or the device shut down — whatever was in the
     * foreground no longer is. See [ForegroundSpanTracker.onForegroundEnded].
     */
    fun onForegroundEnded(timestampMs: Long) {
        eventSink.onForegroundEnded(timestampMs)
    }

    /**
     * Closes the accumulation and returns one map per day.
     *
     * A span still open at the end of the stream is counted towards [nowMs] only when the window
     * actually reaches the present ([windowEndMs] >= [nowMs]), and only as far as the tracker's
     * open-span cap allows. For a window that has already ended, an open span means the closing
     * PAUSED simply fell outside the query, and inventing an end for it would credit a past day
     * with time we cannot see.
     *
     * Idempotent: the open span is consumed, so a second call adds nothing.
     */
    fun finish(windowEndMs: Long, nowMs: Long): List<Map<String, Long>> {
        eventSink.finish(nowMs = nowMs, extendOpenSpanToNow = windowEndMs >= nowMs)
        return buckets.map { it.toMap() }
    }

    /**
     * Adds `[startMs, endMs)` to every day it overlaps.
     *
     * Clamped to the window first, so an event from outside it contributes nothing, and a
     * backwards pair (PAUSED before RESUMED — a corrupt sequence the platform has produced)
     * yields zero rather than the negative duration a bare subtraction would.
     *
     * Also clamped to start no earlier than the last span already counted. [ForegroundSpanTracker]
     * cannot hand out overlapping spans, so this changes nothing today — it is here because an
     * overlap is exactly how a day came to read 17 hours before lunchtime, and this is the last
     * place that could still absorb one.
     */
    private fun addSpan(packageName: String, startMs: Long, endMs: Long) {
        val spanStart = startMs.coerceAtLeast(dayBoundariesMs.first()).coerceAtLeast(lastSpanEndMs)
        val spanEnd = endMs.coerceAtMost(dayBoundariesMs.last())
        if (spanStart >= spanEnd) return
        lastSpanEndMs = spanEnd

        for (day in buckets.indices) {
            val dayStart = dayBoundariesMs[day]
            if (dayStart >= spanEnd) break
            val dayEnd = dayBoundariesMs[day + 1]
            if (dayEnd <= spanStart) continue

            val overlap = minOf(spanEnd, dayEnd) - maxOf(spanStart, dayStart)
            if (overlap > 0L) {
                buckets[day][packageName] = (buckets[day][packageName] ?: 0L) + overlap
            }
        }
    }
}
