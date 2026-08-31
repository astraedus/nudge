package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock that drives the time-based auto-kick.
 *
 * The case this file exists for is [one failing tick must not end the clock]. Both foreground clocks
 * were inline `while (isActive) { tick(); delay(30s) }` loops whose bodies reach a binder read and
 * the WindowManager. A single throw from anywhere outside `AutoKickTimeHandler`'s own try/catch left
 * the loop for good, silently — the user was simply never kicked again, and logcat showed exactly
 * what a healthy-but-waiting clock shows: nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundClockTest {

    private class RecordingLog : NudgeLog {
        val lines = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) { lines += "D $message" }
        override fun i(message: String, throwable: Throwable?) { lines += "I $message" }
        override fun w(message: String, throwable: Throwable?) { lines += "W $message" }
        override fun e(message: String, throwable: Throwable?) { lines += "E $message" }
    }

    private val interval = 30_000L

    /**
     * THE regression. Pre-fix this asserted 1 tick: the throw on tick 1 ended the loop and no clock
     * ever ran again for that session, which is indistinguishable from "still waiting" in logcat.
     */
    @Test
    fun `one failing tick must not end the clock`() = runTest {
        val log = RecordingLog()
        var ticks = 0
        val clock = ForegroundClock(this, interval, log, "test")

        clock.start("com.example.app") {
            ticks++
            if (ticks == 1) throw IllegalStateException("binder blew up")
        }

        advanceTimeBy(interval * 4 + 1)

        assertTrue("the clock must keep ticking after a failure, got $ticks", ticks >= 4)
        assertTrue(
            "the failure must be logged, not swallowed",
            log.lines.any { it.startsWith("W") && it.contains("tick failed") }
        )
        assertTrue("the clock is still alive", clock.isRunning)
        clock.stop("test over")
    }

    @Test
    fun `every tick failing still leaves the clock running`() = runTest {
        val log = RecordingLog()
        var ticks = 0
        val clock = ForegroundClock(this, interval, log, "test")

        clock.start("com.example.app") {
            ticks++
            throw RuntimeException("always")
        }
        advanceTimeBy(interval * 3 + 1)

        assertTrue(ticks >= 3)
        assertTrue(clock.isRunning)
        clock.stop("test over")
    }

    /** The baseline has to be taken at session start, not one interval in. */
    @Test
    fun `the first tick runs immediately`() = runTest {
        var ticks = 0
        val clock = ForegroundClock(this, interval, RecordingLog(), "test")

        clock.start("com.example.app") { ticks++ }
        advanceTimeBy(1)

        assertEquals(1, ticks)
        clock.stop("test over")
    }

    /**
     * Re-entered on every debounced event; restarting the job each time would keep resetting the
     * delay and the clock would never reach a second tick.
     */
    @Test
    fun `restarting for the same key leaves the running clock alone`() = runTest {
        var ticks = 0
        val clock = ForegroundClock(this, interval, RecordingLog(), "test")

        clock.start("com.example.app") { ticks++ }
        advanceTimeBy(1)
        repeat(50) { clock.start("com.example.app") { ticks++ } }
        advanceTimeBy(interval + 1)

        assertEquals("50 re-entries must not add 50 ticks", 2, ticks)
        clock.stop("test over")
    }

    @Test
    fun `a different key replaces the clock`() = runTest {
        val seen = mutableListOf<String>()
        val clock = ForegroundClock(this, interval, RecordingLog(), "test")

        clock.start("com.example.alpha") { seen += it }
        advanceTimeBy(1)
        clock.start("com.example.beta") { seen += it }
        advanceTimeBy(interval + 1)

        assertEquals("com.example.beta", clock.trackedKey)
        assertTrue(seen.contains("com.example.alpha"))
        assertTrue(seen.contains("com.example.beta"))
        clock.stop("test over")
    }

    @Test
    fun `stop ends the clock and clears the key`() = runTest {
        var ticks = 0
        val clock = ForegroundClock(this, interval, RecordingLog(), "test")

        clock.start("com.example.app") { ticks++ }
        advanceTimeBy(1)
        clock.stop("user left")
        val after = ticks
        advanceTimeBy(interval * 3)

        assertEquals(after, ticks)
        assertFalse(clock.isRunning)
        assertEquals(null, clock.trackedKey)
    }

    /**
     * "Why did it stop" was the question nobody could answer from logcat. Start, stop and the reason
     * are all logged unconditionally now.
     */
    @Test
    fun `every transition is logged with a reason`() = runTest {
        val log = RecordingLog()
        val clock = ForegroundClock(this, interval, log, "web")

        clock.start("web:instagram.com") { }
        advanceTimeBy(1)
        clock.stop("left the browser")
        advanceTimeBy(1)

        assertTrue(log.lines.any { it.contains("web clock started key=web:instagram.com") })
        assertTrue(log.lines.any { it.contains("web clock stopped") && it.contains("reason=left the browser") })
        assertTrue(log.lines.any { it.contains("web clock exited") })
    }

    /**
     * The shape that shipped, kept as a witness so the regression is not just described in prose.
     * This is what both clocks were: a bare loop whose body could throw. It ticks ONCE and is gone,
     * with nothing logged and nothing to restart it, and "gone" and "waiting" look identical from
     * logcat, which is why device QA could only report "baseline set once, then silence".
     */
    @Test
    fun `the old inline loop died on the first failing tick`() = runTest {
        // The real service runs its clocks on a SupervisorJob scope, which is what made the death
        // SILENT: the throwing child dies alone, the scope survives, and nothing anywhere notices.
        // The handler stands in for the platform's default: nothing in Nudge ever installed one, so
        // in production this throw went to the thread's uncaught handler and the app carried on with
        // a dead clock and no in-app trace of why.
        val swallow = CoroutineExceptionHandler { _, _ -> }
        val serviceScope =
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + swallow)
        var ticks = 0
        val job = serviceScope.launch {
            while (isActive) {
                ticks++
                if (ticks == 1) throw IllegalStateException("binder blew up")
                delay(interval)
            }
        }
        advanceTimeBy(interval * 4 + 1)

        assertEquals("the un-guarded loop stops for good after one throw", 1, ticks)
        assertFalse("...and it is dead, with nothing to restart it", job.isActive)

        // ...while the guarded clock, given the identical tick, keeps going.
        var guardedTicks = 0
        val clock = ForegroundClock(serviceScope, interval, RecordingLog(), "test")
        clock.start("com.example.app") {
            guardedTicks++
            if (guardedTicks == 1) throw IllegalStateException("binder blew up")
        }
        advanceTimeBy(interval * 4 + 1)
        assertTrue("the guarded loop survives it, got $guardedTicks", guardedTicks >= 4)
        clock.stop("test over")
        serviceScope.cancel()
    }

    @Test
    fun `stopping a clock that never started logs nothing and is safe`() = runTest {
        val log = RecordingLog()
        val clock = ForegroundClock(this, interval, log, "test")

        clock.stop("nothing to do")

        assertTrue(log.lines.isEmpty())
        assertFalse(clock.isRunning)
    }
}
