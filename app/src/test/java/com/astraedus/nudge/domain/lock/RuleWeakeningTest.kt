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
        enabled: Boolean = true,
        autoKickAfter: Int? = null,
        autoKickAfterMinutes: Int? = null,
        autoKickCooldownSeconds: Int = 60
    ) = BlockRule(
        packageName = "com.example",
        mode = mode,
        delaySeconds = delaySeconds,
        dailyLimitMinutes = dailyLimitMinutes,
        enabled = enabled,
        autoKickAfter = autoKickAfter,
        autoKickAfterMinutes = autoKickAfterMinutes,
        autoKickCooldownSeconds = autoKickCooldownSeconds
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

    // ── mode NONE (whole-app blocking switched off) ──
    // Turning off the "Block the whole app" switch writes NONE, which drops ALL gating of the app.
    // It is the largest weakening the config screen can produce and must never save unchallenged.

    @Test
    fun `mode BREATHING to NONE is weakening`() {
        assertTrue(RuleWeakening.isWeakening(rule(mode = "BREATHING"), rule(mode = "NONE")))
    }

    @Test
    fun `mode HARD_BLOCK to NONE is weakening`() {
        assertTrue(RuleWeakening.isWeakening(rule(mode = "HARD_BLOCK"), rule(mode = "NONE")))
    }

    @Test
    fun `mode NONE to DELAY is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(mode = "NONE"), rule(mode = "DELAY")))
    }

    @Test
    fun `mode NONE to NONE is not weakening`() {
        assertFalse(RuleWeakening.isWeakening(rule(mode = "NONE"), rule(mode = "NONE")))
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

    // ── auto-kick after (interaction count) ──

    @Test
    fun `raised autoKickAfter is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(autoKickAfter = 20),
                rule(autoKickAfter = 40)
            )
        )
    }

    @Test
    fun `lowered autoKickAfter is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickAfter = 40),
                rule(autoKickAfter = 20)
            )
        )
    }

    @Test
    fun `removing an existing autoKickAfter is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(autoKickAfter = 20),
                rule(autoKickAfter = null)
            )
        )
    }

    @Test
    fun `adding autoKickAfter where none existed is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickAfter = null),
                rule(autoKickAfter = 20)
            )
        )
    }

    @Test
    fun `autoKickAfter unset on both sides is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickAfter = null),
                rule(autoKickAfter = null)
            )
        )
    }

    // ── auto-kick after minutes (session time) ──

    @Test
    fun `raised autoKickAfterMinutes is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(autoKickAfterMinutes = 10),
                rule(autoKickAfterMinutes = 20)
            )
        )
    }

    @Test
    fun `lowered autoKickAfterMinutes is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickAfterMinutes = 20),
                rule(autoKickAfterMinutes = 10)
            )
        )
    }

    @Test
    fun `removing an existing autoKickAfterMinutes is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(autoKickAfterMinutes = 10),
                rule(autoKickAfterMinutes = null)
            )
        )
    }

    @Test
    fun `adding autoKickAfterMinutes where none existed is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickAfterMinutes = null),
                rule(autoKickAfterMinutes = 10)
            )
        )
    }

    @Test
    fun `autoKickAfterMinutes unset on both sides is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickAfterMinutes = null),
                rule(autoKickAfterMinutes = null)
            )
        )
    }

    // ── auto-kick cooldown ──

    @Test
    fun `lowered autoKickCooldownSeconds is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(autoKickCooldownSeconds = 60),
                rule(autoKickCooldownSeconds = 30)
            )
        )
    }

    @Test
    fun `raised autoKickCooldownSeconds is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickCooldownSeconds = 30),
                rule(autoKickCooldownSeconds = 60)
            )
        )
    }

    @Test
    fun `unchanged autoKickCooldownSeconds is not weakening`() {
        assertFalse(
            RuleWeakening.isWeakening(
                rule(autoKickCooldownSeconds = 60),
                rule(autoKickCooldownSeconds = 60)
            )
        )
    }

    @Test
    fun `lowering autoKickCooldownSeconds to zero is weakening`() {
        assertTrue(
            RuleWeakening.isWeakening(
                rule(autoKickCooldownSeconds = 60),
                rule(autoKickCooldownSeconds = 0)
            )
        )
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

    @Test
    fun `strengthening one auto-kick axis while weakening another still counts as weakening`() {
        // Lower autoKickAfter (60 -> 20, strengthening) but lower cooldown too (60 -> 10, weakening).
        val old = rule(autoKickAfter = 60, autoKickCooldownSeconds = 60)
        val new = rule(autoKickAfter = 20, autoKickCooldownSeconds = 10)
        assertTrue(RuleWeakening.isWeakening(old, new))
    }
}
