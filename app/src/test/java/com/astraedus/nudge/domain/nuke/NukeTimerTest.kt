package com.astraedus.nudge.domain.nuke

import com.astraedus.nudge.testing.FakeNukeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: the timed Nuke's clock model (#184). Phone-on time on the monotonic clock, one segment per
 * boot, checkpoints that bank time. The property every case protects: the timer can run LONGER than
 * asked when the phone loses track, never shorter, and never longer than its full duration from a
 * boot onwards.
 */
class NukeTimerTest {

    private val minute = 60_000L
    private val clock = FakeNukeClock()

    private fun started(minutes: Int = 30): NukeTimer = NukeTimer.start(minutes, clock.now())!!

    // --- starting --------------------------------------------------------------------------------

    @Test
    fun `no timer and a value off the list both start nothing`() {
        assertNull(NukeTimer.start(0, clock.now()))
        assertNull(NukeTimer.start(-15, clock.now()))
        assertNull(NukeTimer.start(17, clock.now()))
        assertNull(NukeTimer.start(24 * 60, clock.now()))
    }

    @Test
    fun `every offered choice starts a timer of exactly that length, all of it left`() {
        NukeTimer.CHOICES_MINUTES.filter { it > 0 }.forEach { minutes ->
            val timer = NukeTimer.start(minutes, clock.now())!!
            assertEquals(minutes * minute, timer.durationMs)
            assertEquals(minutes * minute, timer.remainingMs(clock.now()))
            assertTrue("every choice is under the stored cap", timer.durationMs <= NukeTimer.MAX_DURATION_MS)
        }
    }

    // --- the same boot ---------------------------------------------------------------------------

    @Test
    fun `phone-on time counts down, and stops at zero`() {
        val timer = started(30)
        clock.advance(10 * minute)
        assertEquals(20 * minute, timer.remainingMs(clock.now()))
        clock.advance(20 * minute)
        assertEquals(0, timer.remainingMs(clock.now()))
        clock.advance(5 * minute)
        assertEquals("never negative", 0, timer.remainingMs(clock.now()))
    }

    @Test
    fun `a monotonic clock that somehow went backwards counts nothing, never extra`() {
        val timer = started(30)
        clock.advance(-10 * minute)
        assertEquals(30 * minute, timer.remainingMs(clock.now()))
    }

    @Test
    fun `a checkpoint banks the time so far and changes nothing about what is left`() {
        val timer = started(30)
        clock.advance(12 * minute)
        val banked = timer.checkpoint(clock.now())
        assertEquals(12 * minute, banked.consumedMs)
        assertEquals(timer.remainingMs(clock.now()), banked.remainingMs(clock.now()))
        clock.advance(3 * minute)
        assertEquals(15 * minute, banked.remainingMs(clock.now()))
    }

    // --- reboots ---------------------------------------------------------------------------------

    @Test
    fun `a reboot keeps the banked time, and the new boot's uptime counts`() {
        val timer = started(30)
        clock.advance(10 * minute)
        val banked = timer.checkpoint(clock.now())
        clock.reboot(uptimeMs = 2 * minute)
        assertEquals(18 * minute, banked.remainingMs(clock.now()))
    }

    @Test
    fun `a reboot never ends the timer early, even when the new uptime looks like progress`() {
        // Started late in a long uptime; the phone then reboots and has been up only briefly. Read
        // as the same boot, "now - start" would be hugely negative or, after a long new uptime,
        // a large positive number. Neither may count: only the new boot's own uptime does.
        clock.elapsedMs = 50 * 60 * minute
        val timer = started(60)
        clock.reboot(uptimeMs = 5 * minute)
        assertEquals(55 * minute, timer.remainingMs(clock.now()))
        clock.advance(30 * minute)
        assertEquals(25 * minute, timer.remainingMs(clock.now()))
    }

    @Test
    fun `time since the last checkpoint is lost on a power cut, so the timer only runs longer`() {
        val timer = started(30)
        val banked = timer.checkpoint(clock.now()) // banked 0
        clock.advance(20 * minute) // 20 minutes with no checkpoint, then the battery dies
        clock.reboot(uptimeMs = minute)
        val left = banked.remainingMs(clock.now())
        assertEquals(29 * minute, left)
        assertTrue("longer than the 9 minutes real time would say, never shorter", left >= 9 * minute)
    }

