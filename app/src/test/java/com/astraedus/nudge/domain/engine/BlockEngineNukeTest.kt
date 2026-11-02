package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.testing.anActiveRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: Nuke OVERRIDES every existing mode and limit, rather than joining them as one more case.
 *
 * The truth table is over EVERY [BlockMode] (so a mode added later is covered without editing this
 * file), with and without a daily limit that is spent, with and without an in-app feature in play,
 * and with rules that would otherwise ALLOW. The assertion is the whole decision, compared by value:
 * a Nuke block that inherited a delay, a daily-limit label, grayscale or a tab cover from whatever
 * rule happened to be there would be a Nuke that is partly a rule.
 */
class BlockEngineNukeTest {

    private val engine = BlockEngine(ScheduleEvaluator())
    private val pkg = "com.instagram.android"
    private val spentMs = 61L * 60_000L

    /**
     * A user rule carrying everything a Nuke block must NOT inherit: a delay, grayscale, a name and
     * the tab cover. Each set is explicit on purpose, whatever the shipped defaults are.
     */
    private val userRule = anActiveRule(delaySeconds = 30, grayscale = true, ruleName = "User rule", tabVanish = true)

    /** Every rule shape the engine can see, including none at all. */
    private fun ruleSets(): List<List<ActiveRule>> {
        val sets = mutableListOf<List<ActiveRule>>(emptyList())
        BlockMode.entries.forEach { mode ->
            sets += listOf(userRule.copy(mode = mode))
            sets += listOf(userRule.copy(mode = mode, dailyLimitMinutes = 60))
            sets += listOf(userRule.copy(mode = mode, inAppFeatures = listOf("REELS")))
        }
        sets += BlockMode.entries.map { userRule.copy(mode = it) } // every mode at once
        sets += listOf(userRule.copy(mode = BlockMode.NONE, dailyLimitMinutes = 60)) // "don't block, cap at 60"
        return sets
    }

    @Test
    fun `nuked is a plain hard block, whatever rules and usage say`() {
        ruleSets().forEach { rules ->
            listOf(0L, spentMs).forEach { usage ->
                listOf(null, "REELS").forEach { feature ->
                    listOf(true, false).forEach { includeWholeApp ->
                        val decision = engine.evaluate(
                            packageName = pkg,
                            activeRules = rules,
                            dailyUsageMs = usage,
                            detectedFeature = feature,
                            includeWholeAppRulesForFeature = includeWholeApp,
                            nuked = true
                        )
                        assertEquals(
                            "rules=$rules usage=$usage feature=$feature whole=$includeWholeApp",
                            BlockEngine.NUKE_DECISION,
                            decision
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the nuke decision is a hard block with no delay, limit, grayscale or cover`() {
        val decision = BlockEngine.NUKE_DECISION
        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertEquals(0, decision.delaySeconds)
        assertTrue(decision.nuke)
        assertFalse(decision.grayscale)
        assertFalse(decision.tabVanish)
        assertEquals(null, decision.dailyLimitMinutes)
        assertEquals(null, decision.dailyTimeRemainingMs)
        assertEquals(BlockEngine.NUKE_RULE_NAME, decision.ruleName)
    }

    @Test
    fun `not nuked, the engine answers exactly as it did before Nuke existed`() {
        ruleSets().forEach { rules ->
            listOf(0L, spentMs).forEach { usage ->
                val withFlag = engine.evaluate(pkg, rules, usage, nuked = false)
                val without = engine.evaluate(pkg, rules, usage)
                assertEquals("rules=$rules usage=$usage", without, withFlag)
                if (withFlag is BlockDecision.Block) {
                    assertFalse("a rule block must never claim to be Nuke's", withFlag.nuke)
                }
            }
        }
    }

    @Test
    fun `a nuked app with no rules at all is still blocked`() {
        // The whole point of a separate list: an app nobody wrote a rule for.
        assertEquals(BlockDecision.Allow, engine.evaluate(pkg, emptyList(), 0L))
        assertEquals(BlockEngine.NUKE_DECISION, engine.evaluate(pkg, emptyList(), 0L, nuked = true))
    }
}
