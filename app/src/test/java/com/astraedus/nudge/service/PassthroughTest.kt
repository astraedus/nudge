package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassthroughTest {

    private lateinit var manager: PassthroughManager

    @Before
    fun setUp() {
        manager = PassthroughManager()
    }

    @Test
    fun `grantPassthrough marks package as passed through`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.isGranted("com.example.alpha"))
        assertFalse(manager.isGranted("com.example.beta"))
    }

    @Test
    fun `passthrough clears on app switch`() {
        manager.grant("com.example.alpha")

        val cleared = manager.clearIfAppChanged("com.example.beta")

        assertTrue(cleared)
        assertNull(manager.lastPackage)
        assertNull(manager.lastFeature)
        assertFalse(manager.isGranted("com.example.alpha"))
    }

    @Test
    fun `package passthrough prevents foreground re-evaluation`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.shouldSkipForegroundEvaluation("com.example.alpha"))
        assertFalse(manager.shouldSkipForegroundEvaluation("com.example.beta"))
    }

    @Test
    fun `whole-app passthrough does not skip feature evaluation`() {
        manager.grant("com.example.alpha")

        assertFalse(
            manager.shouldSkipFeatureEvaluation(
                "com.example.alpha",
                "REELS"
            )
        )
    }

    @Test
    fun `feature passthrough skips the matching feature only`() {
        manager.grant("com.example.alpha", "REELS")

        assertTrue(
            manager.shouldSkipFeatureEvaluation(
                "com.example.alpha",
                "REELS"
            )
        )
        assertFalse(
            manager.shouldSkipFeatureEvaluation(
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

    // --- Overlay-bypass detection: tabbing out of a blocked app and back in must re-block. ---

    @Test
    fun `blocked app returning to foreground while overlay flag set is treated as bypass`() {
        // The user tabbed out and re-opened the blocked app; its task comes forward directly,
        // orphaning the overlay. A real foreground switch must clear the stale flag so we re-block.
        assertTrue(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.instagram.android",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }

    @Test
    fun `own package window event does not count as overlay bypass`() {
        // The overlay's own window appearing is not a bypass — keep swallowing it.
        assertFalse(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.astraedus.nudge",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }

    @Test
    fun `system window while overlay up does not count as overlay bypass`() {
        // Launcher / systemui surfacing over the overlay is not the user re-entering the app.
        assertFalse(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.android.systemui",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }

    @Test
    fun `content change churn under a live overlay does not count as overlay bypass`() {
        // The blocked app animating/loading underneath a genuinely-live overlay must NOT clear the
        // flag — only a real foreground switch (WINDOW_STATE_CHANGED) does.
        assertFalse(
            NudgeAccessibilityService.isOverlayBypassedByForeground(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.instagram.android",
                ownPackageName = "com.astraedus.nudge"
            )
        )
    }
}
