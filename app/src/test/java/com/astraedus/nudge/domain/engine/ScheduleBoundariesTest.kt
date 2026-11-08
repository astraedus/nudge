package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.testing.aBlockRuleData
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1: [ScheduleBoundaries.next], the ONE instant the service arms its boundary timer for (#169, #170),
 * and [ScheduleEvaluator.nextStartAt] that feeds it. Every instant is built in an explicit zone.
 */
class ScheduleBoundariesTest {

    private val utc = ZoneOffset.UTC

    private class FixedFirstUnlock(private val stamp: Long, private val zone: ZoneId) : FirstUnlockState {
        override fun firstUnlockAt(): Long = stamp
        override fun dayStartMinute(): Int = 240
        override fun zoneId(): ZoneId = zone
    }

    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** November 2026 in UTC: the 4th is a Wednesday. */
    private fun nov(d: Int, h: Int, mi: Int) = ms(utc, 2026, 11, d, h, mi)

    private fun calendar(ms: Long, zone: String = "UTC"): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = ms }

    private val evaluator = ScheduleEvaluator(FirstUnlockState.NONE)
    private val boundaries = ScheduleBoundaries(evaluator)

    private fun window(start: Int, end: Int, days: List<Int>? = null, dailyLimit: Int? = null, enabled: Boolean = true) =
        aBlockRuleData(
            scheduleDays = days,
            scheduleStartMinute = start,
            scheduleEndMinute = end,
            dailyLimitMinutes = dailyLimit,
            enabled = enabled
        )

    private val allTheTime: BlockRuleData =
        aBlockRuleData(scheduleDays = null, scheduleStartMinute = null, scheduleEndMinute = null, dailyLimitMinutes = null)

    // ---- ScheduleEvaluator.nextStartAt ----

    @Test
    fun `nextStartAt answers the window's next start in the calendar's zone`() {
        assertEquals(nov(4, 9, 0), evaluator.nextStartAt(window(9 * 60, 17 * 60), calendar(nov(4, 8, 0))))
        // Same instant in Brisbane (08:00 UTC = 18:00 local): Thursday 09:00 local, 23:00 UTC.
        assertEquals(nov(4, 23, 0), evaluator.nextStartAt(window(9 * 60, 17 * 60), calendar(nov(4, 8, 0), "Australia/Brisbane")))
    }

    @Test
    fun `nextStartAt is null for a first-unlock rule, whose start is an unlock, not a time`() {
        val firstUnlock = window(9 * 60, 17 * 60).copy(firstUnlockMinutes = 30, armedAt = 0L)
        assertNull(evaluator.nextStartAt(firstUnlock, calendar(nov(4, 8, 0))))
    }

    @Test
    fun `nextStartAt is null with no schedule`() {
        assertNull(evaluator.nextStartAt(allTheTime, calendar(nov(4, 8, 0))))
    }

    // ---- ScheduleBoundaries.next ----

    @Test
    fun `a schedule start is a boundary`() {
        assertEquals(nov(4, 22, 0), boundaries.next(listOf(window(22 * 60, 6 * 60)), calendar(nov(4, 12, 0))))
    }

    @Test
    fun `the end of the window a rule is in is a boundary`() {
        assertEquals(nov(4, 17, 0), boundaries.next(listOf(window(9 * 60, 17 * 60)), calendar(nov(4, 12, 0))))
    }

    @Test
    fun `the earliest boundary over all rules wins`() {
        val rules = listOf(window(22 * 60, 6 * 60), window(9 * 60, 17 * 60), window(13 * 60, 14 * 60))
        assertEquals(nov(4, 13, 0), boundaries.next(rules, calendar(nov(4, 12, 0))))
        assertEquals(nov(4, 14, 0), boundaries.next(rules, calendar(nov(4, 13, 30))))
        assertEquals(nov(4, 17, 0), boundaries.next(rules, calendar(nov(4, 14, 0))))
        assertEquals(nov(4, 22, 0), boundaries.next(rules, calendar(nov(4, 17, 0))))
    }

    @Test
    fun `a disabled rule is no boundary`() {
        assertNull(boundaries.next(listOf(window(9 * 60, 17 * 60, enabled = false)), calendar(nov(4, 12, 0))))
    }

    @Test
    fun `a daily limit adds the day rollover, even on a rule that is on all the time`() {
        val limit = allTheTime.copy(dailyLimitMinutes = 30)
        assertEquals(nov(5, 0, 0), boundaries.next(listOf(limit), calendar(nov(4, 12, 0))))
        // Rollover is read in the calendar's zone: Brisbane midnight on the 5th is 14:00 UTC on the 4th.
        assertEquals(nov(4, 14, 0), boundaries.next(listOf(limit), calendar(nov(4, 2, 0), "Australia/Brisbane")))
    }

    @Test
    fun `a rule window ending before midnight beats the rollover`() {
        val limitedWindow = window(9 * 60, 17 * 60, dailyLimit = 30)
        assertEquals(nov(4, 17, 0), boundaries.next(listOf(limitedWindow), calendar(nov(4, 12, 0))))
        assertEquals(nov(5, 0, 0), boundaries.next(listOf(limitedWindow), calendar(nov(4, 18, 0))))
    }

    @Test
    fun `a first-unlock window's end is a boundary and its start is not`() {
        val stamp = nov(4, 8, 12)
        val firstUnlockEvaluator = ScheduleEvaluator(FixedFirstUnlock(stamp, utc))
        val rule = window(9 * 60, 17 * 60).copy(scheduleStartMinute = null, scheduleEndMinute = null, firstUnlockMinutes = 30, armedAt = 0L)
        val b = ScheduleBoundaries(firstUnlockEvaluator)
        assertEquals(stamp + 30 * 60_000L, b.next(listOf(rule), calendar(stamp + 60_000L)))
        // After the window: nothing on a clock (the next window opens at an unlock).
        assertNull(b.next(listOf(rule), calendar(stamp + 31 * 60_000L)))
    }

    /** #63: a rule that needs no clock has none. This null is what keeps the service from posting a timer. */
    @Test
    fun `rules with nothing on a clock give no boundary`() {
        assertNull(boundaries.next(listOf(allTheTime), calendar(nov(4, 12, 0))))
        assertNull(boundaries.next(emptyList(), calendar(nov(4, 12, 0))))
        assertNull(boundaries.next(listOf(window(0, 0, days = null).copy(scheduleDays = listOf(1, 2, 3, 4, 5, 6, 7), scheduleStartMinute = null, scheduleEndMinute = null)), calendar(nov(4, 12, 0))))
    }

    @Test
    fun `every boundary is strictly after now`() {
        val rules = listOf(window(9 * 60, 17 * 60), window(22 * 60, 6 * 60), allTheTime.copy(dailyLimitMinutes = 5))
        var now = nov(4, 0, 0)
        repeat(20) {
            val next = boundaries.next(rules, calendar(now))!!
            check(next > now) { "boundary $next not after $now" }
            now = next
        }
    }
}
