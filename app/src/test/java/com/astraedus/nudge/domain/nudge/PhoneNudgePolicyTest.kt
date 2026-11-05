package com.astraedus.nudge.domain.nudge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PhoneNudgePolicy]: every reason to defer, one at a time, and the clear case. */
class PhoneNudgePolicyTest {

    @Test
    fun `a quiet, unlocked, interactive phone may be interrupted`() {
        assertTrue(PhoneNudgePolicy.canInterrupt(PhoneNudgePolicy.Facts()))
    }

    @Test
    fun `each blocking fact defers on its own`() {
        listOf(
            PhoneNudgePolicy.Facts(inCall = true),
            PhoneNudgePolicy.Facts(navigationActive = true),
            PhoneNudgePolicy.Facts(carMode = true),
            PhoneNudgePolicy.Facts(ownUiInFront = true),
            PhoneNudgePolicy.Facts(blockOverlayActive = true),
            PhoneNudgePolicy.Facts(deviceLocked = true),
            PhoneNudgePolicy.Facts(screenInteractive = false),
            PhoneNudgePolicy.Facts(cameraInUse = true)
        ).forEach { facts ->
            assertFalse("must defer: $facts", PhoneNudgePolicy.canInterrupt(facts))
        }
    }

    @Test
    fun `navigation is the category or one of the known packages`() {
        assertTrue(PhoneNudgePolicy.isNavigationPackage("com.google.android.apps.maps", false))
        assertTrue(PhoneNudgePolicy.isNavigationPackage("com.example.customnav", true))
        assertFalse(PhoneNudgePolicy.isNavigationPackage("com.instagram.android", false))
        assertFalse(PhoneNudgePolicy.isNavigationPackage(null, true))
    }
}
