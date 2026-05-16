package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InteractionTrackerTest {

    private lateinit var tracker: InteractionTracker

    @Before
    fun setUp() {
        tracker = InteractionTracker()
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
    fun `onAppChanged resets session counts without clearing daily totals`() {
        tracker.onAppChanged("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")
        tracker.recordInteraction("com.example.alpha")

        tracker.onAppChanged("com.example.beta")
        tracker.recordInteraction("com.example.beta")
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
}
