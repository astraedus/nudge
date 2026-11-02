package com.astraedus.nudge.domain.events

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.BrowserRouting
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.service.AwarenessOverlayWindow
import com.astraedus.nudge.service.WebDomainDetector
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2: the device-captured Chrome session (`chrome-open-and-browse.jsonl`: open Chrome, load
 * wikipedia.org then youtube.com, HOME) replayed under an All apps rule (spec 5.5).
 *
 * The same decision chain as [GroupBrowserCaptureReplayTest] (classifier, then [BrowserRouting],
 * then the real [EvaluateBlockUseCase]), with the routing question asked of the use case's own
 * [EvaluateBlockUseCase.isCoveredWhole]: the call `evaluateBrowser` makes. An All apps rule covers a
 * launchable browser like any other app, so a covered Chrome is evaluated as an app and blocked; a
 * Chrome on the rule's allowed list is not covered and goes to its websites.
 */
class AllAppsBrowserCaptureReplayTest {

    private val chrome = "com.android.chrome"
    private val pixelLauncher = "com.google.android.apps.nexuslauncher"

    private val classifier = EventClassifier(
        ownPackageName = "dev.astraedus.nudge",
        systemPackages = setOf(
            "com.android.systemui",
            "com.android.launcher3",
            pixelLauncher,
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller"
        ),
        imePackages = { setOf("com.google.android.inputmethod.latin") },
        frameworkPackage = "android",
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { null }
    )

    private fun classify(record: AccessibilityEventRecord) = classifier.classify(
        record = record,
        currentImePackage = "com.google.android.inputmethod.latin",
        launcherPackages = setOf(pixelLauncher),
        pipOnlyPackages = emptySet()
    )

    private fun reachesDispatch(signal: ForegroundSignal) =
        signal is ForegroundSignal.AppWindow || signal is ForegroundSignal.NotForeground

    private fun allAppsRule(exceptPackages: String? = null) = aBlockRule(
        id = 1,
        allApps = true,
        exceptPackages = exceptPackages,
        mode = BlockMode.HARD_BLOCK.name
    )

    private fun useCaseFor(rule: BlockRule, launchable: Set<String> = setOf(chrome)): EvaluateBlockUseCase {
        val snapshot = RuleSnapshot.of(enabledRules = listOf(rule), groups = emptyList(), members = emptyList())
        return EvaluateBlockUseCase(
            ruleSnapshots = object : RuleSnapshots {
                override suspend fun current() = snapshot
                override suspend fun enabledRules() = snapshot.enabledRules
            },
            usageRepository = FakeUsageRepository(),
            blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE)),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
            preferences = FakeNudgePreferences(),
            contentFilter = mockk<ContentFilter>(),
            nukeEnforcement = NukeEnforcement.NEVER,
            essentialPackages = FakeEssentialPackages(),
            packageFacts = FakePackageFacts(launchable = launchable)
        )
    }

    private val detector = WebDomainDetector()

    private class Outcome(var browserEvaluations: Int = 0, var webEvaluations: Int = 0) {
        val blocks = mutableListOf<BlockDecision.Block>()
    }

    /** The browser evaluations the service would run over the capture, routed by [coveredWhole]. */
    private suspend fun replay(useCase: EvaluateBlockUseCase, coveredWhole: suspend (String) -> Boolean): Outcome {
        val outcome = Outcome()
        for (record in A11yCapture.load("chrome-open-and-browse")) {
            if (!reachesDispatch(classify(record))) continue
            val pkg = record.packageName
            if (!detector.isBrowser(pkg)) continue
            val isBrowserEvaluation = record.type.isWindowChange ||
                record.type == A11yEventType.WINDOW_CONTENT_CHANGED
            if (!isBrowserEvaluation) continue
            outcome.browserEvaluations++

            if (BrowserRouting.route(coveredWhole(pkg)) == BrowserRouting.Route.APP_FIRST) {
                val decision = useCase.invoke(pkg, excludeDirectRules = true)
                if (!BrowserRouting.continuesToWeb(decision)) {
                    outcome.blocks += decision as BlockDecision.Block
                    continue
                }
            }
            outcome.webEvaluations++
        }
        return outcome
    }

    @Test
    fun `a covered Chrome is evaluated as an app and every evaluation blocks`() = runTest {
        val useCase = useCaseFor(allAppsRule())
        assertTrue("Chrome is covered by the All apps rule", useCase.isCoveredWhole(chrome))

        val outcome = replay(useCase) { useCase.isCoveredWhole(it) }

        assertTrue("non-vacuous: the capture drives browser evaluations", outcome.browserEvaluations >= 10)
        assertEquals("every evaluation blocks", outcome.browserEvaluations, outcome.blocks.size)
        assertTrue(outcome.blocks.all { it.mode == BlockMode.HARD_BLOCK && it.ruleName == RuleEvaluator.ALL_APPS_NAME })
        assertTrue("the block screen gets the All apps line", outcome.blocks.all { it.allApps != null })
        assertEquals("no website evaluation at all", 0, outcome.webEvaluations)
    }

    @Test
    fun `a Chrome on the allowed list is not covered and goes to its websites`() = runTest {
        val useCase = useCaseFor(allAppsRule(exceptPackages = chrome))
        assertFalse(useCase.isCoveredWhole(chrome))

        val outcome = replay(useCase) { useCase.isCoveredWhole(it) }

        assertTrue(outcome.browserEvaluations >= 10)
        assertEquals(0, outcome.blocks.size)
        assertEquals(outcome.browserEvaluations, outcome.webEvaluations)
        // The All apps rule names no websites, so the sites the capture visited open.
        for (url in listOf("https://en.wikipedia.org/wiki/Cat", "https://www.youtube.com")) {
            assertEquals(url, BlockDecision.Allow, useCase.evaluateWebDomain(url).decision)
        }
    }

    /** A browser with no launcher entry is not one the user can open, so All apps does not cover it. */
    @Test
    fun `a Chrome with no launcher entry is not covered`() = runTest {
        val useCase = useCaseFor(allAppsRule(), launchable = emptySet())
        assertFalse(useCase.isCoveredWhole(chrome))
    }

    /**
     * The counterfactual: routing that asks only about group rules (the shape before All apps
     * existed) sends every evaluation to the website path, and the All apps rule names no websites,
     * so Chrome opens youtube.com under a rule that is meant to block everything.
     */
    @Test
    fun `routing that ignores the All apps rule never blocks`() = runTest {
        val useCase = useCaseFor(allAppsRule())
        val outcome = replay(useCase) { false }

        assertTrue(outcome.browserEvaluations >= 10)
        assertEquals(0, outcome.blocks.size)
        assertEquals(outcome.browserEvaluations, outcome.webEvaluations)
        assertEquals(BlockDecision.Allow, useCase.evaluateWebDomain("https://www.youtube.com").decision)
    }

    /** The app-level evaluation keeps the All apps rule: `excludeDirectRules` drops only rules naming the package. */
    @Test
    fun `the browser's app evaluation keeps the All apps rule`() = runTest {
        val decision = useCaseFor(allAppsRule()).invoke(chrome, excludeDirectRules = true)
        assertNotNull((decision as BlockDecision.Block).allApps)
    }
}