    @Test
    fun `remaining is never more than the duration, whatever the readings`() {
        val timer = started(15)
        listOf(
            NukeClockReading(0, 7), NukeClockReading(-5, 7), NukeClockReading(0, 8),
            NukeClockReading(Long.MAX_VALUE / 2, 9), NukeClockReading(3, null)
        ).forEach { reading ->
            val left = timer.remainingMs(reading)
            assertTrue("$reading gave $left", left in 0..timer.durationMs)
        }
    }

    @Test
    fun `without a boot count, a clock behind the segment start is read as a reboot`() {
        clock.bootCount = null
        clock.elapsedMs = 40 * minute
        val timer = started(30)
        clock.elapsedMs = 4 * minute // behind the start: this must be a new boot
        assertEquals(26 * minute, timer.remainingMs(clock.now()))
    }

    @Test
    fun `without a boot count, a clock ahead is read as the same boot, which can only under-count`() {
        clock.bootCount = null
        clock.elapsedMs = 2 * minute
        val timer = started(30)
        clock.elapsedMs = 12 * minute
        assertEquals(20 * minute, timer.remainingMs(clock.now()))
    }

    @Test
    fun `a checkpoint after a reboot moves the segment to the new boot`() {
        val timer = started(30).checkpoint(clock.now())
        clock.advance(5 * minute)
        val first = timer.checkpoint(clock.now())
        clock.reboot(uptimeMs = 3 * minute)
        val second = first.checkpoint(clock.now())
        assertEquals(8 * minute, second.consumedMs)
        assertEquals(clock.bootCount, second.bootCount)
        clock.advance(2 * minute)
        assertEquals(20 * minute, second.remainingMs(clock.now()))
    }

    // --- stored values ---------------------------------------------------------------------------

    @Test
    fun `a stored timer that makes no sense reads as no timer, never as a trap`() {
        assertNull(NukeTimer.fromStored(null, 0, 7, 0))
        assertNull(NukeTimer.fromStored(0, 0, 7, 0))
        assertNull(NukeTimer.fromStored(-1, 0, 7, 0))
        assertNull("days long is a corrupt value", NukeTimer.fromStored(NukeTimer.MAX_DURATION_MS + 1, 0, 7, 0))
        assertNull(NukeTimer.fromStored(30 * minute, 0, 7, null))
        assertNull(NukeTimer.fromStored(30 * minute, 0, 7, -1))
    }

    @Test
    fun `a stored consumed value out of range is clamped`() {
        assertEquals(0, NukeTimer.fromStored(30 * minute, -5, 7, 0)!!.consumedMs)
        assertEquals(30 * minute, NukeTimer.fromStored(30 * minute, Long.MAX_VALUE, 7, 0)!!.consumedMs)
        assertEquals(0, NukeTimer.fromStored(30 * minute, null, null, 0)!!.consumedMs)
    }

    @Test
    fun `a stored picker choice off the list is no timer`() {
        NukeTimer.CHOICES_MINUTES.forEach { assertEquals(it, NukeTimer.choiceOrNone(it)) }
        assertEquals(0, NukeTimer.choiceOrNone(null))
        assertEquals(0, NukeTimer.choiceOrNone(7))
        assertEquals(0, NukeTimer.choiceOrNone(-60))
    }

    // --- the policy ------------------------------------------------------------------------------

    @Test
    fun `the key waits only while Nuke is on with time left`() {
        val timer = started(15)
        val on = NukeState(active = true, packages = setOf("a"), keyHash = "h", timer = timer)
        assertEquals(15 * minute, NukePolicy.keyWaitMs(on, clock.now()))
        assertEquals("off: nothing to wait for", 0, NukePolicy.keyWaitMs(on.copy(active = false), clock.now()))
        assertEquals("no timer: no wait", 0, NukePolicy.keyWaitMs(on.copy(timer = null), clock.now()))
        clock.advance(15 * minute)
        assertEquals("run out: no wait", 0, NukePolicy.keyWaitMs(on, clock.now()))
    }
}
