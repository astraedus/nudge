package com.astraedus.nudge.ui.screens.settings

import com.astraedus.nudge.NudgeIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer L1 (pure JVM). #114: the Grayscale guide printed `com.astraedus.nudge` (the class
 * namespace) as the package, so `adb shell pm grant` failed on every device. The expected value is
 * derived from the real applicationId, never typed.
 */
class GrayscaleAdbCommandTest {

    @Test
    fun `command grants to the real applicationId`() {
        assertEquals(
            "adb shell pm grant ${NudgeIdentity.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
            grayscaleAdbCommand(NudgeIdentity.APPLICATION_ID)
        )
    }

    @Test
    fun `command never names the class namespace as the package`() {
        val words = grayscaleAdbCommand(NudgeIdentity.APPLICATION_ID).split(' ')
        assertTrue(NudgeIdentity.APPLICATION_ID in words)
        assertFalse(NudgeIdentity.CLASS_NAMESPACE in words)
    }
}
