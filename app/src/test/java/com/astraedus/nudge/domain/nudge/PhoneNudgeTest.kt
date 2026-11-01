package com.astraedus.nudge.domain.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhoneNudge]: session boundaries, the grace window, the threshold schedule and the escalation.
 *
 * Every rule here is about TIME and SEQUENCE, so time is a number the test moves and the scheduled
 * delay is captured instead of posted.
 */
class PhoneNudgeTest {

    private var now = 1_000_000L
    private val pauses = mutableListOf<PhoneNudge.Pause>()
    private var scheduledMs: Long? = null

    private fun nudge(config: PhoneNudge.Config = PhoneNudge.Config(enabled = true)) = PhoneNudge(
        clock = { now },
        onPause = { pauses += it },
        schedule = { scheduledMs = it },
        cancelSchedule = { scheduledMs = null }
    ).also { it.setConfig(config) }

    private fun minutes(value: Int) {
        now += value * 60_000L
    }

    @Test
    fun `no pause before the threshold, and the timer re-arms for the rest`() {
        val n = nudge()
        n.onUnlocked()
        minutes(19)
        n.onTimerFired()

        assertTrue(pauses.isEmpty())
        assertEquals(1 * 60_000L, scheduledMs)
    }

    @Test
    fun `a pause at the threshold with the default length`() {
        val n = nudge()
        n.onUnlocked()
        minutes(20)
        n.onTimerFired()

        assertEquals(listOf(PhoneNudge.Pause(minutes = 20, seconds = 5)), pauses)
        assertEquals("next one is twenty minutes away", 20 * 60_000L, scheduledMs)
    }

    @Test
    fun `the pause grows each threshold and caps at thirty seconds`() {
        val n = nudge()
        n.onUnlocked()

        val seen = mutableListOf<PhoneNudge.Pause>()
        repeat(5) {
            minutes(20)
            n.onTimerFired()
            seen += pauses.last()
        }

        assertEquals(
            listOf(
                PhoneNudge.Pause(20, 5),
                PhoneNudge.Pause(40, 10),
                PhoneNudge.Pause(60, 20),
                PhoneNudge.Pause(80, 30),
                PhoneNudge.Pause(100, 30)
            ),
            seen
        )
    }

    @Test
    fun `a blank inside the grace window continues the session`() {
        val n = nudge()
        n.onUnlocked()
        minutes(10)
        n.onScreenOff()
        minutes(1)
        n.onScreenOn()

        assertTrue(n.isActive)
        minutes(10)
        n.onTimerFired()
        assertEquals(1, pauses.size)
        assertEquals(20, pauses.single().minutes)
    }

    @Test
    fun `a blank longer than the grace ends the session and nothing fires`() {
        val n = nudge()
        n.onUnlocked()
        minutes(5)
        n.onScreenOff()
        minutes(3)
        n.onScreenOn()

        assertFalse(n.isActive)
        minutes(40)
        n.onTimerFired()
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun `the next unlock after a long blank starts a fresh session`() {
        val n = nudge()
        n.onUnlocked()
        minutes(5)
        n.onScreenOff()
        minutes(5)
        n.onScreenOn()
        n.onUnlocked()

        assertTrue(n.isActive)
        minutes(20)
        n.onTimerFired()
        assertEquals(PhoneNudge.Pause(20, 5), pauses.single())
    }

    @Test
    fun `switched off, the session is not tracked and nothing fires`() {
        val n = nudge(PhoneNudge.Config(enabled = false))
        n.onUnlocked()
        minutes(40)
        n.onTimerFired()

        assertFalse(n.isActive)
        assertTrue(pauses.isEmpty())
        assertEquals(null, scheduledMs)
    }

    @Test
    fun `turning it off mid-session clears the session`() {
        val n = nudge()
        n.onUnlocked()
        minutes(10)
        n.setConfig(PhoneNudge.Config(enabled = false))

        assertFalse(n.isActive)
        assertEquals(null, scheduledMs)
    }

    @Test
    fun `changing the threshold re-arms the current session`() {
        val n = nudge()
        n.onUnlocked()
        minutes(10)
        n.setConfig(PhoneNudge.Config(enabled = true, thresholdMinutes = 30))

        assertEquals(20 * 60_000L, scheduledMs)
    }

    @Test
    fun `reset forgets the session and the escalation`() {
        val n = nudge()
        n.onUnlocked()
        minutes(20)
        n.onTimerFired()
        n.reset()

        assertFalse(n.isActive)
        minutes(20)
        n.onUnlocked()
        minutes(20)
        n.onTimerFired()
        assertEquals(PhoneNudge.Pause(20, 5), pauses.last())
    }

    @Test
    fun `the escalation doubles and caps`() {
        assertEquals(5, PhoneNudge.pauseSeconds(1, 5))
        assertEquals(10, PhoneNudge.pauseSeconds(2, 5))
        assertEquals(20, PhoneNudge.pauseSeconds(3, 5))
        assertEquals(30, PhoneNudge.pauseSeconds(4, 5))
        assertEquals(30, PhoneNudge.pauseSeconds(9, 5))
        assertEquals(5, PhoneNudge.pauseSeconds(1, 5))
        assertEquals(30, PhoneNudge.pauseSeconds(4, 15))
        assertEquals(60, PhoneNudge.pauseSeconds(1, 60))
        assertEquals(60, PhoneNudge.pauseSeconds(3, 60))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold outside the supported range is rejected`() {
        PhoneNudge.Config(enabled = true, thresholdMinutes = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pause outside the supported range is rejected`() {
        PhoneNudge.Config(enabled = true, firstPauseSeconds = 90)
    }
}
