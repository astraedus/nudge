package com.astraedus.nudge.data.repository

import com.astraedus.nudge.testing.aBlockRule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1. When `armedAt` moves on a write (spec 5.3, corrected after review).
 *
 * The promise has two halves and the first implementation only had one. A rule made or switched on
 * now starts at the NEXT first unlock, so the stamp must move on a create, an off-to-on switch and a
 * rule that newly becomes a first-unlock one. Every other write must keep the old stamp, because
 * moving it during a running window ends that window with no challenge: the reviewer's case was
 * making a Morning Lock stronger (30 to 60 minutes) and losing the morning.
 */
class RuleArmingTest {

    private val now = 1_000_000L

    private fun rule(
        armedAt: Long = RuleArming.NEVER_ARMED,
        enabled: Boolean = true,
        firstUnlockMinutes: Int? = null
    ) = aBlockRule(enabled = enabled, firstUnlockMinutes = firstUnlockMinutes, armedAt = armedAt)

    @Test
    fun `a new disabled rule is not armed`() {
        val armed = RuleArming.arm(rule(enabled = false), previous = null, nowMs = now)

        assertEquals(RuleArming.NEVER_ARMED, armed.armedAt)
    }

    @Test
    fun `a new enabled rule is armed now`() {
        val armed = RuleArming.arm(rule(), previous = null, nowMs = now)

        assertEquals(now, armed.armedAt)
    }

    @Test
    fun `a carried stamp survives a delete and re-add`() {
        // App config deletes the package's rules and re-inserts them, so the repository sees a new
        // row. A stamp the caller carried is what keeps a running window running.
        val armed = RuleArming.arm(rule(armedAt = 4_000L), previous = null, nowMs = now)

        assertEquals(4_000L, armed.armedAt)
    }

    @Test
    fun `switching a rule on arms it now`() {
        val armed = RuleArming.arm(
            rule(enabled = true, armedAt = 4_000L),
            previous = rule(enabled = false, armedAt = 4_000L),
            nowMs = now
        )

        assertEquals(now, armed.armedAt)
    }

    @Test
    fun `a rule that newly becomes a first-unlock rule is armed now`() {
        val armed = RuleArming.arm(
            rule(firstUnlockMinutes = 30, armedAt = 4_000L),
            previous = rule(firstUnlockMinutes = null, armedAt = 4_000L),
            nowMs = now
        )

        assertEquals(now, armed.armedAt)
    }

    @Test
    fun `an ordinary save keeps the stamp, including a stronger window`() {
        for (next in listOf(
            rule(firstUnlockMinutes = 60, armedAt = 4_000L),
            rule(firstUnlockMinutes = 30, armedAt = 4_000L),
            rule(firstUnlockMinutes = 30, armedAt = 4_000L, enabled = true)
        )) {
            val armed = RuleArming.arm(next, previous = rule(firstUnlockMinutes = 30, armedAt = 4_000L), nowMs = now)

            assertEquals("a save must not end a running window", 4_000L, armed.armedAt)
        }
    }

    @Test
    fun `a write that switches a rule off leaves its stamp alone`() {
        val armed = RuleArming.arm(
            rule(enabled = false, firstUnlockMinutes = 30, armedAt = 4_000L),
            previous = rule(firstUnlockMinutes = 30, armedAt = 4_000L),
            nowMs = now
        )

        assertEquals(4_000L, armed.armedAt)
    }
}
