package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InteractionTrackerTest {

    private lateinit var tracker: InteractionTracker
    private var fakeTime = 1_000_000L

    @Before
    fun setUp() {
        fakeTime = 1_000_000L
        tracker = InteractionTracker()
        tracker.clock = { fakeTime }
    }

    @Test
    fun `session count increments per package`() {
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.beta")

        assertEquals(2, tracker.getSessionCount("com.example.alpha"))
        assertEquals(1, tracker.getSessionCount("com.example.beta"))
    }

    @Test
    fun `daily count increments per package`() {
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.beta")

        assertEquals(2, tracker.getDailyTotal("com.example.alpha"))
        assertEquals(1, tracker.getDailyTotal("com.example.beta"))
    }

    @Test
    fun `onAppChanged resets session after expiry without clearing daily totals`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")

        // Switch away, advance past expiry, switch back
        tracker.onAppChanged("com.example.beta")
        tracker.recordInteraction("com.example.beta")
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        tracker.onAppChanged("com.example.alpha")

        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
        assertEquals(2, tracker.getDailyTotal("com.example.alpha"))
        assertEquals(1, tracker.getSessionCount("com.example.beta"))
    }

    @Test
    fun `recordInteraction returns updated counts`() {
        val first = tracker.recordInteraction("com.example.alpha")
        val second = tracker.recordInteraction("com.example.alpha")

        assertEquals("com.example.alpha", first.packageName)
        assertEquals(1, first.sessionCount)
        assertEquals(1, first.dailyTotal)
        assertEquals(2, second.sessionCount)
        assertEquals(2, second.dailyTotal)
    }

    @Test
    fun `resetSession clears current session without clearing daily total`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")

        tracker.resetSession("com.example.alpha")
        val afterReset = tracker.recordInteraction("com.example.alpha")

        assertEquals(1, afterReset.sessionCount)
        assertEquals(3, afterReset.dailyTotal)
    }

    // --- Session expiry tests ---

    @Test
    fun `sessionCountPersistsWhenReturningWithinExpiry`() {
        tracker.onAppChanged("com.example.alpha")
        repeat(5) { tracker.recordInteraction("com.example.alpha") }
        assertEquals(5, tracker.getSessionCount("com.example.alpha"))

        // Switch away
        tracker.onAppChanged("com.example.launcher")

        // Return within 5 minutes
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS - 1000
        tracker.onAppChanged("com.example.alpha")

        assertEquals(5, tracker.getSessionCount("com.example.alpha"))
    }

    @Test
    fun `sessionCountResetsWhenReturningAfterExpiry`() {
        tracker.onAppChanged("com.example.alpha")
        repeat(5) { tracker.recordInteraction("com.example.alpha") }
        assertEquals(5, tracker.getSessionCount("com.example.alpha"))

        // Switch away
        tracker.onAppChanged("com.example.launcher")

        // Return after more than 5 minutes
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        tracker.onAppChanged("com.example.alpha")

        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
    }

    @Test
    fun `sessionCountPreservedDuringCooldownRegardlessOfExpiry`() {
        tracker.onAppChanged("com.example.alpha")
        repeat(5) { tracker.recordInteraction("com.example.alpha") }

        // Set a cooldown longer than the session expiry so it outlasts it
        tracker.setCooldown("com.example.alpha", InteractionTracker.SESSION_EXPIRY_MS * 2)
        tracker.onAppChanged("com.example.launcher")

        // Return after session expiry -- but cooldown is still active, so count persists
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        tracker.onAppChanged("com.example.alpha")

        assertEquals(5, tracker.getSessionCount("com.example.alpha"))
    }

    @Test
    fun `lastLeftAtClearedOnResetSession`() {
        tracker.onAppChanged("com.example.alpha")
        repeat(3) { tracker.recordInteraction("com.example.alpha") }

        // Switch away (records lastLeftAt)
        tracker.onAppChanged("com.example.launcher")
        // Reset session for alpha (should clear lastLeftAt)
        tracker.resetSession("com.example.alpha")

        // Return after a short time -- since lastLeftAt was cleared by resetSession,
        // there's no leftAt entry, so it should reset to 0 (null path)
        fakeTime += 1000
        tracker.onAppChanged("com.example.alpha")

        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
    }

    @Test
    fun `lastLeftAtClearedOnResetDaily`() {
        tracker.onAppChanged("com.example.alpha")
        repeat(3) { tracker.recordInteraction("com.example.alpha") }

        // Switch away (records lastLeftAt)
        tracker.onAppChanged("com.example.launcher")
        // Reset daily (should clear lastLeftAt)
        tracker.resetDaily()

        // Return after a short time -- since lastLeftAt was cleared by resetDaily,
        // there's no leftAt entry, so it should reset to 0 (null path)
        fakeTime += 1000
        tracker.onAppChanged("com.example.alpha")

        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
    }

    @Test
    fun `first entry to a new package always starts at zero`() {
        // No prior interaction with alpha -- first entry should be 0
        tracker.onAppChanged("com.example.alpha")
        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
    }
}
