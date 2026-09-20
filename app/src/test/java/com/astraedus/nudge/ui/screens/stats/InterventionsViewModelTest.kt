package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.BlockHistoryFixture
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
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

    private lateinit var usageRepository: UsageRepository
    private lateinit var installedAppsRepository: InstalledAppsRepository

    private val events = MutableStateFlow<List<UsageEvent>>(emptyList())
    private val allTimeShown = MutableStateFlow(0)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        usageRepository = mockk(relaxed = true)
        installedAppsRepository = mockk(relaxed = true)

        every { usageRepository.getEventsSince(any()) } returns events
        every { usageRepository.getAllTimeShownCount() } returns allTimeShown
        coEvery { installedAppsRepository.resolveAppName(any()) } answers { firstArg() }
        coEvery { installedAppsRepository.resolveIcon(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the all-time headline counts confrontations, not the rows behind them`() =
        runTest(dispatcher) {
            val history = BlockHistoryFixture.day(dayStartMs = 1_700_000_000_000L, shown = 38, walkAways = 12)
            allTimeShown.value = BlockHistoryFixture.shownCount(history)

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
        allTimeShown.value = 0

        val state = viewModel().uiState.first { !it.isLoading }

        assertEquals(0, state.allTimeTotal)
    }

    @Test
    fun `flipping the range re-slices the loaded events instead of re-querying Room`() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60L * 60L * 1000L
            events.value = listOf(
                blockedEvent(now - 2 * dayMs),   // inside 7d and 30d
                blockedEvent(now - 20 * dayMs)   // inside 30d only
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
            verify(exactly = 1) { usageRepository.getEventsSince(any()) }
        }

    private fun viewModel() = InterventionsViewModel(
        usageRepository = usageRepository,
        installedAppsRepository = installedAppsRepository,
        calculator = InsightsCalculator()
    )

    private fun blockedEvent(timestamp: Long) = UsageEvent(
        packageName = "app.a",
        timestamp = timestamp,
        wasBlocked = true,
        blockMode = "DELAY",
        userChangedMind = false
    )
}
