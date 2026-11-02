package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.astraedus.nudge.testing.anActiveRule

/**
 * ISSUE [#131](https://github.com/astraedus/nudge/issues/131): one tap on YouTube, one whole-app
 * Delay rule, TWO overlay launches 10-25ms apart and two Blocked rows.
 *
 * Two paths reached the launch for that tap: the foreground path, and the in-app feature detector
 * (which had just recognised Shorts, and which also matches whole-app rules). Each computed the
 * block's identity from what IT was asking about, so the detector's copy carried `feature=SHORTS`
 * and the foreground path's did not. The launch gate's duplicate check and the arrival's
 * "already counted" check both compare identities, so both saw two different blocks.
 *
 * These tests run the REAL [BlockEngine] and the REAL [BlockLaunchGate.identityOf], and ask the
 * question the gate asks: is it the same block? For the whole CLASS of whole-app rules (every
 * branch of the engine that can decide one), never one instance.
 */
class BlockIdentityTest {

    private val engine = BlockEngine(ScheduleEvaluator())
    private val youtube = "com.google.android.youtube"

    private fun wholeApp(mode: BlockMode, dailyLimitMinutes: Int? = null) = anActiveRule(
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = dailyLimitMinutes,
        enabled = true,
        ruleName = "whole app ${mode.name}"
    )

    private fun shortsRule(mode: BlockMode) = anActiveRule(
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        inAppFeatures = listOf("shorts"),
        ruleName = "shorts ${mode.name}"
    )

    /** The foreground path: no feature asked about. */
    private fun foreground(rules: List<ActiveRule>, usageMs: Long = 0L) =
        engine.evaluate(youtube, rules, usageMs) as BlockDecision.Block

    /** The feature detector's path, as `detectAndEvaluateFeature` calls it with no pass held. */
    private fun featurePath(rules: List<ActiveRule>, usageMs: Long = 0L) = engine.evaluate(
        youtube,
        rules,
        usageMs,
        detectedFeature = "shorts",
        includeWholeAppRulesForFeature = true
    ) as BlockDecision.Block

    private fun identity(decision: BlockDecision.Block) = BlockLaunchGate.identityOf(decision, youtube)

    /**
     * Every engine branch that can decide a block for a WHOLE-APP rule: the unconditional hard
     * block, the spent daily budget, and each timed mode.
     */
    private val wholeAppCases: List<Pair<String, Pair<List<ActiveRule>, Long>>> = listOf(
        "unconditional HARD_BLOCK" to (listOf(wholeApp(BlockMode.HARD_BLOCK)) to 0L),
        "spent daily budget" to (listOf(wholeApp(BlockMode.NONE, dailyLimitMinutes = 1)) to 120_000L),
        "DELAY" to (listOf(wholeApp(BlockMode.DELAY)) to 0L),
        "HOLD" to (listOf(wholeApp(BlockMode.HOLD)) to 0L),
        "BREATHING" to (listOf(wholeApp(BlockMode.BREATHING)) to 0L)
    )

    @Test
    fun `a whole-app rule is the same block whichever path surfaced it`() {
        for ((name, case) in wholeAppCases) {
            val (rules, usage) = case
            val viaForeground = foreground(rules, usage)
            val viaFeature = featurePath(rules, usage)
            assertNull("$name: a whole-app rule is about no feature", viaFeature.feature)
            assertEquals(
                "$name: the foreground path and the feature detector surfaced ONE rule for ONE " +
                    "tap; two identities is two launches and two Blocked rows (#131)",
                identity(viaForeground),
                identity(viaFeature)
            )
        }
    }

    /**
     * The other half, which must NOT collapse: a FEATURE rule is a different thing the user ran
     * into, and stays its own block and its own confrontation (the Willpower page shows it).
     */
    @Test
    fun `a feature rule keeps its feature and stays a different block from the whole app`() {
        for (mode in listOf(BlockMode.HARD_BLOCK, BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING)) {
            val feature = featurePath(listOf(shortsRule(mode)))
            assertEquals("$mode: the deciding rule names the feature", "shorts", feature.feature)

            val whole = foreground(listOf(wholeApp(mode)))
            assertNotEquals(
                "$mode: launch fingerprints",
                identity(whole).decisionKey,
                identity(feature).decisionKey
            )
            assertNotEquals(
                "$mode: confrontations",
                identity(whole).confrontationKey,
                identity(feature).confrontationKey
            )
        }
    }

    /**
     * When both kinds apply, the label follows the rule that WON. A Shorts hard block beats a
     * whole-app Delay, so the block is about Shorts; a whole-app hard block beats a Shorts Delay, so
     * it is the whole app's.
     */
    @Test
    fun `with both kinds applicable, the feature is the winning rule's`() {
        val shortsWins = featurePath(listOf(wholeApp(BlockMode.DELAY), shortsRule(BlockMode.HARD_BLOCK)))
        assertEquals(BlockMode.HARD_BLOCK, shortsWins.mode)
        assertEquals("shorts", shortsWins.feature)

        val wholeWins = featurePath(listOf(shortsRule(BlockMode.DELAY), wholeApp(BlockMode.HARD_BLOCK)))
        assertEquals(BlockMode.HARD_BLOCK, wholeWins.mode)
        assertNull(wholeWins.feature)
        assertEquals(identity(foreground(listOf(wholeApp(BlockMode.HARD_BLOCK)))), identity(wholeWins))
    }

    @Test
    fun `nuke is its own block, never a duplicate of a rule's hard block`() {
        val nuke = engine.evaluate(youtube, emptyList(), 0L, nuked = true) as BlockDecision.Block
        val ruleHardBlock = foreground(listOf(wholeApp(BlockMode.HARD_BLOCK)))
        assertNull(nuke.feature)
        assertNotEquals(identity(ruleHardBlock).decisionKey, identity(nuke).decisionKey)
        assertEquals(
            BlockLaunchGate.decisionFingerprint(youtube, BlockLaunchGate.NUKE_FINGERPRINT_MODE),
            identity(nuke).decisionKey
        )
    }

    /** The identity is spelled by the two older functions, so the existing gate tests still hold. */
    @Test
    fun `the identity is the fingerprint and confrontation key the gate already compares`() {
        val daily = BlockDecision.Block(BlockMode.HARD_BLOCK, dailyLimitMinutes = 30, feature = "shorts")
        val id = BlockLaunchGate.identityOf(daily, youtube, webDomain = "youtube.com")
        assertEquals(
            BlockLaunchGate.decisionFingerprint(
                youtube,
                "HARD_BLOCK",
                featureKey = "shorts",
                webDomain = "youtube.com",
                dailyLimited = true
            ),
            id.decisionKey
        )
        assertEquals(
            BlockLaunchGate.confrontationKey(youtube, featureKey = "shorts", webDomain = "youtube.com"),
            id.confrontationKey
        )
    }
}
