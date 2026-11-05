package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The intent-to-pause mapping behind [PhoneNudgeActivity.onNewIntent].
 *
 * The Compose keying itself is not JVM-testable, but the contract it depends on is: a re-delivery
 * maps to DIFFERENT args, which is what changes the key and restarts the countdown. The intent
 * extras cannot be read in a JVM test, so the pure function takes the two values instead.
 */
class PhoneNudgeArgsTest {

    @Test
    fun `a re-delivery maps to the new pause, not the old one`() {
        val first = phoneNudgeArgs(20, 5)
        val second = phoneNudgeArgs(10, 3)

        assertEquals(PhoneNudgeArgs(minutes = 10, seconds = 3), second)
        assertNotEquals("the key must change so the countdown restarts", first, second)
    }

    @Test
    fun `nonsense values are clamped rather than trusted`() {
        assertEquals(PhoneNudgeArgs(minutes = 1, seconds = 0), phoneNudgeArgs(0, -5))
        assertEquals(PhoneNudgeArgs(minutes = 20, seconds = 5), phoneNudgeArgs(20, 5))
    }
}
