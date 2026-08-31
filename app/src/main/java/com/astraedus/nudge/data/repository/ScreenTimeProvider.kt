package com.astraedus.nudge.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.usage.DailyUsageAccumulator
import com.astraedus.nudge.domain.usage.ForegroundSpanTracker
import com.astraedus.nudge.domain.usage.HourlyUsageAccumulator
import com.astraedus.nudge.domain.usage.WeeklyUsage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides screen time data from Android's UsageStatsManager.
 *
 * This is the correct data source for "Screen Time" display. The internal
 * Room DB (usage_events table) only logs block/allow decisions and does NOT
 * track foreground duration at all — it carried an always-zero `durationMs`
 * column until issue #22 removed it.
 *
 * **One app is in the foreground at a time.** Every read here goes through
 * [ForegroundSpanTracker], which turns the event stream into non-overlapping spans, so no two
 * apps can be billed for the same minute and no day can add up to more than the day. Each read
 * path used to pair events with its own `package -> startTime` map, which allowed exactly that:
 * a real phone reported ~17 hours of screen time before lunchtime, because several apps held
 * open spans at once and each was credited from its RESUMED through to now (v1.15.1).
 *
 * Requires PACKAGE_USAGE_STATS permission (granted via Settings > Special Access > Usage Access).
 * Returns 0 gracefully when permission is missing.
 */
