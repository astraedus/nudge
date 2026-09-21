package com.astraedus.nudge.domain.hold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Float comparisons below are proportions of a millisecond duration; this is generous enough
 * that only a real logic error trips it, never rounding. */
private const val EPS = 0.001f

/** The duration used by every [HoldProgress] test unless a test says otherwise (issue #35). */
private const val HOLD_MS = 3_000L

/**
 * Tests for [HoldProgress], the press/release/completion state machine behind the press-and-hold.
 *
 * It is the ONLY thing standing between a legitimate one-time hold and a bug that grants passthrough
 * twice, or one that locks a user out of an app they held their thumb on for the full duration.
 */
class HoldProgressTest {

    // ── HoldProgress: fresh state ──

    @Test
    fun `a fresh machine is not pressed, not completed, and fraction reads 0f at any time`() {
        val progress = HoldProgress(HOLD_MS)

        assertFalse(progress.isPressed)
        assertFalse(progress.isCompleted)
        assertEquals(0f, progress.fraction(nowMs = 0L), EPS)
        assertEquals(0f, progress.fraction(nowMs = 999_999L), EPS)
    }

    // ── HoldProgress: partial progress ──

    /**
     * If the reported fraction ever ran ahead of the real held time, the visual bar would promise
     * completion before the hold actually finished — the exact "reflex tap" affordance this feature
     * exists to prevent.
     */
    @Test
    fun `pressing then advancing part way does not complete and reports the held proportion`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)
        val justCompleted = progress.advance(nowMs = 1_500L)

        assertFalse(justCompleted)
        assertFalse(progress.isCompleted)
        assertEquals(0.5f, progress.fraction(nowMs = 1_500L), EPS)
    }

    // ── HoldProgress: crossing the finish line ──

    @Test
    fun `advancing to exactly the full duration completes`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)

        assertTrue(progress.advance(nowMs = HOLD_MS))
        assertTrue(progress.isCompleted)
    }

    @Test
    fun `advancing past the full duration also completes`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)

        assertTrue(progress.advance(nowMs = HOLD_MS + 2_000L))
        assertTrue(progress.isCompleted)
    }

    /**
     * The single most important assertion in this file: [HoldProgress.advance] must return `true`
     * exactly once per completed hold. The caller grants passthrough on a `true` return — a second
     * `true` for the same hold means the app opens twice for one gesture, which is a strictly worse
     * bug than the reflex-tap problem this feature was built to solve.
     */
    @Test
    fun `completion fires exactly once, never again on later advance calls`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)

        assertTrue(progress.advance(nowMs = HOLD_MS))
        assertFalse(progress.advance(nowMs = HOLD_MS))
        assertFalse(progress.advance(nowMs = HOLD_MS + 10_000L))
        assertTrue(progress.isCompleted)
    }

    // ── HoldProgress: release resets ──

    /**
     * Letting go must genuinely discard the held time, not just stop reporting it. If `release`
     * left `pressedAtMs` alone, a later press-and-hold could inherit time from a gesture the user
     * already abandoned and complete faster than the duration promises.
     */
    @Test
    fun `release before completion resets, and a fresh press starts the clock over`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)
        assertFalse(progress.advance(nowMs = 1_500L))
        progress.release()

        // The original hold would have completed at HOLD_MS; released, it must not.
        assertFalse(progress.advance(nowMs = HOLD_MS))
        assertEquals(0f, progress.fraction(nowMs = HOLD_MS), EPS)
        assertFalse(progress.isPressed)

        // A hold begun at t=0 and released must not be completable by a press at t+duration-1ms:
        // the fresh press owns its own clock, not the leftover deadline of the abandoned one.
        val freshPressAt = HOLD_MS - 1
        progress.press(nowMs = freshPressAt)
        assertFalse(progress.advance(nowMs = freshPressAt + HOLD_MS - 1))
        assertTrue(progress.advance(nowMs = freshPressAt + HOLD_MS))
        assertTrue(progress.isCompleted)
    }

    // ── HoldProgress: re-press while already pressed ──

    /**
     * [HoldProgress.press] restarts an in-progress hold from the new press time rather than ignoring
     * it, per the class KDoc: the only caller that can press twice without an intervening release is
     * the driving effect re-entering after the overlay was backgrounded, and time spent off-screen
     * must not count toward opening an app (issue #8's rule, applied here too).
     */
    @Test
    fun `a second press while already pressed restarts the hold, discarding time already held`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)
        assertFalse(progress.advance(nowMs = 2_000L)) // 2s in, not complete

        progress.press(nowMs = 2_000L) // restart from here; the first 2s must not carry over

        assertFalse(progress.advance(nowMs = 2_000L + HOLD_MS - 1))
        assertTrue(progress.advance(nowMs = 2_000L + HOLD_MS))
        assertTrue(progress.isCompleted)
    }

    // ── HoldProgress: completion is terminal ──

    @Test
    fun `release after completion does not un-complete it`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)
        assertTrue(progress.advance(nowMs = HOLD_MS))

        progress.release()

        assertTrue(progress.isCompleted)
        assertEquals(1f, progress.fraction(nowMs = HOLD_MS), EPS)
    }

    @Test
    fun `press after completion does not re-arm it`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)
        assertTrue(progress.advance(nowMs = HOLD_MS))

        progress.press(nowMs = 5_000L)

        assertFalse(progress.isPressed) // press() is a no-op once completed
        assertFalse(progress.advance(nowMs = 5_000L + HOLD_MS))
        assertTrue(progress.isCompleted)
    }

    // ── HoldProgress: reset ──

    /**
     * The block overlay can receive a new delivery onto the same live activity ([HoldProgress]'s
     * class KDoc); a re-delivered block is a NEW attempt and owes the user a full hold, so `reset`
     * must return even a completed machine to fully untouched — and it must be completable again.
     */
    @Test
    fun `reset returns a completed machine to untouched, and it can complete again`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 0L)
        assertTrue(progress.advance(nowMs = HOLD_MS))

        progress.reset()

        assertFalse(progress.isPressed)
        assertFalse(progress.isCompleted)
        assertEquals(0f, progress.fraction(nowMs = HOLD_MS), EPS)

        progress.press(nowMs = 10_000L)
        assertTrue(progress.advance(nowMs = 10_000L + HOLD_MS))
        assertTrue(progress.isCompleted)
    }

    // ── HoldProgress: backwards clock ──

    /**
     * An NTP correction or timezone change must not let the hold complete or go negative — the same
     * defensive rule the breathing-exercise elapsed-time accounting follows (issue #8).
     */
    @Test
    fun `a backwards clock jump cannot complete a hold or produce a negative fraction`() {
        val progress = HoldProgress(HOLD_MS)

        progress.press(nowMs = 10_000L)

        assertFalse(progress.advance(nowMs = 9_000L))
        assertEquals(0f, progress.fraction(nowMs = 9_000L), EPS)
        assertFalse(progress.isCompleted)
    }

    // ── HoldProgress: degenerate zero-length duration ──

    /**
     * The UI never renders a zero-length hold, but pinning the degenerate case stops it from
     * silently starting to throw or infinite-loop if that assumption ever changes.
     */
    @Test
    fun `a zero-length machine completes on the first advance after a press`() {
        val progress = HoldProgress(durationMs = 0L)

        progress.press(nowMs = 0L)
        assertEquals(1f, progress.fraction(nowMs = 0L), EPS) // already "full" while pressed

        assertTrue(progress.advance(nowMs = 0L))
        assertTrue(progress.isCompleted)
        assertFalse(progress.advance(nowMs = 0L)) // still fires only once
    }
}
