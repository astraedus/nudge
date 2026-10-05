package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on WHERE the "Bro. wtf." check-in is fed inside [NudgeAccessibilityService].
 *
 * The detector's rules are tested in `BounceDetectorTest` and the adapter's in `BounceCheckInTest`.
 * Both stay green if the SERVICE stops calling them, or calls them from a branch an early return can
 * skip, which is the only way the feature can silently stop working. `docs/TESTING.md`: "nothing at
 * this layer can prove the adapter actually calls the decision class on every callback -- that half
 * stays a source-level contract test." Same shape as [TabCoverWiringContractTest].
 */
class BounceCheckInWiringContractTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private val code: String by lazy {
        stripComments(read("main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"))
    }

    private fun bodyOf(signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        var depth = 0
        var i = code.indexOf('{', start)
        val from = i
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after $signature")
    }

    /** Apps opened: from the one place that sees every classification, above every early return. */
    @Test
    fun `apps are fed from applyForegroundSignal`() {
        val body = bodyOf("private fun applyForegroundSignal(signal: ForegroundSignal)")
        assertTrue(body.contains("bounceCheckIn.onForegroundSignal(signal)"))
    }

    /**
     * Walls: every overlay launch the gate ALLOWS, and none it refuses. A dropped launch is no wall
     * the user ever saw, and counting it would fire the check-in on a gate's internal churn.
     */
    @Test
    fun `an overlay launch reports a wall only after the gate allowed it`() {
        val body = bodyOf("private fun launchBlockOverlay(")
        val refusal = body.indexOf("decision != BlockLaunchGate.Decision.LAUNCH")
        val wall = body.indexOf("bounceCheckIn.onWall(targetPackage)")
        val start = body.indexOf("startActivity(overlayIntent)")
        assertTrue("the gate refusal must be present", refusal >= 0)
        assertTrue("launchBlockOverlay must report the wall", wall >= 0)
        assertTrue("the wall is reported after the refusal returns", wall > refusal)
        assertTrue("and before the activity starts, on the same path", wall < start)
        assertTrue(
            "exactly one wall report per launch",
            Regex("""bounceCheckIn\.onWall\(""").findAll(body).count() == 1
        )
    }

    /** A kick is a wall: the ONE executor is wired to report it. */
    @Test
    fun `the auto-kick executor is wired to the check-in`() {
        val wiring = code.substringAfter("autoKickExecutor = AutoKickExecutor(")
            .substringBefore("autoKickTimeHandler = AutoKickTimeHandler(")
        assertTrue(wiring.contains("onKicked ="))
        assertTrue(wiring.contains("bounceCheckIn::onWall"))

        val executor = stripComments(read("main/java/com/astraedus/nudge/service/AutoKickExecutor.kt"))
        val kick = executor.substringAfter("fun kick(")
        assertTrue("kick() must report every kick", kick.contains("onKicked(packageName)"))
    }

    /**
     * The hot path never reads the preference. The switch is cached by a collector, and the
     * dispatch only ever touches the adapter.
     */
    @Test
    fun `the event path never reads the preference`() {
        val dispatch = bodyOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        assertFalse(dispatch.contains("bounceCheckInEnabled"))
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
