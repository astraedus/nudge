package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on WHERE the "Bro. wtf." check-in is fed inside [NudgeAccessibilityService].
 *
 * What the service feeds is decided in [BounceCheckIn] and tested as behaviour in
 * `BounceCheckInTest` (L3 pattern, `docs/testing-strategy.md`): which gate verdicts count as a
 * wall (`onBlockLaunched`), how a kick is attributed (`onKicked`), which signals open an app. What
 * no value test can prove is that the SERVICE calls those entry points from a path an early
 * return cannot skip, in the right order. That residue, and only that, is pinned here.
 */
class BounceCheckInWiringContractTest {

    private val service = SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService.kt")
    private val code: String by lazy { SourceScan.code(service) }

    private fun bodyOf(signature: String) = SourceScan.bodyOf(code, signature)

    /** Apps opened: from the one place that sees every classification, above every early return. */
    @Test
    fun `apps are fed from applyForegroundSignal`() {
        val body = bodyOf("private fun applyForegroundSignal(signal: ForegroundSignal)")
        assertTrue(
            "the check-in must be fed from the one classification point, or an early return " +
                "skips it and the feature silently stops",
            body.contains("bounceCheckIn.onForegroundSignal(signal)")
        )
    }

    /**
     * Walls: reported after the gate refusal has returned and before the activity starts, once per
     * launch. A report before the refusal counts a launch nobody saw; after the start it can be
     * skipped by a throw.
     */
    @Test
    fun `an overlay launch reports a wall once, between the gate refusal and the activity start`() {
        val body = bodyOf("private fun launchBlockOverlay(")
        val refusal = body.indexOf("decision != BlockLaunchGate.Decision.LAUNCH")
        val wall = body.indexOf("bounceCheckIn.onBlockLaunched(decision, targetPackage, webDomain)")
        val start = body.indexOf("startActivity(overlayIntent)")
        assertTrue("the gate refusal must be present", refusal >= 0)
        assertTrue("launchBlockOverlay must report the launch verdict, with its site", wall >= 0)
        assertTrue("the wall is reported after the refusal returns", wall > refusal)
        assertTrue("and before the activity starts, on the same path", wall < start)
        assertEquals(
            "exactly one wall report per launch",
            1,
            Regex("""bounceCheckIn\.onBlockLaunched\(""").findAll(body).count()
        )
    }

    /**
     * The site has to travel from the block DECISION to the overlay launch. Pinned here because the
     * detector tests call `onWall` directly: dropping `webDomain` at either call site would revert
     * #106 with every value test still green.
     */
    @Test
    fun `the web block paths hand their site to the overlay launch`() {
        assertTrue(
            "a rule or content-filter web block must pass the domain it was decided on",
            bodyOf("private suspend fun handleDecision(").contains("webDomain = web?.domain")
        )
        assertTrue(
            "the web auto-kick cooldown overlay must pass its domain",
            bodyOf("private fun enforceWebCooldown(").contains("webDomain = domain")
        )
    }

    /** A kick is a wall: the ONE executor is wired to report it, and reports every kick. */
    @Test
    fun `the auto-kick executor is wired to the check-in`() {
        val wiring = code.substringAfter("autoKickExecutor = AutoKickExecutor(")
            .substringBefore("autoKickTimeHandler = AutoKickTimeHandler(")
        assertTrue(
            "the executor's onKicked must hand the key and the browser to the check-in",
            wiring.contains("bounceCheckIn.onKicked(key, webSessionUsageProvider.browserPackage)")
        )
        val executor = SourceScan.code(
            SourceScan.kotlinFile("com/astraedus/nudge/service/AutoKickExecutor.kt")
        )
        assertTrue(
            "kick() must report every kick",
            executor.substringAfter("fun kick(").contains("onKicked(packageName)")
        )
    }

    /**
     * The hot path never reads the preference. The switch is cached by a collector, and the
     * dispatch only ever touches the adapter.
     */
    @Test
    fun `the event path never reads the preference`() {
        val dispatch = bodyOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        assertFalse(
            "a DataStore read per accessibility event is exactly what the hot path must never do",
            dispatch.contains("bounceCheckInEnabled")
        )
        assertTrue(
            "the switch is collected once, off the event path",
            code.contains("bounceCheckInEnabled.collect")
        )
    }

    /** Turning Nudge off behaves as if uninstalled, a half-finished streak included. */
    @Test
    fun `the master toggle off resets the check-in`() {
        assertTrue(bodyOf("private fun onGlobalDisabled()").contains("bounceCheckIn.reset()"))
    }
}
