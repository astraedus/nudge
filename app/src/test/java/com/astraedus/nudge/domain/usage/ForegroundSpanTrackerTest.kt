package com.astraedus.nudge.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground-accounting contract behind every screen-time number in the app.
 *
 * The bug these pin: a real daily-driver phone reported **~17 hours of screen time before
 * lunchtime**, across a handful of apps. Each read path paired events through its own
 * `package -> startTime` map, so nothing enforced that only one app is foreground at a time, and
 * an app whose closing event went missing was credited from its RESUMED all the way to "now" —
 * for days, if that is how long ago it was. Several such spans overlapping is how a morning came
 * to be worth more hours than the morning had.
 *
 * Everything here is a mechanism by which time could be counted that the user did not spend.
 */
class ForegroundSpanTrackerTest {

    private val chrome = "com.android.chrome"
    private val youtube = "com.google.android.youtube"
    private val maps = "com.google.android.apps.maps"

    private val dayStart = 1_767_222_000_000L // an arbitrary local midnight

    private fun hours(h: Long) = h * 60L * 60L * 1000L
    private fun minutes(m: Long) = m * 60L * 1000L

    /** `dayStart` plus [h] hours and [m] minutes — reads like a clock in the tests below. */
    private fun at(h: Long, m: Long = 0) = dayStart + hours(h) + minutes(m)

    private data class Span(val packageName: String, val startMs: Long, val endMs: Long) {
        val durationMs get() = endMs - startMs
    }

    private val spans = mutableListOf<Span>()

    private fun tracker(openTailLimitMs: Long = ForegroundSpanTracker.DEFAULT_OPEN_TAIL_LIMIT_MS) =
        ForegroundSpanTracker(openTailLimitMs) { pkg, startMs, endMs ->
            spans += Span(pkg, startMs, endMs)
        }

    private fun totalFor(packageName: String) =
        spans.filter { it.packageName == packageName }.sumOf { it.durationMs }

    private fun total() = spans.sumOf { it.durationMs }

    // --- one app at a time: the 17-hour bug ---

