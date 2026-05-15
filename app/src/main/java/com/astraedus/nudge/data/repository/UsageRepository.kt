package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(
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

    fun getUsageForDay(dayStart: Long, dayEnd: Long): Flow<List<UsageEvent>> =
        usageEventDao.getEventsForDay(dayStart, dayEnd)

    /** Delete events older than [retainDays] days. */
    suspend fun cleanup(retainDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - retainDays.toLong() * 24L * 60L * 60L * 1000L
        usageEventDao.deleteOlderThan(cutoff)
    }
}
