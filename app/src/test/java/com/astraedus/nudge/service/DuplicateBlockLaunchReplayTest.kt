package com.astraedus.nudge.service

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.block.BlockLaunchGate.BlockIdentity
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.anActiveRule

/**
 * ISSUE [#131](https://github.com/astraedus/nudge/issues/131): *"each declined launch of a
 * Delay-rule app logs 2-3 `handling block` lines within ~20-40ms where exactly 1 is expected, so
 * the Blocked counter is inflated."*
 *
 * Replayed from `youtube-delay-double-launch.jsonl`, captured on the bench Pixel during
 * `walkaway-count` (Blocked +4 for 3 declines). Its header carries the same run's logcat for the
 * double launch. The capture holds the EVENTS; the service's decisions are not in the trace, so
 * this test makes them at the events the logcat ties them to:
 *
 *  1. YouTube's first content change of decline 2, while the launcher is still the foreground we
 *     observed: the foreground evaluation, dropped `DROP_FOREGROUND_MOVED` and deferred (#58).
 *  2. YouTube's `WINDOW_STATE_CHANGED`: the settle that redeems it, so a foreground evaluation is
 *     in flight on the IO scope.
 *  3. YouTube's next content change: the feature detector recognises Shorts, and its evaluation
 *     matches the WHOLE-APP Delay (`includeWholeAppRulesForFeature`), also on the IO scope.
 *
 * Both decisions then land within milliseconds, in either order (on the device the feature
 * detector's landed first at decline 2). Everything that decides is real: the classifier, the
 * guard and its gate, the rule engine, and [BlockLaunchGate.identityOf], which is the one function
 * `handleDecision` builds its keys with.
 *
 * Layer L2 (recorded-event replay). The fix is not ordering-based (it is the block's identity), so
 * rule (d)'s counterfactual is the PRE-FIX IDENTITY on the same events, in both landing orders:
 * it must still launch twice, which is what proves this fixture still reproduces the defect.
 */
class DuplicateBlockLaunchReplayTest {

    private val nudge = "dev.astraedus.nudge"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val youtube = "com.google.android.youtube"
    private val ime = "com.google.android.inputmethod.latin"

    /** Derived, never retyped: what the detector reports for Shorts (rule (b)). */
    private val shorts = InAppDetector.Feature.SHORTS.key

    /** The bench fixture's rule: YouTube, whole app, a 5-second Delay. */
    private val rules = listOf(
        anActiveRule(mode = BlockMode.DELAY, delaySeconds = 5, dailyLimitMinutes = null, enabled = true)
    )

