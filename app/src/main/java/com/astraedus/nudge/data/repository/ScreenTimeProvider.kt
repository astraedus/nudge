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
     * Get per-app foreground time for today using event-based calculation.
     * Uses queryEvents (ACTIVITY_RESUMED/PAUSED pairs) which is accurate in real-time,
     * unlike queryUsageStats(INTERVAL_DAILY) which returns stale pre-aggregated buckets
     * on Android 12+.
     */
    fun getPerAppScreenTimeToday(): Map<String, Long> {
        return try {
            val usm = usageStatsManager ?: return emptyMap()
            val todayStart = timeTracker.startOfToday()
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(todayStart, now) ?: return emptyMap()
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

            for ((pkg, startTime) in foregroundStarts) {
                perApp[pkg] = (perApp[pkg] ?: 0L) + (now - startTime)
            }

            perApp.filter { it.value > 0L }
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    /** Get total screen time for today in milliseconds (all apps combined). */
    fun getTotalScreenTimeToday(): Long {
        return getPerAppScreenTimeToday().values.sum()
    }

    /**
     * Get daily screen time totals for the last 7 days.
     * Returns a list of 7 entries, index 0 = 6 days ago, index 6 = today.
     * Each value is total foreground time in milliseconds for that day.
     */
    fun getDailyScreenTimesForWeek(): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(7) { 0L }
            val todayStart = timeTracker.startOfToday()
            val dayMs = 24L * 60L * 60L * 1000L

            (6 downTo 0).map { daysAgo ->
                val dayStart = todayStart - daysAgo * dayMs
                val dayEnd = if (daysAgo == 0) System.currentTimeMillis() else dayStart + dayMs
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
     * Get per-hour screen time breakdown for today.
     * Returns a list of 24 entries (index = hour 0-23), each value in milliseconds.
     *
     * Uses UsageEvents to determine which hours had foreground activity.
     * This is an approximation: for each foreground session, we distribute time
     * across the hours the session spans.
     */
    fun getHourlyScreenTimeToday(): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(24) { 0L }
            val todayStart = timeTracker.startOfToday()
            val now = System.currentTimeMillis()
            val hourMs = 60L * 60L * 1000L
            val hourly = MutableList(24) { 0L }

            val events = usm.queryEvents(todayStart, now) ?: return hourly
            val event = UsageEvents.Event()

            // Track foreground start times per package
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
                            distributeToHours(hourly, startTime, event.timeStamp, todayStart, hourMs)
                        }
                    }
                }
            }

            // Handle still-open sessions (app currently in foreground)
            for ((_, startTime) in foregroundStarts) {
                distributeToHours(hourly, startTime, now, todayStart, hourMs)
            }

            hourly
        } catch (_: SecurityException) {
            List(24) { 0L }
        }
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
