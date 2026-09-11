package com.astraedus.nudge.ui.widget

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NudgeWidgetUpdater` itself is NOT JVM-testable: it calls into Glance and needs a real
 * `Context` and a real `AppWidgetManager`, neither of which exist on the JVM and this project
 * deliberately has no `glance-appwidget-testing` dependency (see `app/build.gradle.kts`).
 *
 * What IS testable, and what this class actually exercises, is [WidgetRefreshDebouncer] — the
 * pure coalescing rule behind the updater, driven here with an explicit `nowMs` rather than a
 * real or faked clock.
 */
class NudgeWidgetUpdaterTest {

    private val windowMs = 5_000L

    // ------------------------------------------------------------------------ first call

    @Test
    fun `the very first tryAcquire always returns true whatever the clock reads`() {
        assertTrue(WidgetRefreshDebouncer(windowMs).tryAcquire(0L))
        assertTrue(WidgetRefreshDebouncer(windowMs).tryAcquire(Long.MAX_VALUE))
        assertTrue(WidgetRefreshDebouncer(windowMs).tryAcquire(1_000_000L))
        // Even a nowMs that coincides with the sentinel NEVER value must succeed: the first call
        // short-circuits on "no prior run recorded", it never compares against nowMs at all.
        assertTrue(WidgetRefreshDebouncer(windowMs).tryAcquire(Long.MIN_VALUE))
    }

    // ------------------------------------------------------------------------ inside the window

    @Test
    fun `a second call inside the window returns false`() {
        val debouncer = WidgetRefreshDebouncer(windowMs)

        assertTrue(debouncer.tryAcquire(0L))
        assertFalse(debouncer.tryAcquire(windowMs - 1))
    }

    @Test
    fun `a call exactly at first plus windowMs succeeds because the comparison is strict less-than`() {
        val debouncer = WidgetRefreshDebouncer(windowMs)

        assertTrue(debouncer.tryAcquire(0L))
        // Source: `if (last != NEVER && nowMs - last < windowMs) return false`. At nowMs == last +
        // windowMs the difference equals windowMs exactly, which is NOT `< windowMs`, so this must
        // succeed rather than be coalesced away.
        assertTrue(debouncer.tryAcquire(windowMs))
    }

    @Test
    fun `a burst of calls inside one window coalesces to exactly one true`() {
        val debouncer = WidgetRefreshDebouncer(windowMs)

        val results = listOf(0L, 100L, 500L, 1_000L, windowMs - 1).map { debouncer.tryAcquire(it) }

        assertEquals(1, results.count { it })
        assertTrue("the very first call in the burst must be the one that wins", results.first())
    }

    // ------------------------------------------------------------------------ window restarts

    @Test
    fun `after a successful acquire the window restarts from that acquire, not the first-ever call`() {
        val debouncer = WidgetRefreshDebouncer(windowMs)

        assertTrue(debouncer.tryAcquire(0L))
        // Exactly at the boundary from the first call — succeeds, and becomes the new anchor.
        assertTrue(debouncer.tryAcquire(windowMs))
        // A call just after the second acquire must be rejected because it is inside a window
        // measured from t = windowMs (diff = 1). If the anchor had wrongly stayed at the very
        // first call (t = 0), the diff from there would be windowMs + 1 — not `< windowMs` — and
        // this call would incorrectly succeed. Its rejection is the proof the anchor moved.
        assertFalse(debouncer.tryAcquire(windowMs + 1))
    }

    // ------------------------------------------------------------------------ rejected calls don't extend

    @Test
    fun `a rejected call does not extend the window`() {
        // acquire at 0, reject at 5000 (inside the 10000ms window), then acquire at 10000 must
        // still succeed — the window is still anchored at 0, not silently pushed forward by the
        // rejected call at 5000.
        val debouncer = WidgetRefreshDebouncer(windowMs = 10_000L)

        assertTrue(debouncer.tryAcquire(0L))
        assertFalse(debouncer.tryAcquire(5_000L))
        assertTrue(debouncer.tryAcquire(10_000L))
    }

    // ------------------------------------------------------------------------ backwards clock jump

    @Test
    fun `a backwards clock jump does not lock the debouncer out forever`() {
        val debouncer = WidgetRefreshDebouncer(windowMs)

        assertTrue(debouncer.tryAcquire(10_000L))
        // The source computes `nowMs - last` with no clamping: a backward jump produces a negative
        // difference, which is still `< windowMs`, so it reads exactly like "too soon" and is
        // rejected — the debouncer has no notion of "that timestamp is impossible, ignore it".
        assertFalse(
            "a backward jump is indistinguishable from 'too soon' under plain subtraction",
            debouncer.tryAcquire(1_000L)
        )
        // But it is not a permanent lockout: because the rejected call never updated `last`, the
        // instant nowMs resumes advancing (as a real monotonic clock always does) past
        // last + windowMs, acquisition succeeds again.
        assertTrue(debouncer.tryAcquire(10_000L + windowMs))
    }

    // ------------------------------------------------------------------------ concurrency

    @Test
    fun `exactly one of many concurrent callers at the same nowMs gets true`() {
        val debouncer = WidgetRefreshDebouncer(windowMs)
        val threadCount = 200
        val nowMs = 42_000L

        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val successes = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(threadCount) {
                pool.submit {
                    ready.countDown()
                    start.await()
                    if (debouncer.tryAcquire(nowMs)) {
                        successes.incrementAndGet()
                    }
                    done.countDown()
                }
            }

            assertTrue("workers failed to line up in time", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue("workers failed to finish in time", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        assertEquals(1, successes.get())
    }
}
