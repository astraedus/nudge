package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.FeatureMode
import com.astraedus.nudge.domain.model.WebBlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIRROR (the front-camera delay) in the domain, and the CLASS invariants that make the next mode
 * impossible to half-wire: every mode with a duration is a timed block the engine decides, every
 * mode name parses as a website mode, and every feature override maps to a mode and back.
 */
class MirrorModeEngineTest {

    private val engine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE))

    private fun rule(mode: BlockMode, seconds: Int = 20, limit: Int? = null) =
        ActiveRule(mode = mode, delaySeconds = seconds, dailyLimitMinutes = limit, enabled = true)

    @Test
    fun `MIRROR is a timed mode that spends delaySeconds`() {
        assertTrue(BlockMode.MIRROR.usesDuration)
        val block = engine.evaluate("com.example.app", listOf(rule(BlockMode.MIRROR, seconds = 30)), 0L)
            as BlockDecision.Block
        assertEquals(BlockMode.MIRROR, block.mode)
        assertEquals(30, block.delaySeconds)
    }

    /**
     * The class: a mode that has a duration and is missing from the engine's timed list would
     * Allow (a blocker not blocking). Every [BlockMode.usesDuration] mode must come back as its
     * own block with its own seconds.
     */
    @Test
    fun `every mode with a duration is decided by the engine as its own timed block`() {
        BlockMode.entries.filter { it.usesDuration }.forEach { mode ->
            val decision = engine.evaluate("com.example.app", listOf(rule(mode, seconds = 25)), 0L)
            assertTrue("$mode must block", decision is BlockDecision.Block)
            decision as BlockDecision.Block
            assertEquals(mode, decision.mode)
            assertEquals(25, decision.delaySeconds)
        }
    }

    @Test
    fun `HARD_BLOCK and a spent daily budget both beat MIRROR`() {
        val withHard = engine.evaluate(
            "com.example.app",
            listOf(rule(BlockMode.MIRROR), rule(BlockMode.HARD_BLOCK, seconds = 0)),
            0L
        ) as BlockDecision.Block
        assertEquals(BlockMode.HARD_BLOCK, withHard.mode)

        val spent = engine.evaluate("com.example.app", listOf(rule(BlockMode.MIRROR, limit = 30)), 31L * 60_000L)
            as BlockDecision.Block
        assertEquals(BlockMode.HARD_BLOCK, spent.mode)
    }

    /**
     * Adding MIRROR to the tie-break must change no existing answer: DELAY and HOLD still win over
     * it, and it wins over BREATHING (it is a delay, the same price as DELAY at one duration).
     */
    @Test
    fun `MIRROR ties break after DELAY and HOLD and before BREATHING`() {
        fun winner(vararg modes: BlockMode) =
            (engine.evaluate("com.example.app", modes.map { rule(it) }, 0L) as BlockDecision.Block).mode

        assertEquals(BlockMode.DELAY, winner(BlockMode.MIRROR, BlockMode.DELAY))
        assertEquals(BlockMode.HOLD, winner(BlockMode.MIRROR, BlockMode.HOLD))
        assertEquals(BlockMode.MIRROR, winner(BlockMode.BREATHING, BlockMode.MIRROR))
    }

    @Test
    fun `every block mode name resolves as a website mode, MIRROR included`() {
        BlockMode.entries.forEach { mode ->
            assertEquals(mode, WebBlockMode.resolve(ruleMode = "HARD_BLOCK", webBlockMode = mode.name))
            assertEquals(mode, WebBlockMode.resolve(ruleMode = mode.name, webBlockMode = null))
        }
    }

    @Test
    fun `every feature override maps to a block mode and back, MIRROR included`() {
        assertEquals(BlockMode.MIRROR, FeatureMode.MIRROR.toBlockMode())
        assertEquals(FeatureMode.MIRROR, FeatureMode.fromBlockMode("MIRROR"))
        assertTrue(FeatureMode.MIRROR.usesDuration)
        FeatureMode.entries.filter { it != FeatureMode.INHERIT }.forEach { mode ->
            val blockMode = mode.toBlockMode()!!
            assertEquals(mode, FeatureMode.fromBlockMode(blockMode.name))
            assertEquals("$mode duration must match its block mode", blockMode.usesDuration, mode.usesDuration)
        }
    }

    /** Every gating mode except Hard block has an override, so no mode is app-level only. */
    @Test
    fun `every gating block mode is offered as a feature override`() {
        val offered = FeatureMode.entries.mapNotNull { it.toBlockMode() }.toSet()
        assertEquals(BlockMode.entries.filter { it != BlockMode.NONE }.toSet(), offered)
    }
}
