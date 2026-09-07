package com.astraedus.nudge.domain.block

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-kick cooldown is the only enforcement in Nudge that runs before a rule is read and
 * writes no `UsageEvent`. These four rows say it can never enforce on its own authority.
 */
class CooldownGateTest {

    @Test
    fun `a live cooldown on a package a rule still covers enforces`() {
        assertTrue(CooldownGate.shouldEnforce(hasRuleEntry = true, isInCooldown = true))
    }

    @Test
    fun `a cooldown on a package no rule covers does not enforce`() {
        // The bug shape: the user deletes the rule (or turns its auto-kick off) while the cooldown
        // timer is still running. The in-memory map outlives the rule, and acting on it puts a
        // block overlay over an app nothing is configured to block, leaving no trace in the stats.
        assertFalse(CooldownGate.shouldEnforce(hasRuleEntry = false, isInCooldown = true))
    }

    @Test
    fun `no cooldown never enforces, rule or not`() {
        assertFalse(CooldownGate.shouldEnforce(hasRuleEntry = true, isInCooldown = false))
        assertFalse(CooldownGate.shouldEnforce(hasRuleEntry = false, isInCooldown = false))
    }

    @Test
    fun `only a rule-less live cooldown is stale`() {
        assertTrue(CooldownGate.isStale(hasRuleEntry = false, isInCooldown = true))
        assertFalse(CooldownGate.isStale(hasRuleEntry = true, isInCooldown = true))
        assertFalse(CooldownGate.isStale(hasRuleEntry = false, isInCooldown = false))
        assertFalse(CooldownGate.isStale(hasRuleEntry = true, isInCooldown = false))
    }

    /**
     * The two functions must never both be true: a cooldown is either enforceable or stale, and a
     * caller that clears the stale one and then enforces it would be doing both.
     */
    @Test
    fun `enforce and stale are mutually exclusive across the whole table`() {
        listOf(true, false).forEach { hasRule ->
            listOf(true, false).forEach { inCooldown ->
                assertFalse(
                    "hasRule=$hasRule inCooldown=$inCooldown was both enforceable and stale",
                    CooldownGate.shouldEnforce(hasRule, inCooldown) &&
                        CooldownGate.isStale(hasRule, inCooldown)
                )
            }
        }
    }
}
