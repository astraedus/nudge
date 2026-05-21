package com.astraedus.nudge.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.astraedus.nudge.domain.engine.TimeTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides screen time data from Android's UsageStatsManager.
 *
 * This is the correct data source for "Screen Time" display. The internal
 * Room DB (usage_events table) only logs block/allow decisions and does NOT
 * track foreground duration — its durationMs field is always 0.
 *
 * Requires PACKAGE_USAGE_STATS permission (granted via Settings > Special Access > Usage Access).
 * Returns 0 gracefully when permission is missing.
 */
@Singleton
class ScreenTimeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeTracker: TimeTracker
) {

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
     * Get per-app foreground time for an arbitrary time range using event-based calculation.
     * Uses queryEvents (ACTIVITY_RESUMED/PAUSED pairs) which is accurate in real-time,
     * unlike queryUsageStats(INTERVAL_DAILY) which returns stale pre-aggregated buckets
     * on Android 12+.
     *
     * @param dayStartMs start of the range (inclusive), epoch millis
     * @param dayEndMs end of the range (exclusive), epoch millis
     */
    fun getPerAppScreenTime(dayStartMs: Long, dayEndMs: Long): Map<String, Long> {
        return try {
            val usm = usageStatsManager ?: return emptyMap()
            val now = System.currentTimeMillis()
            val effectiveEnd = dayEndMs.coerceAtMost(now)
            if (dayStartMs >= effectiveEnd) return emptyMap()

            val events = usm.queryEvents(dayStartMs, effectiveEnd) ?: return emptyMap()
            val event = UsageEvents.Event()

            val foregroundStarts = mutableMapOf<String, Long>()
            val perApp = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foregroundStarts[event.packageName] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val startTime = foregroundStarts.remove(event.packageName)
                        if (startTime != null) {
                            perApp[event.packageName] =
                                (perApp[event.packageName] ?: 0L) + (event.timeStamp - startTime)
                        }
                    }
                }
            }

            // Only add still-open sessions if the range includes "now"
            if (dayEndMs >= now) {
                for ((pkg, startTime) in foregroundStarts) {
                    perApp[pkg] = (perApp[pkg] ?: 0L) + (now - startTime)
                }
            }

            perApp.filter { it.value > 0L }
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    /** Convenience: get per-app foreground time for today. */
    fun getPerAppScreenTimeToday(): Map<String, Long> {
        val todayStart = timeTracker.startOfToday()
        val now = System.currentTimeMillis()
        return getPerAppScreenTime(todayStart, now)
    }

    /** Get total screen time for an arbitrary range in milliseconds. */
    fun getTotalScreenTime(dayStartMs: Long, dayEndMs: Long): Long {
        return getPerAppScreenTime(dayStartMs, dayEndMs).values.sum()
    }

    /** Convenience: get total screen time for today in milliseconds. */
    fun getTotalScreenTimeToday(): Long {
        return getPerAppScreenTimeToday().values.sum()
    }

    /**
     * Get daily screen time totals for 7 days ending at [lastDayStartMs].
     * Returns a list of 7 entries, index 0 = 6 days before lastDay, index 6 = lastDay.
     *
     * @param lastDayStartMs start-of-day epoch millis for the last day of the window (default: today)
     */
    fun getDailyScreenTimesForWeek(lastDayStartMs: Long = timeTracker.startOfToday()): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(7) { 0L }
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60L * 60L * 1000L

            (6 downTo 0).map { daysAgo ->
                val dayStart = lastDayStartMs - daysAgo * dayMs
                val dayEnd = if (dayStart + dayMs > now) now else dayStart + dayMs
                if (dayStart >= now) return@map 0L
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    dayStart,
                    dayEnd
                ) ?: emptyList()
                stats.sumOf { it.totalTimeInForeground }
            }
        } catch (_: SecurityException) {
            List(7) { 0L }
        }
    }

    /**
     * Get per-hour screen time breakdown for an arbitrary day.
     * Returns a list of 24 entries (index = hour 0-23), each value in milliseconds.
     *
     * @param dayStartMs start of the day (midnight), epoch millis
     * @param dayEndMs end of the day (next midnight or now for today), epoch millis
     */
    fun getHourlyScreenTime(dayStartMs: Long, dayEndMs: Long): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(24) { 0L }
            val now = System.currentTimeMillis()
            val effectiveEnd = dayEndMs.coerceAtMost(now)
            if (dayStartMs >= effectiveEnd) return List(24) { 0L }

            val hourMs = 60L * 60L * 1000L
            val hourly = MutableList(24) { 0L }

            val events = usm.queryEvents(dayStartMs, effectiveEnd) ?: return hourly
            val event = UsageEvents.Event()

            val foregroundStarts = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foregroundStarts[event.packageName] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val startTime = foregroundStarts.remove(event.packageName)
                        if (startTime != null) {
                            distributeToHours(hourly, startTime, event.timeStamp, dayStartMs, hourMs)
                        }
                    }
                }
            }

            if (dayEndMs >= now) {
                for ((_, startTime) in foregroundStarts) {
                    distributeToHours(hourly, startTime, now, dayStartMs, hourMs)
                }
            }

            hourly
        } catch (_: SecurityException) {
            List(24) { 0L }
        }
    }

    /** Convenience: get per-hour screen time breakdown for today. */
    fun getHourlyScreenTimeToday(): List<Long> {
        val todayStart = timeTracker.startOfToday()
        val now = System.currentTimeMillis()
        return getHourlyScreenTime(todayStart, now)
    }

    /**
     * Get daily screen time totals for a specific app over 7 days ending at [lastDayStartMs].
     * Returns a list of 7 entries, index 0 = 6 days before lastDay, index 6 = lastDay.
     *
     * @param packageName the app's package name
     * @param lastDayStartMs start-of-day epoch millis for the last day of the window (default: today)
     */
    fun getPerAppDailyScreenTimesForWeek(packageName: String, lastDayStartMs: Long = timeTracker.startOfToday()): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(7) { 0L }
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60L * 60L * 1000L

            (6 downTo 0).map { daysAgo ->
                val dayStart = lastDayStartMs - daysAgo * dayMs
                val dayEnd = if (dayStart + dayMs > now) now else dayStart + dayMs
                if (dayStart >= now) return@map 0L
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    dayStart,
                    dayEnd
                ) ?: emptyList()
                stats.filter { it.packageName == packageName }
                    .sumOf { it.totalTimeInForeground }
            }
        } catch (_: SecurityException) {
            List(7) { 0L }
        }
    }

    /**
     * Get per-hour screen time breakdown for a specific app on an arbitrary day.
     *
     * @param packageName the app's package name
     * @param dayStartMs start of the day (midnight), epoch millis
     * @param dayEndMs end of the day (next midnight or now for today), epoch millis
     */
    fun getPerAppHourlyScreenTime(packageName: String, dayStartMs: Long, dayEndMs: Long): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(24) { 0L }
            val now = System.currentTimeMillis()
            val effectiveEnd = dayEndMs.coerceAtMost(now)
            if (dayStartMs >= effectiveEnd) return List(24) { 0L }

            val hourMs = 60L * 60L * 1000L
            val hourly = MutableList(24) { 0L }

            val events = usm.queryEvents(dayStartMs, effectiveEnd) ?: return hourly
            val event = UsageEvents.Event()
            val foregroundStarts = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foregroundStarts[event.packageName] = event.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val startTime = foregroundStarts.remove(event.packageName)
                        if (startTime != null) {
                            distributeToHours(hourly, startTime, event.timeStamp, dayStartMs, hourMs)
                        }
                    }
                }
            }

            if (dayEndMs >= now) {
                for ((_, startTime) in foregroundStarts) {
                    distributeToHours(hourly, startTime, now, dayStartMs, hourMs)
                }
            }

            hourly
        } catch (_: SecurityException) {
            List(24) { 0L }
        }
    }

    /** Convenience: get per-hour screen time for a specific app today. */
    fun getPerAppHourlyScreenTimeToday(packageName: String): List<Long> {
        val todayStart = timeTracker.startOfToday()
        val now = System.currentTimeMillis()
        return getPerAppHourlyScreenTime(packageName, todayStart, now)
    }

    /**
     * Distribute a foreground session's duration across hourly buckets.
     */
    private fun distributeToHours(
        hourly: MutableList<Long>,
        sessionStart: Long,
        sessionEnd: Long,
        todayStart: Long,
        hourMs: Long
    ) {
        val clampedStart = sessionStart.coerceAtLeast(todayStart)
        val clampedEnd = sessionEnd.coerceAtMost(todayStart + 24 * hourMs)

        if (clampedStart >= clampedEnd) return

        val startHour = ((clampedStart - todayStart) / hourMs).toInt().coerceIn(0, 23)
        val endHour = ((clampedEnd - todayStart) / hourMs).toInt().coerceIn(0, 23)

        for (hour in startHour..endHour) {
            val bucketStart = todayStart + hour * hourMs
            val bucketEnd = bucketStart + hourMs
            val overlapStart = clampedStart.coerceAtLeast(bucketStart)
            val overlapEnd = clampedEnd.coerceAtMost(bucketEnd)
            if (overlapStart < overlapEnd) {
                hourly[hour] += overlapEnd - overlapStart
            }
        }
    }
}
