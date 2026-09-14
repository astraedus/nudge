package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for the ORDERING inside `NudgeAccessibilityService.onAccessibilityEvent`.
 *
 * The bug was not in any value a unit test can inspect — it was in where an early return sat. The
 * `SYSTEM_PACKAGES` branch returned ~200 lines before the passthrough clear inside
 * `evaluateForegroundPackage`, so going Home never cleared a completed delay's
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

    /**
     * Body of the system-surface / Home branch, up to its clearOverlays call.
     *
     * The branch used to be `if (packageName in SYSTEM_PACKAGES)`, re-deriving from the package set
     * what the rest of the file was deriving separately. It now reads the single classified signal
     * (issue #28) — the same branch, asking someone else the question.
     */
    private val systemPackageBranch: String by lazy {
        val start = source.indexOf(
            "if (signal is ForegroundSignal.Home || signal is ForegroundSignal.SystemSurface) {"
        )
        assertTrue("the system-surface / Home branch must still exist", start >= 0)
        val end = source.indexOf("clearOverlays(packageName, \"system_package\"", start)
        assertTrue("the system-surface branch must still clear overlays", end > start)
        source.substring(start, end)
    }

    private val clearForHomeBody: String by lazy {
        val start = source.indexOf("private fun onWentHome(")
        assertTrue("onWentHome must exist", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        source.substring(start, if (end > start) end else source.length)
    }

    /**
     * The fix itself, re-pinned onto the new shape: going Home must still be distinguished from
     * every other system surface INSIDE this branch, before it returns.
     *
     * What changed is where the REVOCATION happens. It is no longer inside this branch at all — the
     * single `applyForegroundSignal(signal)` call at the top of `onAccessibilityEvent` performs it, because
     * `ForegroundSignal.Home` is one of only two signals `SittingTracker` allows to end a sitting.
     * That is strictly stronger than the old arrangement: the old one relied on this branch
     * remembering to call the clear, and an early return added above it would have re-broken it.
     * `EventDispatchOrderContractTest` pins the new guarantee; this pins that the branch still
     * knows the difference.
     */
    @Test
    fun `the system-package branch decides whether the user went home before returning`() {
        assertTrue(
            "the branch must still distinguish Home from the rest before it returns",
            systemPackageBranch.contains("signal is ForegroundSignal.Home") &&
                systemPackageBranch.contains("onWentHome(")
        )
        val applyForegroundSignal = source.indexOf("applyForegroundSignal(signal)")
        val branch = source.indexOf(
            "if (signal is ForegroundSignal.Home || signal is ForegroundSignal.SystemSurface) {"
        )
        assertTrue(
            "the sitting (and therefore the revocation) must be applied before this branch returns",
            applyForegroundSignal in 0 until branch
        )
    }

    /**
     * The regression that would be worse than the bug: clearing for EVERY system package would
     * re-delay a user for pulling the notification shade or dismissing a permission dialog.
     *
     * Now guaranteed twice over — this branch must not clear, and `SittingTracker` cannot end a
     * sitting on a `SystemSurface` signal at all (`SittingTrackerTest."no non-app signal can end a
     * sitting"`).
     */
    @Test
    fun `passthrough is not cleared unconditionally for system packages`() {
        assertFalse(
            "the shade / IME / permission dialogs must not clear passthrough",
            systemPackageBranch.contains("passthroughManager().clear()") ||
                systemPackageBranch.contains("passthrough.clear()") ||
                systemPackageBranch.contains("clearIfAppChanged(")
        )
    }

    /**
     * Home is "the user left the app" for the web passthrough too, not just the app-level one.
     *
     * Re-pinned: both grants are now dropped by ONE call — `PassthroughManager.clear()`, reached
     * from the sitting transition — instead of two calls this function had to remember. The
     * requirement is unchanged and the "second piece of state to remember" trap is gone, so the
     * assertion moves to where the guarantee now lives.
     */
    @Test
    fun `going home clears both the app and the web passthrough`() {
        assertTrue(
            "ending a sitting must clear the grant",
            passthroughManagerSource.contains("is SittingEvent.Ended -> clear()")
        )
        assertTrue(
            "clear() must drop the app-level grant",
            passthroughManagerSource.contains("lastPackage = null")
        )
        assertTrue(
            "clear() must drop the web-domain grant — one call, both axes",
            passthroughManagerSource.contains("lastDomain = null")
        )
        assertTrue(
            "going home must also stop the web foreground-time clock",
            clearForHomeBody.contains("endWebSession(")
        )
    }

    private val passthroughManagerSource: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/astraedus/nudge/service/PassthroughManager.kt"),
            File("app/src/main/java/com/astraedus/nudge/service/PassthroughManager.kt")
        )
        (candidates.firstOrNull { it.exists() } ?: error("PassthroughManager.kt not found")).readText()
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
                "onWentHome must not touch $forbidden",
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
        val transient = source.indexOf("if (signal is ForegroundSignal.Transient) {")
        val guard = source.indexOf("maybeGuardSettingsEscape(packageName)")
        val systemReturn = source.indexOf(
            "if (signal is ForegroundSignal.Home || signal is ForegroundSignal.SystemSurface) {"
        )
        assertTrue("transient-window return must exist", transient >= 0)
        assertTrue("system-surface branch must exist", systemReturn >= 0)
        assertTrue(
            "escape guard must run before the system-surface return",
            guard in (transient + 1) until systemReturn
        )
    }

    /**
     * The global master toggle must stay downstream of the system-package return — the gate is only
     * correct because every enforcement path is below it, and this change must not have moved it.
     */
    @Test
    fun `the global toggle gate still sits after the system-package return`() {
        val systemReturn = source.indexOf(
            "if (signal is ForegroundSignal.Home || signal is ForegroundSignal.SystemSurface) {"
        )
        val globalGate = source.indexOf("if (!globalEnabledCached) {")
        assertTrue("global gate must exist", globalGate >= 0)
        assertTrue("global gate must follow the system-surface branch", globalGate > systemReturn)
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
