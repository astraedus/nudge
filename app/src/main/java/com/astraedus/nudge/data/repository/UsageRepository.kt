package com.astraedus.nudge.data.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageEventDao: UsageEventDao,
    private val timeTracker: TimeTracker
) {

    suspend fun logEvent(event: UsageEvent) = usageEventDao.insert(event)

    /** Total usage in milliseconds for a package today. */
    fun getDailyUsage(packageName: String): Flow<Long> {
        val todayStart = timeTracker.startOfToday()
        return usageEventDao.getTotalDurationForPackage(packageName, todayStart)
            .map { it ?: 0L }
    }

    /**
     * Get today's foreground usage time from Android's UsageStatsManager.
     * More reliable than our custom durationMs field since it uses the OS-level tracking.
     * Returns time in milliseconds.
     */
    fun getDailyForegroundTimeMs(packageName: String): Long {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L
            val todayStart = timeTracker.startOfToday()
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                todayStart,
                now
            )
            stats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
        } catch (_: SecurityException) {
            // PACKAGE_USAGE_STATS permission not granted
            0L
        }
    }

    fun getUsageForDay(dayStart: Long, dayEnd: Long): Flow<List<UsageEvent>> =
        usageEventDao.getEventsForDay(dayStart, dayEnd)

    fun getTotalDurationForDay(dayStart: Long, dayEnd: Long): Flow<Long?> =
        usageEventDao.getTotalDurationForDay(dayStart, dayEnd)

    fun getEventsSince(since: Long): Flow<List<UsageEvent>> =
        usageEventDao.getEventsSince(since)

    fun getChangedMindCountForDay(dayStart: Long, dayEnd: Long): Flow<Int> =
        usageEventDao.getChangedMindCount(dayStart, dayEnd)

    fun getBlockedCountForDay(dayStart: Long, dayEnd: Long): Flow<Int> =
        usageEventDao.getBlockedCount(dayStart, dayEnd)

    /** Delete events older than [retainDays] days. */
    suspend fun cleanup(retainDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - retainDays.toLong() * 24L * 60L * 60L * 1000L
        usageEventDao.deleteOlderThan(cutoff)
    }
}
