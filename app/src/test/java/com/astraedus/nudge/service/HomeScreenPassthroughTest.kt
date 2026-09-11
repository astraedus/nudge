package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.sitting.SittingEvent
import org.junit.Test

/**
 * Going HOME after completing a delay must re-arm the block.
 *
 * The defect: [NudgeAccessibilityService.SYSTEM_PACKAGES] contains the stock launchers and the
 * system-package early-return in `onAccessibilityEvent` fires long before `evaluateForegroundPackage`
 * reached the passthrough clear. So "pass YouTube's delay → Home → reopen YouTube"
 * skipped the delay indefinitely; only opening a DIFFERENT non-system app in between re-armed it.
 *
 * The fix must NOT be "clear for every system package": the shade, the IME, permission dialogs and
 * our own overlay surface briefly WITHOUT the user leaving the app, and re-delaying someone for
 * pulling the notification shade is a worse bug than the one being fixed. These tests pin both
 * directions.
 */
class HomeScreenPassthroughTest {

    private val ownPackage = "com.astraedus.nudge"
    private val pixelLauncher = "com.google.android.apps.nexuslauncher"
    private val launchers = setOf(pixelLauncher, "com.android.launcher3")

    private fun wentHome(
        packageName: String,
        eventType: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        launcherPackages: Set<String> = launchers,
        currentImePackage: String? = null
    ): Boolean = NudgeAccessibilityService.isHomeScreenForeground(
        eventType = eventType,
        packageName = packageName,
        launcherPackages = launcherPackages,
        ownPackageName = ownPackage,
        currentImePackage = currentImePackage
    )

    // --- The launcher IS leaving the app ---

    @Test
    fun `the default launcher coming to the front is the user leaving the app`() {
        assertTrue(wentHome(pixelLauncher))
    }

    @Test
    fun `every resolved home package counts, not just the default`() {
        assertTrue(wentHome("com.android.launcher3"))
    }

    @Test
    fun `a launcher that is not in the resolved set is not treated as home`() {
        // Fail toward doing nothing: an unresolved / stale launcher set behaves exactly like the
        // pre-fix service rather than guessing which package is home.
        assertFalse(wentHome("com.oem.someotherlauncher"))
    }

    @Test
    fun `an unresolvable launcher set clears nothing`() {
        assertFalse(wentHome(pixelLauncher, launcherPackages = emptySet()))
    }

    // --- Transient system surfaces are NOT leaving the app ---

    @Test
    fun `the notification shade is not leaving the app`() {
        assertFalse(wentHome("com.android.systemui"))
    }

    @Test
    fun `the active keyboard is not leaving the app`() {
        // The FUTO case from issue #5: matched dynamically, not from a hardcoded list.
        assertFalse(wentHome("org.futo.inputmethod.latin", currentImePackage = "org.futo.inputmethod.latin"))
    }

    @Test
    fun `a hardcoded IME is not leaving the app`() {
        assertFalse(wentHome("com.google.android.inputmethod.latin"))
    }

    @Test
    fun `the framework package hosting popups and toasts is not leaving the app`() {
        assertFalse(wentHome(NudgeAccessibilityService.FRAMEWORK_PACKAGE))
    }

    @Test
    fun `our own overlay is not leaving the app`() {
        assertFalse(wentHome(ownPackage, launcherPackages = launchers + ownPackage))
    }

    @Test
    fun `a permission dialog is not leaving the app`() {
        assertFalse(wentHome("com.android.permissioncontroller"))
    }

    @Test
    fun `the settings app is not leaving the app`() {
        assertFalse(wentHome("com.android.settings"))
    }

    @Test
    fun `a blank package is never home`() {
        assertFalse(wentHome(""))
    }

    // --- Only a real foreground change counts ---

