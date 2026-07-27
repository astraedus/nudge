package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for issue #7: "occasionally app timer does not start on re-entrance of app".
 *
 * Re-entering an app via the recents overview or a notification tap sometimes delivers only
 * TYPE_WINDOW_CONTENT_CHANGED — no TYPE_WINDOW_STATE_CHANGED — and only in-app-detection packages
 * (Instagram/YouTube/TikTok) fell through to foreground evaluation on those events. For every other
 * app the return produced no evaluation at all: no delay re-block, no counter session, no
 * time-remaining overlay.
 *
 * The fallback must NOT reintroduce issue #5 (a keyboard / framework popup / background window
 * being mistaken for an app switch, which would wipe post-delay passthrough and re-block the user),
 * so it is state-verified against the real active window and rejects every transient package.
 */
class ContentChangeAppSwitchTest {

    private val own = "com.astraedus.nudge"
    private val keep = "com.google.android.keep"
    private val discord = "com.discord"
    private val futo = "org.futo.inputmethod.latin"

    /** Convenience wrapper: the active window is whatever the OS would report at that moment. */
    private fun shouldSwitch(
        packageName: String,
        lastPackage: String?,
        activeWindowPackage: String?,
        currentImePackage: String? = futo
    ): Boolean = NudgeAccessibilityService.shouldTreatContentChangeAsAppSwitch(
        packageName = packageName,
        lastPackage = lastPackage,
        ownPackageName = own,
        currentImePackage = currentImePackage,
        activeWindowPackage = { activeWindowPackage }
    )

    // --- The issue #7 fix: a verified re-entry is evaluated ---

    @Test
    fun `returning to a different app that owns the active window is a foreground switch`() {
        // The exact issue #7 repro: user was last in Discord, taps Keep in the recents overview,
        // and the OS delivers only a content change. Keep genuinely owns the active window, so the
        // return must be evaluated and the block re-asserted.
        assertTrue(shouldSwitch(packageName = keep, lastPackage = discord, activeWindowPackage = keep))
    }

    @Test
    fun `a first-ever event with no known last package is a foreground switch when verified`() {
        assertTrue(shouldSwitch(packageName = keep, lastPackage = null, activeWindowPackage = keep))
    }

    // --- Not a switch: the app we are already in ---

    @Test
    fun `content changes from the app already in the foreground are not a switch`() {
        // Ordinary churn while using the app. Re-evaluating on this would be pointless work, and
        // for a package still holding passthrough it would run the skip path over and over.
        assertFalse(shouldSwitch(packageName = keep, lastPackage = keep, activeWindowPackage = keep))
    }

    // --- Not a switch: unverified against the active window (issue #5 regression guards) ---

    @Test
    fun `a content change from a package that does not own the active window is ignored`() {
        // Content-change events also come from windows that are not in front (picture-in-picture,
        // background windows still updating). Acting on these would be a ghost app switch that
        // clears the passthrough of the app the user is actually looking at — issue #5 all over.
        assertFalse(shouldSwitch(packageName = discord, lastPackage = keep, activeWindowPackage = keep))
    }

    @Test
    fun `an unreadable active window is never treated as a switch`() {
        // rootInActiveWindow can be null or throw. Unverifiable means do nothing: a false positive
        // costs the user their passthrough, a false negative costs nothing (the next event retries).
        assertFalse(shouldSwitch(packageName = keep, lastPackage = discord, activeWindowPackage = null))
    }

    // --- Not a switch: transient and system windows (issue #5 regression guards) ---

    @Test
    fun `the active keyboard is never a foreground switch even if it owns the active window`() {
        // Issue #5's exact bug shape: the reporter's FUTO keyboard surfaced as a different package
        // after a completed delay. Routing that into evaluation cleared passthrough and re-blocked
        // the app on the next tap. It must be rejected here even when fully "verified".
        assertFalse(shouldSwitch(packageName = futo, lastPackage = keep, activeWindowPackage = futo))
    }

    @Test
    fun `a hardcoded keyboard is never a foreground switch`() {
        assertFalse(
            shouldSwitch(
                packageName = "com.google.android.inputmethod.latin",
                lastPackage = keep,
                activeWindowPackage = "com.google.android.inputmethod.latin",
                currentImePackage = null
            )
        )
    }

    @Test
    fun `the android framework package (paste and long-press popups) is never a foreground switch`() {
        assertFalse(
            shouldSwitch(
                packageName = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
                lastPackage = keep,
                activeWindowPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE
            )
        )
    }

    @Test
    fun `system packages are never a foreground switch`() {
        NudgeAccessibilityService.SYSTEM_PACKAGES.forEach { system ->
            assertFalse(
                "expected $system to be rejected as a foreground switch",
                shouldSwitch(packageName = system, lastPackage = keep, activeWindowPackage = system)
            )
        }
    }

    @Test
    fun `our own package is never a foreground switch`() {
        // The block overlay and the guard overlay are Nudge activities; evaluating on their own
        // content changes would let Nudge block itself.
        assertFalse(shouldSwitch(packageName = own, lastPackage = keep, activeWindowPackage = own))
    }

    @Test
    fun `a blank package is never a foreground switch`() {
        assertFalse(shouldSwitch(packageName = "", lastPackage = keep, activeWindowPackage = ""))
    }

    // --- Cost: the active-window read is the expensive part of this hot path ---

    @Test
    fun `the active window is not read for packages rejected by the cheap checks`() {
        // This runs on every content-change event of every app, so the node-tree read must be
        // reached only when the cheap comparisons cannot already rule the event out.
        var reads = 0
        val counting = { reads++; keep }

        fun check(packageName: String, lastPackage: String?) =
            NudgeAccessibilityService.shouldTreatContentChangeAsAppSwitch(
                packageName = packageName,
                lastPackage = lastPackage,
                ownPackageName = own,
                currentImePackage = futo,
                activeWindowPackage = counting
            )

        check(packageName = keep, lastPackage = keep)      // same app — the common case
        check(packageName = own, lastPackage = keep)       // our own overlay
        check(packageName = futo, lastPackage = keep)      // the keyboard
        check(packageName = "com.android.systemui", lastPackage = keep)
        assertEquals(0, reads)

        check(packageName = keep, lastPackage = discord)   // a real candidate
        assertEquals(1, reads)
    }
}
