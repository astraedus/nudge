package com.astraedus.nudge.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for "the Settings screen's permission ticks track reality", in the same spirit
 * as `ScreenTimeSourceContractTest`: the defect was not in any VALUE a unit test could inspect, it
 * was in the SHAPE of the code — `remember { mutableStateOf(x) }` reads `x` once at first composition
 * and never again, AND (a second bug layered under the first) the read itself was the wrong signal.
 *
 * The bug: `accessibilityEnabled`, `overlayEnabled` and `usageStatsEnabled` were each a one-shot
 * `remember { mutableStateOf(...) }` reading `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
 * directly. Verified against AOSP master (`AccessibilityServiceConnection.binderDied` ->
 * `mCrashedServices`, `AccessibilityManagerService.updateServicesLocked`'s explicit
 * `if (...getCrashedServicesLocked().contains(componentName)) continue;`): when an accessibility
 * service's process is killed — an LMK reap overnight, an OEM kill — the component is left IN that
 * settings string forever; AOSP never rebinds it and only an explicit force-stop removes it. So even
 * a LIVE ContentObserver on that exact string would keep reporting "enabled" over a permanently dead
 * service — the same false green tick the fix exists to delete, just refreshed more often. Real
 * liveness only exists in `AccessibilityManager.getEnabledAccessibilityServiceList(...)`
 * (`ProtectionStatus.isAccessibilityServiceConnected`), which is why the tick must be
 * "granted AND connected" (`ProtectionStatus.isAccessibilityServiceWorking`), not "granted" alone.
 * `docs/BACKLOG.md` filed this after a v1.12.0 QA pass, and it's the leading theory for our only Play
 * review: 3 stars, "It doesn't work sometimes" — a user with no way to see their own protection had
 * silently died.
 *
 * Nothing here can be caught by exercising the screen on the JVM (it needs a real Android
 * `Context`, `ContentResolver`, `AccessibilityManager` and `Lifecycle`), so the shape is what gets
 * pinned: any future edit that reintroduces a one-shot read, or decides the tick from the settings
 * string alone, fails here.
 */
class LivePermissionStateContractTest {

    /**
     * The file's CODE, with comments stripped.
     *
     * These assertions are about what the code does, and the file deliberately documents the defect
     * it was fixed for — naming `mutableStateOf` and `ENABLED_ACCESSIBILITY_SERVICES` in prose so the
     * next reader knows why they are shaped this way. Scanning raw text would make writing that
     * explanation fail the test that protects it.
     */
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    /** Collapse whitespace so a reformat (line wrap, indent change) can't dodge a regex match. */
    private fun normalized(text: String): String =
        text.replace(Regex("""\s+"""), " ")

    private val settingsScreen =
        "main/java/com/astraedus/nudge/ui/screens/settings/SettingsScreen.kt"

    /** The exact shape that shipped the bug: a permission read wrapped directly in `remember`. */
    @Test
    fun `no permission is read exactly once via a bare remember mutableStateOf`() {
        val text = normalized(source(settingsScreen))

        listOf(
            "remember { mutableStateOf(ProtectionStatus.isAccessibilityServiceEnabled",
            "remember { mutableStateOf(ProtectionStatus.isAccessibilityServiceWorking",
            "remember { mutableStateOf(ProtectionStatus.isAccessibilityServiceGranted",
            "remember { mutableStateOf(isAccessibilityEnabled",
            "remember { mutableStateOf(Settings.canDrawOverlays",
            "remember { mutableStateOf(hasUsageStatsPermission"
        ).forEach { oneShotRead ->
            assertFalse(
                "$settingsScreen must not contain `$oneShotRead` inside a bare " +
                    "`remember { mutableStateOf(...) }` — that reads the permission ONCE at first " +
                    "composition and never again, which is how a green tick survived the " +
                    "accessibility service dying underneath it.",
                text.contains(normalized(oneShotRead))
            )
        }
    }

    /**
     * Accessibility state needs a LIVE watch, not just a resume-time recheck — the user can be
     * sitting in the system Settings screen in a split window right next to this one when the
     * service gets disabled, and the tick must not wait for a navigation event that never comes.
     */
    @Test
    fun `accessibility state is watched live via a ContentObserver on the accessibility services URI`() {
        val text = source(settingsScreen)

        assertTrue(
            "$settingsScreen must register a ContentObserver so accessibility state updates live " +
                "while the screen is on screen, not only when the user navigates back to it",
            text.contains("ContentObserver")
        )
        assertTrue(
            "$settingsScreen must watch ProtectionStatus.ACCESSIBILITY_SERVICES_URI specifically — " +
                "that is the one URI the accessibility settings actually change",
            text.contains("ProtectionStatus.ACCESSIBILITY_SERVICES_URI")
        )
        assertTrue(
            "$settingsScreen must unregister its ContentObserver — a registered observer that " +
                "outlives the screen is a leak on every single visit to Settings",
            text.contains("unregisterContentObserver")
        )
    }

    /**
     * Overlay and usage-stats grants are AppOpsManager modes with no watchable Settings.Secure key,
     * so they can only be caught by rechecking when the user comes back — which is also exactly when
     * they return from the system permission screen this screen just sent them to.
     */
    @Test
    fun `all three permissions are rechecked on lifecycle resume`() {
        val text = source(settingsScreen)

        assertTrue(
            "$settingsScreen must recheck permission state on ON_RESUME — overlay and usage-stats " +
                "grants have no watchable key, so a resume-time recheck is their only path to a live " +
                "answer, and it's what catches the moment the user returns from the system screen",
            text.contains("ON_RESUME")
        )
        assertTrue(
            "$settingsScreen must remove its lifecycle observer on dispose — a leaked " +
                "LifecycleEventObserver is a leak on every visit to Settings, same as the " +
                "ContentObserver",
            text.contains("removeObserver")
        )
    }

    /**
     * The settings string is INTENT, not liveness (see class doc — AOSP leaves a crashed service IN
     * it forever). A tick driven by `isAccessibilityServiceGranted` alone, or by the raw settings
     * string, would be green over a service the system has already given up on rebinding — the exact
     * failure this fix exists to delete, just observed more promptly. The tick must come from the
     * connected/working answer instead.
     */
    @Test
    fun `accessibility state is not decided from the granted signal alone`() {
        val text = source(settingsScreen)

        assertTrue(
            "$settingsScreen must read ProtectionStatus.isAccessibilityServiceConnected (directly, " +
                "or via isAccessibilityServiceWorking) — the settings string alone is INTENT, not " +
                "liveness: AOSP leaves a crashed service's component IN that string forever, so a " +
                "granted-only tick is green over a service the system will never rebind",
            text.contains("ProtectionStatus.isAccessibilityServiceConnected") ||
                text.contains("ProtectionStatus.isAccessibilityServiceWorking")
        )
        assertFalse(
            "$settingsScreen must not read Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES itself — " +
                "that is a second, private answer to a question ProtectionStatus already answers, " +
                "and it is the exact string that survives a crash",
            text.contains("ENABLED_ACCESSIBILITY_SERVICES")
        )
    }

    /**
     * A user whose switch already reads "on" cannot be told to grant a permission they already
     * granted — that copy would read as nonsense. The crashed state needs its own recovery message
     * (toggle off/on, or reboot), which is the only user-accessible exit from AOSP's crashed set.
     */
    @Test
    fun `a crashed accessibility service gets its own recovery copy, not the default grant prompt`() {
        val text = source(settingsScreen)

        assertTrue(
            "$settingsScreen must track a distinct 'granted but not connected' state (e.g. " +
                "accessibilityCrashed) — a switch reading on with a dead service behind it needs " +
                "different copy from a switch that was never turned on",
            text.contains("Crashed") || text.contains("crashed")
        )
    }
}
