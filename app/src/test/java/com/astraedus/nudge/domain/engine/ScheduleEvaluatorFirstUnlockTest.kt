package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1. `ScheduleEvaluator`'s first-unlock branch (spec 5.3/5.5): the same schedule semantics, the
 * window's answer, and the fact that a rule is one kind of schedule or the other, never both.
 */
class ScheduleEvaluatorFirstUnlockTest {

    private val newYork = ZoneId.of("America/New_York")

    private class FixedState(
        private val stamp: Long,
        private val dayStart: Int = 240,
        private val zone: ZoneId,
    ) : FirstUnlockState {
        override fun firstUnlockAt(): Long = stamp
        override fun dayStartMinute(): Int = dayStart
        override fun zoneId(): ZoneId = zone
    }

    private fun at(date: String, hour: Int, minute: Int, zone: ZoneId = ZoneId.of("America/New_York")): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** A [Calendar] at the same instant, which is what the evaluator's public API takes. */
    private fun calendarAt(ms: Long): Calendar =
        Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York")).apply {
            timeInMillis = ms
        }

    private fun rule(
        minutes: Int? = null,
        days: List<Int>? = null,
        start: Int? = null,
        end: Int? = null,
        armedAt: Long = 0L,
    ) = ActiveRule(
        mode = BlockMode.HARD_BLOCK,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        scheduleDays = days,
        scheduleStartMinute = start,
        scheduleEndMinute = end,
        firstUnlockMinutes = minutes,
        armedAt = armedAt,
    )

    @Test
    fun `a first-unlock rule is active inside the window and not after it`() {
        val stamp = at("2026-06-08", 8, 12)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))
        val r = rule(minutes = 30)

        assertTrue(evaluator.isActiveAt(r, calendarAt(stamp + 60_000L)))
        assertFalse(evaluator.isActiveAt(r, calendarAt(stamp + 30 * 60_000L)))
    }

    @Test
    fun `days are the logical day's, so a two am unlock is still last night's rule`() {
        // Saturday 2026-06-06, 1 AM: Friday's logical day with a 4 AM start.
        val stamp = at("2026-06-06", 1, 0)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))
        val now = calendarAt(at("2026-06-06", 1, 10))

        assertTrue(evaluator.isActiveAt(rule(minutes = 30, days = listOf(5)), now))
        assertFalse(evaluator.isActiveAt(rule(minutes = 30, days = listOf(6)), now))
    }

    @Test
    fun `a first-unlock rule ignores its days-and-times fields`() {
        // "When" is one choice. A row that carries both (an import from a future build, say) must
        // not be read as an intersection: the first-unlock kind wins.
        val stamp = at("2026-06-08", 8, 12)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))

        assertTrue(
            evaluator.isActiveAt(
                rule(minutes = 30, start = 22 * 60, end = 23 * 60),
                calendarAt(stamp + 60_000L)
            )
        )
    }

    @Test
    fun `windowEndAt answers with the window's end, for the block screen's Until`() {
        val stamp = at("2026-06-08", 8, 12)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))

        assertEquals(
            stamp + 30 * 60_000L,
            evaluator.windowEndAt(rule(minutes = 30), calendarAt(stamp + 60_000L))
        )
        assertNull(evaluator.windowEndAt(rule(minutes = 30), calendarAt(stamp + 30 * 60_000L)))
    }

    @Test
    fun `a rule saved after the unlock waits for tomorrow`() {
        val stamp = at("2026-06-08", 8, 12)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))

        assertFalse(
            evaluator.isActiveAt(
                rule(minutes = 30, armedAt = at("2026-06-08", 8, 15)),
                calendarAt(stamp + 20 * 60_000L)
            )
        )
    }

    @Test
    fun `a days-and-times rule is unchanged by a first-unlock anchor`() {
        val stamp = at("2026-06-08", 8, 12)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))
        val r = rule(start = 9 * 60, end = 17 * 60)

        assertTrue(evaluator.isActiveAt(r, calendarAt(at("2026-06-08", 10, 0))))
        assertFalse(evaluator.isActiveAt(r, calendarAt(at("2026-06-08", 18, 0))))
    }

    @Test
    fun `the no-argument constructor never activates a first-unlock rule`() {
        // Fail-open default for hand-built evaluators: no anchor means no window. Production binds
        // FirstUnlockStateCache, so this is never the app's path.
        val evaluated = ScheduleEvaluator(FirstUnlockState.NONE).isActiveAt(
            rule(minutes = 30),
            calendarAt(System.currentTimeMillis())
        )

        assertFalse(evaluated)
    }

    @Test
    fun `the BlockRuleData overload agrees with the ActiveRule one`() {
        val stamp = at("2026-06-08", 8, 12)
        val evaluator = ScheduleEvaluator(FixedState(stamp, zone = newYork))
        val now = at("2026-06-08", 8, 20)
        val data = BlockRuleData(
            id = 1,
            packageName = "com.instagram.android",
            groupId = null,
            mode = BlockMode.HARD_BLOCK,
            delaySeconds = 15,
            dailyLimitMinutes = null,
            enabled = true,
            firstUnlockMinutes = 30,
        )

        assertTrue(evaluator.isActiveAt(data, calendarAt(now)))
        assertEquals(
            evaluator.windowEndAt(rule(minutes = 30), calendarAt(now)),
            evaluator.windowEndAt(data, calendarAt(now))
        )
    }
}
