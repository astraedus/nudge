package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.BlockHistoryFixture
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeUsageEventDao
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.TestClock
import com.astraedus.nudge.testing.aUsageEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The two Interventions contracts that cannot be expressed in [InsightsCalculatorTest]
 * because they live in the wiring rather than the arithmetic:
 *
 *  1. the all-time headline must subtract the walk-away rows the blocked count
 *     double-counts — reading the DAO count raw is the bug this screen exists to avoid;
 *  2. flipping the range must re-slice the events already in memory, never re-query Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterventionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = TestClock()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    /** The `usage_events` table the real repository reads; no app is installed, so names fall back to packages. */
    private val events = FakeUsageEventDao()

    @Test
    fun `the all-time headline counts confrontations, not the rows behind them`() =
        runTest(dispatcher) {
            val history = BlockHistoryFixture.day(dayStartMs = 1_700_000_000_000L, shown = 38, walkAways = 12)
            events.insertAll(history)
            assertEquals(50, BlockHistoryFixture.shownCount(history))

            val state = viewModel().uiState.first { !it.isLoading }

            assertEquals(50, state.allTimeTotal)
            // What a raw `wasBlocked` count of the same history would have shown.
            assertEquals(62, BlockHistoryFixture.blockedRowCount(history))
        }

    /**
     * An empty history renders 0, not an empty space or a stale number. (The old arithmetic
     * version of this correction needed a guard here against a negative headline; counting the
     * rows that ARE confrontations cannot go below zero, which is half the reason it replaced
     * the subtraction.)
     */
    @Test
    fun `a device with no history reads zero`() = runTest(dispatcher) {
        val state = viewModel().uiState.first { !it.isLoading }

        assertEquals(0, state.allTimeTotal)
    }

    @Test
    fun `flipping the range re-slices the loaded events instead of re-querying Room`() =
        runTest(dispatcher) {
            val now = clock.now
            val dayMs = 24L * 60L * 60L * 1000L
            events.insertAll(
                listOf(
                    blockedEvent(now - 2 * dayMs),   // inside 7d and 30d
                    blockedEvent(now - 20 * dayMs)   // inside 30d only
                )
            )

            val viewModel = viewModel()

            val thirtyDays = viewModel.uiState.first { !it.isLoading }
            assertEquals(InsightsRange.THIRTY_DAYS, thirtyDays.range)
            assertEquals(2, thirtyDays.insights.rangeTotal)

            viewModel.selectRange(InsightsRange.SEVEN_DAYS)
            val sevenDays = viewModel.uiState.first { it.range == InsightsRange.SEVEN_DAYS }
            assertEquals(1, sevenDays.insights.rangeTotal)

            viewModel.selectRange(InsightsRange.THIRTY_DAYS)
            viewModel.uiState.first { it.range == InsightsRange.THIRTY_DAYS }

            // Two range flips, still exactly one query: the widest window is fetched once
            // and every narrower view is a slice of it.
            assertEquals(1, events.eventsSinceQueries)
        }

    private fun viewModel() = InterventionsViewModel(
        usageRepository = FakeUsageRepository(events = events),
        installedAppsRepository = FakeInstalledAppsRepository(),
        calculator = InsightsCalculator(),
        clock = clock.asSource()
    )

    private fun blockedEvent(timestamp: Long) =
        aUsageEvent("app.a", timestamp = timestamp, wasBlocked = true, blockMode = "DELAY")
}
