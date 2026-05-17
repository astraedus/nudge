package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests verifying that BlockEngine correctly evaluates rules created from web domain matching.
 * Web domain rules are passed to BlockEngine as whole-app rules (inAppFeatures = null).
 */
class WebDomainBlockEngineTest {

    private lateinit var engine: BlockEngine

    @Before
    fun setUp() {
        engine = BlockEngine(ScheduleEvaluator())
    }

    @Test
    fun `web domain rule with HARD_BLOCK blocks`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.HARD_BLOCK,
                delaySeconds = 0,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = null,
                ruleName = "Web - Hard Block"
            )
        )
        val decision = engine.evaluate("web", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
        assertEquals("Web - Hard Block", decision.ruleName)
    }

    @Test
    fun `web domain rule with DELAY applies delay`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = null,
                ruleName = "Web - Delay"
            )
        )
        val decision = engine.evaluate("com.instagram.android", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, block.mode)
        assertEquals(15, block.delaySeconds)
    }

    @Test
    fun `web domain rule with BREATHING applies breathing`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.BREATHING,
                delaySeconds = 20,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = null,
                ruleName = "Web - Breathing"
            )
        )
        val decision = engine.evaluate("com.google.android.youtube", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        val block = decision as BlockDecision.Block
        assertEquals(BlockMode.BREATHING, block.mode)
        assertEquals(20, block.delaySeconds)
    }

    @Test
    fun `disabled web domain rule allows`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.HARD_BLOCK,
                delaySeconds = 0,
                dailyLimitMinutes = null,
                enabled = false,
                inAppFeatures = null,
                ruleName = "Web - Hard Block"
            )
        )
        val decision = engine.evaluate("web", rules, 0L)
        assertTrue(decision is BlockDecision.Allow)
    }

    @Test
    fun `web domain rule with daily limit blocks after exceeded`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = 30,
                enabled = true,
                inAppFeatures = null,
                ruleName = "Web - Delay"
            )
        )
        // 31 minutes of usage exceeds 30-minute limit
        val decision = engine.evaluate("com.instagram.android", rules, 31L * 60L * 1000L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `web domain rule with daily limit allows when under budget`() {
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = 30,
                enabled = true,
                inAppFeatures = null,
                ruleName = "Web - Delay"
            )
        )
        // 10 minutes of usage is under 30-minute limit -- should use DELAY mode
        val decision = engine.evaluate("com.instagram.android", rules, 10L * 60L * 1000L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `web domain rule with null webDomains field does not affect engine`() {
        // This tests that a rule without web domains (used for normal app blocking)
        // still works fine through the engine
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.DELAY,
                delaySeconds = 15,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = null,
                ruleName = null
            )
        )
        val decision = engine.evaluate("com.instagram.android", rules, 0L)
        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `scheduled web domain rule respects schedule`() {
        // Rule with schedule that is NOT active now (use impossible schedule)
        val rules = listOf(
            ActiveRule(
                mode = BlockMode.HARD_BLOCK,
                delaySeconds = 0,
                dailyLimitMinutes = null,
                enabled = true,
                inAppFeatures = null,
                // Schedule for days that don't exist
                scheduleDays = listOf(8), // Invalid day -- ScheduleEvaluator won't match
                scheduleStartMinute = 0,
                scheduleEndMinute = 1,
                ruleName = "Web - Hard Block"
            )
        )
        val decision = engine.evaluate("web", rules, 0L)
        // Schedule evaluator should not match day 8, so rule is filtered out
        assertTrue(decision is BlockDecision.Allow)
    }
}
