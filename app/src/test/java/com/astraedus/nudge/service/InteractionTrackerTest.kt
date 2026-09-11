package com.astraedus.nudge.service

import com.astraedus.nudge.domain.interaction.CountMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // --- Session foreground-time baseline (time-based auto-kick) ---

    @Test
    fun `session usage baseline starts absent and is set on demand`() {
        assertNull(tracker.getSessionUsageBaseline("com.example.alpha"))

        tracker.setSessionUsageBaseline("com.example.alpha", 12_345L)

        assertEquals(12_345L, tracker.getSessionUsageBaseline("com.example.alpha"))
    }

    @Test
    fun `session usage baseline is per package`() {
        tracker.setSessionUsageBaseline("com.example.alpha", 1_000L)
        tracker.setSessionUsageBaseline("com.example.beta", 2_000L)

        assertEquals(1_000L, tracker.getSessionUsageBaseline("com.example.alpha"))
        assertEquals(2_000L, tracker.getSessionUsageBaseline("com.example.beta"))
    }

    @Test
    fun `session usage baseline survives a return inside the session window`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.setSessionUsageBaseline("com.example.alpha", 5_000L)

        tracker.onAppChanged("com.example.launcher")
        fakeTime += 1_000
        tracker.onAppChanged("com.example.alpha")

        assertEquals(5_000L, tracker.getSessionUsageBaseline("com.example.alpha"))
    }

    @Test
    fun `session usage baseline clears once the session expires`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.setSessionUsageBaseline("com.example.alpha", 5_000L)

        tracker.onAppChanged("com.example.launcher")
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        tracker.onAppChanged("com.example.alpha")

        assertNull(tracker.getSessionUsageBaseline("com.example.alpha"))
    }

    @Test
    fun `session usage baseline survives an expiry while in cooldown`() {
        // Cooldown means the user was just kicked; the same carve-out that preserves the
        // interaction count must preserve the time baseline, or the two triggers would disagree
        // about whether this is still the same sitting.
        tracker.onAppChanged("com.example.alpha")
        tracker.setSessionUsageBaseline("com.example.alpha", 5_000L)
        tracker.setCooldown("com.example.alpha", 10L * 60L * 1000L)

        tracker.onAppChanged("com.example.launcher")
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        tracker.onAppChanged("com.example.alpha")

        assertEquals(5_000L, tracker.getSessionUsageBaseline("com.example.alpha"))
    }

    @Test
    fun `resetSession clears the usage baseline alongside the count`() {
        tracker.onAppChanged("com.example.alpha")
        repeat(3) { tracker.recordInteraction("com.example.alpha") }
        tracker.setSessionUsageBaseline("com.example.alpha", 5_000L)

        tracker.resetSession("com.example.alpha")

        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
        assertNull(tracker.getSessionUsageBaseline("com.example.alpha"))
    }
    // --- one session, one unit, all the way down to "today" (round-2 review) ---

    /**
     * The overlay renders the daily total directly beneath the session count, so a single mixed
     * total is the same lie the session's mode rule exists to prevent, surviving one field over:
     * three taps then five reels rendered "5 reels / today: 8".
     */
    @Test
    fun `daily totals are kept per mode so today never mixes taps into items`() {
        val pkg = "com.instagram.android"
        repeat(3) { tracker.recordInteractions(pkg, 1, CountMode.TAPS) }
        assertEquals(3, tracker.getDailyTotal(pkg))

        repeat(5) { tracker.recordInteractions(pkg, 1, CountMode.ITEMS) }

        val snapshot = tracker.snapshot(pkg)
        assertEquals(CountMode.ITEMS, snapshot.mode)
        assertEquals("the promotion resets the session count", 5, snapshot.sessionCount)
        assertEquals("and today must be items too, not 8", 5, snapshot.dailyTotal)
    }

    /** The taps are not destroyed, only kept in their own unit -- they are still today's taps. */
    @Test
    fun `the taps recorded before a promotion survive under their own mode`() {
        val pkg = "com.instagram.android"
        repeat(3) { tracker.recordInteractions(pkg, 1, CountMode.TAPS) }
        tracker.recordInteractions(pkg, 1, CountMode.ITEMS)

        tracker.resetSession(pkg)
        // A fresh session starts in TAPS, which is the unit those three were counted in.
        assertEquals(3, tracker.getDailyTotal(pkg))
    }

    /**
     * The label is part of the session, so every path that resets a session must clear it. It used
     * to live on `InteractionHandler` and be cleared on the SITTING's schedule instead, which drifted
     * from the count's in both directions.
     */
    @Test
    fun `resetting a session clears its label`() {
        val pkg = "com.instagram.android"
        tracker.setSessionLabel(pkg, "reels")
        tracker.recordInteractions(pkg, 1, CountMode.ITEMS)
        assertEquals("reels", tracker.snapshot(pkg).label)

        tracker.resetSession(pkg)

        assertNull(tracker.snapshot(pkg).label)
    }

    @Test
    fun `a session expiring clears its label along with its count`() {
        val pkg = "com.instagram.android"
        fakeTime = 1_000L
        tracker.onAppChanged(pkg)
        tracker.setSessionLabel(pkg, "reels")
        tracker.recordInteractions(pkg, 1, CountMode.ITEMS)

        tracker.onAppChanged("com.other.app")
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        tracker.onAppChanged(pkg)

        assertNull("the caption must not outlive the count it described", tracker.snapshot(pkg).label)
        assertEquals(0, tracker.getSessionCount(pkg))
    }

    /** A label is a description of a session, never evidence that one exists. */
    @Test
    fun `setting a label starts no session and touches no count`() {
        val pkg = "com.instagram.android"
        tracker.setSessionLabel(pkg, "reels")

        assertEquals(0, tracker.getSessionCount(pkg))
        assertEquals(0, tracker.getDailyTotal(pkg))
    }

}