@Singleton
class ScreenTimeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeTracker: TimeTracker
) {

    /** Foreground time for one app over a range, plus how many sessions it was spread over. */
    data class SessionStats(val totalMs: Long, val sessionCount: Int) {
        /** Mean session length, or null when there is nothing to average. */
        val averageMs: Long? get() = if (sessionCount > 0) totalMs / sessionCount else null
    }

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /** Check if Usage Access permission is granted. */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Per-app foreground time AND the number of foreground sessions that produced it, for
     * an arbitrary range — one `queryEvents` pass, one place that pairs RESUMED with PAUSED.
     *
     * This is a RANGE primitive, not a day one. Day-scoped and week-scoped totals all come from
     * [getWeeklyUsage] instead, so the bars and the drill-down under them can never be two
     * different computations of the same calendar day (they were, and they disagreed).
     *
     * The session count is what makes an *average session length* computable
     * (`totalMs / sessionCount`), which the Willpower screen uses to estimate how much
     * time a walk-away actually saved.
     */
    fun getPerAppSessionStats(rangeStartMs: Long, rangeEndMs: Long): Map<String, SessionStats> {
        return try {
            val usm = usageStatsManager ?: return emptyMap()
            val now = System.currentTimeMillis()
            val effectiveEnd = rangeEndMs.coerceAtMost(now)
            if (rangeStartMs >= effectiveEnd) return emptyMap()

            val events = usm.queryEvents(rangeStartMs, effectiveEnd) ?: return emptyMap()
            val perApp = mutableMapOf<String, SessionStats>()

            val tracker = ForegroundSpanTracker { pkg, startMs, endMs ->
                val existing = perApp[pkg] ?: SessionStats(0L, 0)
                perApp[pkg] = SessionStats(
                    totalMs = existing.totalMs + (endMs - startMs),
                    sessionCount = existing.sessionCount + 1
                )
            }
            feed(events, tracker)
            tracker.finish(nowMs = now, extendOpenSpanToNow = rangeEndMs >= now)

            perApp
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    /**
     * Per-day, per-app foreground time for the [WEEK_DAYS]-day window ending at [lastDayStartMs].
     *
     * **The one source of truth for every day-scoped screen-time number in the app** — the weekly
     * bars, the day drill-down's hero total, and its per-app list all read this single value.
     *
     * It replaced a `queryUsageStats(INTERVAL_DAILY)` series that ran beside the drill-down's
     * event-based computation. Those pre-aggregated buckets are stale and midnight-misaligned on
     * Android 12+ (see [getPerAppSessionStats]), so the two could flatly contradict each other:
     * a Wednesday bar rendered tall and dark while drilling into that Wednesday showed "0s" and
     * "No usage recorded". Both are now the same numbers, not merely two computations expected
     * to agree.
     *
     * **One pass, not seven.** A single `queryEvents` over the whole window feeds
     * [DailyUsageAccumulator], which splits each RESUMED->PAUSED span across the days it covers.
     * Seven per-day queries would cost seven binder round-trips on every 30 s Home/Stats poll on
     * a 3 GB Pixel 3, and could not see a session crossing midnight at all — each half would be
     * an unpaired event in its own day's query and would be dropped from both.
     *
     * Day boundaries come from `TimeTracker.startOfDayDaysBefore` (calendar arithmetic), so they
     * are true local midnights either side of a DST transition.
     *
     * Returns a zeroed window (right shape, no data) without permission, on a read failure, or
     * for a window that lies entirely in the future.
     *
     * @param lastDayStartMs start-of-day epoch millis for the last day of the window (default: today)
     */
    fun getWeeklyUsage(lastDayStartMs: Long = timeTracker.startOfToday()): WeeklyUsage {
        val dayStarts = (WEEK_DAYS - 1 downTo 0).map { daysAgo ->
            timeTracker.startOfDayDaysBefore(lastDayStartMs, daysAgo)
        }
        // A negative "days before" walks forward: the exclusive end of the window is the start of
        // the day AFTER the last one. Calendar arithmetic again, not `+ DAY_MS`.
        val windowEndMs = timeTracker.startOfDayDaysBefore(lastDayStartMs, -1)
        val windowStartMs = dayStarts.first()

        return try {
            val usm = usageStatsManager ?: return WeeklyUsage.empty(dayStarts)
            val now = System.currentTimeMillis()
            val effectiveEnd = windowEndMs.coerceAtMost(now)
            if (windowStartMs >= effectiveEnd) return WeeklyUsage.empty(dayStarts)

            val events = usm.queryEvents(windowStartMs, effectiveEnd)
                ?: return WeeklyUsage.empty(dayStarts)
            val accumulator = DailyUsageAccumulator(dayStarts + windowEndMs)
            feed(events, accumulator.eventSink)

            WeeklyUsage(dayStarts, accumulator.finish(windowEndMs = windowEndMs, nowMs = now))
        } catch (_: SecurityException) {
            WeeklyUsage.empty(dayStarts)
        }
    }

    /**
     * Get per-hour screen time breakdown for an arbitrary day.
     * Returns a list of 24 entries (index = hour 0-23), each value in milliseconds.
     *
     * @param dayStartMs start of the day (midnight), epoch millis
     * @param dayEndMs end of the day (next midnight or now for today), epoch millis
     */
    fun getHourlyScreenTime(dayStartMs: Long, dayEndMs: Long): List<Long> =
        hourlyScreenTime(dayStartMs, dayEndMs, packageName = null)

    /**
     * Get per-hour screen time breakdown for a specific app on an arbitrary day.
     *
     * @param packageName the app's package name
     * @param dayStartMs start of the day (midnight), epoch millis
     * @param dayEndMs end of the day (next midnight or now for today), epoch millis
     */
    fun getPerAppHourlyScreenTime(packageName: String, dayStartMs: Long, dayEndMs: Long): List<Long> =
        hourlyScreenTime(dayStartMs, dayEndMs, packageName)

    /**
     * The one hourly read, for the whole device ([packageName] null) or for one app.
     *
     * **Every event is fed to the tracker, and the filter is applied to the resulting spans, not
     * to the stream.** Filtering first is what the per-app version used to do, and it blinded the
     * tracker: without the other apps' RESUMED events nothing could tell it this app had stopped
     * being foreground, so one missing PAUSED billed the app through to now and painted hours the
     * phone spent in a pocket.
     */
    private fun hourlyScreenTime(
        dayStartMs: Long,
        dayEndMs: Long,
        packageName: String?
    ): List<Long> {
        return try {
            val usm = usageStatsManager ?: return HourlyUsageAccumulator.empty()
            val now = System.currentTimeMillis()
            val effectiveEnd = dayEndMs.coerceAtMost(now)
            if (dayStartMs >= effectiveEnd) return HourlyUsageAccumulator.empty()

            val events = usm.queryEvents(dayStartMs, effectiveEnd)
                ?: return HourlyUsageAccumulator.empty()

            val accumulator = HourlyUsageAccumulator(dayStartMs, effectiveEnd)
            val tracker = ForegroundSpanTracker { pkg, startMs, endMs ->
                if (packageName == null || pkg == packageName) accumulator.add(startMs, endMs)
            }
            feed(events, tracker)
            tracker.finish(nowMs = now, extendOpenSpanToNow = dayEndMs >= now)

            accumulator.totals()
        } catch (_: SecurityException) {
            HourlyUsageAccumulator.empty()
        }
    }

    /**
     * Walks a `queryEvents` cursor into a [ForegroundSpanTracker] — the **one** place platform
     * event types are interpreted, shared by the weekly bars, the hourly heatmap and the session
     * stats. Three hand-rolled copies of this loop is how the three of them came to disagree.
     *
     * `ACTIVITY_STOPPED`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN` and `DEVICE_SHUTDOWN` all
     * close the open span. The screen-off pair is the important one: a phone put down mid-session
     * often never delivers the app's own `ACTIVITY_PAUSED`, and before these were handled that
     * app kept accruing "screen time" for as long as the phone stayed dark. They are device-level
     * events, reported by the platform under package `"android"`, and the constants are added
     * after our minSdk of 26 — harmless, since they are compile-time ints and an older device
     * simply never emits them (`ACTIVITY_RESUMED` itself is an API-29 constant).
     */
    private fun feed(events: UsageEvents, tracker: ForegroundSpanTracker) {
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // Device-level first, and deliberately not gated on a package name: these say the
                // foreground ended regardless of whose it was.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN ->
                    tracker.onForegroundEnded(event.timeStamp)

                UsageEvents.Event.ACTIVITY_RESUMED ->
                    event.packageName?.let { tracker.onResumed(it, event.className, event.timeStamp) }
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    event.packageName?.let { tracker.onPaused(it, event.timeStamp) }
                UsageEvents.Event.ACTIVITY_STOPPED ->
                    event.packageName?.let { tracker.onStopped(it, event.className, event.timeStamp) }
            }
        }
    }

    companion object {
        /**
         * Days in a weekly window. Must match `StatsDaySelection.WINDOW_DAYS` — the screens index
         * into [WeeklyUsage] by the bar the user tapped. Pinned by `WeeklyUsageTest`.
         */
        const val WEEK_DAYS = 7
    }
}
