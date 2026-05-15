package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetUsageStatsUseCase @Inject constructor(
    private val usageRepository: UsageRepository,
    private val timeTracker: TimeTracker
) {

    /** Returns a map of packageName to total daily usage in milliseconds. */
    fun invoke(): Flow<Map<String, Long>> {
        val todayStart = timeTracker.startOfToday()
        val todayEnd = todayStart + 24L * 60L * 60L * 1000L

        return usageRepository.getUsageForDay(todayStart, todayEnd).map { events ->
            events.groupBy { it.packageName }
                .mapValues { (_, eventsForPkg) -> eventsForPkg.sumOf { it.durationMs } }
        }
    }
}
