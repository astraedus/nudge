package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "block only Shorts, leave YouTube alone" contract.
 *
 * Before [BlockMode.NONE] existed, the app config screen always wrote a *blocking* app-level rule
 * and feature overrides could only differ from it — so a feature-scoped rule whose host app stayed
 * open was not expressible at all, and users who wanted Shorts blocked got the whole of YouTube
 * blocked instead.
 *
 * NONE makes the app-level rule a carrier for the daily limit / counter / overlays without gating
 * the app. These tests pin both halves: it must block nothing on its own, and it must still honour
 * a daily budget.
 */
class BlockEngineNoneModeTest {

    private val engine = BlockEngine(ScheduleEvaluator())
    private val pkg = "com.google.android.youtube"

    private fun noneAppRule(dailyLimitMinutes: Int? = null) = ActiveRule(
        mode = BlockMode.NONE,
        delaySeconds = 15,
        dailyLimitMinutes = dailyLimitMinutes,
        enabled = true,
        inAppFeatures = null,
        grayscale = false,
        ruleName = "No block"
    )

    private fun shortsRule(mode: BlockMode = BlockMode.DELAY) = ActiveRule(
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        inAppFeatures = listOf("SHORTS"),
        grayscale = false,
        ruleName = "Shorts - Delay"
    )

    @Test
    fun `opening the app itself is allowed when the app-level rule is NONE`() {
        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule(), shortsRule()),
            dailyUsageMs = 0L,
            detectedFeature = null
        )

        assertEquals(BlockDecision.Allow, decision)
    }

    @Test
    fun `the feature still blocks while the host app stays open`() {
        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule(), shortsRule()),
            dailyUsageMs = 0L,
            detectedFeature = "SHORTS"
        )

        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
    }

    /** A NONE rule on its own is inert — no feature rule, nothing to fall back to. */
    @Test
    fun `a lone NONE rule never blocks`() {
        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule()),
            dailyUsageMs = 0L,
            detectedFeature = null
        )

        assertEquals(BlockDecision.Allow, decision)
    }

    /** NONE must not be treated as a blocking mode just because a feature was detected. */
    @Test
    fun `a lone NONE rule never blocks even when a feature is detected`() {
        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule()),
            dailyUsageMs = 0L,
            detectedFeature = "SHORTS"
        )

        assertEquals(BlockDecision.Allow, decision)
    }

    /**
     * "Don't gate this app, but stop me after 30 minutes." The time-budget check keys off
     * dailyLimitMinutes rather than the mode, so a NONE rule still enforces its cap.
     */
    @Test
    fun `a NONE rule still enforces its daily limit once exhausted`() {
        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule(dailyLimitMinutes = 30)),
            dailyUsageMs = 31L * 60L * 1000L,
            detectedFeature = null
        )

        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `a NONE rule under its daily limit still allows`() {
        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule(dailyLimitMinutes = 30)),
            dailyUsageMs = 10L * 60L * 1000L,
            detectedFeature = null
        )

        assertEquals(BlockDecision.Allow, decision)
    }

    /**
     * A NONE app rule must not soften a genuine block coming from another rule on the same
     * package — priority is unchanged, NONE simply never wins a branch.
     */
    @Test
    fun `a NONE rule does not suppress a sibling hard block`() {
        val hardBlock = ActiveRule(
            mode = BlockMode.HARD_BLOCK,
            delaySeconds = 15,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = null,
            grayscale = false,
            ruleName = "Hard Block"
        )

        val decision = engine.evaluate(
            packageName = pkg,
            activeRules = listOf(noneAppRule(), hardBlock),
            dailyUsageMs = 0L,
            detectedFeature = null
        )

        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }
}
