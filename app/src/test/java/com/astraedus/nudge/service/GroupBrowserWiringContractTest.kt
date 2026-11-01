package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the four service sites where a browser covered by a group rule is evaluated as an app (spec
 * 5.5, #109). The decisions themselves are pure and tested at their own layers
 * (`WholeAppCoverageTest`, `PassthroughWholeAxisTest`, `EvaluateBlockGroupRulesTest`) and replayed
 * against a device capture in `GroupBrowserCaptureReplayTest`; the service is not JVM-constructible
 * (real `AccessibilityService`, Hilt entry point, live windows), so this pins that it calls them,
 * the same tool `NukeWiringContractTest` uses for the same reason.
 *
 *  1. The foreground path routes a browser through `evaluateBrowser` with its coverage.
 *  2. The content-change browser branch does the same, minus a live grant.
 *  3. Feature detection steps aside for a covered browser.
 *  4. Both grant checks let a covered browser through only on a WHOLE-app grant.
 */
class GroupBrowserWiringContractTest {

    private fun read(relative: String): String = listOf(File("src/main/java/$relative"), File("app/src/main/java/$relative"))
        .firstOrNull { it.exists() }
        ?.readText()
        ?: error("$relative not found from ${File("").absolutePath}")

    /** Code only: these sources explain in prose the very calls they must not make. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    private val service by lazy { code(read("com/astraedus/nudge/service/NudgeAccessibilityService.kt")) }

    private fun body(source: String, signature: String, nextSignature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = source.indexOf(nextSignature, start + signature.length)
        assertTrue("$nextSignature must follow $signature", end > start)
        return source.substring(start, end)
    }

    private fun squash(text: String) = text.replace(Regex("""\s+"""), " ")

    private val evaluateForeground by lazy {
        body(service, "private fun evaluateForegroundPackage(", "private suspend fun evaluateWebDomain(")
    }
    private val evaluateBrowser by lazy {
        body(service, "private suspend fun evaluateBrowser(", "private suspend fun onWebDomainForeground(")
    }
    private val contentChanged by lazy {
        body(service, "private fun handleWindowContentChanged(", "private fun detectAndEvaluateFeature(")
    }
    private val featureDetection by lazy {
        body(service, "private fun detectAndEvaluateFeature(", "private fun maintainHostSurfaces(")
    }
    private val grantAlive by lazy {
        body(service, "private fun grantAliveFor(", "private fun floatingVerdictFor(")
    }
    private val grantLets by lazy {
        body(service, "private fun grantLetsThrough(", "private fun passthroughEarned(")
    }
    private val earned by lazy {
        body(service, "private fun passthroughEarned(", "private fun evaluateForegroundPackage(")
    }
    private val coverage by lazy {
        body(service, "private fun isCoveredWholeNow(", "private var currentImePackage")
    }

    @Test
    fun `site 1 - the foreground path routes a browser by its coverage`() {
        val squashed = squash(evaluateForeground)
        assertTrue(
            "the pinned nuke-aware browser branch stays",
            squashed.contains("if (!nuked && entryPoint.webDomainDetector().isBrowser(packageName)) {")
        )
        assertTrue(
            squashed.contains(
                "isBrowser(packageName)) { evaluateBrowser(packageName, coveredWhole = isCoveredWholeNow(packageName)) }"
            )
        )
        assertFalse("never straight to the website path", evaluateForeground.contains("evaluateWebDomain("))
    }

    @Test
    fun `site 2 - a content change in a covered browser re-checks it as an app unless a grant lets it through`() {
        assertTrue(contentChanged.contains("isCoveredWholeNow(packageName) && !grantAliveFor(packageName)"))
        assertTrue(contentChanged.contains("evaluateBrowser(packageName, coveredWhole)"))
        assertFalse("never straight to the website path", contentChanged.contains("evaluateWebDomain("))
    }

    @Test
    fun `site 3 - feature detection steps aside for a covered browser, right after the nuke line`() {
        val squashed = squash(featureDetection)
        assertTrue(
            squashed.contains("if (isNukedNow(packageName)) return if (isCoveredWholeNow(packageName)) return")
        )
    }

    @Test
    fun `site 4 - both grant checks go through passthroughEarned, which asks for a whole grant`() {
        assertTrue(grantAlive.contains("passthroughEarned(packageName, entryPoint.passthroughManager())"))
        assertTrue(grantLets.contains("passthroughEarned(packageName, passthrough)"))
        assertTrue(earned.contains("isCoveredWholeNow(packageName)"))
        assertTrue(earned.contains("passthrough.isGrantedWhole(packageName)"))
        assertTrue(earned.contains("passthrough.shouldSkipForegroundEvaluation(packageName)"))
        assertFalse(
            "the plain grant must not be asked anywhere but passthroughEarned",
            grantAlive.contains("shouldSkipForegroundEvaluation(") || grantLets.contains("shouldSkipForegroundEvaluation(")
        )
    }

    @Test
    fun `evaluateBrowser evaluates group rules only and blocks the browser itself`() {
        assertTrue(evaluateBrowser.contains("BrowserRouting.route(coveredWhole)"))
        assertTrue(evaluateBrowser.contains("excludeDirectRules = true"))
        assertTrue(evaluateBrowser.contains("BrowserRouting.continuesToWeb("))
        assertTrue(evaluateBrowser.contains("handleDecision(decision, browserPackage)"))
        assertFalse("an app block carries no website", evaluateBrowser.contains("web ="))
        assertTrue(evaluateBrowser.contains("evaluateWebDomain(browserPackage)"))
    }

    @Test
    fun `the website path is reached only through evaluateBrowser`() {
        val calls = Regex("""(?<![.\w])evaluateWebDomain\(""").findAll(service).count()
        // One declaration, one call (in evaluateBrowser).
        assertEquals(2, calls)
    }

    @Test
    fun `coverage reads the warm snapshot and asks the same schedule the engine does`() {
        assertTrue(coverage.contains("isBrowser(packageName)"))
        assertTrue(coverage.contains("ruleSnapshotSource().snapshot.value ?: return false"))
        assertTrue(coverage.contains("WholeAppCoverage.coversNow("))
        assertTrue(coverage.contains("isActiveNow("))
    }
}
