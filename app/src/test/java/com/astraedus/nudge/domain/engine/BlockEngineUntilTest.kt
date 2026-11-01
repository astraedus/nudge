package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.AllAppsBlock
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1: the block screen's "Until 8:42 AM" ([BlockDecision.Block.until]) and its All apps line
 * ([BlockDecision.Block.allApps]) come from the rule that DECIDED the block, never from another
 * applicable rule (spec 3.9).
 */
class BlockEngineUntilTest {

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }

    private fun engineAt(now: Calendar) =
        BlockEngine(ScheduleEvaluator(), NudgeLog.NoOp) { now.clone() as Calendar }

    private val allAppsLine = AllAppsBlock(
        exceptPackages = listOf("maps"),
        exceptGroupNames = emptyList(),
        allowedPackages = listOf("maps")
    )

    private fun rule(
        mode: BlockMode,
        start: Int? = null,
        end: Int? = null,
        days: List<Int>? = null,
        limit: Int? = null,
        allApps: AllAppsBlock? = null,
        name: String = mode.name
    ) = ActiveRule(
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = limit,
        enabled = true,
        scheduleDays = days,
        scheduleStartMinute = start,
        scheduleEndMinute = end,
        ruleName = name,
        allApps = allApps
    )

    private fun block(decision: BlockDecision) = decision as BlockDecision.Block

    @Test
    fun `the deciding scheduled rule carries its window end and its All apps line`() {
        // Wednesday 4 Nov 2026, 10:15:42.
        val now = at(4, 10, 15, 42)
        val decision = block(
            engineAt(now).evaluate("app", listOf(rule(BlockMode.DELAY, 9 * 60, 17 * 60, allApps = allAppsLine)), 0L)
        )
        assertEquals(at(4, 17, 0).timeInMillis, decision.until)
        assertEquals(allAppsLine, decision.allApps)
    }

    @Test
    fun `an overnight window ends the next morning`() {
        val now = at(4, 23, 30)
        val decision = block(engineAt(now).evaluate("app", listOf(rule(BlockMode.HARD_BLOCK, 22 * 60, 6 * 60)), 0L))
        assertEquals(at(5, 6, 0).timeInMillis, decision.until)
    }

    @Test
    fun `a rule on all the time has no until`() {
        val decision = block(engineAt(at(4, 10, 0)).evaluate("app", listOf(rule(BlockMode.BREATHING)), 0L))
        assertNull(decision.until)
    }

    /**
     * A group Hard block decides over an All apps Delay that also covers the app: the screen is the
     * group's, so it carries neither the All apps line nor the All apps rule's window end.
     */
    @Test
    fun `a non-deciding rule's until and All apps line never leak`() {
        val now = at(4, 10, 0)
        val group = rule(BlockMode.HARD_BLOCK, name = "Social")
        val allApps = rule(BlockMode.DELAY, 9 * 60, 17 * 60, allApps = allAppsLine, name = "All apps")

        for (rules in listOf(listOf(group, allApps), listOf(allApps, group))) {
            val decision = block(engineAt(now).evaluate("app", rules, 0L))
            assertEquals(BlockMode.HARD_BLOCK, decision.mode)
            assertEquals("Social", decision.ruleName)
            assertNull(decision.allApps)
            assertNull(decision.until)
        }
    }

    @Test
    fun `Nuke carries neither`() {
        val rules = listOf(rule(BlockMode.DELAY, 9 * 60, 17 * 60, allApps = allAppsLine))
        val decision = block(engineAt(at(4, 10, 0)).evaluate("app", rules, 0L, nuked = true))
        assertEquals(BlockEngine.NUKE_DECISION, decision)
        assertNull(decision.until)
        assertNull(decision.allApps)
    }

    /**
     * A spent daily limit blocks until usage resets at local midnight (`TimeTracker.startOfToday`).
     * An overnight window that runs past midnight must not promise its own end: the app opens at
     * 00:00 with a fresh budget.
     */
    @Test
    fun `a spent limit in an overnight window ends at midnight, not at the window's end`() {
        val now = at(4, 23, 30)
        val decision = block(
            engineAt(now).evaluate("app", listOf(rule(BlockMode.DELAY, 22 * 60, 6 * 60, limit = 30)), 31 * 60_000L)
        )
        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertEquals(at(5, 0, 0).timeInMillis, decision.until)
    }

    /** A spent limit in a window that ends before midnight keeps the window's end. */
    @Test
    fun `a spent limit in a same-day window ends with the window`() {
        val now = at(4, 10, 0)
        val decision = block(
            engineAt(now).evaluate("app", listOf(rule(BlockMode.DELAY, 9 * 60, 17 * 60, limit = 30)), 31 * 60_000L)
        )
        assertEquals(at(4, 17, 0).timeInMillis, decision.until)
    }

    /** A spent limit on a rule that is on all the time shows no "Until": the limit line covers it. */
    @Test
    fun `a spent limit with no schedule has no until`() {
        val decision = block(
            engineAt(at(4, 10, 0)).evaluate("app", listOf(rule(BlockMode.DELAY, limit = 30)), 31 * 60_000L)
        )
        assertNull(decision.until)
    }

    /**
     * Counterfactual for the midnight cap: without it, the same overnight case reads the window's
     * 06:00 end, which is what the schedule alone says. Pins that the cap, not the schedule, moved it.
     */
    @Test
    fun `the overnight window itself ends at six`() {
        val now = at(4, 23, 30)
        val rule = rule(BlockMode.DELAY, 22 * 60, 6 * 60, limit = 30)
        assertEquals(at(5, 6, 0).timeInMillis, ScheduleEvaluator().windowEndAt(rule, now))
    }
}