    @Test
    fun `another app taking the foreground closes the open span, with no PAUSED at all`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "MainActivity", at(9))
        // No PAUSED for Chrome — a killed process, a dropped event. YouTube coming up is proof
        // enough that Chrome stopped being the app on screen.
        tracker.onResumed(youtube, "WatchActivity", at(10))
        tracker.onPaused(youtube, at(11))
        tracker.finish(nowMs = at(12), extendOpenSpanToNow = true)

        assertEquals(hours(1), totalFor(chrome))
        assertEquals(hours(1), totalFor(youtube))
    }

    @Test
    fun `two apps can never be credited with the same minute`() {
        // The shape that produced 17 hours: every app's PAUSED goes missing, so under the old
        // per-package map all three were "open" at once and each was billed through to now.
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(6))
        tracker.onResumed(youtube, "B", at(7))
        tracker.onResumed(maps, "C", at(8))
        val now = at(12)
        tracker.finish(nowMs = now, extendOpenSpanToNow = true)

        assertEquals("chrome ran 06:00-07:00", hours(1), totalFor(chrome))
        assertEquals("youtube ran 07:00-08:00", hours(1), totalFor(youtube))
        assertEquals("maps held the foreground from 08:00 to now", hours(4), totalFor(maps))
        assertEquals(
            "six wall-clock hours cannot produce more than six hours of screen time",
            hours(6),
            total()
        )
    }

    @Test
    fun `emitted spans never overlap, whatever order the stream arrives in`() {
        val tracker = tracker()
        // A deliberately malformed interleaving: missing PAUSEDs, a PAUSED for an app that is not
        // open, a PAUSED before its RESUMED, repeated RESUMEDs.
        tracker.onResumed(chrome, "A", at(1))
        tracker.onResumed(chrome, "A2", at(1, 30))
        tracker.onResumed(youtube, "B", at(2))
        tracker.onPaused(chrome, at(2, 30)) // chrome is not open any more
        tracker.onResumed(maps, "C", at(3))
        tracker.onPaused(youtube, at(3, 30)) // youtube is not open any more
        tracker.onForegroundEnded(at(4))
        tracker.onPaused(maps, at(5)) // nothing open
        tracker.onResumed(youtube, "B", at(6))
        tracker.onPaused(youtube, at(5, 30)) // backwards pair
        tracker.finish(nowMs = at(7), extendOpenSpanToNow = true)

        spans.zipWithNext().forEach { (earlier, later) ->
            assertTrue(
                "spans must partition time, got $earlier then $later",
                earlier.endMs <= later.startMs
            )
        }
        assertTrue("no span may be empty or negative", spans.all { it.durationMs > 0 })
        assertTrue(
            "total screen time cannot exceed the wall clock it was measured over",
            total() <= at(7) - at(1)
        )
    }

    // --- the screen going off ---

    @Test
    fun `the screen going off ends the session even when the app never pauses`() {
        val tracker = tracker()
        tracker.onResumed(youtube, "WatchActivity", at(22))
        tracker.onForegroundEnded(at(22, 40)) // SCREEN_NON_INTERACTIVE — phone put down
        tracker.finish(nowMs = at(23) + hours(9), extendOpenSpanToNow = true)

        assertEquals(
            "a phone asleep in a pocket is not screen time",
            minutes(40),
            totalFor(youtube)
        )
    }

    @Test
    fun `nothing accrues while the screen stays off`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.onForegroundEnded(at(9, 10)) // screen off
        // Screen back on and the user returns to Chrome an hour later.
        tracker.onResumed(chrome, "A", at(10, 10))
        tracker.onPaused(chrome, at(10, 20))
        tracker.finish(nowMs = at(11), extendOpenSpanToNow = true)

        assertEquals(minutes(20), totalFor(chrome))
    }

    // --- an open span is inferred, so it is capped ---

    @Test
    fun `an open span is capped rather than billed from a stale RESUMED`() {
        // A RESUMED two days ago with no close event. Extending it to now is what turned one lost
        // event into days of "screen time" spread across every bar in the window.
        val tracker = tracker()
        val staleStart = dayStart - hours(48)
        tracker.onResumed(chrome, "A", staleStart)
        tracker.finish(nowMs = at(12), extendOpenSpanToNow = true)

        assertEquals(ForegroundSpanTracker.DEFAULT_OPEN_TAIL_LIMIT_MS, totalFor(chrome))
        assertEquals(staleStart, spans.single().startMs)
    }

    @Test
    fun `a live session shorter than the cap is counted in full`() {
        val tracker = tracker()
        tracker.onResumed(youtube, "W", at(12))
        tracker.finish(nowMs = at(12, 25), extendOpenSpanToNow = true)

        assertEquals(minutes(25), totalFor(youtube))
    }

    @Test
    fun `a measured span longer than the cap is never shortened`() {
        // The cap applies only to time we INFERRED. Both ends of this one were observed.
        val tracker = tracker()
        tracker.onResumed(youtube, "W", at(8))
        tracker.onPaused(youtube, at(20))
        tracker.finish(nowMs = at(21), extendOpenSpanToNow = true)

        assertEquals(hours(12), totalFor(youtube))
    }

    @Test
    fun `an open span is dropped for a window that has already ended`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.finish(nowMs = at(9) + hours(72), extendOpenSpanToNow = false)

        assertEquals(0L, total())
    }

    @Test
    fun `finish is idempotent`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.finish(nowMs = at(9, 30), extendOpenSpanToNow = true)
        tracker.finish(nowMs = at(9, 30), extendOpenSpanToNow = true)

        assertEquals(minutes(30), total())
        assertEquals(1, spans.size)
    }

    // --- ACTIVITY_STOPPED, matched on the activity that opened the span ---

    @Test
    fun `the STOPPED of the open activity ends the span`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "MainActivity", at(9))
        tracker.onStopped(chrome, "MainActivity", at(9, 15))
        tracker.finish(nowMs = at(18), extendOpenSpanToNow = true)

        assertEquals(minutes(15), totalFor(chrome))
    }

    @Test
    fun `the trailing STOPPED of an in-app transition does not end the new span`() {
        // Moving between two activities of one app emits `A PAUSED, B RESUMED, A STOPPED`.
        // Reading that last event as "chrome is no longer foreground" would lose the whole visit.
        val tracker = tracker()
        tracker.onResumed(chrome, "ListActivity", at(9))
        tracker.onPaused(chrome, at(9, 5))
        tracker.onResumed(chrome, "DetailActivity", at(9, 5))
        tracker.onStopped(chrome, "ListActivity", at(9, 6))
        tracker.onPaused(chrome, at(9, 30))
        tracker.finish(nowMs = at(10), extendOpenSpanToNow = true)

        assertEquals(minutes(30), totalFor(chrome))
    }

    @Test
    fun `a STOPPED for another app is ignored`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.onStopped(youtube, "W", at(9, 10))
        tracker.onPaused(chrome, at(9, 20))
        tracker.finish(nowMs = at(10), extendOpenSpanToNow = true)

        assertEquals(minutes(20), totalFor(chrome))
    }

    @Test
    fun `a STOPPED with no class name cannot close a span it may not own`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.onStopped(chrome, null, at(9, 10))
        tracker.onPaused(chrome, at(9, 20))
        tracker.finish(nowMs = at(10), extendOpenSpanToNow = true)

        assertEquals(minutes(20), totalFor(chrome))
    }

    // --- malformed sequences ---

    @Test
    fun `a PAUSED for an app that is not open is ignored`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.onPaused(youtube, at(9, 10))
        tracker.onPaused(chrome, at(9, 20))
        tracker.finish(nowMs = at(10), extendOpenSpanToNow = true)

        assertEquals(minutes(20), totalFor(chrome))
        assertEquals(0L, totalFor(youtube))
    }

    @Test
    fun `a second RESUMED for the open app moves its start forward`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(9))
        tracker.onResumed(chrome, "A", at(11))
        tracker.onPaused(chrome, at(12))
        tracker.finish(nowMs = at(13), extendOpenSpanToNow = true)

        assertEquals(hours(1), totalFor(chrome))
    }

    @Test
    fun `an out-of-order RESUMED cannot lengthen the open span`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(11))
        tracker.onResumed(chrome, "A", at(9))
        tracker.onPaused(chrome, at(12))
        tracker.finish(nowMs = at(13), extendOpenSpanToNow = true)

        assertEquals(hours(1), totalFor(chrome))
    }

    @Test
    fun `a backwards pair is worth zero, never a negative span`() {
        val tracker = tracker()
        tracker.onResumed(chrome, "A", at(10))
        tracker.onPaused(chrome, at(9))
        tracker.finish(nowMs = at(11), extendOpenSpanToNow = true)

        assertEquals(0L, total())
    }
}
