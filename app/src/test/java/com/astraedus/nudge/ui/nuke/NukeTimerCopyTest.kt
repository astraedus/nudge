package com.astraedus.nudge.ui.nuke

import com.astraedus.nudge.domain.nuke.NukeTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L1: every line the Nuke timer (#184) shows. */
class NukeTimerCopyTest {

    private val minute = 60_000L

    @Test
    fun `time left rounds up, so it never says 0 while time is left`() {
        assertEquals("1 min", NukeTimerCopy.timeLeft(1))
        assertEquals("1 min", NukeTimerCopy.timeLeft(minute))
        assertEquals("2 min", NukeTimerCopy.timeLeft(minute + 1))
        assertEquals("24 min", NukeTimerCopy.timeLeft(23 * minute + 30_000))
        assertEquals("1 h", NukeTimerCopy.timeLeft(60 * minute))
        assertEquals("1 h 5 min", NukeTimerCopy.timeLeft(65 * minute))
        assertEquals("2 h", NukeTimerCopy.timeLeft(120 * minute))
    }

    @Test
    fun `the refusal says how long is left and what to do`() {
        assertEquals(
            "Your timer has 24 min left. Scan again when it ends.",
            NukeTimerCopy.refusal(24 * minute)
        )
    }

    @Test
    fun `the status line is absent with no timer, a countdown, then done`() {
        assertNull(NukeTimerCopy.statusLine(null))
        assertEquals("15 min left", NukeTimerCopy.statusLine(15 * minute))
        assertEquals("Timer done", NukeTimerCopy.statusLine(0))
    }

    @Test
    fun `every picker choice has its own plain label`() {
        assertEquals(
            listOf("None", "15 min", "30 min", "45 min", "1 hour", "2 hours"),
            NukeTimer.CHOICES_MINUTES.map(NukeTimerCopy::choiceLabel)
        )
    }
}
