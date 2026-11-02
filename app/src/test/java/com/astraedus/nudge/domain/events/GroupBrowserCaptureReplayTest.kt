package com.astraedus.nudge.domain.events

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.BrowserRouting
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.engine.WholeAppCoverage
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.service.AwarenessOverlayWindow
import com.astraedus.nudge.service.WebDomainDetector
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.engine.CoverageFacts

/**
 * L2: the device-captured Chrome session (`chrome-open-and-browse.jsonl`: open Chrome, load
 * wikipedia.org then youtube.com, HOME) replayed through the classifier and the browser routing, with
 * a group Hard block covering Chrome (spec 5.5, #109).
 *
 * The service is not JVM-constructible, so this replays its decision chain on the pure parts it is
 * made of, the same chain `GroupBrowserWiringContractTest` pins in the source:
 *  1. [EventClassifier] decides which events reach the service's dispatch (an app window or a
 *     non-foreground event; Home, system surfaces, our own UI and transients return first).
 *  2. A browser event that reaches it is a browser evaluation: a window change goes to
 *     `evaluateForegroundPackage` (site 1), a content change to the browser branch of
 *     `handleWindowContentChanged` (site 2). Both call `evaluateBrowser`, which routes by
 *     [BrowserRouting] over [WholeAppCoverage].
 *  3. An APP_FIRST route evaluates the browser with the real [EvaluateBlockUseCase]
 *     (`excludeDirectRules = true`); only an Allow continues to website evaluation.
 *
 * Debounces are not modelled: they only drop evaluations, and every one that survives them is one of
 * the evaluations counted here, so "every evaluation blocks, none reaches the website path" holds
 * for any subset.
 */
class GroupBrowserCaptureReplayTest {

    private val chrome = "com.android.chrome"
    private val pixelLauncher = "com.google.android.apps.nexuslauncher"
    private val social = 7L

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

    /** The signals the service's dispatch handles; every other one returns before it. */
    private fun reachesDispatch(signal: ForegroundSignal) =
        signal is ForegroundSignal.AppWindow || signal is ForegroundSignal.NotForeground

    private val snapshot = RuleSnapshot.of(
        enabledRules = listOf(aBlockRule(id = 1, groupId = social, mode = BlockMode.HARD_BLOCK.name)),
        groups = listOf(anAppGroup("Social", id = social)),
        members = listOf(aGroupMember(social, chrome), aGroupMember(social, "com.instagram.android"))
    )

    private val useCase = EvaluateBlockUseCase(
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
        packageFacts = FakePackageFacts()
    )

    private val detector = WebDomainDetector()
    private val schedule = ScheduleEvaluator(FirstUnlockState.NONE)

    private class Outcome(var browserEvaluations: Int = 0, var webEvaluations: Int = 0) {
        val blocks = mutableListOf<BlockDecision.Block>()
    }

    /** The browser evaluations the service would run over the capture, with [coveredWhole] routing. */
    private suspend fun replay(coveredWhole: (String) -> Boolean): Outcome {
        val outcome = Outcome()
        for (record in A11yCapture.load("chrome-open-and-browse")) {
            val signal = classify(record)
            if (!reachesDispatch(signal)) continue
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

    private fun coveredNow(pkg: String) =
        WholeAppCoverage.coversNow(
            pkg,
            snapshot.ruleData,
            snapshot.membership,
            CoverageFacts(essential = false, launchable = true, category = null, browser = false)
        ) { schedule.isActiveNow(it) }

    @Test
    fun `Chrome reaches the foreground in the capture`() {
        val chromeForeground = A11yCapture.load("chrome-open-and-browse")
            .filter { it.type == A11yEventType.WINDOW_STATE_CHANGED }
            .map { classify(it) }
            .count { it == ForegroundSignal.AppWindow(chrome) }
        assertTrue("the oracle's premise: Chrome comes to the front", chromeForeground >= 1)
    }

    @Test
    fun `a group Hard block covering Chrome blocks every Chrome evaluation as an app, no website evaluation`() = runTest {
        assertTrue("Chrome is covered by the group", coveredNow(chrome))
        val outcome = replay(::coveredNow)

        assertTrue("non-vacuous: the capture drives browser evaluations", outcome.browserEvaluations >= 10)
        assertEquals("every evaluation blocks", outcome.browserEvaluations, outcome.blocks.size)
        assertTrue(outcome.blocks.all { it.mode == BlockMode.HARD_BLOCK && it.ruleName == "Social" })
        assertEquals("no website evaluation at all", 0, outcome.webEvaluations)
    }

    /**
     * The counterfactual: before spec 5.5 every browser routed to the website path, whatever group
     * it was in. The group rule has no websites, so the sites the capture visited are allowed: the
     * escape #109 reported.
     */
    @Test
    fun `pre-fix routing sends every evaluation to the website path and never blocks`() = runTest {
        val outcome = replay { false }

        assertTrue(outcome.browserEvaluations >= 10)
        assertEquals(0, outcome.blocks.size)
        assertEquals(outcome.browserEvaluations, outcome.webEvaluations)
        for (url in listOf("https://en.wikipedia.org/wiki/Cat", "https://www.youtube.com")) {
            assertEquals(url, BlockDecision.Allow, useCase.evaluateWebDomain(url).decision)
        }
    }

    @Test
    fun `routing truth table`() {
        assertEquals(BrowserRouting.Route.APP_FIRST, BrowserRouting.route(coveredWhole = true))
        assertEquals(BrowserRouting.Route.WEB, BrowserRouting.route(coveredWhole = false))
        assertTrue(BrowserRouting.continuesToWeb(BlockDecision.Allow))
        assertTrue(!BrowserRouting.continuesToWeb(BlockDecision.Block(mode = BlockMode.DELAY, delaySeconds = 5)))
    }
}
