package com.astraedus.nudge.ui.screens.home

import android.content.Context
import com.astraedus.nudge.data.db.BlockHistoryFixture
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.usage.WeeklyUsage
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The dashboard's Blocked tiles count confrontations, and a walk-away is not one of them twice.
 *
 * Reported on the 1.17.2 dev build: "the Blocked tile goes up by 2 every time I walk away."
 * `HomeViewModel` fed both tiles the raw `wasBlocked` row count, which includes the second row a
 * walk-away writes, so the bench device read **210 blocked / 29 walked away** for 181 real
 * confrontations - while the Interventions screen, two taps away, read 181.
 *
 * The fixture is a real day of history (see [BlockHistoryFixture]); the counts the repository
 * answers with are the ones each DAO query would return over it, so what this test pins is the
 * thing that was actually wrong: which question the screen asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeBlockedTileTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val timeTracker = TimeTracker()

    private lateinit var usageRepository: UsageRepository
    private lateinit var screenTimeProvider: ScreenTimeProvider
    private lateinit var blockRuleRepository: BlockRuleRepository
    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var preferences: NudgePreferences
    private lateinit var context: Context

    private val dayStart: Long by lazy { timeTracker.startOfToday() }
    private val dayEnd: Long by lazy { timeTracker.startOfDayDaysBefore(dayStart, -1) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        usageRepository = mockk(relaxed = true)
        screenTimeProvider = mockk(relaxed = true)
        blockRuleRepository = mockk(relaxed = true)
        installedAppsRepository = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { preferences.isGlobalEnabled } returns MutableStateFlow(true)
        every { blockRuleRepository.getEnabledRules() } returns MutableStateFlow(emptyList())
        every { screenTimeProvider.hasPermission() } returns true
        every { screenTimeProvider.getWeeklyUsage(any()) } answers {
            WeeklyUsage(listOf(firstArg()), listOf(emptyMap()))
        }
        coEvery { installedAppsRepository.resolveAppName(any()) } answers { firstArg() }
        coEvery { installedAppsRepository.resolveIcon(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Yesterday's rows are in the all-time numbers and not in today's, so a passing today-count
     * cannot be an all-time count wearing the wrong label.
     */
    private fun history(
        todayShown: Int,
        todayWalkAways: Int,
        earlierShown: Int = 0,
        earlierWalkAways: Int = 0
    ): List<UsageEvent> =
        BlockHistoryFixture.day(
            dayStartMs = timeTracker.startOfDayDaysBefore(dayStart, 3),
            shown = earlierShown,
            walkAways = earlierWalkAways
        ) + BlockHistoryFixture.day(
            dayStartMs = dayStart,
            shown = todayShown,
            walkAways = todayWalkAways,
            allowed = 4
        )

    private fun serve(history: List<UsageEvent>) {
        every { usageRepository.getShownCountForDay(dayStart, dayEnd) } returns
            MutableStateFlow(BlockHistoryFixture.shownCount(history, dayStart, dayEnd))
        every { usageRepository.getChangedMindCountForDay(dayStart, dayEnd) } returns
            MutableStateFlow(BlockHistoryFixture.changedMindCount(history, dayStart, dayEnd))
        every { usageRepository.getAllTimeShownCount() } returns
            MutableStateFlow(BlockHistoryFixture.shownCount(history))
        every { usageRepository.getAllTimeChangedMindCount() } returns
            MutableStateFlow(BlockHistoryFixture.changedMindCount(history))
        every { usageRepository.getEventsSince(any()) } returns MutableStateFlow(history)
    }

    private fun viewModel() = HomeViewModel(
        context = context,
        nudgePreferences = preferences,
        usageRepository = usageRepository,
        blockRuleRepository = blockRuleRepository,
        screenTimeProvider = screenTimeProvider,
        timeTracker = timeTracker,
        homeChartsBuilder = HomeChartsBuilder(StatsCalculator(timeTracker)),
        installedAppsRepository = installedAppsRepository,
        insightsCalculator = InsightsCalculator()
    )

    @Test
    fun `walking away counts as one walk-away, not as two more blocks`() = runTest(dispatcher) {
        // 10 confrontations sat through, 4 turned around from = 14 faced today.
        val history = history(todayShown = 10, todayWalkAways = 4)
        serve(history)

        val state = viewModel().uiState.first { it.blockedCountToday > 0 }

        assertEquals("Blocked (today)", 14, state.blockedCountToday)
        assertEquals("Walked away (today)", 4, state.changedMindCountToday)
        // What the tile used to show for this same day.
        assertEquals(18, BlockHistoryFixture.blockedRowCount(history, dayStart, dayEnd))
    }

    @Test
    fun `the all-time tile counts every confrontation once, across days`() = runTest(dispatcher) {
        val history = history(
            todayShown = 10,
            todayWalkAways = 4,
            earlierShown = 152,
            earlierWalkAways = 25
        )
        serve(history)

        val state = viewModel().uiState.first { it.allTimeBlockedCount > 0 }

        // 152 + 25 earlier, 10 + 4 today.
        assertEquals("Blocked (all time)", 191, state.allTimeBlockedCount)
        assertEquals("Walked away (all time)", 29, state.allTimeChangedMindCount)
        assertEquals(220, BlockHistoryFixture.blockedRowCount(history))
    }

    @Test
    fun `a day with nothing but walk-aways still reads as the confrontations they were`() =
        runTest(dispatcher) {
            serve(history(todayShown = 0, todayWalkAways = 3))

            val state = viewModel().uiState.first { it.blockedCountToday > 0 }

            assertEquals(3, state.blockedCountToday)
            assertEquals(3, state.changedMindCountToday)
        }

    /**
     * The mini chart under the tiles is the same quantity in bar form. It read from the same raw
     * column, so it drew each walk-away in BOTH series on one bar.
     */
    @Test
    fun `the weekly trend bars agree with the tile above them`() = runTest(dispatcher) {
        serve(history(todayShown = 10, todayWalkAways = 4))

        val state = viewModel().uiState.first { it.blockedCountToday > 0 }
        val today = state.charts.weeklyTrend.last()

        assertEquals(state.blockedCountToday, today.blockedCount)
        assertEquals(state.changedMindCountToday, today.walkedAwayCount)
    }
}
