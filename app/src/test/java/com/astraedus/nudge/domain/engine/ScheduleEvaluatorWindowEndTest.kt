package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L1: [ScheduleEvaluator.windowEndAt], the "Until 8:42 AM" on the block screen and the Home lock card. */
class ScheduleEvaluatorWindowEndTest {

    private val evaluator = ScheduleEvaluator()

    /** November 2026: the 2nd is a Monday (ISO day 1), the 4th a Wednesday (3). */
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }

    private fun rule(days: List<Int>? = null, start: Int? = null, end: Int? = null) = ActiveRule(
        mode = BlockMode.DELAY,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        scheduleDays = days,
        scheduleStartMinute = start,
        scheduleEndMinute = end
    )

    @Test
    fun `a same-day window ends at its end time`() {
        assertEquals(at(4, 17, 0).timeInMillis, evaluator.windowEndAt(rule(start = 9 * 60, end = 17 * 60), at(4, 10, 0)))
    }

    @Test
    fun `an overnight window ends the next morning, from either side of midnight`() {
        val overnight = rule(start = 22 * 60, end = 6 * 60)
        assertEquals(at(5, 6, 0).timeInMillis, evaluator.windowEndAt(overnight, at(4, 23, 0)))
        assertEquals(at(5, 6, 0).timeInMillis, evaluator.windowEndAt(overnight, at(5, 2, 0)))
    }

    @Test
    fun `an all-day days-only rule ends at midnight after its last consecutive day`() {
        // Mon-Fri, all day, asked on Wednesday: ends Saturday 00:00.
        val weekdays = rule(days = listOf(1, 2, 3, 4, 5))
        assertEquals(at(7, 0, 0).timeInMillis, evaluator.windowEndAt(weekdays, at(4, 10, 0)))
        // Wednesday only: ends Thursday 00:00.
        assertEquals(at(5, 0, 0).timeInMillis, evaluator.windowEndAt(rule(days = listOf(3)), at(4, 10, 0)))
    }

    @Test
    fun `days and times end at the time on that day`() {
        val r = rule(days = listOf(3), start = 9 * 60, end = 17 * 60)
        assertEquals(at(4, 17, 0).timeInMillis, evaluator.windowEndAt(r, at(4, 9, 0)))
    }

    @Test
    fun `not in the window gives null`() {
        assertNull(evaluator.windowEndAt(rule(start = 9 * 60, end = 17 * 60), at(4, 17, 0)))
        assertNull(evaluator.windowEndAt(rule(start = 9 * 60, end = 17 * 60), at(4, 8, 59)))
        assertNull(evaluator.windowEndAt(rule(days = listOf(1)), at(4, 10, 0)))
    }

    @Test
    fun `no schedule gives null`() {
        assertNull(evaluator.windowEndAt(rule(), at(4, 10, 0)))
    }

    @Test
    fun `every day all day never ends within a week, so null`() {
        assertNull(evaluator.windowEndAt(rule(days = (1..7).toList()), at(4, 10, 0)))
    }

    @Test
    fun `seconds and millis are zeroed`() {
        val end = evaluator.windowEndAt(rule(start = 9 * 60, end = 17 * 60), at(4, 16, 59, second = 59, millis = 999))
        assertEquals(at(4, 17, 0).timeInMillis, end)
    }

    /** The one schedule semantics: the end is the first minute [ScheduleEvaluator.isActiveAt] says no. */
    @Test
    fun `the end is the first inactive minute and the minute before it is active`() {
        val rules = listOf(
            rule(start = 9 * 60, end = 17 * 60),
            rule(start = 22 * 60, end = 6 * 60),
            rule(days = listOf(3, 4)),
            rule(days = listOf(3), start = 20 * 60, end = 2 * 60)
        )
        for (r in rules) {
            val now = at(4, 22, 30)
            val end = evaluator.windowEndAt(r, now) ?: continue
            val endCal = Calendar.getInstance().apply { timeInMillis = end }
            val before = Calendar.getInstance().apply { timeInMillis = end - 60_000L }
            assertEquals("$r at its end", false, evaluator.isActiveAt(r, endCal))
            assertEquals("$r a minute before its end", true, evaluator.isActiveAt(r, before))
        }
    }

    @Test
    fun `the BlockRuleData overload agrees`() {
        val data = BlockRuleData(
            id = 1L, packageName = null, groupId = null, mode = BlockMode.DELAY, delaySeconds = 15,
            dailyLimitMinutes = null, enabled = true, scheduleStartMinute = 22 * 60, scheduleEndMinute = 6 * 60,
            allApps = true
        )
        assertEquals(at(5, 6, 0).timeInMillis, evaluator.windowEndAt(data, at(4, 23, 0)))
    }
}
