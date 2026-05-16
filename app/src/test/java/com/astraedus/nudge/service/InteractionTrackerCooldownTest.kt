package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InteractionTrackerCooldownTest {

    private lateinit var tracker: InteractionTracker

    @Before
    fun setUp() {
        tracker = InteractionTracker()
    }

    @Test
    fun `isInCooldown returns false when no cooldown set`() {
        assertFalse(tracker.isInCooldown("com.example.alpha"))
    }

    @Test
    fun `isInCooldown returns true during cooldown period`() {
        // Set a cooldown that should be active right now (far future expiry)
        tracker.setCooldown("com.example.alpha", 60_000L)
        assertTrue(tracker.isInCooldown("com.example.alpha"))
    }

    @Test
    fun `getCooldownRemainingMs returns 0 when no cooldown`() {
        assertEquals(0L, tracker.getCooldownRemainingMs("com.example.alpha"))
    }

    @Test
    fun `getCooldownRemainingMs returns positive value during cooldown`() {
        tracker.setCooldown("com.example.alpha", 60_000L)
        val remaining = tracker.getCooldownRemainingMs("com.example.alpha")
        assertTrue("Expected remaining > 0 but got $remaining", remaining > 0)
        assertTrue("Expected remaining <= 60000 but got $remaining", remaining <= 60_000L)
    }

    @Test
    fun `clearCooldown removes the cooldown`() {
        tracker.setCooldown("com.example.alpha", 60_000L)
        assertTrue(tracker.isInCooldown("com.example.alpha"))

        tracker.clearCooldown("com.example.alpha")
        assertFalse(tracker.isInCooldown("com.example.alpha"))
    }

    @Test
    fun `cooldown is per-package`() {
        tracker.setCooldown("com.example.alpha", 60_000L)

        assertTrue(tracker.isInCooldown("com.example.alpha"))
        assertFalse(tracker.isInCooldown("com.example.beta"))
    }

    @Test
    fun `onAppChanged does not reset session during cooldown`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        assertEquals(2, tracker.getSessionCount("com.example.alpha"))

        // Set cooldown and switch away and back
        tracker.setCooldown("com.example.alpha", 60_000L)
        tracker.onAppChanged("com.example.beta")
        tracker.onAppChanged("com.example.alpha")

        // Session count should NOT be reset because of cooldown
        assertEquals(2, tracker.getSessionCount("com.example.alpha"))
    }

    @Test
    fun `onAppChanged resets session normally when not in cooldown`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        assertEquals(2, tracker.getSessionCount("com.example.alpha"))

        // Switch away and back without cooldown
        tracker.onAppChanged("com.example.beta")
        tracker.onAppChanged("com.example.alpha")

        // Session count should be reset
        assertEquals(0, tracker.getSessionCount("com.example.alpha"))
    }
}
