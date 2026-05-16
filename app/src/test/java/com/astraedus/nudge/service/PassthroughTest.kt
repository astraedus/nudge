package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassthroughTest {

    @Before
    fun setUp() {
        NudgeAccessibilityService.resetPassthroughForTests()
    }

    @Test
    fun `grantPassthrough marks package as passed through`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        assertTrue(NudgeAccessibilityService.isPassthroughGranted("com.example.alpha"))
        assertFalse(NudgeAccessibilityService.isPassthroughGranted("com.example.beta"))
    }

    @Test
    fun `passthrough clears on app switch`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        val cleared = NudgeAccessibilityService.clearPassthroughIfAppChanged("com.example.beta")

        assertTrue(cleared)
        assertNull(NudgeAccessibilityService.lastPassthroughPackage)
        assertNull(NudgeAccessibilityService.lastPassthroughFeature)
        assertFalse(NudgeAccessibilityService.isPassthroughGranted("com.example.alpha"))
    }

    @Test
    fun `package passthrough prevents foreground re-evaluation`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        assertTrue(NudgeAccessibilityService.shouldSkipForegroundEvaluationForPassthrough("com.example.alpha"))
        assertFalse(NudgeAccessibilityService.shouldSkipForegroundEvaluationForPassthrough("com.example.beta"))
    }

    @Test
    fun `whole-app passthrough does not skip feature evaluation`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha")

        assertFalse(
            NudgeAccessibilityService.shouldSkipFeatureEvaluationForPassthrough(
                "com.example.alpha",
                "REELS"
            )
        )
    }

    @Test
    fun `feature passthrough skips the matching feature only`() {
        NudgeAccessibilityService.grantPassthrough("com.example.alpha", "REELS")

        assertTrue(
            NudgeAccessibilityService.shouldSkipFeatureEvaluationForPassthrough(
                "com.example.alpha",
                "REELS"
            )
        )
        assertFalse(
            NudgeAccessibilityService.shouldSkipFeatureEvaluationForPassthrough(
                "com.example.alpha",
                "EXPLORE"
            )
        )
    }

    @Test
    fun `own package overlay widget events do not clear counter state`() {
        assertFalse(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "android.widget.TextView",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }

    @Test
    fun `own package app window events clear counter state`() {
        assertTrue(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.astraedus.nudge.MainActivity",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }

    @Test
    fun `own package non-window events do not clear counter state`() {
        assertFalse(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                className = "com.astraedus.nudge.MainActivity",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }
}
