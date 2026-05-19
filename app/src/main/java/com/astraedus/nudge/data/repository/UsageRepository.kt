package com.astraedus.nudge.data.repository

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.service.UsageProvider
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
) : UsageProvider {

    suspend fun logEvent(event: UsageEvent) = usageEventDao.insert(event)

    /** Total usage in milliseconds for a package today. */
    fun getDailyUsage(packageName: String): Flow<Long> {
        val todayStart = timeTracker.startOfToday()
        return usageEventDao.getTotalDurationForPackage(packageName, todayStart)
            .map { it ?: 0L }
    }

    /**
     * Get today's foreground usage time from queryEvents (ACTIVITY_RESUMED/PAUSED pairs).
     * queryUsageStats(INTERVAL_DAILY) returns stale pre-aggregated buckets on Android 12+;
     * event-based calculation gives accurate real-time data.
     */
    override fun getDailyForegroundTimeMs(packageName: String): Long {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L
            val todayStart = timeTracker.startOfToday()
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(todayStart, now) ?: return 0L
            val event = UsageEvents.Event()

            var totalMs = 0L
            var lastResumed = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> lastResumed = event.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (lastResumed > 0L) {
                            totalMs += event.timeStamp - lastResumed
                            lastResumed = 0L
                        }
                    }
                }
            }

            if (lastResumed > 0L) totalMs += now - lastResumed
            totalMs
        } catch (_: SecurityException) {
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

    fun getAllTimeBlockedCount(): Flow<Int> =
        usageEventDao.getAllTimeBlockedCount()

    fun getAllTimeChangedMindCount(): Flow<Int> =
        usageEventDao.getAllTimeChangedMindCount()

    /** Delete events older than [retainDays] days. */
    suspend fun cleanup(retainDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - retainDays.toLong() * 24L * 60L * 60L * 1000L
        usageEventDao.deleteOlderThan(cutoff)
    }
}
