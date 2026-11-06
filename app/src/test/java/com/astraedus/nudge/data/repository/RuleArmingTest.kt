package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.testing.aBlockRule
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---- the editor's sentence (review should-fix 2) --------------------------------------

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val stamp: Long = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

    private fun firstUnlock(armedAt: Long, enabled: Boolean = true, minutes: Int = 30) =
        rule(armedAt = armedAt, enabled = enabled, firstUnlockMinutes = minutes)

    private fun startsTomorrow(previous: BlockRule?, next: BlockRule, nowMs: Long) =
        RuleArming.startsAtNextUnlock(previous, next, stamp, nowMs, 240, zone)

    @Test
    fun `a rule whose window is running now does not claim it starts at the next unlock`() {
        // The reviewer's case: an existing first-unlock rule, armed before today's unlock, opened
        // its window at 08:00 and is blocking at 08:10. Saving it keeps the stamp, so the sentence
        // would be a lie.
        // (The window itself is checked in FirstUnlockWindowTest; here the sentence is what matters.)
        val previous = firstUnlock(armedAt = stamp - 60_000L)
        val next = firstUnlock(armedAt = stamp - 60_000L)

        assertFalse(startsTomorrow(previous, next, nowMs = stamp + 10 * 60_000L))
    }

    @Test
    fun `a rule armed after today's unlock says it starts at the next one`() {
        val previous = firstUnlock(armedAt = stamp + 60_000L)

        assertTrue(startsTomorrow(previous, previous, nowMs = stamp + 10 * 60_000L))
    }

    @Test
    fun `a new first-unlock rule, and a newly first-unlock one, say it`() {
        assertTrue(
            "a rule created after today's unlock",
            startsTomorrow(previous = null, next = firstUnlock(armedAt = 0L), nowMs = stamp + 60_000L)
        )
        assertTrue(
            "a days-and-times rule switching to first unlock",
            startsTomorrow(
                previous = rule(armedAt = 0L),
                next = firstUnlock(armedAt = 0L),
                nowMs = stamp + 60_000L
            )
        )
    }

    @Test
    fun `with no unlock yet today nothing says it starts at the next one`() {
        val next = firstUnlock(armedAt = 0L)
        val yesterday = stamp - 24 * 60 * 60_000L

        assertFalse(startsTomorrow(null, next, stamp - 60 * 60_000L))
        assertFalse(
            "yesterday's stamp is not today's",
            RuleArming.startsAtNextUnlock(null, next, yesterday, stamp + 60_000L, 240, zone)
        )
    }

    @Test
    fun `a rule that is off or not first-unlock never says it`() {
        assertFalse(startsTomorrow(null, firstUnlock(armedAt = 0L, enabled = false), stamp + 60_000L))
        assertFalse(startsTomorrow(null, rule(armedAt = 0L), stamp + 60_000L))
    }

    @Test
    fun `carriedArmedAt keeps an enabled rule's stamp and arms a re-enabled one`() {
        assertEquals(
            4_000L,
            RuleArming.carriedArmedAt(
                firstUnlock(armedAt = 4_000L),
                firstUnlock(armedAt = 4_000L)
            )
        )
        assertEquals(
            "re-enabling re-arms, which is should-fix 1",
            RuleArming.NEVER_ARMED,
            RuleArming.carriedArmedAt(
                rule(armedAt = 4_000L, enabled = false),
                firstUnlock(armedAt = 4_000L)
            )
        )
        assertEquals(
            "a new rule lets the repository arm it",
            RuleArming.NEVER_ARMED,
            RuleArming.carriedArmedAt(null, firstUnlock(armedAt = 0L))
        )
    }
}
