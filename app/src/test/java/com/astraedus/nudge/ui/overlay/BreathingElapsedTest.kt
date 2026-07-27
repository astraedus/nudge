package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the breathing exercise's elapsed-time accounting (issue #8).
 *
 * The bug: the exercise measured progress as `now - startTime` from a single timestamp taken when
 * the effect started. Because the countdown coroutine kept running after the user tabbed out, wall
 * clock time spent away from the overlay counted toward completion — the exercise finished
 * invisibly and granted passthrough. Progress is now accumulated per VISIBLE segment: the ticker is
 * cancelled when the overlay leaves RESUMED and each segment's duration is folded in only when the
 * user is actually looking at it.
 */
class BreathingElapsedTest {

    // --- advanceBreathingElapsed: only visible time accumulates ---

    @Test
    fun `a visible segment adds its wall-clock duration to the accumulated total`() {
        assertEquals(4_000L, advanceBreathingElapsed(elapsedMs = 0L, segmentStartMs = 1_000L, nowMs = 5_000L))
    }

    @Test
    fun `segments accumulate across a pause instead of restarting`() {
        // Two 4s visible segments separated by an arbitrarily long time away from the overlay: the
        // total is 8s of looking at it, NOT the wall clock spread between the first and last tick.
        val afterFirstBreath = advanceBreathingElapsed(0L, segmentStartMs = 1_000L, nowMs = 5_000L)
        // …user tabs out for 10 minutes; the ticker is cancelled, so no tick happens in that window.
        val afterSecondBreath =
            advanceBreathingElapsed(afterFirstBreath, segmentStartMs = 605_000L, nowMs = 609_000L)

        assertEquals(8_000L, afterSecondBreath)
    }

    @Test
    fun `time away from the overlay never counts toward completion`() {
        // The issue #8 invariant, stated directly: a 30s exercise with only 4s of visible time is
        // not complete, no matter how much wall clock has passed since it started.
        val elapsed = advanceBreathingElapsed(0L, segmentStartMs = 0L, nowMs = 4_000L)
        assertFalse(isBreathingComplete(elapsed, totalMs = 30_000L))
    }

    @Test
    fun `a backwards clock jump cannot rewind progress`() {
        // An NTP correction or timezone change mid-segment must not strand the user on the exercise.
        assertEquals(7_000L, advanceBreathingElapsed(elapsedMs = 7_000L, segmentStartMs = 9_000L, nowMs = 2_000L))
    }

    // --- breathingProgress ---

    @Test
    fun `progress is the fraction of visible time completed`() {
        assertEquals(0.5f, breathingProgress(elapsedMs = 15_000L, totalMs = 30_000L), 0.0001f)
    }

    @Test
    fun `progress is clamped to the 0 to 1 range`() {
        assertEquals(0f, breathingProgress(elapsedMs = -5_000L, totalMs = 30_000L), 0.0001f)
        assertEquals(1f, breathingProgress(elapsedMs = 90_000L, totalMs = 30_000L), 0.0001f)
    }

    @Test
    fun `a zero-length exercise reads as complete rather than dividing by zero`() {
        assertEquals(1f, breathingProgress(elapsedMs = 0L, totalMs = 0L), 0.0001f)
        assertTrue(isBreathingComplete(elapsedMs = 0L, totalMs = 0L))
    }

    // --- isBreathingComplete ---

    @Test
    fun `the exercise completes only once the full visible duration is covered`() {
        assertFalse(isBreathingComplete(elapsedMs = 29_999L, totalMs = 30_000L))
        assertTrue(isBreathingComplete(elapsedMs = 30_000L, totalMs = 30_000L))
        assertTrue(isBreathingComplete(elapsedMs = 30_001L, totalMs = 30_000L))
    }
}
