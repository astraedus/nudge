package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHERE Nuke sits in the service and the overlay (docs/architecture/nuke-mode.md).
 *
 * The decisions are pure and tested at L1 (`BlockEngineNukeTest`, `EvaluateBlockNukeTest`,
 * `NukePolicyTest`). What those cannot see is whether the service CONSULTS them in the right place:
 * a Nuke check placed below the emergency-pass return would compile, pass every value test, and let
 * the daily 2-minute pass open a nuked app. The service is not JVM-constructible (real
 * `AccessibilityService`, Hilt entry point, live windows), so these pin the shape, the same tool
 * `HomeScreenPassthroughContractTest` and `EventDispatchOrderContractTest` use for the same reason.
 * Device confirmation is the L6 case list in the Nuke PR.
 */
class NukeWiringContractTest {

    private fun read(relative: String): String = SourceScan.kotlinFile(relative).readText()

    /** Code only: these sources explain in prose the very calls they must not make. */
    private fun code(text: String): String = SourceScan.stripComments(text)

    private val service by lazy { code(read("com/astraedus/nudge/service/NudgeAccessibilityService.kt")) }

    private fun body(source: String, signature: String, nextSignature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = source.indexOf(nextSignature, start + signature.length)
        assertTrue("$nextSignature must follow $signature", end > start)
        return source.substring(start, end)
    }

    private val evaluateForeground by lazy {
        body(service, "private fun evaluateForegroundPackage(", "private suspend fun evaluateWebDomain(")
    }
    private val grants by lazy {
        body(service, "private fun grantLetsThrough(", "private fun evaluateForegroundPackage(")
    }

    @Test
    fun `every grant lives in grantLetsThrough, and nowhere else on the foreground path`() {
        listOf(
            "emergencyPassManager().isPassActive(packageName)",
            "CooldownGate.shouldEnforce(",
            "passthrough.shouldSkipForegroundEvaluation(packageName)"
        ).forEach { grant ->
            assertTrue("$grant must be inside grantLetsThrough", grants.contains(grant))
            assertFalse(
                "$grant must not be consulted directly by evaluateForegroundPackage, where Nuke " +
                    "could not skip it",
                evaluateForeground.contains(grant)
            )
        }
    }

    @Test
    fun `a nuked app never consults a grant`() {
        val nukedAt = evaluateForeground.indexOf("val nuked = isNukedNow(packageName)")
        val grantAt = evaluateForeground.indexOf("grantLetsThrough(")
        assertTrue("the Nuke check must exist", nukedAt >= 0)
        assertTrue("the grants must still be consulted for everything else", grantAt >= 0)
        assertTrue("the Nuke check must come first", nukedAt < grantAt)
        assertEquals("grantLetsThrough is called exactly once", 1, Regex("""grantLetsThrough\(""").findAll(evaluateForeground).count())
        assertTrue(
            "and only when not nuked, on one line",
            evaluateForeground.contains("if (!nuked && grantLetsThrough(packageName, passthrough)) return")
        )
    }

    @Test
    fun `a nuked browser is evaluated as an app, not as a website`() {
        assertTrue(
            evaluateForeground.contains("if (!nuked && entryPoint.webDomainDetector().isBrowser(packageName))")
        )
    }

    @Test
    fun `the content-change and in-app feature paths know about Nuke`() {
        val contentChanged = body(
            service,
            "private fun handleWindowContentChanged(",
            "private fun detectAndEvaluateFeature("
        )
        val nukeAt = contentChanged.indexOf("isNukedNow(packageName)")
        val browserAt = contentChanged.indexOf("webDomainDetector().isBrowser(packageName)")
        assertTrue("a nuked browser must not reach the URL-bar path first", nukeAt in 0 until browserAt)

        val feature = body(service, "private fun detectAndEvaluateFeature(", "private fun maintainHostSurfaces(")
        assertTrue(
            "feature detection must stand down for a nuked app, which the foreground path blocks whole",
            feature.trimStart().contains("if (isNukedNow(packageName)) return")
        )
    }

    @Test
    fun `turning Nuke on re-evaluates the app in front`() {
        val collector = service.substring(service.indexOf("nukeState.collect"))
        assertTrue(collector.contains("reevaluateForegroundForNuke("))
        val reevaluate = body(service, "private fun reevaluateForegroundForNuke(", "override fun onAccessibilityEvent(")
        assertTrue("the debounce must be spent", reevaluate.contains("lastEvalTime = 0L"))
        assertTrue(reevaluate.contains("evaluateForegroundPackage(front)"))
    }

    @Test
    fun `the Nuke block reaches the overlay flagged, with its own fingerprint`() {
        val handle = body(service, "private suspend fun handleDecision(", "private suspend fun enforceExhaustedBudget(")
        assertTrue(handle.contains("putExtra(BlockOverlayActivity.EXTRA_NUKE, decision.nuke)"))
        // The fingerprint itself is a value now (#131), pinned by `BlockIdentityTest`: what is left
        // to pin here is that the launch takes its keys from that one function.
        assertTrue(handle.contains("BlockLaunchGate.identityOf(decision, packageName, web?.domain)"))
    }

    @Test
    fun `Nuke engages the OS escape-route guard`() {
        val guard = body(service, "private fun maybeGuardSettingsEscape(", "private fun harvestWindowText(")
        assertTrue(guard.contains("if (!strictModeEnabledCached && !nukeGuarding) return"))
        assertTrue(guard.contains("putExtra(StrictModeGuardActivity.EXTRA_NUKE, nukeGuarding)"))
        assertTrue(guard.contains("NukeEmergencyCode.LENGTH"))
    }

    // --- the overlay ----------------------------------------------------------------------------

    private val overlayActivity by lazy { code(read("com/astraedus/nudge/ui/overlay/BlockOverlayActivity.kt")) }
    private val nukeContent by lazy { code(read("com/astraedus/nudge/ui/overlay/NukeBlockContent.kt")) }

    @Test
    fun `the Nuke overlay has no daily pass, and cannot be given one`() {
        assertFalse(nukeContent.contains("EmergencyPassAction("))
        assertFalse(nukeContent.contains("onUseEmergencyPass"))
        assertFalse(nukeContent.contains("canUseEmergencyPass"))
    }

    @Test
    fun `the Nuke overlay's only way off the screen is the walk-away`() {
        assertTrue(
            "the Nuke overlay's button must be the ordinary walk-away (row, GLOBAL_ACTION_HOME, #26 window)",
            Regex("""NukeBlockContent\([^)]*onGoHome = \{ navigateHome\(\) \}""").containsMatchIn(overlayActivity)
        )
        assertFalse("a Nuke block must never grant passthrough", nukeContent.contains("passthroughManager"))
        assertFalse(nukeContent.contains("finish()"))
        assertTrue(overlayActivity.contains("BlockMode.HARD_BLOCK -> if (nuke) {"))
    }
}
