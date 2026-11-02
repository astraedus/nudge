package com.astraedus.nudge.ui.screens.home

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.data.repository.toRuleData
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.nuke.NukeGate
import com.astraedus.nudge.ui.nuke.NukeUnlockState
import com.astraedus.nudge.ui.lock.StrictModeGate
import com.astraedus.nudge.ui.screens.stats.AppInterventionStat
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.formatDayTotal
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row of the dashboard's "Blocked most" card.
 *
 * The icon arrives already rasterised. It used to be a `Drawable` that the row turned into an
 * `ImageBitmap` inside a `remember` during composition — a PackageManager-owned, mutable,
 * stateful object being decoded on the frame thread, on a card that sits at the top of the
 * first screen the app opens. Rasterising it in the flow's IO step instead leaves the
 * composable with nothing to do but draw, and makes the `@Immutable` promise on this class
 * true rather than aspirational (a `Drawable` is anything but).
 */
@Immutable
data class TopBlockedApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val count: Int
)

@Immutable
data class HomeUiState(
    val isGlobalEnabled: Boolean = true,
    val todayTotalUsageFormatted: String = "0s",
    val activeAppCount: Int = 0,
    /**
     * The "Blocked" tiles: confrontations the user was SHOWN, each counted once. A walk-away is a
     * walk-away and lands in [changedMindCountToday] / [allTimeChangedMindCount]; it is not also a
     * second block. See `UsageEvent.isShownConfrontation`.
     */
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

/** What the dashboard's Nuke card shows. */
@Immutable
data class NukeSummary(
    val active: Boolean = false,
    val hasKey: Boolean = false,
    /** Apps Nuke actually blocks: [NukePolicy.nukedCount], the same number the Nuke screen shows. */
    val appCount: Int = 0
) {
    /** Never set up at all: no key and no list. The card then invites setup instead of a state. */
    val needsSetup: Boolean get() = !hasKey && appCount == 0

    companion object {
        fun from(state: NukeState, essentials: Set<String>) = NukeSummary(
            active = state.active,
            hasKey = state.hasKey,
            appCount = NukePolicy.nukedCount(state, essentials)
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nudgePreferences: NudgePreferences,
    private val usageRepository: UsageRepository,
    private val blockRuleRepository: BlockRuleRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val homeChartsBuilder: HomeChartsBuilder,
    private val installedAppsRepository: InstalledAppsRepository,
    private val insightsCalculator: InsightsCalculator,
    private val essentialApps: EssentialApps,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /**
     * Nuke's gate on the master toggle. While Nuke is on, the master switch is the obvious one-tap
     * way around it (every enforcement path sits behind `globalEnabled`), so turning it OFF costs
     * exactly what ending Nuke costs: the key, or the 64-character code.
     */
    val nukeGate = NukeGate(currentState = { nudgePreferences.nukeState.first() })
    val nukeUnlock: StateFlow<NukeUnlockState?> = nukeGate.unlock

    /** The one essentials list, resolved off the main thread (it reads PackageManager and roles). */
    private fun essentialsFlow() = flow { emit(essentialApps.packages()) }.flowOn(ioDispatcher)

    /**
     * The essentials and the one browser list together (one resolution, cached together): what a
     * group's membership resolves against besides the installed apps.
     */
    private fun essentialsAndBrowsersFlow() =
        flow { emit(essentialApps.packages() to essentialApps.browsers()) }.flowOn(ioDispatcher)

    /**
     * The installed apps with a launcher entry, each with its category: what an All apps rule and a
     * category group can cover (spec 4.1, 5.2).
     */
    private fun launchableFlow() = installedAppsRepository.changes.map {
        installedAppsRepository.getInstalledApps().associate { it.packageName to it.category }
    }.flowOn(ioDispatcher)

    /**
     * The dashboard's Nuke card. Its OWN flow rather than a field on [uiState]: the card changes
     * when Nuke does, and folding it into the clock-driven combine would re-render the whole
     * dashboard state on every Nuke write and vice versa.
     *
     * Counted against the same [EssentialApps] set the Nuke screen uses, so an essential app still
     * stored on the list (Messages, say, listed before it became essential) is left out of BOTH
     * counts, never just one.
     */
    val nukeSummary: StateFlow<NukeSummary> = combine(
        nudgePreferences.nukeState,
        essentialsFlow()
    ) { state, essentials -> NukeSummary.from(state, essentials) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NukeSummary())

    /**
     * The lock card (spec 3.8): the All apps rule blocking right now, or null. Its OWN flow, like
     * [nukeSummary], so it never rides the dashboard's clock-driven combine. The rules are read in
     * the flow body, so nothing is read until the card is collected.
     *
     * A scheduled rule starts and ends on its own, so while one exists the card is re-derived at the
     * top of every minute (the schedule's resolution) and disappears at the window end. With no
     * enabled scheduled All apps rule there is no ticker at all: the card only changes when a rule
     * does.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lockCard: StateFlow<LockCard?> = flow {
        val labels = installedAppsRepository.getInstalledApps().associate { it.packageName to it.appName }
        emitAll(
            combine(blockRuleRepository.getAllRules(), blockRuleRepository.getAllGroups()) { rules, groups ->
                val names = groups.associate { it.id to it.name }
                rules.map { it.toRuleData(groupNames = names) } to labels
            }
        )
    }.flatMapLatest { (rules, labels) ->
        flow {
            if (!lockCardNeedsClock(rules)) {
                emit(lockCardFor(rules, Calendar.getInstance(), labels))
                return@flow
            }
            while (true) {
                val now = Calendar.getInstance()
                emit(lockCardFor(rules, now, labels))
                delay(millisToNextMinute(now.timeInMillis))
            }
        }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
            // SHOWN confrontations, not raw `wasBlocked` rows. A walk-away writes a second
            // `wasBlocked` row for the same confrontation, so the raw count made this tile rise
            // by two every time the user turned around — 210 on a device that had faced 181.
            usageRepository.getShownCountForDay(dayStart, dayEnd),
            usageRepository.getChangedMindCountForDay(dayStart, dayEnd),
            usageRepository.getAllTimeShownCount(),
            usageRepository.getAllTimeChangedMindCount()
        ) { shownToday, changedMindToday, allTimeShown, allTimeChangedMind ->
            CountsSnapshot(shownToday, changedMindToday, allTimeShown, allTimeChangedMind)
        }
    }

    /**
     * The week of `usage_events` behind the dashboard's trend chart. Same Room table the tiles
     * already observe, one extra windowed query — no new storage and no new writes.
     *
     * `shareIn` because this now has TWO consumers, [uiState] for the chart and
     * [topBlockedFlow] for the card underneath it. Collecting it twice would open a second
     * Room subscription onto the identical query, which is both wasted work and a second set
     * of rows that can arrive on a different tick from the first.
     */
    private val weekEventsFlow = dayStartFlow.flatMapLatest { dayStart ->
        // `startOfTrailingWeek`, not calendar arithmetic spelled out here: "a week ago" has
        // exactly one definition in this app (`TimeTracker`), and a DST transition inside the
        // window is why it cannot be `6 * DAY_MS`.
        val weekStart = timeTracker.startOfTrailingWeek(dayStart)
        // The boundary travels WITH the rows it selected. This chain and [screenTimeFlow] are
        // two independent subscriptions off one [dayStartFlow], so at a midnight rollover they
        // restart independently and, for a frame, one can be a day ahead of the other. Anything
        // that re-derives "a week ago" from the OTHER chain's day start is therefore describing
        // a window these rows were not queried with. Carrying it here makes the pairing
        // structural instead of a race that merely settles within one 30 s tick.
        usageRepository.getEventsSince(weekStart).map { events ->
            WeekEventsSnapshot(weekStartMs = weekStart, events = events)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    /**
     * The dashboard's "Blocked most" rows, as their OWN chain rather than a step inside
     * [uiState]'s transform.
     *
     * It used to be computed in that transform, which meant the 30-second screen-time poll
     * re-ranked a week of events and hopped to IO to re-read every label and icon out of
     * PackageManager on every tick — on a screen where nothing about those rows had changed,
     * because a block either happened or it did not and neither is a function of the clock.
     * Hanging it off [weekEventsFlow] means it recomputes when the EVENTS change, which is
     * what it is a function of.
     *
     * The ranking goes through [InsightsCalculator.topBlockedApps], the one per-app
     * aggregation in the app, so this card and the Interventions leaderboard cannot disagree
     * about which app pulled hardest. It is windowed by the boundary those rows were actually
     * SELECTED with rather than one re-derived here, so the card cannot describe a week its
     * own data does not cover. No new DAO query, no new Room subscription.
     *
     * `distinctUntilChanged` sits on the RANKED list, not on the raw rows: a week's events
     * change shape constantly (every walk-away, every allowed open) while the top five and
     * their counts do not, and [AppInterventionStat] is a data class, so equality is free and
     * exact. Only a ranking that actually moved pays for the label and icon resolution below.
     *
     * `flowOn(ioDispatcher)` covers both steps. The resolvers are `suspend` PackageManager
     * reads, memory-cached (negative misses included), so they are map lookups in the steady
     * state and a handful of binder calls exactly once — but that once must not land on the
     * main thread, which is where `stateIn(viewModelScope)` would otherwise run them.
     */
    private val topBlockedFlow: Flow<List<TopBlockedApp>> = weekEventsFlow
        .map { weekEvents ->
            insightsCalculator.topBlockedApps(
                events = weekEvents.events,
                sinceMs = weekEvents.weekStartMs,
                nowMs = System.currentTimeMillis(),
                limit = TOP_BLOCKED_LIMIT
            )
        }
        .distinctUntilChanged()
        .map { stats -> resolveRows(stats) }
        .flowOn(ioDispatcher)

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
    }.flowOn(ioDispatcher)

    // Two `combine`s rather than one: `combine` is only typed up to five flows, and the split
    // falls on the right seam anyway — everything above ticks with the clock, the top-blocked
    // list ticks with the events.
    val uiState: StateFlow<HomeUiState> = combine(
        nudgePreferences.isGlobalEnabled,
        // Apps covered by a rule that is on (direct or through a group), as the engine sees them.
        combine(
            blockRuleRepository.getEnabledRules(),
            blockRuleRepository.getAllGroups(),
            blockRuleRepository.getAllGroupMembers(),
            launchableFlow(),
            essentialsAndBrowsersFlow()
        ) { rules, groups, members, launchable, (essentials, browsers) ->
            activeAppsCount(rules, groups, members, launchable, essentials, browsers)
        },
        countsFlow,
        screenTimeFlow,
        weekEventsFlow
    ) { enabled, activeAppCount, counts, screenTime, weekEvents ->
        val charts = homeChartsBuilder.build(
            weeklyTotals = screenTime.weeklyTotals,
            weekEvents = weekEvents.events,
            todayStartMs = screenTime.dayStartMs
        )
        HomeUiState(
            isGlobalEnabled = enabled,
            todayTotalUsageFormatted = formatDayTotal(screenTime.todayMs, timeTracker),
            activeAppCount = activeAppCount,
            blockedCountToday = counts.shownToday,
            changedMindCountToday = counts.changedMindToday,
            allTimeBlockedCount = counts.allTimeShown,
            allTimeChangedMindCount = counts.allTimeChangedMind,
            hasUsagePermission = screenTime.hasPermission,
            charts = charts,
            weekTotalFormatted = timeTracker.formatDuration(charts.weekTotalMs)
        )
    }.combine(topBlockedFlow) { state, topBlocked ->
        state.copy(topBlocked = topBlocked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /**
     * Turns a ranking into rows: display label, and the icon already rasterised at the size the
     * card draws it.
     *
     * The pixel size is the DEVICE's size for the card's own [TOP_BLOCKED_ICON_SIZE] box, read
     * from the same `dp` constant the composable lays out with, so the bitmap cannot end up a
     * different size from the box it is drawn into. A ViewModel has no `LocalDensity`, hence
     * the display metrics; they are read per resolution rather than cached at construction
     * because density can change under us (a display switch on a foldable) and a multiply
     * costs nothing.
     */
    private suspend fun resolveRows(stats: List<AppInterventionStat>): List<TopBlockedApp> {
        val density = context.resources.displayMetrics.density
        val iconPx = (TOP_BLOCKED_ICON_SIZE.value * density).roundToInt().coerceAtLeast(1)
        return stats.map { stat ->
            TopBlockedApp(
                packageName = stat.packageName,
                label = insightsCalculator.appDisplayLabel(
                    stat.packageName,
                    installedAppsRepository.resolveAppName(stat.packageName)
                ),
                icon = installedAppsRepository.resolveIcon(stat.packageName)?.rasterise(iconPx),
                count = stat.total
            )
        }
    }

    /**
     * Null rather than a thrown exception for a drawable that will not rasterise.
     *
     * The row already has a designed no-icon state (a Material block glyph), and this call now
     * runs inside a flow feeding [uiState] rather than inside composition: letting a single
     * bad icon throw here would take the WHOLE dashboard's state down, not just its own row.
     * Nothing suspends inside the `try`, so this cannot swallow a cancellation.
     */
    private fun Drawable.rasterise(sizePx: Int): ImageBitmap? = try {
        toBitmap(width = sizePx, height = sizePx).asImageBitmap()
    } catch (_: Exception) {
        null
    }

    /** A week of block decisions, carrying the window boundary they were queried with. */
    private data class WeekEventsSnapshot(
        val weekStartMs: Long,
        val events: List<UsageEvent>
    )

    /** Confrontations shown and walked away from; `shown` already excludes the walk-away rows. */
    private data class CountsSnapshot(
        val shownToday: Int,
        val changedMindToday: Int,
        val allTimeShown: Int,
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
                // Nuke first, then Strict Mode: both locks guard this switch, and each is asked
                // only once the one before it has been satisfied.
                val nuke = nudgePreferences.nukeState.first()
                nukeGate.run(
                    prompt = "Turn off all blocking",
                    weaken = NukePolicy.masterToggleOffRequiresUnlock(nuke)
                ) {
                    strictModeGate.run(prompt = "Turn off all blocking") {
                        nudgePreferences.setGlobalEnabled(false)
                    }
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

    // --- Nuke unlock dialog (see [NukeUnlockHost]) --------------------------------------------

    fun onNukeScanned(payload: String?) {
        viewModelScope.launch { nukeGate.onScanned(payload) }
    }

    fun useNukeEmergencyCode() = nukeGate.useEmergencyCode()

    fun verifyNukeEmergency(input: String) {
        viewModelScope.launch { nukeGate.verifyEmergency(input) }
    }

    fun backToNukeChoice() = nukeGate.backToChoice()

    fun cancelNukeUnlock() = nukeGate.cancel()

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L

        /** Rows on the "Blocked most" card. Five fits without the card scrolling. */
        private const val TOP_BLOCKED_LIMIT = 5
    }
}
