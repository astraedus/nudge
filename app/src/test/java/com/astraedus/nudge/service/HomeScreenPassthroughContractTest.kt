package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for the ORDERING inside `NudgeAccessibilityService.onAccessibilityEvent`.
 *
 * The bug was not in any value a unit test can inspect — it was in where an early return sat. The
 * `SYSTEM_PACKAGES` branch returns at ~line 690 while `PassthroughManager.clearIfAppChanged` lives
 * at ~line 884 inside `evaluateForegroundPackage`, so going Home never cleared a completed delay's
 * passthrough and re-opening the app skipped the delay indefinitely. The service is not JVM-testable
 * (real `AccessibilityService`, Hilt entry point, live windows), so this pins the shape, exactly as
 * `BlockOverlayWalkAwayContractTest` and `ImportedSettingsWriteContractTest` already do elsewhere.
 */
class HomeScreenPassthroughContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"),
            File("app/src/main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt")
        )
        (candidates.firstOrNull { it.exists() }
            ?: error("NudgeAccessibilityService.kt not found from ${File("").absolutePath}"))
            .readText()
    }

    /** Body of the `if (packageName in SYSTEM_PACKAGES) { … }` branch, up to its clearOverlays call. */
    private val systemPackageBranch: String by lazy {
        val start = source.indexOf("if (packageName in SYSTEM_PACKAGES) {")
        assertTrue("the SYSTEM_PACKAGES branch must still exist", start >= 0)
        val end = source.indexOf("clearOverlays(packageName, \"system_package\"", start)
        assertTrue("the SYSTEM_PACKAGES branch must still clear overlays", end > start)
        source.substring(start, end)
    }

    private val clearForHomeBody: String by lazy {
        val start = source.indexOf("private fun clearPassthroughForHome(")
        assertTrue("clearPassthroughForHome must exist", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        source.substring(start, if (end > start) end else source.length)
    }

    /**
     * The fix itself: the launcher must be recognised INSIDE the system-package branch, before it
     * returns. Anything that moves the home check after the return re-introduces the bug.
     */
    @Test
    fun `the system-package branch decides whether the user went home before returning`() {
        assertTrue(
            "going Home must clear passthrough before the SYSTEM_PACKAGES early-return",
            systemPackageBranch.contains("wentHome(") &&
                systemPackageBranch.contains("clearPassthroughForHome(")
        )
    }

    /**
     * The regression that would be worse than the bug: clearing for EVERY system package would
     * re-delay a user for pulling the notification shade or dismissing a permission dialog.
     */
    @Test
    fun `passthrough is not cleared unconditionally for system packages`() {
        assertFalse(
            "the shade / IME / permission dialogs must not clear passthrough",
            systemPackageBranch.contains("passthroughManager().clear()") ||
                systemPackageBranch.contains("passthrough.clear()")
        )
    }

    /** Home is "the user left the app" for the web passthrough too, not just the app-level one. */
    @Test
    fun `going home clears both the app and the web passthrough`() {
        assertTrue(
            "must clear the app-level grant",
            clearForHomeBody.contains("clearIfAppChanged(")
        )
        // The web grant moved out of a `lastBlockedDomain` field on the service and into
        // `PassthroughManager` alongside the app-level one (v1.15.2), so that both axes have one
        // lifetime and one clearing path. The requirement is unchanged: going Home must drop it.
        assertTrue(
            "must clear the web-domain grant",
            clearForHomeBody.contains("clearWebGrant()")
        )
        assertTrue(
            "leaving the browser must also stop the web foreground-time clock",
            clearForHomeBody.contains("endWebSession(")
        )
    }

    /**
     * Scope discipline. `InteractionTracker`'s 5-minute session expiry and the auto-kick cooldown
     * deliberately treat a quick trip home as the SAME sitting (a tab-out-and-back must not refill a
     * time budget). Leaving the app revokes permission to SKIP a delay; it does not end the session.
     */
    @Test
    fun `the home clear touches no session or cooldown bookkeeping`() {
        listOf(
            "resetSession",
            "setCooldown",
            "clearCooldown",
            "onAppChanged",
            "interactionTracker()",
            "setSessionUsageBaseline"
        ).forEach { forbidden ->
            assertFalse(
                "clearPassthroughForHome must not touch $forbidden",
                clearForHomeBody.contains(forbidden)
            )
        }
    }

    /**
     * Issue #7 / #5 ordering, unchanged: the Strict Mode escape guard must still run BEFORE the
     * system-package return (it is what catches the Settings → Accessibility escape route), and the
     * transient-window return must still sit upstream of it.
     */
    @Test
    fun `the strict-mode escape guard still runs before the system-package return`() {
        val transient = source.indexOf("if (isTransientNonAppPackage(packageName, currentImePackage)) {")
        val guard = source.indexOf("maybeGuardSettingsEscape(packageName)")
        val systemReturn = source.indexOf("if (packageName in SYSTEM_PACKAGES) {")
        assertTrue("transient-window return must exist", transient >= 0)
        assertTrue("escape guard must run before the SYSTEM_PACKAGES return", guard in (transient + 1) until systemReturn)
    }

    /**
     * The global master toggle must stay downstream of the system-package return — the gate is only
     * correct because every enforcement path is below it, and this change must not have moved it.
     */
    @Test
    fun `the global toggle gate still sits after the system-package return`() {
        val systemReturn = source.indexOf("if (packageName in SYSTEM_PACKAGES) {")
        val globalGate = source.indexOf("if (!globalEnabledCached) {")
        assertTrue("global gate must exist", globalGate >= 0)
        assertTrue("global gate must follow the SYSTEM_PACKAGES branch", globalGate > systemReturn)
    }

    /**
     * The launcher set must be RESOLVED, never hardcoded: the default home app is user-choosable and
     * OEM-specific, and `SYSTEM_PACKAGES` already proves a hardcoded list goes stale (it is why a
     * third-party keyboard hit issue #5).
     */
    @Test
    fun `the launcher set is resolved from PackageManager at runtime`() {
        assertTrue(
            "home packages must come from a CATEGORY_HOME resolution",
            source.contains("Intent.CATEGORY_HOME")
        )
        assertTrue(
            "the resolution must be refreshed, not resolved once forever",
            source.contains("refreshLauncherPackagesIfStale(")
        )
    }
}
