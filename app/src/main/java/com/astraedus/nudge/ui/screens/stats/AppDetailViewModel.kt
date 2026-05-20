package com.astraedus.nudge.ui.screens.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class AppDetailUiState(
    val packageName: String = "",
    val appName: String = "",
    val todayFormatted: String = "0s",
    val weeklyData: List<DayData> = emptyList(),
    val hourlyMs: List<Long> = List(24) { 0L },
    val trendData: List<TrendDay> = emptyList(),
    val blockedCountToday: Int = 0,
    val walkedAwayCountToday: Int = 0,
    val blockedCountTotal: Int = 0,
    val walkedAwayCountTotal: Int = 0,
    val blockModeBreakdown: Map<String, Int> = emptyMap()
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val statsCalculator: StatsCalculator
) : ViewModel() {

    private val packageName: String = savedStateHandle.get<String>("packageName") ?: ""
    private val todayStart = timeTracker.startOfToday()
    private val todayEnd = todayStart + DAY_MS
    private val weekStart = todayStart - 6 * DAY_MS

    private val appName: String by lazy {
        installedAppsRepository.resolveAppName(packageName)
    }

    private val weekEventsFlow = usageRepository.getEventsSince(weekStart)
    private val allEventsFlow = usageRepository.getEventsSince(0L)

    private val screenTimeFlow = flow {
        while (true) {
            val todayMs = screenTimeProvider.getPerAppScreenTimeToday()
                .getOrDefault(packageName, 0L)
            val weekly = screenTimeProvider.getPerAppDailyScreenTimesForWeek(packageName)
            val hourly = screenTimeProvider.getPerAppHourlyScreenTimeToday(packageName)
            emit(AppScreenTimeSnapshot(todayMs, weekly, hourly))
            delay(30_000L)
        }
    }

    val uiState: StateFlow<AppDetailUiState> = combine(
        weekEventsFlow,
        allEventsFlow,
        screenTimeFlow
    ) { weekEvents, allEvents, screenTime ->
        buildUiState(weekEvents, allEvents, screenTime)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppDetailUiState())

    private fun buildUiState(
        weekEvents: List<UsageEvent>,
        allEvents: List<UsageEvent>,
        screenTime: AppScreenTimeSnapshot
    ): AppDetailUiState {
        val appWeekEvents = weekEvents.filter { it.packageName == packageName }
        val appAllEvents = allEvents.filter { it.packageName == packageName }
        val todayEvents = appWeekEvents.filter { it.timestamp in todayStart until todayEnd }

        val todayFormatted = if (screenTime.todayMs < 60_000L && screenTime.todayMs > 0L) {
            "< 1m"
        } else {
            timeTracker.formatDuration(screenTime.todayMs)
        }

        val modeBreakdown = appAllEvents
            .filter { it.wasBlocked && it.blockMode != null }
            .groupBy { it.blockMode!! }
            .mapValues { it.value.size }

        return AppDetailUiState(
            packageName = packageName,
            appName = appName,
            todayFormatted = todayFormatted,
            weeklyData = statsCalculator.buildWeeklyDataFromTotals(screenTime.weeklyTotals),
            hourlyMs = screenTime.hourlyMs,
            trendData = statsCalculator.buildAppTrendData(weekEvents, packageName),
            blockedCountToday = todayEvents.count { it.wasBlocked },
            walkedAwayCountToday = todayEvents.count { it.userChangedMind },
            blockedCountTotal = appAllEvents.count { it.wasBlocked },
            walkedAwayCountTotal = appAllEvents.count { it.userChangedMind },
            blockModeBreakdown = modeBreakdown
        )
    }

    private data class AppScreenTimeSnapshot(
        val todayMs: Long,
        val weeklyTotals: List<Long>,
        val hourlyMs: List<Long>
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
