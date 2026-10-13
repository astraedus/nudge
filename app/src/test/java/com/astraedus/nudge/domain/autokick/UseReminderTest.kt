package com.astraedus.nudge.domain.autokick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UseReminderTest {

    @Test
    fun `the title names the app and the configured minutes`() {
        assertEquals("You've been on Instagram for 20 min", UseReminder("Instagram", 20).title)
    }

    @Test
    fun `the body is the gentle question`() {
        assertEquals("Just a heads-up. Still what you came here for?", UseReminder("YouTube", 5).body)
    }

    @Test
    fun `durations of an hour or more read as hours`() {
        assertEquals("1 min", UseReminder.formatDuration(1))
        assertEquals("59 min", UseReminder.formatDuration(59))
        assertEquals("1 hr", UseReminder.formatDuration(60))
        assertEquals("1 hr 30 min", UseReminder.formatDuration(90))
        assertEquals("2 hr", UseReminder.formatDuration(120))
        assertEquals("24 hr", UseReminder.formatDuration(1440))
    }

    @Test
    fun `a nonsense duration never renders zero`() {
        assertEquals("1 min", UseReminder.formatDuration(0))
        assertEquals("1 min", UseReminder.formatDuration(-3))
    }

    @Test
    fun `the copy never scolds`() {
        // Nudge's voice (NudgeMessages): a fact the user asked for, then one open question.
        val banned = listOf("wasted", "too long", "again", "addict", "should", "!")
        for (minutes in listOf(1, 20, 60, 90, 1440)) {
            val reminder = UseReminder("TikTok", minutes)
            val text = (reminder.title + " " + reminder.body).lowercase()
            for (word in banned) assertFalse("'$word' in: $text", word in text)
        }
    }
}
