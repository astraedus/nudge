package com.astraedus.nudge.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hourly heatmap's bucketing.
 *
 * This arithmetic used to live as a private helper inside `ScreenTimeProvider`, next to the
 * heatmap's own copy of the event pairing — which is how it inherited the over-count that had a
 * phone reporting ~17 hours of screen time before lunchtime. The pairing is now
 * [ForegroundSpanTracker]'s job and this only splits the spans it produces, so the cells and the
 * bars above them are the same measurement.
 */
class HourlyUsageAccumulatorTest {

    private val dayStart = 1_767_222_000_000L

    private fun hours(h: Long) = h * 60L * 60L * 1000L
    private fun minutes(m: Long) = m * 60L * 1000L
    private fun at(h: Long, m: Long = 0) = dayStart + hours(h) + minutes(m)

    private fun accumulator(dayEndMs: Long = dayStart + hours(24)) =
        HourlyUsageAccumulator(dayStart, dayEndMs)

    @Test
    fun `an empty day is 24 zeroes`() {
        assertEquals(24, HourlyUsageAccumulator.empty().size)
        assertTrue(HourlyUsageAccumulator.empty().all { it == 0L })
        assertEquals(HourlyUsageAccumulator.empty(), accumulator().totals())
    }

    @Test
    fun `a span inside one hour lands in that hour alone`() {
        val accumulator = accumulator()
        accumulator.add(at(9, 10), at(9, 40))

        val totals = accumulator.totals()
        assertEquals(minutes(30), totals[9])
        assertEquals(0L, totals.filterIndexed { hour, _ -> hour != 9 }.sum())
    }

    @Test
    fun `a span across hours is split at each hour boundary`() {
        val accumulator = accumulator()
        accumulator.add(at(8, 45), at(11, 15))

        val totals = accumulator.totals()
        assertEquals(minutes(15), totals[8])
        assertEquals(hours(1), totals[9])
        assertEquals(hours(1), totals[10])
        assertEquals(minutes(15), totals[11])
        assertEquals(minutes(150), totals.sum())
    }

    @Test
    fun `time before the day and after its end is clipped away`() {
        val accumulator = accumulator()
        accumulator.add(dayStart - hours(2), at(0, 30))
        accumulator.add(at(23, 30), dayStart + hours(26))

        val totals = accumulator.totals()
        assertEquals(minutes(30), totals[0])
        assertEquals(minutes(30), totals[23])
        assertEquals(hours(1), totals.sum())
    }

    @Test
    fun `today stops at now, so the heatmap cannot draw hours that have not happened`() {
        // For today the caller passes `now` as the day end; a span the tracker capped at now
        // must not spill into the rest of the row.
        val now = at(11, 20)
        val accumulator = accumulator(dayEndMs = now)
        accumulator.add(at(10), now)

        val totals = accumulator.totals()
        assertEquals(hours(1), totals[10])
        assertEquals(minutes(20), totals[11])
        assertEquals(0L, totals.drop(12).sum())
    }

    @Test
    fun `a span outside the day contributes nothing`() {
        val accumulator = accumulator()
        accumulator.add(dayStart - hours(5), dayStart - hours(4))
        accumulator.add(dayStart + hours(25), dayStart + hours(26))

        assertEquals(0L, accumulator.totals().sum())
    }

    @Test
    fun `a backwards span is worth zero`() {
        val accumulator = accumulator()
        accumulator.add(at(10), at(9))

        assertEquals(0L, accumulator.totals().sum())
    }

    @Test
    fun `no cell can exceed an hour when the spans do not overlap`() {
        val accumulator = accumulator()
        // A full day of back-to-back sessions — the densest input the tracker can produce.
        for (hour in 0 until 24) accumulator.add(at(hour.toLong()), at(hour.toLong() + 1))

        val totals = accumulator.totals()
        assertTrue("no hour may hold more than an hour", totals.all { it <= hours(1) })
        assertEquals(hours(24), totals.sum())
    }

    @Test
    fun `a 25-hour day keeps its extra hour rather than dropping it off the row`() {
        // A clocks-back day is 25 hours long against 24 cells. The repeated hour belongs
        // somewhere, and the last cell is the only honest place for it.
        val accumulator = accumulator(dayEndMs = dayStart + hours(25))
        accumulator.add(at(23), dayStart + hours(25))

        val totals = accumulator.totals()
        assertEquals(24, totals.size)
        assertEquals(hours(2), totals[23])
        assertEquals(hours(2), totals.sum())
    }
}