    @Test
    fun `launcher content-change churn is not a trip home`() {
        // Widgets, wallpaper and the icon grid redraw behind a fullscreen app; none of that means
        // anything came forward. Same restriction isOverlayBypassedByForeground makes.
        assertFalse(wentHome(pixelLauncher, eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    }

    @Test
    fun `a windows-changed event from the launcher is not a trip home`() {
        assertFalse(wentHome(pixelLauncher, eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED))
    }

    @Test
    fun `a view-clicked event from the launcher is not a trip home`() {
        assertFalse(wentHome(pixelLauncher, eventType = AccessibilityEvent.TYPE_VIEW_CLICKED))
    }

    // --- Resolving the launcher set from a raw CATEGORY_HOME query ---

    @Test
    fun `settings FallbackHome never enters the launcher set`() {
        // AOSP declares Settings$FallbackHome with CATEGORY_HOME + CATEGORY_DEFAULT (the pre-unlock
        // placeholder home), so a stock queryIntentActivities really does return it. Treating it as
        // home would clear passthrough on every settings excursion.
        val resolved = NudgeAccessibilityService.sanitizeLauncherPackages(
            listOf(pixelLauncher, "com.android.settings"),
            ownPackage
        )
        assertEquals(setOf(pixelLauncher), resolved)
    }

    @Test
    fun `the framework resolver activity never enters the launcher set`() {
        // With no default home set, resolveActivity returns the framework's chooser.
        val resolved = NudgeAccessibilityService.sanitizeLauncherPackages(
            listOf(NudgeAccessibilityService.FRAMEWORK_PACKAGE, pixelLauncher),
            ownPackage
        )
        assertEquals(setOf(pixelLauncher), resolved)
    }

    @Test
    fun `our own package, blanks, nulls and IMEs never enter the launcher set`() {
        val resolved = NudgeAccessibilityService.sanitizeLauncherPackages(
            listOf(ownPackage, "", "  ", null, "com.google.android.inputmethod.latin", pixelLauncher),
            ownPackage
        )
        assertEquals(setOf(pixelLauncher), resolved)
    }

    @Test
    fun `duplicates from the default plus the full query collapse`() {
        val resolved = NudgeAccessibilityService.sanitizeLauncherPackages(
            listOf(pixelLauncher, pixelLauncher, "com.android.launcher3"),
            ownPackage
        )
        assertEquals(launchers, resolved)
    }

    @Test
    fun `an empty resolution yields an empty set rather than throwing`() {
        assertTrue(NudgeAccessibilityService.sanitizeLauncherPackages(emptyList(), ownPackage).isEmpty())
    }

    // --- What the clear actually does to state ---

    @Test
    fun `home clears a completed delay's passthrough so the app re-blocks`() {
        val passthrough = PassthroughManager()
        passthrough.grant("com.google.android.youtube")

        // Re-pinned onto the real revocation path (issue #28). Going Home is one of only two
        // signals SittingTracker allows to end a sitting, and ending a sitting is what revokes.
        val event = passthrough.onForegroundSignal(ForegroundSignal.Home(pixelLauncher), 1_000)

        assertTrue("going home must end the sitting", event is SittingEvent.Ended)
        assertFalse(passthrough.shouldSkipForegroundEvaluation("com.google.android.youtube"))
    }

    /**
     * Re-pinned, not deleted: a genuine app switch must still cost the pass, but issue #28 moved
     * WHEN. Another app in front for a few seconds is a sub-flow (a picker, a share sheet, a
     * permission dialog) and must NOT revoke; one that holds the foreground past the return window
     * is the user having actually left, and still does. Both halves are asserted here so neither
     * can be lost.
     */
    @Test
    fun `a different app clears passthrough once it has held the foreground past the return window`() {
        val passthrough = PassthroughManager()
        passthrough.grant("com.google.android.youtube")

        // A brief excursion is a sub-flow: the grant survives.
        passthrough.onForegroundSignal(ForegroundSignal.AppWindow("com.instagram.android"), 1_000)
        assertTrue(
            "a few seconds in another app must not revoke a pass the user earned",
            passthrough.shouldSkipForegroundEvaluation("com.google.android.youtube")
        )

        // Held past the window, it is a real app switch and the pass is gone.
        val window = InteractionTracker.SESSION_EXPIRY_MS
        passthrough.onForegroundSignal(
            ForegroundSignal.AppWindow("com.instagram.android"),
            1_000 + window
        )
        assertFalse(passthrough.shouldSkipForegroundEvaluation("com.google.android.youtube"))
    }

    @Test
    fun `clearing on home leaves the auto-kick cooldown alone`() {
        // Leaving the app revokes permission to SKIP the delay; it does not end the sitting. The
        // cooldown (and InteractionTracker's 5-minute session expiry) deliberately survive a quick
        // trip home, so a tab-out-and-back cannot refill a budget.
        val tracker = InteractionTracker()
        val passthrough = PassthroughManager()
        tracker.setCooldown("com.google.android.youtube", 60_000L)
        passthrough.grant("com.google.android.youtube")

        passthrough.onForegroundSignal(ForegroundSignal.Home(pixelLauncher), 1_000)

        assertTrue(tracker.isInCooldown("com.google.android.youtube"))
    }

    @Test
    fun `clearing on home leaves the session count alone`() {
        val tracker = InteractionTracker()
        val passthrough = PassthroughManager()
        tracker.onAppChanged("com.google.android.youtube")
        tracker.recordInteraction("com.google.android.youtube")
        tracker.recordInteraction("com.google.android.youtube")
        passthrough.grant("com.google.android.youtube")

        passthrough.onForegroundSignal(ForegroundSignal.Home(pixelLauncher), 1_000)

        assertEquals(2, tracker.getSessionCount("com.google.android.youtube"))
    }
}
