package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class BlockEngineTest {

    private lateinit var engine: BlockEngine

    @Before
    fun setUp() {
        engine = BlockEngine(ScheduleEvaluator())
    }

    @Test
    fun `no rules returns Allow`() {
        val decision = engine.evaluate("com.example.app", emptyList(), 0L)
        assertTrue(decision is BlockDecision.Allow)
    }

    @Test
    fun `single HARD_BLOCK rule returns Block HARD_BLOCK`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HARD_BLOCK, delaySeconds = 0, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `DELAY rule returns Block DELAY with correct seconds`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.DELAY, delaySeconds = 30, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, block.mode)
        assertEquals(30, block.delaySeconds)
    }

    @Test
    fun `BREATHING rule returns Block BREATHING with correct seconds`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.BREATHING, delaySeconds = 20, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.BREATHING, block.mode)
        assertEquals(20, block.delaySeconds)
    }

    @Test
    fun `HARD_BLOCK wins over DELAY when both present`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.DELAY, delaySeconds = 15, dailyLimitMinutes = null, enabled = true),
            ActiveRule(mode = BlockMode.HARD_BLOCK, delaySeconds = 0, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `daily limit not exceeded returns Allow when only time-limited rule`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HARD_BLOCK, delaySeconds = 0, dailyLimitMinutes = 60, enabled = true)
        )
        // 30 minutes used out of 60 minute limit
        val usageMs = 30L * 60L * 1000L
        val decision = engine.evaluate("com.example.app", rules, usageMs)
        // Should not hard block because daily limit not exceeded, and there's no unconditional hard block
        // Wait - this rule IS a HARD_BLOCK with a dailyLimitMinutes. The engine checks for unconditional
        // HARD_BLOCK first (no daily limit). This rule has a daily limit so it's conditional.
        // Then it checks time budget exceeded - 30 min < 60 min, so no.
        // Then it checks DELAY / BREATHING - none.
        // So it returns Allow.
        assertTrue(decision is BlockDecision.Allow)
    }

    @Test
    fun `daily limit exceeded returns Block HARD_BLOCK`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.DELAY, delaySeconds = 15, dailyLimitMinutes = 30, enabled = true)
        )
        // 31 minutes used out of 30 minute limit
        val usageMs = 31L * 60L * 1000L
        val decision = engine.evaluate("com.example.app", rules, usageMs)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `disabled rules are ignored`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HARD_BLOCK, delaySeconds = 0, dailyLimitMinutes = null, enabled = false)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Allow)
    }

    @Test
    fun `multiple DELAY rules uses first one delay seconds`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.DELAY, delaySeconds = 10, dailyLimitMinutes = null, enabled = true),
            ActiveRule(mode = BlockMode.DELAY, delaySeconds = 30, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, block.mode)
        assertEquals(10, block.delaySeconds)
    }

    @Test
    fun `feature-scoped rule does not block whole app launch`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = listOf("REELS")
            )
        )

        val decision = engine.evaluate("com.instagram.android", rules, 0L)

        assertTrue(decision is BlockDecision.Allow)
    }

    @Test
    fun `feature-scoped rule blocks when matching feature is detected`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = listOf("REELS")
            )
        )

        val decision = engine.evaluate("com.instagram.android", rules, 0L, detectedFeature = "REELS")

        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, block.mode)
        assertEquals(15, block.delaySeconds)
    }

    // ── HOLD (mirrors DELAY: same BlockDecision.Block shape, same delaySeconds, different UI) ──

    @Test
    fun `HOLD rule returns Block HOLD with correct seconds`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HOLD, delaySeconds = 20, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.HOLD, block.mode)
        assertEquals(20, block.delaySeconds)
    }

    @Test
    fun `disabled HOLD rule returns Allow`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HOLD, delaySeconds = 20, dailyLimitMinutes = null, enabled = false)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Allow)
    }

    /**
     * A HOLD rule outside its schedule must Allow, exactly as a DELAY rule does. Asserted for BOTH
     * modes in one test so the claim is a comparison and not two separate hopes.
     *
     * The out-of-schedule window is every ISO day EXCEPT today, derived from the clock rather than
     * hand-picked: a hardcoded day would pass six days a week and fail on the seventh, and an
     * out-of-RANGE day (`8`) would be testing an unparseable schedule, which is a different thing
     * from a schedule that simply is not on right now.
     */
    @Test
    fun `an out-of-schedule HOLD rule Allows, exactly as an out-of-schedule DELAY rule does`() {
        val todayIso = ScheduleEvaluator.calendarDayToIso(
            Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        )
        val everyOtherDay = (1..7).filter { it != todayIso }

        listOf(BlockMode.HOLD, BlockMode.DELAY).forEach { mode ->
            val rules = listOf(
                ActiveRule(
                    mode = mode,
                    delaySeconds = 20,
                    dailyLimitMinutes = null,
                    enabled = true,
                    scheduleDays = everyOtherDay,
                    scheduleStartMinute = 0,
                    scheduleEndMinute = 1440
                )
            )
            val decision = engine.evaluate("com.example.app", rules, 0L)
            assertTrue("mode=$mode should Allow outside its schedule", decision is BlockDecision.Allow)
        }
    }

    @Test
    fun `HARD_BLOCK wins over HOLD when both present`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HOLD, delaySeconds = 20, dailyLimitMinutes = null, enabled = true),
            ActiveRule(mode = BlockMode.HARD_BLOCK, delaySeconds = 0, dailyLimitMinutes = null, enabled = true)
        )
        val decision = engine.evaluate("com.example.app", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `daily limit exceeded beats HOLD and returns Block HARD_BLOCK`() {
        val rules = listOf(
            ActiveRule(mode = BlockMode.HOLD, delaySeconds = 20, dailyLimitMinutes = 30, enabled = true)
        )
        // 31 minutes used out of 30 minute limit
        val usageMs = 31L * 60L * 1000L
        val decision = engine.evaluate("com.example.app", rules, usageMs)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `HOLD decision carries grayscale ruleName dailyTimeRemainingMs and dailyLimitMinutes`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.HOLD,
                delaySeconds = 20,
                dailyLimitMinutes = 60,
                enabled = true,
                grayscale = true,
                ruleName = "Hold Rule"
            )
        )
        // 10 minutes used out of 60 minute limit -- 50 minutes remaining
        val usageMs = 10L * 60L * 1000L
        val decision = engine.evaluate("com.example.app", rules, usageMs)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.HOLD, block.mode)
        assertTrue(block.grayscale)
        assertEquals("Hold Rule", block.ruleName)
        assertEquals(50L * 60L * 1000L, block.dailyTimeRemainingMs)
        assertEquals(60, block.dailyLimitMinutes)
    }

    @Test
    fun `every timed BlockMode produces a Block carrying its own mode and delaySeconds`() {
        // Test the class, not the instance: derive the timed modes from the enum itself (minus
        // NONE, which gates nothing, and HARD_BLOCK, which is decided ahead of the timed scan)
        // so a future timed mode is covered automatically instead of needing a hand-typed list.
        val timedModes = BlockMode.entries.filterNot {
            it == BlockMode.NONE || it == BlockMode.HARD_BLOCK
        }
        assertTrue("expected at least one timed mode to exist", timedModes.isNotEmpty())

        timedModes.forEach { mode ->
            val rules = listOf(
                ActiveRule(mode = mode, delaySeconds = 20, dailyLimitMinutes = null, enabled = true)
            )
            val decision = engine.evaluate("com.example.app", rules, 0L)
            assertTrue("mode=$mode should Block", decision is BlockDecision.Block)
            val block = decision as BlockDecision.Block
            assertEquals("mode=$mode", mode, block.mode)
            assertEquals("mode=$mode", 20, block.delaySeconds)
        }
    }

    @Test
    fun `feature evaluation can ignore whole-app rules after whole-app passthrough`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = null,
                enabled = true
            ),
            ActiveRule(
                mode = BlockMode.HARD_BLOCK,
                delaySeconds = 0,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = listOf("REELS")
            )
        )

        val decision = engine.evaluate(
            packageName = "com.instagram.android",
            activeRules = rules,
            dailyUsageMs = 0L,
            detectedFeature = "REELS",
            includeWholeAppRulesForFeature = false
        )

        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }
}
