package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
