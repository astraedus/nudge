package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.testing.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3 (pure lifecycle decision): [ScheduleBoundaryWatch], the one timer to the next schedule boundary
 * (#169). A fake scheduler and an injected clock; the boundary list is a hand-set queue here, and
 * the real `ScheduleBoundaries` over real rules is `ScheduleBoundaryTriggerPathTest`'s job.
 */
class ScheduleBoundaryWatchTest {

    private val clock = TestClock()
    private val scheduler = FakeBoundaryScheduler(clock)
    private val boundaries = ArrayDeque<Long>()
    private var ready = true
    private var rechecks = 0
    private val lines = mutableListOf<String>()

    private val log = object : NudgeLog {
        override fun d(message: String, throwable: Throwable?) { lines += message }
        override fun i(message: String, throwable: Throwable?) { lines += message }
        override fun w(message: String, throwable: Throwable?) { lines += message }
        override fun e(message: String, throwable: Throwable?) { lines += message }
    }

    private val watch = ScheduleBoundaryWatch(
        scheduler = scheduler,
        wallClock = clock.asSource(),
        // The earliest queued boundary strictly after now, like ScheduleBoundaries.next.
        nextBoundary = { now -> boundaries.firstOrNull { it > now } },
        deviceReady = { ready },
        onBoundary = { rechecks++ },
        logger = log
    )

    private val t0 = clock.now
    private val minute = 60_000L

    @Test
    fun `one post for the whole wait to the next boundary, not a tick`() {
        boundaries += t0 + 37 * minute
        watch.rearm("rules")
        assertEquals(1, scheduler.posts)
        assertEquals(37 * minute, scheduler.pendingDelayMs)
        assertEquals(t0 + 37 * minute, watch.targetMs)
        assertTrue(lines.any { it == "schedule boundary armed at=${t0 + 37 * minute} inMs=${37 * minute} reason=rules" })
    }

    /** #63: rules with nothing on a clock post NO timer at all. */
    @Test
    fun `no boundary posts nothing`() {
        watch.rearm("rules")
        assertEquals(0, scheduler.posts)
        assertNull(scheduler.pendingDelayMs)
        assertNull(watch.targetMs)
    }

    @Test
    fun `at the boundary the app in front is re-checked and the next boundary armed`() {
        boundaries += listOf(t0 + 10 * minute, t0 + 70 * minute)
        watch.rearm("rules")
        scheduler.elapse()
        assertEquals(1, rechecks)
        assertEquals(60 * minute, scheduler.pendingDelayMs)
        assertEquals(2, scheduler.posts)
    }

    @Test
    fun `after the last boundary nothing stays armed`() {
        boundaries += t0 + 10 * minute
        watch.rearm("rules")
        scheduler.elapse()
        assertEquals(1, rechecks)
        assertNull(scheduler.pendingDelayMs)
    }

    @Test
    fun `a rearm replaces the earlier timer, never adds one`() {
        boundaries += t0 + 10 * minute
        watch.rearm("rules")
        boundaries.addFirst(t0 + 5 * minute)
        watch.rearm("rules")
        assertEquals(5 * minute, scheduler.pendingDelayMs)
        scheduler.elapse()
        assertEquals(1, rechecks)
    }

    /** `delay` runs on uptime; a clock set back while waiting makes the task early on the wall clock. */
    @Test
    fun `a fire before the wall-clock target re-arms and re-checks nothing`() {
        boundaries += t0 + 10 * minute
        watch.rearm("rules")
        clock.advanceBy(4 * minute)
        scheduler.runNow()
        assertEquals(0, rechecks)
        assertEquals(6 * minute, scheduler.pendingDelayMs)
    }

    @Test
    fun `a boundary crossed behind the lock screen waits for the unlock`() {
        boundaries += t0 + 10 * minute
        watch.rearm("rules")
        ready = false
        scheduler.elapse()
        assertEquals(0, rechecks)
        assertTrue(watch.pendingRecheck)
        ready = true
        watch.onUnlocked()
        assertEquals(1, rechecks)
        assertFalse(watch.pendingRecheck)
        // And only once: a second unlock with nothing crossed does nothing.
        watch.onUnlocked()
        assertEquals(1, rechecks)
    }

    /** Doze: the Handler did not run while the screen was off, so screen-on catches it up. */
    @Test
    fun `screen on after sleeping past the boundary crosses it`() {
        boundaries += listOf(t0 + 10 * minute, t0 + 600 * minute)
        watch.rearm("rules")
        clock.advanceBy(90 * minute) // asleep; the posted task never ran
        watch.onScreenOn()
        assertEquals(1, rechecks)
        assertEquals((600 - 90) * minute, scheduler.pendingDelayMs)
    }

    @Test
    fun `screen on over the lock screen after the boundary re-checks at the unlock`() {
        boundaries += t0 + 10 * minute
        watch.rearm("rules")
        clock.advanceBy(30 * minute)
        ready = false
        watch.onScreenOn()
        assertEquals(0, rechecks)
        ready = true
        watch.onUnlocked()
        assertEquals(1, rechecks)
    }

    @Test
    fun `screen on before the boundary re-arms from the wall clock and re-checks nothing`() {
        boundaries += t0 + 100 * minute
        watch.rearm("rules")
        clock.advanceBy(40 * minute)
        watch.onScreenOn()
        assertEquals(0, rechecks)
        assertEquals(60 * minute, scheduler.pendingDelayMs)
    }

    @Test
    fun `a clock, timezone or date change re-checks now and re-arms from the new time`() {
        boundaries += listOf(t0 + 10 * minute, t0 + 300 * minute)
        watch.rearm("rules")
        clock.advanceBy(120 * minute) // the user set the clock forward
        watch.onClockChanged("time_set")
        assertEquals(1, rechecks)
        assertEquals(180 * minute, scheduler.pendingDelayMs)
    }

    @Test
    fun `cancel leaves nothing posted and nothing pending`() {
        boundaries += t0 + 10 * minute
        watch.rearm("rules")
        ready = false
        clock.advanceBy(20 * minute)
        watch.onScreenOn()
        assertTrue(watch.pendingRecheck)
        watch.cancel()
        assertNull(scheduler.pendingDelayMs)
        assertNull(watch.targetMs)
        assertFalse(watch.pendingRecheck)
        ready = true
        watch.onUnlocked()
        assertEquals(0, rechecks)
    }

    // -- recheckTarget: which app, if any, the re-check looks at --

    @Test
    fun `the re-check looks at the app in front`() {
        assertEquals("com.instagram.android", ScheduleBoundaryWatch.recheckTarget(true, false, "com.instagram.android", OWN))
    }

    @Test
    fun `the re-check looks at nothing when Nudge is off, a block is up, the window is unreadable or our own`() {
        assertNull(ScheduleBoundaryWatch.recheckTarget(false, false, "com.instagram.android", OWN))
        assertNull(ScheduleBoundaryWatch.recheckTarget(true, true, "com.instagram.android", OWN))
        assertNull(ScheduleBoundaryWatch.recheckTarget(true, false, null, OWN))
        assertNull(ScheduleBoundaryWatch.recheckTarget(true, false, "", OWN))
        assertNull(ScheduleBoundaryWatch.recheckTarget(true, false, OWN, OWN))
    }

    private companion object {
        /** The runtime package (`applicationId`), which is what `Context.packageName` returns. */
        val OWN: String = com.astraedus.nudge.BuildConfig.APPLICATION_ID
    }
}
