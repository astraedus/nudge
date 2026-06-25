package com.astraedus.nudge.domain.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure Strict Mode OS-escape guard matcher.
 *
 * The window-text fixtures below are the REAL, concatenated visible text harvested from a Pixel 3
 * (Android 12, AOSP/Pixel Settings) on 2026-06-24 via `astra-adb dump`, app label
 * "Nudge - App Blocker". Keeping them real is the whole point: the matcher is only as good as the
 * actual node signatures it keys on.
 *
 * Safety bias under test: the matcher must guard the two escape routes, must NOT trap the user on
 * the accessibility LIST page (which also shows our label) or on unrelated Settings pages, and must
 * fully disengage when Strict Mode is off or a grace window is active.
 */
class StrictModeEscapeGuardTest {

    private val label = "Nudge - App Blocker"
    private val settingsPkg = "com.android.settings"

    /** Real text from the Nudge Accessibility detail page (where the service toggle lives). */
    private val accessibilityDetailText = """
        Use Nudge - App Blocker
        Nudge - App Blocker shortcut
        Nudge monitors which app is in the foreground to apply your blocking rules. No data leaves your device.
        Options
        Off
    """.trimIndent()

    /** Real text from the Nudge App Info page (Force stop / Uninstall). */
    private val appInfoText = """
        Nudge - App Blocker
        Open
        Uninstall
        Force stop
        Notifications
        Permissions
        Storage and cache
        Screen time
    """.trimIndent()

    /** Real text from the Accessibility LIST page — shows our label but must NOT be guarded. */
    private val accessibilityListText = """
        Downloaded apps
        Nudge - App Blocker
        Off
        TalkBack
        Screen reader
        Text and display
        Display
        Extra dim
    """.trimIndent()

    // ── the required matrix ──

    @Test
    fun `settings + a11y detail + strict on + no grace -- guard`() {
        assertTrue(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = accessibilityDetailText,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `same a11y detail but strict OFF -- no guard`() {
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = accessibilityDetailText,
                appLabel = label,
                strictEnabled = false,
                withinGrace = false
            )
        )
    }

    @Test
    fun `same a11y detail but within grace -- no guard`() {
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = accessibilityDetailText,
                appLabel = label,
                strictEnabled = true,
                withinGrace = true
            )
        )
    }

    @Test
    fun `non-settings package -- no guard`() {
        // Even if the text somehow contained our label + signals, a non-settings host is ignored.
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = "com.instagram.android",
                windowText = accessibilityDetailText,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `settings page without our app signals -- no guard`() {
        // Generic Settings home / unrelated page: no app label, no escape signals.
        val unrelated = """
            Network & internet
            Connected devices
            Apps
            Notifications
            Battery
            Storage
        """.trimIndent()
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = unrelated,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `app info with uninstall and force stop -- guard`() {
        assertTrue(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = appInfoText,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    // ── false-positive guards (safety bias: fewer false positives) ──

    @Test
    fun `accessibility LIST page is NOT guarded even though it shows our label`() {
        // Critical: the list of all accessibility services contains "Nudge - App Blocker" but no
        // "shortcut"/"Use <label>"/force-stop/uninstall signal. Guarding here would trap the user.
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = accessibilityListText,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `another app's app-info page (force stop or uninstall) without our label -- no guard`() {
        val otherAppInfo = """
            Some Other App
            Uninstall
            Force stop
            Notifications
        """.trimIndent()
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = otherAppInfo,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `blank app label fails closed -- no guard even on the real detail page`() {
        assertFalse(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = accessibilityDetailText,
                appLabel = "",
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `matching is case-insensitive on both label and signals`() {
        val upper = accessibilityDetailText.uppercase()
        assertTrue(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = settingsPkg,
                windowText = upper,
                appLabel = label.uppercase(),
                strictEnabled = true,
                withinGrace = false
            )
        )
    }

    @Test
    fun `tolerated OEM settings package is matched`() {
        assertTrue("com.miui.securitycenter" in StrictModeEscapeGuard.SETTINGS_PACKAGES)
        assertTrue(
            StrictModeEscapeGuard.shouldGuardSettingsScreen(
                foregroundPkg = "com.miui.securitycenter",
                windowText = appInfoText,
                appLabel = label,
                strictEnabled = true,
                withinGrace = false
            )
        )
    }
}
