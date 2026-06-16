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
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.formatDateLabel
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.toEpochMs
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
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
    val blockModeBreakdown: Map<String, Int> = emptyMap(),
    val isToday: Boolean = true,
    val dateLabel: String = "Today"
)

@OptIn(ExperimentalCoroutinesApi::class)
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

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    // Resolved lazily off the main thread on first build; cached in the repo.
    private suspend fun appName(): String =
        installedAppsRepository.resolveAppName(packageName)

    fun goToPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun goToNextDay() {
        val next = _selectedDate.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) {
            _selectedDate.value = next
        }
    }

    private val weekEventsFlow = _selectedDate.flatMapLatest { date ->
        val dayStartMs = date.toEpochMs()
        val weekStart = dayStartMs - 6 * DAY_MS
        usageRepository.getEventsSince(weekStart)
    }

    private val allEventsFlow = usageRepository.getEventsSince(0L)

    private val screenTimeFlow = _selectedDate.flatMapLatest { date ->
        flow {
            while (true) {
                val dayStartMs = date.toEpochMs()
                val isToday = date == LocalDate.now()
                val dayEndMs = if (isToday) System.currentTimeMillis() else dayStartMs + DAY_MS
                val dayMs = screenTimeProvider.getPerAppScreenTime(dayStartMs, dayEndMs)
                    .getOrDefault(packageName, 0L)
                val weekly = screenTimeProvider.getPerAppDailyScreenTimesForWeek(packageName, dayStartMs)
                val hourly = screenTimeProvider.getPerAppHourlyScreenTime(packageName, dayStartMs, dayEndMs)
                emit(AppScreenTimeSnapshot(dayMs, weekly, hourly))
                delay(30_000L)
            }
        }
    }

    val uiState: StateFlow<AppDetailUiState> = combine(
        weekEventsFlow,
        allEventsFlow,
        screenTimeFlow,
        _selectedDate
    ) { weekEvents, allEvents, screenTime, date ->
        buildUiState(weekEvents, allEvents, screenTime, date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppDetailUiState())

    private suspend fun buildUiState(
        weekEvents: List<UsageEvent>,
        allEvents: List<UsageEvent>,
        screenTime: AppScreenTimeSnapshot,
        date: LocalDate
    ): AppDetailUiState {
        val isToday = date == LocalDate.now()
        val dayStartMs = date.toEpochMs()
        val dayEndMs = dayStartMs + DAY_MS

        val appWeekEvents = weekEvents.filter { it.packageName == packageName }
        val appAllEvents = allEvents.filter { it.packageName == packageName }
        val selectedDayEvents = appWeekEvents.filter { it.timestamp in dayStartMs until dayEndMs }

        val todayFormatted = if (screenTime.todayMs < 60_000L && screenTime.todayMs > 0L) {
            "< 1m"
        } else {
            timeTracker.formatDuration(screenTime.todayMs)
        }

        val modeBreakdown = appAllEvents
            .filter { it.wasBlocked && it.blockMode != null }
            .groupBy { it.blockMode!! }
            .mapValues { it.value.size }

        val dateLabel = if (isToday) "Today" else formatDateLabel(date)

        return AppDetailUiState(
            packageName = packageName,
            appName = appName(),
            todayFormatted = todayFormatted,
            weeklyData = statsCalculator.buildWeeklyDataFromTotals(screenTime.weeklyTotals, dayStartMs),
            hourlyMs = screenTime.hourlyMs,
            trendData = statsCalculator.buildAppTrendData(weekEvents, packageName, dayStartMs),
            blockedCountToday = selectedDayEvents.count { it.wasBlocked },
            walkedAwayCountToday = selectedDayEvents.count { it.userChangedMind },
            blockedCountTotal = appAllEvents.count { it.wasBlocked },
            walkedAwayCountTotal = appAllEvents.count { it.userChangedMind },
            blockModeBreakdown = modeBreakdown,
            isToday = isToday,
            dateLabel = dateLabel
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
