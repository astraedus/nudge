package com.astraedus.nudge.ui.screens.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.usage.WeeklyUsage
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.formatDayTotal
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.polled
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.toEpochMs
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.toLocalDate
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val dateLabel: String = "Today",
    val selectedDayIndex: Int = StatsDaySelection.WINDOW_DAYS - 1,
    val weekRangeLabel: String = "Last 7 days",
    val canGoForward: Boolean = false,
    val weekTotalFormatted: String = "0s"
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

    private val _selection = MutableStateFlow(StatsDaySelection.startingAt(LocalDate.now()))
    val selection: StateFlow<StatsDaySelection> = _selection

    // Resolved lazily off the main thread on first build; cached in the repo.
    private suspend fun appName(): String =
        installedAppsRepository.resolveAppName(packageName)

    fun goToPreviousDay() {
        _selection.value = _selection.value.previousDay()
    }

    fun goToNextDay() {
        _selection.value = _selection.value.nextDay(LocalDate.now())
    }

    fun selectDay(index: Int) {
        _selection.value = _selection.value.selectIndex(index, LocalDate.now())
    }

    fun jumpToToday() {
        _selection.value = _selection.value.jumpToToday(LocalDate.now())
    }

    private val weekEventsFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            val windowStart = weekEnd.minusDays((StatsDaySelection.WINDOW_DAYS - 1).toLong())
            usageRepository.getEventsSince(windowStart.toEpochMs())
        }

    private val allEventsFlow = usageRepository.getEventsSince(0L)

    /**
     * The window's screen time, per day and per app. This app's bars are one column read out of
     * it and the selected day's total is one cell — the same value, never a second computation,
     * so a bar here and the number under it describe the same day by construction.
     */
    private val weeklyUsageFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            polled(isLive = weekEnd == LocalDate.now()) {
                screenTimeProvider.getWeeklyUsage(weekEnd.toEpochMs())
            }
        }

    /** The selected day's hourly breakdown — a within-day question the weekly value cannot answer. */
    private val hourlyFlow = _selection
        .map { it.selected }
        .distinctUntilChanged()
        .flatMapLatest { date ->
            polled(isLive = date == LocalDate.now()) {
                val dayStartMs = date.toEpochMs()
                val dayEndMs = if (date == LocalDate.now()) {
                    System.currentTimeMillis()
                } else {
                    timeTracker.startOfDayDaysBefore(dayStartMs, -1)
                }
                screenTimeProvider.getPerAppHourlyScreenTime(packageName, dayStartMs, dayEndMs)
            }
        }

    /**
     * Five sources is `combine`'s typed limit, so the two screen-time reads are paired first.
     * They are separate flows because the weekly value only changes when the WINDOW moves while
     * the hourly breakdown changes on every bar tap.
     */
    private val screenTimeFlow = combine(weeklyUsageFlow, hourlyFlow) { weekly, hourly ->
        weekly to hourly
    }

    val uiState: StateFlow<AppDetailUiState> = combine(
        weekEventsFlow,
        allEventsFlow,
        screenTimeFlow,
        _selection
    ) { weekEvents, allEvents, (weeklyUsage, hourlyMs), selection ->
        buildUiState(weekEvents, allEvents, weeklyUsage, hourlyMs, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppDetailUiState())

    private suspend fun buildUiState(
        weekEvents: List<UsageEvent>,
        allEvents: List<UsageEvent>,
        weeklyUsage: WeeklyUsage,
        hourlyMs: List<Long>,
        selection: StatsDaySelection
    ): AppDetailUiState {
        val today = LocalDate.now()
        val isToday = selection.isSelectedToday(today)
        val dayStartMs = selection.selected.toEpochMs()
        val dayEndMs = timeTracker.startOfDayDaysBefore(dayStartMs, -1)
        // Everything describing the WINDOW is worded from the window actually loaded; everything
        // describing the DAY comes from the selection. The two are briefly out of step while a new
        // window loads, and printing the new dates over the old bars is the very
        // chart-disagrees-with-its-numbers defect this screen was fixed for.
        val weekEndStartMs = weeklyUsage.lastDayStartMs
        val loadedWeekEnd = weekEndStartMs.toLocalDate()
        val loadedWeekStart = weeklyUsage.firstDayStartMs.toLocalDate()
        val weeklyTotals = weeklyUsage.dailyTotalsFor(packageName)

        val appWeekEvents = weekEvents.filter { it.packageName == packageName }
        val appAllEvents = allEvents.filter { it.packageName == packageName }
        val selectedDayEvents = appWeekEvents.filter { it.timestamp in dayStartMs until dayEndMs }

        val modeBreakdown = appAllEvents
            .filter { it.isShownConfrontation && it.blockMode != null }
            .groupBy { it.blockMode!! }
            .mapValues { it.value.size }

        return AppDetailUiState(
            packageName = packageName,
            appName = appName(),
            // The selected bar's own value — read from the series that drew it, not recomputed.
            todayFormatted = formatDayTotal(
                weeklyUsage.perAppOn(dayStartMs)[packageName] ?: 0L,
                timeTracker
            ),
            weeklyData = statsCalculator.buildWeeklyDataFromTotals(weeklyTotals, weekEndStartMs),
            hourlyMs = hourlyMs,
            trendData = statsCalculator.buildAppTrendData(weekEvents, packageName, weekEndStartMs),
            blockedCountToday = selectedDayEvents.count { it.isShownConfrontation },
            walkedAwayCountToday = selectedDayEvents.count { it.userChangedMind },
            blockedCountTotal = appAllEvents.count { it.isShownConfrontation },
            walkedAwayCountTotal = appAllEvents.count { it.userChangedMind },
            blockModeBreakdown = modeBreakdown,
            isToday = isToday,
            dateLabel = StatsDateLabels.day(selection.selected, today),
            selectedDayIndex = selection.selectedIndex,
            weekRangeLabel = StatsDateLabels.range(loadedWeekStart, loadedWeekEnd, today),
            canGoForward = selection.canGoForward(today),
            weekTotalFormatted = timeTracker.formatDuration(weeklyTotals.sum())
        )
    }

}
