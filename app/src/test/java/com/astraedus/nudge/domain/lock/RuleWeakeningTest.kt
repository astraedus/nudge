package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RuleWeakening.isWeakening]. This decides which config edits demand the Strict Mode
 * challenge — getting "strengthening reads as weakening" wrong would gate harmless edits, and the
 * reverse would let users silently undo blocks, so each dimension is tested both directions.
 */
class RuleWeakeningTest {

    private fun rule(
        mode: String = "DELAY",
        delaySeconds: Int = 15,
        dailyLimitMinutes: Int? = null,
        enabled: Boolean = true
    ) = BlockRule(
        packageName = "com.example",
        mode = mode,
        delaySeconds = delaySeconds,
        dailyLimitMinutes = dailyLimitMinutes,
        enabled = enabled
    )

    // ── delay ──

    @Test
    fun `shorter delay is weakening`() {
        assertTrue(RuleWeakening.isWeakening(rule(delaySeconds = 30), rule(delaySeconds = 15)))
    }

    @Test
    fun `longer delay is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(delaySeconds = 15), rule(delaySeconds = 30)))
    }

    // ── mode ──

    @Test
    fun `mode HARD_BLOCK to DELAY is weakening`() {
        assertTrue(RuleWeakening.isWeakening(rule(mode = "HARD_BLOCK"), rule(mode = "DELAY")))
    }

    @Test
    fun `mode DELAY to HARD_BLOCK is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(mode = "DELAY"), rule(mode = "HARD_BLOCK")))
    }

    @Test
    fun `mode DELAY to BREATHING is weakening`() {
        assertTrue(RuleWeakening.isWeakening(rule(mode = "DELAY"), rule(mode = "BREATHING")))
    }

    @Test
    fun `mode BREATHING to DELAY is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(mode = "BREATHING"), rule(mode = "DELAY")))
    }

    // ── daily limit ──

    @Test
    fun `lower daily limit is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(dailyLimitMinutes = 60),
                rule(dailyLimitMinutes = 30)
            )
        )
    }

    @Test
    fun `higher daily limit is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(dailyLimitMinutes = 30),
                rule(dailyLimitMinutes = 60)
            )
        )
    }

    @Test
    fun `removing an existing daily limit is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(dailyLimitMinutes = 30),
                rule(dailyLimitMinutes = null)
            )
        )
    }

    @Test
    fun `adding a daily limit where none existed is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(dailyLimitMinutes = null),
                rule(dailyLimitMinutes = 30)
            )
        )
    }

    // ── enabled ──

    @Test
    fun `disabling is weakening`() {
        assertTrue(RuleWeakening.isWeakening(rule(enabled = true), rule(enabled = false)))
    }

    @Test
    fun `enabling is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(enabled = false), rule(enabled = true)))
    }

    // ── identity / mixed ──

    @Test
    fun `identical rule is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(), rule()))
    }

    @Test
    fun `weakening on one axis while strengthening another still counts as weakening`() {
        // Stronger mode (DELAY -> HARD_BLOCK) but shorter delay (30 -> 5).
        val old = rule(mode = "DELAY", delaySeconds = 30)
        val new = rule(mode = "HARD_BLOCK", delaySeconds = 5)
        assertTrue(RuleWeakening.isWeakening(old, new))
    }
}
