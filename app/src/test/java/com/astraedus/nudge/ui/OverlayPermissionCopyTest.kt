package com.astraedus.nudge.ui

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level copy gate on the "Display Over Other Apps" / "Overlay Permission" rows in
 * onboarding and Settings.
 *
 * ## The defect this pins
 * SYSTEM_ALERT_WINDOW draws the block screen, but it does a second, much less visible job: a
 * granted overlay permission helps `NudgeMonitorService` restart itself when
 * `ProtectionWatchdogWorker` finds it dead, because Android 12+ refuses to start a foreground
 * service from the background at all without it — see
 * `docs/architecture/service-lifecycle-and-watchdog.md`, "Real start paths for the foreground
 * service". Both onboarding and Settings described only the first job ("shows block screens") and
 * let the user skip the permission with no idea that doing so also makes it more likely the app
 * cannot heal itself overnight. That is the same shape of defect issue #23 already burned a Play
 * review on: a failure mode invisible to the user until blocking has already quietly stopped.
 *
 * Deliberately worded as "helps" / "more likely", never "guarantees" / "always" or "never": Android
 * 16 (API 36) narrowed the overlay exemption background restarts rely on, so even a granted
 * permission is not a hard guarantee on every OS version. Opening Nudge yourself is the one thing
 * that reliably restarts it regardless of Android version, which both screens' copy says.
 *
 * ## Why a source-grep test, not a Compose UI test
 * Both screens are large, hand-styled Composables with no test tags on this copy, and Settings'
 * description is a plain conditional expression, not a resource lookup — there is nothing here a
 * Robolectric or instrumented render would check more cheaply than reading the literal string. This
 * follows the same shape as `ProtectionAlertCopyTest` (service copy) and `MonitorServiceContractTest`
 * (service shape): assert on the properties of the real source text.
 *
 * ## Counterfactual (run manually, not by CI)
 * Deleting the "helps Nudge restart its own protection" sentence from `OnboardingScreen.kt`, or
 * deleting the `if (overlayEnabled) { ... } else { ... }` cost branches from `SettingsScreen.kt` in
 * favor of the old static "Required to show block screens" string, makes this test fail — confirmed
 * by hand, twice (once for the original wording, again after this copy was softened to drop the
 * "guarantees a restart" overclaim): both sentences were removed, the corresponding
 * `mentions ... restart cost` / `is sharper when ... not granted` test failed with the expected
 * assertion message on both screens, and the text was restored. A future editor who trims this copy
 * back to "just describes the overlay" will hit that same failure, which is the point: the cost
 * sentence is load-bearing, not decoration.
 */
class OverlayPermissionCopyTest {

    private fun read(relativePath: String): String = SourceScan.mainFile(relativePath).readText()

    private val onboardingSource by lazy {
        read("java/com/astraedus/nudge/ui/screens/onboarding/OnboardingScreen.kt")
    }

    private val settingsSource by lazy {
        read("java/com/astraedus/nudge/ui/screens/settings/SettingsScreen.kt")
    }

    /**
     * Isolates the overlay permission's copy from the rest of a large file by anchoring on its
     * title and cutting off at the next `PermissionCard`/`PermissionItem` call (or, failing that,
     * a generous fixed window). This is what keeps an unrelated edit elsewhere in either screen
     * from accidentally starving or satisfying this test.
     */
    private fun overlaySnippet(source: String, titleLiteral: String): String {
        val titleIndex = source.indexOf(titleLiteral)
        assertTrue("expected to find the overlay permission row titled $titleLiteral", titleIndex >= 0)
        val searchFrom = titleIndex + titleLiteral.length
        val nextCallIndex = Regex("""PermissionCard\(|PermissionItem\(""")
            .find(source, searchFrom)?.range?.first
            ?: (searchFrom + 800).coerceAtMost(source.length)
        return source.substring(titleIndex, nextCallIndex)
    }

    private val onboardingOverlaySnippet by lazy {
        overlaySnippet(onboardingSource, "\"Display Over Other Apps\"")
    }

    private val settingsOverlaySnippet by lazy {
        overlaySnippet(settingsSource, "\"Overlay Permission\"")
    }

    @Test
    fun `onboarding overlay copy mentions both the block screen and the restart cost`() {
        val lower = onboardingOverlaySnippet.lowercase()

        assertTrue(
            "Onboarding's Display Over Other Apps card must still say it draws the block " +
                "screen/delay/breathing overlay — that is the permission's other job and dropping " +
                "it would leave the user with no idea what they are granting at all.",
            lower.contains("overlay") && lower.contains("block")
        )
        assertTrue(
            "Onboarding's Display Over Other Apps card must say skipping it can stop Nudge from " +
                "restarting its own protection in the background after the phone kills it (issue " +
                "#62) — without this sentence the user has no way to know that declining this " +
                "permission silently breaks the watchdog's ability to heal a dead service.",
            lower.contains("restart") && lower.contains("background")
        )
    }

    @Test
    fun `settings overlay copy is sharper when the permission is not granted`() {
        val lower = settingsOverlaySnippet.lowercase()

        assertTrue(
            "Settings' Overlay Permission row must mention the block screen somewhere across its " +
                "granted/not-granted branches.",
            lower.contains("block")
        )
        assertTrue(
            "Settings' Overlay Permission row must mention the background restart cost somewhere " +
                "across its granted/not-granted branches — deleting this is the exact regression " +
                "issue #62 exists to prevent, matching the sibling accessibilityCrashed row's " +
                "pattern of naming the real consequence of the current state.",
            lower.contains("restart") && lower.contains("background")
        )
        assertTrue(
            "Settings' Overlay Permission description must branch on the live overlayEnabled " +
                "state, the same pattern the sibling Accessibility row already uses for " +
                "accessibilityCrashed — a single static string cannot say 'off' only when it is " +
                "actually off.",
            settingsOverlaySnippet.contains("if (overlayEnabled)") ||
                settingsOverlaySnippet.contains("if(overlayEnabled)")
        )
    }

    @Test
    fun `overlay copy never leaks implementation jargon to the user`() {
        val jargon = listOf("foreground service", "system_alert_window", "exemption")

        listOf(
            "onboarding" to onboardingOverlaySnippet,
            "settings" to settingsOverlaySnippet
        ).forEach { (screen, snippet) ->
            val lower = snippet.lowercase()
            jargon.forEach { term ->
                assertFalse(
                    "$screen's overlay copy must not say \"$term\" — this row is user-facing " +
                        "copy, not a code comment, and Android/AOSP terms belong in the source " +
                        "comment next to it, not the string the user reads.",
                    lower.contains(term)
                )
            }
        }
    }
}
