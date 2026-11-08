package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.testing.anActiveRule
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1: [BlockDecision.Block.endsAt], the instant the block screen closes itself and the app in front is
 * evaluated afresh (#170). The gap it closes: a SPENT daily limit on a rule that is on all the time
 * carried no `until`, so the screen had nothing to end at and stayed up past midnight.
 */
class BlockEngineEndsAtTest {

    private val zone = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        }

    private fun engineAt(now: Calendar) =
        BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE), NudgeLog.NoOp) { now.clone() as Calendar }

    private fun block(d: BlockDecision) = d as BlockDecision.Block

    private val thirtyMinutes = 30L * 60_000L

    @Test
    fun `a spent limit on an all-the-time rule ends at the next local midnight, with no until shown`() {
        val d = block(engineAt(at(4, 21, 0)).evaluate("app", listOf(anActiveRule(mode = BlockMode.NONE, dailyLimitMinutes = 30)), thirtyMinutes))
        assertNull("the display value stays as it was", d.until)
        assertEquals(at(5, 0, 0).timeInMillis, d.budgetResetAt)
        assertEquals(at(5, 0, 0).timeInMillis, d.endsAt)
    }

    @Test
    fun `a spent limit in an overnight window ends at midnight, before the window does`() {
        val rule = anActiveRule(mode = BlockMode.DELAY, dailyLimitMinutes = 30, scheduleStartMinute = 22 * 60, scheduleEndMinute = 6 * 60)
        val d = block(engineAt(at(4, 23, 30)).evaluate("app", listOf(rule), thirtyMinutes))
        assertEquals(at(5, 0, 0).timeInMillis, d.endsAt)
    }

    @Test
    fun `a spent limit in a window that closes before midnight ends with the window`() {
        val rule = anActiveRule(mode = BlockMode.DELAY, dailyLimitMinutes = 30, scheduleStartMinute = 9 * 60, scheduleEndMinute = 17 * 60)
        val d = block(engineAt(at(4, 12, 0)).evaluate("app", listOf(rule), thirtyMinutes))
        assertEquals(at(4, 17, 0).timeInMillis, d.endsAt)
    }

    @Test
    fun `a scheduled block ends with its window and carries no budget reset`() {
        val d = block(engineAt(at(4, 10, 0)).evaluate("app", listOf(anActiveRule(mode = BlockMode.HARD_BLOCK, scheduleStartMinute = 9 * 60, scheduleEndMinute = 17 * 60)), 0L))
        assertNull(d.budgetResetAt)
        assertEquals(at(4, 17, 0).timeInMillis, d.endsAt)
    }

    @Test
    fun `a limit that is NOT spent does not stamp a reset on the timed block`() {
        val rule = anActiveRule(mode = BlockMode.DELAY, dailyLimitMinutes = 30)
        val d = block(engineAt(at(4, 10, 0)).evaluate("app", listOf(rule), 0L))
        assertNull(d.budgetResetAt)
        assertNull("on all the time, the clock never ends a Delay", d.endsAt)
    }

    @Test
    fun `nothing on a clock ends an all-the-time block or Nuke`() {
        assertNull(block(engineAt(at(4, 10, 0)).evaluate("app", listOf(anActiveRule(mode = BlockMode.HARD_BLOCK)), 0L)).endsAt)
        assertNull(block(engineAt(at(4, 10, 0)).evaluate("app", emptyList(), 0L, nuked = true)).endsAt)
    }
}
