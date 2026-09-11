package com.astraedus.nudge.ui.screens.home

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.ui.lock.StrictModeGate
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.formatDayTotal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One row of the dashboard's "Blocked most this week" card. */
@Immutable
data class TopBlockedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val count: Int
)

@Immutable
data class HomeUiState(
    val isGlobalEnabled: Boolean = true,
    val todayTotalUsageFormatted: String = "0s",
    val activeRuleCount: Int = 0,
    val blockedCountToday: Int = 0,
    val changedMindCountToday: Int = 0,
    val allTimeBlockedCount: Int = 0,
    val allTimeChangedMindCount: Int = 0,
    val hasUsagePermission: Boolean = true,
    /** The two mini charts on the dashboard. */
    val charts: HomeCharts = HomeCharts(),
    val weekTotalFormatted: String = "0s",
    /** Apps that pulled hardest over the same 7 days the card above them charts. */
    val topBlocked: List<TopBlockedApp> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val nudgePreferences: NudgePreferences,
    private val usageRepository: UsageRepository,
    private val blockRuleRepository: BlockRuleRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val homeChartsBuilder: HomeChartsBuilder,
    private val installedAppsRepository: InstalledAppsRepository,
    private val insightsCalculator: InsightsCalculator
) : ViewModel() {

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /**
     * Start-of-today, re-derived on every poll tick rather than once at construction.
     *
     * It used to be a `val` computed in the constructor, so a phone left on the home screen
     * over midnight kept counting yesterday's events under a heading that said "Today" — the
     * same "the label and the numbers disagree" defect as the stats-chart selection bug.
     * `distinctUntilChanged` means the downstream Room queries are still re-subscribed exactly
     * once a day, not every 30 s.
     *
     * This is the ONE clock for the whole screen: counts, the week window, the screen-time
     * poll and the chart builder all consume this value rather than calling
     * `startOfToday()` themselves, so no two sections of the dashboard can disagree about
     * which day is "today" around a midnight rollover. `shareIn(replay = 1)` keeps it a
     * single timer no matter how many downstream chains subscribe.
     */
    private val dayStartFlow = flow {
        while (true) {
            emit(timeTracker.startOfToday())
            delay(POLL_INTERVAL_MS)
        }
    }.distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private val countsFlow = dayStartFlow.flatMapLatest { dayStart ->
        // Calendar arithmetic: on a DST day `+ DAY_MS` is an hour off true local midnight, so
        // today's counts would take an hour of yesterday's (or drop an hour of their own).
        val dayEnd = timeTracker.startOfDayDaysBefore(dayStart, -1)
        combine(
            usageRepository.getBlockedCountForDay(dayStart, dayEnd),
            usageRepository.getChangedMindCountForDay(dayStart, dayEnd),
            usageRepository.getAllTimeBlockedCount(),
            usageRepository.getAllTimeChangedMindCount()
        ) { blockedToday, changedMindToday, allTimeBlocked, allTimeChangedMind ->
            CountsSnapshot(blockedToday, changedMindToday, allTimeBlocked, allTimeChangedMind)
        }
    }

    /**
     * The week of `usage_events` behind the dashboard's trend chart. Same Room table the tiles
     * already observe, one extra windowed query — no new storage and no new writes.
     */
    private val weekEventsFlow = dayStartFlow.flatMapLatest { dayStart ->
        // Calendar arithmetic, not `6 * DAY_MS`: a DST transition inside the trailing week
        // would otherwise shift the window start an hour off true local midnight.
        val weekStart = timeTracker.startOfDayDaysBefore(dayStart, WEEK_DAYS - 1)
        // The boundary travels WITH the rows it selected. This chain and [screenTimeFlow] are
        // two independent subscriptions off one [dayStartFlow], so at a midnight rollover they
        // restart independently and, for a frame, one can be a day ahead of the other. Anything
        // that re-derives "a week ago" from the OTHER chain's day start is therefore describing
        // a window these rows were not queried with. Carrying it here makes the pairing
        // structural instead of a race that merely settles within one 30 s tick.
        usageRepository.getEventsSince(weekStart).map { events ->
            WeekEventsSnapshot(weekStartMs = weekStart, events = events)
        }
    }

    /**
     * `UsageStatsManager` has no Flow, so screen time is polled. On IO: it is a binder read plus
     * a walk over a week of usage events, and this feeds the main thread via `stateIn`.
     * Derived from [dayStartFlow] so every reading is bucketed against the same "today"
     * the rest of the dashboard uses; the snapshot carries that day start along for the
     * chart builder.
     *
     * The "Screen Time" tile and the last bar of the week chart are the SAME number here — the
     * tile reads today's cell out of the weekly value rather than issuing its own day query.
     * Two reads would be two chances to disagree on one screen, which is the bug this replaced.
     */
    private val screenTimeFlow = dayStartFlow.flatMapLatest { dayStart ->
        flow {
            while (true) {
                val weeklyUsage = screenTimeProvider.getWeeklyUsage(dayStart)
                emit(
                    ScreenTimeSnapshot(
                        dayStartMs = dayStart,
                        hasPermission = screenTimeProvider.hasPermission(),
                        todayMs = weeklyUsage.totalOn(dayStart),
                        weeklyTotals = weeklyUsage.dailyTotals()
                    )
                )
                delay(POLL_INTERVAL_MS)
            }
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<HomeUiState> = combine(
        nudgePreferences.isGlobalEnabled,
        blockRuleRepository.getEnabledRules().map { rules ->
            rules.mapNotNull { it.packageName }.distinct().size
        },
        countsFlow,
        screenTimeFlow,
        weekEventsFlow
    ) { enabled, activeRuleCount, counts, screenTime, weekEvents ->
        val charts = homeChartsBuilder.build(
            weeklyTotals = screenTime.weeklyTotals,
            weekEvents = weekEvents.events,
            todayStartMs = screenTime.dayStartMs
        )
        HomeUiState(
            isGlobalEnabled = enabled,
            todayTotalUsageFormatted = formatDayTotal(screenTime.todayMs, timeTracker),
            activeRuleCount = activeRuleCount,
            blockedCountToday = counts.blockedToday,
            changedMindCountToday = counts.changedMindToday,
            allTimeBlockedCount = counts.allTimeBlocked,
            allTimeChangedMindCount = counts.allTimeChangedMind,
            hasUsagePermission = screenTime.hasPermission,
            charts = charts,
            weekTotalFormatted = timeTracker.formatDuration(charts.weekTotalMs),
            topBlocked = topBlocked(weekEvents)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /**
     * The dashboard's "Blocked most this week" rows.
     *
     * Reads the SAME rows [weekEventsFlow] already holds, windowed by the boundary those rows
     * were actually SELECTED with rather than one re-derived here, so the card cannot describe
     * a week its own data does not cover. It goes through [InsightsCalculator.topBlockedApps],
     * the one per-app aggregation in the app, so this card and the Interventions leaderboard
     * cannot disagree about which app pulled hardest either. No new DAO query, no new Room
     * subscription.
     *
     * Label and icon resolution is on IO: both resolvers are `suspend` PackageManager reads,
     * memory-cached (negative misses included), so this is a map lookup in steady state and a
     * handful of binder calls exactly once, but that once must not land on the main thread,
     * which is where the `stateIn(viewModelScope)` transform runs.
     */
    private suspend fun topBlocked(weekEvents: WeekEventsSnapshot): List<TopBlockedApp> {
        val stats = insightsCalculator.topBlockedApps(
            events = weekEvents.events,
            sinceMs = weekEvents.weekStartMs,
            nowMs = System.currentTimeMillis(),
            limit = TOP_BLOCKED_LIMIT
        )
        if (stats.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            stats.map { stat ->
                TopBlockedApp(
                    packageName = stat.packageName,
                    label = insightsCalculator.appDisplayLabel(
                        stat.packageName,
                        installedAppsRepository.resolveAppName(stat.packageName)
                    ),
                    icon = installedAppsRepository.resolveIcon(stat.packageName),
                    count = stat.total
                )
            }
        }
    }

    /** A week of block decisions, carrying the window boundary they were queried with. */
    private data class WeekEventsSnapshot(
        val weekStartMs: Long,
        val events: List<UsageEvent>
    )

    private data class CountsSnapshot(
        val blockedToday: Int,
        val changedMindToday: Int,
        val allTimeBlocked: Int,
        val allTimeChangedMind: Int
    )

    private data class ScreenTimeSnapshot(
        val dayStartMs: Long,
        val hasPermission: Boolean,
        val todayMs: Long,
        val weeklyTotals: List<Long>
    )

    fun toggleGlobalEnabled() {
        viewModelScope.launch {
            val current = uiState.value.isGlobalEnabled
            // Turning protection ON is free; only ON -> OFF (weakening) is gated by Strict Mode.
            if (current) {
                strictModeGate.run(prompt = "Turn off all blocking") {
                    nudgePreferences.setGlobalEnabled(false)
                }
            } else {
                nudgePreferences.setGlobalEnabled(true)
            }
        }
    }

    /** Called from the challenge dialog; runs the pending weakening action on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }

    companion object {
        /** Days of events behind the trend chart. Matches the screen-time window's own width. */
        private const val WEEK_DAYS = ScreenTimeProvider.WEEK_DAYS
        private const val POLL_INTERVAL_MS = 30_000L

        /** Rows on the "Blocked most this week" card. Five fits without the card scrolling. */
        private const val TOP_BLOCKED_LIMIT = 5
    }
}
