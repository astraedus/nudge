package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeMessagesTest {

    @Test
    fun `message lists are non-empty`() {
        assertFalse(NudgeMessages.delayTitles.isEmpty())
        assertFalse(NudgeMessages.delaySubtitles.isEmpty())
        assertFalse(NudgeMessages.hardBlockMessages.isEmpty())
    }

    @Test
    fun `getRandom returns a valid message`() {
        repeat(20) {
            val message = NudgeMessages.getRandom()

            assertTrue(message.isNotBlank())
            assertTrue(message in NudgeMessages.allMessages)
        }
    }
}
