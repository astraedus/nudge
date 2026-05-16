package com.astraedus.nudge.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val formattedDuration: String,
    val fraction: Float
)

data class StatsUiState(
    val totalFormatted: String = "0s",
    val appStats: List<AppUsageStat> = emptyList(),
    val weeklyData: List<DayData> = emptyList(),
    val trendData: List<TrendDay> = emptyList(),
    val hourlyMs: List<Long> = List(24) { 0L },
    val streakDays: Int = 0
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val timeTracker: TimeTracker,
    private val statsCalculator: StatsCalculator
) : ViewModel() {

    private val todayStart = timeTracker.startOfToday()
    private val todayEnd = todayStart + DAY_MS
    private val weekStart = todayStart - 6 * DAY_MS

    private val appNameMap: Map<String, String> by lazy {
        installedAppsRepository.getInstalledApps().associate { it.packageName to it.appName }
    }

    private val todayEventsFlow = usageRepository.getUsageForDay(todayStart, todayEnd)
    private val weekEventsFlow = usageRepository.getEventsSince(weekStart)

    val uiState: StateFlow<StatsUiState> = combine(
        todayEventsFlow,
        weekEventsFlow
    ) { todayEvents, weekEvents ->
        buildUiState(todayEvents, weekEvents)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private fun buildUiState(todayEvents: List<UsageEvent>, weekEvents: List<UsageEvent>): StatsUiState {
        // Today's per-app stats
        val byPackage = todayEvents.groupBy { it.packageName }
            .mapValues { (_, evts) -> evts.sumOf { it.durationMs } }
            .entries
            .sortedByDescending { it.value }

        val totalMs = byPackage.sumOf { it.value }
        val maxMs = byPackage.maxOfOrNull { it.value } ?: 1L

        val appStats = byPackage.map { (pkg, ms) ->
            AppUsageStat(
                packageName = pkg,
                appName = appNameMap[pkg] ?: pkg,
                durationMs = ms,
                formattedDuration = timeTracker.formatDuration(ms),
                fraction = (ms.toFloat() / maxMs.toFloat()).coerceIn(0.05f, 1f)
            )
        }

        return StatsUiState(
            totalFormatted = timeTracker.formatDuration(totalMs),
            appStats = appStats,
            weeklyData = statsCalculator.buildWeeklyData(weekEvents),
            trendData = statsCalculator.buildTrendData(weekEvents),
            hourlyMs = statsCalculator.buildHourlyData(todayEvents),
            streakDays = statsCalculator.calculateStreak(weekEvents)
        )
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