    private val engine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE))

    private val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { null }
    )

    private val capture = A11yCapture.load("youtube-delay-double-launch")

    /** Which path asked: the foreground path asks about no feature, the detector about Shorts. */
    private enum class Path(val askedFeature: String?) { FOREGROUND(null), FEATURE("SHORTS") }

    /** How a launch's two keys are computed: the fix, or what `handleDecision` did before it. */
    private enum class Keys { FROM_DECISION, FROM_ASKING_PATH }

    /** The service's launch/claim/row sequence (`handleDecision`) around the real guard. */
    private inner class Service(private val keys: Keys) {
        var clock = 0L
        val guard = BlockLaunchGuard().also { it.nowMs = { clock } }
        var launches = 0
        var rows = 0

        fun deliver(record: AccessibilityEventRecord): ForegroundSignal {
            clock = record.eventTimeMs
            val signal = classifier.classify(
                record = record,
                currentImePackage = ime,
                launcherPackages = setOf(launcher),
                pipOnlyPackages = emptySet()
            )
            guard.onForegroundSignal(signal)
            return signal
        }

        /** `evaluateBlockUseCase` as each path calls it, with no pass held. */
        fun evaluate(path: Path): BlockDecision.Block = engine.evaluate(
            packageName = youtube,
            activeRules = rules,
            dailyUsageMs = 0L,
            detectedFeature = path.askedFeature?.let { shorts },
            includeWholeAppRulesForFeature = true
        ) as BlockDecision.Block

        private fun identity(decision: BlockDecision.Block, path: Path): BlockIdentity = when (keys) {
            Keys.FROM_DECISION -> BlockLaunchGate.identityOf(decision, youtube)
            Keys.FROM_ASKING_PATH -> {
                val feature = path.askedFeature?.let { shorts }
                BlockIdentity(
                    decisionKey = BlockLaunchGate.decisionFingerprint(
                        attributedPackage = youtube,
                        blockMode = decision.mode.name,
                        featureKey = feature,
                        dailyLimited = decision.dailyLimitMinutes != null
                    ),
                    confrontationKey = BlockLaunchGate.confrontationKey(youtube, featureKey = feature)
                )
            }
        }

        /** @return the gate's verdict, so the replay can check the fixture still says what it did. */
        fun handleDecision(path: Path): BlockLaunchGate.Decision {
            val keys = identity(evaluate(path), path)
            val verdict = guard.decide(youtube, keys.decisionKey)
            guard.onLaunchAttempt(youtube, verdict)
            if (verdict != BlockLaunchGate.Decision.LAUNCH) return verdict
            launches++
            guard.onOverlayLaunched(youtube, keys.decisionKey)
            if (guard.claimConfrontation(youtube, keys.confrontationKey)) rows++
            return verdict
        }

        /** "I changed my mind": the overlay is on screen, the user declines, it finishes. */
        fun decline() {
            guard.onOverlayShown()
            val id = guard.currentOverlayId()
            guard.onWalkAwayStarted(youtube)
            guard.onOverlayDismissed(id)
        }
    }

    private fun isYoutube(r: AccessibilityEventRecord) = r.packageName == youtube
    private fun isLauncherWindow(r: AccessibilityEventRecord) =
        r.packageName == launcher && r.type == A11yEventType.WINDOW_STATE_CHANGED

    /**
     * Replay the whole capture, making the service's decisions where the logcat put them, and land
     * decline 2's two in-flight decisions in [landingOrder].
     */
    private fun replay(keys: Keys, landingOrder: List<Path>): Service {
        val service = Service(keys)
        val declineOne = capture.indexOfFirst { isYoutube(it) && it.type == A11yEventType.WINDOW_STATE_CHANGED }
        val firstLanding = (declineOne until capture.size).first { isLauncherWindow(capture[it]) }
        val fallback = (firstLanding until capture.size).first { isYoutube(capture[it]) }
        val settle = (fallback until capture.size).first {
            isYoutube(capture[it]) && capture[it].type == A11yEventType.WINDOW_STATE_CHANGED
        }
        val featureRead = (settle + 1 until capture.size).first {
            isYoutube(capture[it]) && capture[it].type == A11yEventType.WINDOW_CONTENT_CHANGED
        }
        val secondLanding = (featureRead until capture.size).first { isLauncherWindow(capture[it]) }

        capture.forEachIndexed { i, record ->
            if (i == firstLanding || i == secondLanding) service.decline()
            val signal = service.deliver(record)
            when (i) {
                declineOne -> assertEquals(
                    "decline 1: YouTube's window is in front, and its block is shown",
                    BlockLaunchGate.Decision.LAUNCH,
                    service.handleDecision(Path.FOREGROUND)
                )
                fallback -> {
                    assertEquals(
                        "fixture: decline 2 opens on a content change while the launcher is still " +
                            "the observed foreground, which the device logged as DROP_FOREGROUND_MOVED",
                        A11yEventType.WINDOW_CONTENT_CHANGED,
                        record.type
                    )
                    assertEquals(
                        BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED,
                        service.handleDecision(Path.FOREGROUND)
                    )
                }
                settle -> assertTrue(
                    "fixture: YouTube's window settling redeems the dropped block (#58)",
                    service.guard.redeemDeferredLaunch(record.type, signal)
                )
                featureRead -> landingOrder.forEach { service.handleDecision(it) }
            }
        }
        return service
    }

    private val bothOrders = listOf(
        listOf(Path.FEATURE, Path.FOREGROUND),
        listOf(Path.FOREGROUND, Path.FEATURE)
    )

    @Test
    fun `one tap on a Delay-blocked app launches one overlay and writes one row`() {
        for (order in bothOrders) {
            val service = replay(Keys.FROM_DECISION, order)
            assertEquals(
                "landing order $order: two declines, two overlays (the second decision to land is " +
                    "the same block already in flight, DROP_ALREADY_PENDING)",
                2,
                service.launches
            )
            assertEquals("landing order $order: two declines, two Blocked rows", 2, service.rows)
        }
    }

    /**
     * RULE (d)'s COUNTERFACTUAL. The same events and the same decisions, keyed the way
     * `handleDecision` keyed them before #131: by the path that asked. It must still double-launch
     * and double-count in BOTH landing orders, or this fixture has stopped reproducing the bug and
     * the test above is asserting nothing.
     */
    @Test
    fun `counterfactual - keyed by the asking path, the same tap launches twice and counts twice`() {
        for (order in bothOrders) {
            val service = replay(Keys.FROM_ASKING_PATH, order)
            assertEquals("landing order $order: the device's 13ms double launch", 3, service.launches)
            assertEquals("landing order $order: and the Blocked +1 too many", 3, service.rows)
        }
    }

    /**
     * What the fix must NOT collapse: a genuine Shorts rule is a different block from the whole
     * app's, and still launches and counts on its own when it is the rule that decides.
     */
    @Test
    fun `a real Shorts rule is still its own block`() {
        val shortsRule = anActiveRule(
            mode = BlockMode.HARD_BLOCK,
            delaySeconds = 0,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = listOf(shorts)
        )
        val fromFeature = engine.evaluate(youtube, rules + shortsRule, 0L, detectedFeature = shorts)
            as BlockDecision.Block
        val fromForeground = engine.evaluate(youtube, rules + shortsRule, 0L) as BlockDecision.Block
        assertEquals(shorts, fromFeature.feature)
        assertTrue(
            BlockLaunchGate.identityOf(fromFeature, youtube) !=
                BlockLaunchGate.identityOf(fromForeground, youtube)
        )
    }
}
