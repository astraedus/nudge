package com.astraedus.nudge.domain.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhoneNudge]: session boundaries, the threshold schedule, deferral and the escalation.
 *
 * Every rule is about TIME and SEQUENCE, so time is a number the test moves and the scheduled delay
 * is captured instead of posted.
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

    private fun fire(canInterrupt: Boolean = true) = phoneNudge.onTimerFired(canInterrupt)

    private lateinit var phoneNudge: PhoneNudge

    private fun ready(config: PhoneNudge.Config = PhoneNudge.Config(enabled = true)): PhoneNudge {
        phoneNudge = nudge(config)
        phoneNudge.onUnlocked()
        return phoneNudge
    }

    @Test
    fun `no pause before the threshold, and the timer re-arms for the rest`() {
        ready()
        minutes(19)
        fire()

        assertTrue(pauses.isEmpty())
        assertEquals(1 * 60_000L, scheduledMs)
    }

    @Test
    fun `a pause at the threshold shows the real elapsed minutes`() {
        ready()
        minutes(20)
        fire()

        assertEquals(listOf(PhoneNudge.Pause(minutes = 20, seconds = 5)), pauses)
        assertEquals("next one is twenty minutes away", 20 * 60_000L, scheduledMs)
    }

    @Test
    fun `a late fire shows the real minutes, not the threshold`() {
        ready()
        minutes(23)
        fire()

        assertEquals(23, pauses.single().minutes)
    }

    @Test
    fun `the pause grows each threshold and caps at thirty seconds`() {
        ready()
        val seen = mutableListOf<PhoneNudge.Pause>()
        repeat(5) {
            minutes(20)
            fire()
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

    // ── deferral ──

    @Test
    fun `a call defers the pause without counting it`() {
        ready()
        minutes(20)
        fire(canInterrupt = false)

        assertTrue("nothing shown", pauses.isEmpty())
        assertEquals("retry in thirty seconds", PhoneNudge.DEFER_MS, scheduledMs)
        minutes(1)
        fire(canInterrupt = true)
        assertEquals(1, pauses.size)
        assertEquals(21, pauses.single().minutes)
    }

    @Test
    fun `a deferred pause does not advance the escalation`() {
        ready()
        minutes(20)
        fire(canInterrupt = false)
        minutes(1)
        fire(canInterrupt = true)

        assertEquals(5, pauses.single().seconds)
        assertEquals("next due is anchored to the shown pause", 20 * 60_000L, scheduledMs)
    }

    // ── session boundaries ──

    @Test
    fun `a blank inside the grace window continues the session`() {
        ready()
        minutes(10)
        phoneNudge.onScreenOff()
        minutes(1)
        phoneNudge.onUnlocked()

        assertTrue(phoneNudge.isActive)
        minutes(10)
        fire()
        assertEquals(1, pauses.size)
        assertEquals(20, pauses.single().minutes)
    }

    /** A long blank must never resume a sitting, however the session reached it (#136 review). */
    @Test
    fun `a sitting ends after the grace however it got there`() {
        ready()
        minutes(19)
        phoneNudge.onScreenOff()
        minutes(60)
        phoneNudge.onUnlocked()
        minutes(1)
        fire()
        assertTrue("the old 19 minutes must not carry over", pauses.isEmpty())
    }

    /**
     * An alarm or a notification wakes the lock screen and it goes dark again, with no unlock. The
     * gap runs from the first screen-off, so the wake must not restart the grace window.
     */
    @Test
    fun `a locked glance does not extend the grace`() {
        ready()
        minutes(19)
        phoneNudge.onScreenOff()
        minutes(60)
        phoneNudge.onScreenOff()
        minutes(1)
        phoneNudge.onUnlocked()
        minutes(1)
        fire()
        assertTrue("yesterday's sitting must not resume", pauses.isEmpty())
    }

    @Test
    fun `a blank inside the grace resumes the same sitting`() {
        ready()
        minutes(19)
        phoneNudge.onScreenOff()
        minutes(1)
        phoneNudge.onUnlocked()
        minutes(1)
        fire()
        assertEquals(1, pauses.size)
        assertEquals(20, pauses.single().minutes)
    }

    @Test
    fun `a blank longer than the grace starts a fresh session`() {
        ready()
        minutes(5)
        phoneNudge.onScreenOff()
        minutes(3)
        phoneNudge.onUnlocked()

        assertTrue(phoneNudge.isActive)
        minutes(10)
        fire()
        assertTrue("the old five minutes must not carry over", pauses.isEmpty())
        minutes(10)
        fire()
        assertEquals(PhoneNudge.Pause(20, 5), pauses.single())
    }

    @Test
    fun `the next unlock after a long blank starts a fresh session`() {
        ready()
        minutes(5)
        phoneNudge.onScreenOff()
        minutes(5)
        phoneNudge.onUnlocked()
        phoneNudge.onUnlocked()

        assertTrue(phoneNudge.isActive)
        minutes(20)
        fire()
        assertEquals(PhoneNudge.Pause(20, 5), pauses.single())
    }

    @Test
    fun `switched off, the session is not tracked and nothing fires`() {
        phoneNudge = nudge(PhoneNudge.Config(enabled = false))
        phoneNudge.onUnlocked()
        minutes(40)
        fire()

        assertFalse(phoneNudge.isActive)
        assertTrue(pauses.isEmpty())
        assertEquals(null, scheduledMs)
    }

    @Test
    fun `turning it off mid-session clears the session`() {
        ready()
        minutes(10)
        phoneNudge.setConfig(PhoneNudge.Config(enabled = false))

        assertFalse(phoneNudge.isActive)
        assertEquals(null, scheduledMs)
    }

    @Test
    fun `reset forgets the session and the escalation`() {
        ready()
        minutes(20)
        fire()
        phoneNudge.reset()

        assertFalse(phoneNudge.isActive)
        minutes(20)
        phoneNudge.onUnlocked()
        minutes(20)
        fire()
        assertEquals(PhoneNudge.Pause(20, 5), pauses.last())
    }

    // ── anchoring ──

    @Test
    fun `lowering the threshold after a pause does not fire a catch-up burst`() {
        ready(PhoneNudge.Config(enabled = true, thresholdMinutes = 60))
        minutes(60)
        fire()
        assertEquals(1, pauses.size)

        minutes(10)
        phoneNudge.setConfig(PhoneNudge.Config(enabled = true, thresholdMinutes = 10))
        assertEquals("one immediate pause, not six", 0L, scheduledMs)
        fire()
        assertEquals(2, pauses.size)
        assertEquals(70, pauses.last().minutes)

        minutes(1)
        fire()
        assertEquals("the next one is ten minutes after the shown pause", 2, pauses.size)
    }

    // ── copy and bounds ──

    @Test
    fun `the escalation doubles and caps`() {
        assertEquals(5, PhoneNudge.pauseSeconds(1, 5))
        assertEquals(10, PhoneNudge.pauseSeconds(2, 5))
        assertEquals(20, PhoneNudge.pauseSeconds(3, 5))
        assertEquals(30, PhoneNudge.pauseSeconds(4, 5))
        assertEquals(30, PhoneNudge.pauseSeconds(9, 5))
        assertEquals(10, PhoneNudge.pauseSeconds(1, 10))
        assertEquals(30, PhoneNudge.pauseSeconds(3, 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold outside the supported range is rejected`() {
        PhoneNudge.Config(enabled = true, thresholdMinutes = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pause outside the supported range is rejected`() {
        PhoneNudge.Config(enabled = true, firstPauseSeconds = 60)
    }
}
