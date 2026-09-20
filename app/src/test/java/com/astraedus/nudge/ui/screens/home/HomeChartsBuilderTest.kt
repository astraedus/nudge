package com.astraedus.nudge.ui.screens.home

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The home dashboard's two charts. They are derived from data the app already loads, so the
 * risk here is not the query — it is bucketing a day differently from the stats screen and
 * shipping two graphs of the same week that disagree.
 */
class HomeChartsBuilderTest {

    private lateinit var timeTracker: TimeTracker
    private lateinit var calculator: StatsCalculator
    private lateinit var builder: HomeChartsBuilder

    private val dayMs = 24L * 60L * 60L * 1000L

    @Before
    fun setup() {
        timeTracker = TimeTracker()
        calculator = StatsCalculator(timeTracker)
        builder = HomeChartsBuilder(calculator)
    }

    @Test
    fun `builds seven bars for both charts`() {
        val charts = builder.build(
            weeklyTotals = List(7) { 60_000L },
            weekEvents = emptyList(),
            todayStartMs = timeTracker.startOfToday()
        )

        assertEquals(7, charts.weeklyScreenTime.size)
        assertEquals(7, charts.weeklyTrend.size)
    }

    @Test
    fun `week total is the sum of the daily totals`() {
        val totals = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)

        val charts = builder.build(totals, emptyList(), timeTracker.startOfToday())

        assertEquals(28L, charts.weekTotalMs)
    }

    @Test
    fun `today is the last bar, matching the stats screen`() {
        val todayStart = timeTracker.startOfToday()
        val totals = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L)

        val charts = builder.build(totals, emptyList(), todayStart)

        assertEquals(70L, charts.weeklyScreenTime.last().totalMs)
        assertEquals(10L, charts.weeklyScreenTime.first().totalMs)
        assertEquals(
            calculator.buildWeeklyDataFromTotals(totals, todayStart),
            charts.weeklyScreenTime
        )
    }

    /**
     * A walk-away row carries `wasBlocked = true` as well, so it is the SAME confrontation the
     * overlay-shown row already recorded. It belongs in the walked-away series and nowhere else:
     * this test used to expect it in both, which is the dashboard reading a single walk-away as
     * an extra block (fixed in 1.17.2, reported as "the Blocked tile goes up by 2").
     */
    @Test
    fun `a walk-away is charted as a walk-away, never as an extra block`() {
        val todayStart = timeTracker.startOfToday()
        val events = listOf(
            event(todayStart + 1_000, wasBlocked = true),
            event(todayStart + 2_000, wasBlocked = true, userChangedMind = true),
            event(todayStart - 3 * dayMs + 1_000, wasBlocked = true),
            event(todayStart - 6 * dayMs + 1_000, userChangedMind = true)
        )

        val charts = builder.build(List(7) { 0L }, events, todayStart)

        assertEquals("two overlays shown, not three", 2, charts.weekBlocked)
        assertEquals(2, charts.weekWalkedAway)
        assertEquals("today: one shown, one walked away", 1, charts.weeklyTrend.last().blockedCount)
        assertEquals(1, charts.weeklyTrend.last().walkedAwayCount)
        assertEquals(1, charts.weeklyTrend.first().walkedAwayCount)
    }

    @Test
    fun `events older than the window are not charted`() {
        val todayStart = timeTracker.startOfToday()
        val events = listOf(event(todayStart - 30 * dayMs, wasBlocked = true))

        val charts = builder.build(List(7) { 0L }, events, todayStart)

        assertEquals(0, charts.weekBlocked)
        assertTrue(charts.isEmpty)
    }

    @Test
    fun `a week with nothing in it reports empty so the card can say so`() {
        val charts = builder.build(List(7) { 0L }, emptyList(), timeTracker.startOfToday())

        assertTrue(charts.isEmpty)
    }

    @Test
    fun `screen time alone is enough to stop being empty`() {
        val charts = builder.build(
            weeklyTotals = listOf(0L, 0L, 0L, 0L, 0L, 0L, 5_000L),
            weekEvents = emptyList(),
            todayStartMs = timeTracker.startOfToday()
        )

        assertFalse(charts.isEmpty)
    }

    @Test
    fun `nudges alone are enough to stop being empty`() {
        val todayStart = timeTracker.startOfToday()
        val charts = builder.build(
            weeklyTotals = List(7) { 0L },
            weekEvents = listOf(event(todayStart + 1_000, wasBlocked = true)),
            todayStartMs = todayStart
        )

        assertFalse(charts.isEmpty)
    }

    @Test
    fun `missing daily totals degrade to zero rather than dropping bars`() {
        val charts = builder.build(emptyList(), emptyList(), timeTracker.startOfToday())

        assertEquals(7, charts.weeklyScreenTime.size)
        assertEquals(0L, charts.weekTotalMs)
    }

    private fun event(
        timestamp: Long,
        wasBlocked: Boolean = false,
        userChangedMind: Boolean = false
    ) = UsageEvent(
        id = 0,
        packageName = "com.test.app",
        timestamp = timestamp,
        wasBlocked = wasBlocked,
        userChangedMind = userChangedMind
    )
}
