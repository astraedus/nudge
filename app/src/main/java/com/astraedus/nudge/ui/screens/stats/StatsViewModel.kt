package com.astraedus.nudge.ui.screens.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.usage.WeeklyUsage
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val formattedDuration: String,
    val fraction: Float
)

@Immutable
data class StatsUiState(
    val totalFormatted: String = "0s",
    val appStats: List<AppUsageStat> = emptyList(),
    val weeklyData: List<DayData> = emptyList(),
    val trendData: List<TrendDay> = emptyList(),
    val hourlyMs: List<Long> = List(24) { 0L },
    val streakDays: Int = 0,
    val hasUsagePermission: Boolean = true,
    val isToday: Boolean = true,
    val dateLabel: String = "Today",
    /** Which of the 7 bars is the selected day. Drives chart highlighting. */
    val selectedDayIndex: Int = StatsDaySelection.WINDOW_DAYS - 1,
    /** "Last 7 days", or explicit dates once the user scrolls back. */
    val weekRangeLabel: String = "Last 7 days",
    /** Whether the forward arrow does anything. */
    val canGoForward: Boolean = false,
    /** Screen time across the whole 7-bar window, already formatted. */
    val weekTotalFormatted: String = "0s",
    /** Nudge counts for the SELECTED day — the numbers a tapped bar has to move. */
    val selectedDayBlocked: Int = 0,
    val selectedDayWalkedAway: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val statsCalculator: StatsCalculator
) : ViewModel() {

    private val _selection = MutableStateFlow(StatsDaySelection.startingAt(LocalDate.now()))

    /** The single source of truth for "which day am I looking at". */
    val selection: StateFlow<StatsDaySelection> = _selection.asStateFlow()

    // Name caching lives in InstalledAppsRepository (per-package, off-main-thread).
    private suspend fun resolveAppName(packageName: String): String =
        installedAppsRepository.resolveAppName(packageName)

    fun goToPreviousDay() {
        _selection.value = _selection.value.previousDay()
    }

    fun goToNextDay() {
        _selection.value = _selection.value.nextDay(LocalDate.now())
    }

    /** Called when a bar is tapped. This is what the old per-chart `selectedIndex` never did. */
    fun selectDay(index: Int) {
        _selection.value = _selection.value.selectIndex(index, LocalDate.now())
    }

    fun jumpToToday() {
        _selection.value = _selection.value.jumpToToday(LocalDate.now())
    }

    /**
     * Room events covering the whole displayed window. Keyed on the WINDOW, not the selected
     * day, so tapping between bars re-slices the list already in memory instead of
     * re-subscribing to a new query.
     */
    private val weekEventsFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            val windowStart = weekEnd.minusDays((StatsDaySelection.WINDOW_DAYS - 1).toLong())
            usageRepository.getEventsSince(windowStart.toEpochMs())
        }

    /**
     * Screen time for the whole displayed window, per day AND per app — the bars *and* the
     * selected day's numbers, out of one `queryEvents` pass.
     *
     * The bars used to come from `getDailyScreenTimesForWeek` (pre-aggregated
     * `queryUsageStats(INTERVAL_DAILY)` buckets) while the drill-down below them computed its own
     * total from live events. A tall, dark Wednesday bar over a drill-down reading "0s" is what
     * that bought. One value now answers both questions, so they cannot disagree.
     *
     * Only re-read when the WINDOW moves; tapping between bars re-slices what is already in memory.
     */
    private val weeklyUsageFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            polled(isLive = weekEnd == LocalDate.now()) {
                screenTimeProvider.getWeeklyUsage(weekEnd.toEpochMs())
            }
        }

    /**
     * The hourly heatmap for the SELECTED day — the one day-scoped read that remains, because a
     * within-day breakdown is a different question from a per-day total and 7x24 buckets are not
     * worth carrying for the six days nobody is looking at.
     */
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
                screenTimeProvider.getHourlyScreenTime(dayStartMs, dayEndMs)
            }
        }

    val uiState: StateFlow<StatsUiState> = combine(
        weekEventsFlow,
        weeklyUsageFlow,
        hourlyFlow,
        _selection
    ) { weekEvents, weeklyUsage, hourlyMs, selection ->
        buildUiState(weekEvents, weeklyUsage, hourlyMs, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private suspend fun buildUiState(
        weekEvents: List<UsageEvent>,
        weeklyUsage: WeeklyUsage,
        hourlyMs: List<Long>,
        selection: StatsDaySelection
    ): StatsUiState {
        val today = LocalDate.now()
        val hasPermission = screenTimeProvider.hasPermission()
        val isToday = selection.isSelectedToday(today)
        val dayStartMs = selection.selected.toEpochMs()
        val dayEndMs = timeTracker.startOfDayDaysBefore(dayStartMs, -1)
        // ONE rule for the whole screen: everything that describes the WINDOW is worded from the
        // window actually loaded, everything that describes the DAY comes from the selection.
        // `selection` moves the instant an arrow is tapped while the new window is still in
        // flight, so wording the window from it would, for that frame, print new dates over old
        // bars — the same "the label and the numbers disagree" defect, one level up.
        val weekEndStartMs = weeklyUsage.lastDayStartMs
        val loadedWeekEnd = weekEndStartMs.toLocalDate()
        val loadedWeekStart = weeklyUsage.firstDayStartMs.toLocalDate()
        val dayPerApp = weeklyUsage.perAppOn(dayStartMs)
        val weeklyTotals = weeklyUsage.dailyTotals()

        val byPackage = dayPerApp.entries.sortedByDescending { it.value }
        val maxMs = byPackage.maxOfOrNull { it.value } ?: 1L

        val appStats = byPackage
            .filter { it.value > 0L }
            .map { (pkg, ms) ->
                AppUsageStat(
                    packageName = pkg,
                    appName = resolveAppName(pkg),
                    durationMs = ms,
                    formattedDuration = timeTracker.formatDuration(ms),
                    fraction = (ms.toFloat() / maxMs.toFloat()).coerceIn(0.05f, 1f)
                )
            }

        val selectedDayEvents = weekEvents.filter { it.timestamp in dayStartMs until dayEndMs }

        return StatsUiState(
            // The hero total is the sum of the very list under it, on the very bar above it.
            totalFormatted = formatDayTotal(dayPerApp.values.sum(), timeTracker),
            appStats = appStats,
            weeklyData = statsCalculator.buildWeeklyDataFromTotals(weeklyTotals, weekEndStartMs),
            trendData = statsCalculator.buildTrendData(weekEvents, weekEndStartMs),
            hourlyMs = hourlyMs,
            // Anchored on the window's last day, not the selected one: a streak is a
            // "how am I doing right now" number, and scrubbing back through the week to
            // inspect a Tuesday must not rewrite it.
            streakDays = statsCalculator.calculateStreak(weekEvents, weekEndStartMs),
            hasUsagePermission = hasPermission,
            isToday = isToday,
            dateLabel = StatsDateLabels.day(selection.selected, today),
            selectedDayIndex = selection.selectedIndex,
            weekRangeLabel = StatsDateLabels.range(loadedWeekStart, loadedWeekEnd, today),
            canGoForward = selection.canGoForward(today),
            weekTotalFormatted = timeTracker.formatDuration(weeklyTotals.sum()),
            // Confrontations shown, not rows written: the walk-away row below carries
            // `wasBlocked` too, and counting it here would show every walk-away twice.
            selectedDayBlocked = selectedDayEvents.count { it.isShownConfrontation },
            selectedDayWalkedAway = selectedDayEvents.count { it.userChangedMind }
        )
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L

        fun LocalDate.toEpochMs(): Long =
            atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /**
         * The calendar day a loaded window's boundary falls on. Used to word the window from the
         * DATA rather than from the selection, so the range in the header and the labels on the
         * bars beneath it always describe the same seven days.
         */
        fun Long.toLocalDate(): LocalDate =
            Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

        fun formatDateLabel(date: LocalDate): String = StatsDateLabels.full(date)

        /**
         * A day's screen-time total, worded the same on every day-scoped screen.
         *
         * The two screens disagreed: this one read `ms < 60_000` and so printed **"< 1m" for a
         * day with zero usage**, while App Detail guarded with `> 0` and printed "0s". A day
         * you genuinely did not touch the phone is not "under a minute", and two screens
         * describing the same zero differently is exactly the class of oddity being fixed here.
         */
        internal fun formatDayTotal(ms: Long, timeTracker: TimeTracker): String =
            if (ms in 1L until 60_000L) "< 1m" else timeTracker.formatDuration(ms)

        /**
         * `UsageStatsManager` has no Flow, so a live day has to be polled. A window that has
         * already ended cannot change, so it emits once and completes — a phone left on a past
         * day used to keep waking every 30 s to re-read seven identical binder queries.
         *
         * Runs on IO: `getWeeklyUsage` is a binder read plus a walk over a week of usage events,
         * and this feeds a `stateIn(viewModelScope)`, i.e. the main thread.
         */
        internal fun <T> polled(isLive: Boolean, produce: suspend () -> T): Flow<T> = flow {
            while (true) {
                emit(produce())
                if (!isLive) break
                delay(POLL_INTERVAL_MS)
            }
        }.flowOn(Dispatchers.IO)
    }
}
