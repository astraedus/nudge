package com.astraedus.nudge.service

import com.astraedus.nudge.domain.block.BlockLaunchGate
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
 * ISSUE [#143](https://github.com/astraedus/nudge/issues/143): a cold launch of Google Camera under
 * a Delay rule skipped the block screen, 5/5 on the bench Pixel 3.
 *
 * Replayed from `camera-cold-trampoline-pending-drop.jsonl`, whose header carries the same run's
 * logcat. The overlay's `START` lands, then CameraLauncher re-fronts Camera's own task and bounces
 * through `permissioncontroller`'s `GrantPermissionsActivity`. The overlay activity is never
 * created, so it never reports itself shown. The service then:
 *
 *  1. at Camera's first window: evaluates the block and launches the overlay (pending, unshown);
 *  2. at the permission dialog's window: sees a genuine bypass and marks the overlay inactive;
 *  3. at Camera's next content change, verified as a switch back (#7's fallback; Camera fires no
 *     second window event): evaluates the same block, and the gate refused it as
 *     `DROP_ALREADY_PENDING`, a duplicate of an overlay that would never be drawn.
 *
 * The capture holds the EVENTS; the service's decisions are not in it, so this test makes them at
 * the events the logcat ties them to. Everything that decides is real: the classifier, the guard
 * and its gate, the rule engine, and [BlockLaunchGate.identityOf].
 *
 * Layer L2 (recorded-event replay). The fix is not an ordering fix, so rule (d)'s counterfactual is
 * the PRE-FIX GATE on the same events: a re-show refused as already pending, which must still leave
 * the overlay buried. The tap must count once whichever side of the displacement the first launch's
 * claim lands on (it runs on the IO scope on the device), so both claim orders are replayed.
 */
class TrampolineLaunchReplayTest {

    private val nudge = "dev.astraedus.nudge"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val camera = "com.google.android.GoogleCamera"
    private val permissionController = "com.google.android.permissioncontroller"
    private val ime = "com.google.android.inputmethod.latin"

    /** The bench fixture's rule: Camera, whole app, a 15-second Delay. */
    private val rules = listOf(
        anActiveRule(mode = BlockMode.DELAY, delaySeconds = 15, dailyLimitMinutes = null, enabled = true)
    )

    private val engine = BlockEngine(ScheduleEvaluator())

    private val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { null }
    )

    private val capture = A11yCapture.load("camera-cold-trampoline-pending-drop")

    /** Which gate decides: the fix, or the pre-#143 gate, which refused every re-show as pending. */
    private enum class Gate { FIXED, PRE_FIX }

    /** Where the first launch's IO-scope claim lands relative to the permission dialog's window. */
    private enum class ClaimLanding { BEFORE_DISPLACEMENT, AFTER_DISPLACEMENT }

    /**
     * The service's `isOverlayActive` bypass branch and `launchBlockOverlay`/`handleDecision`
     * sequence, around the real guard.
     */
    private inner class Service(private val gate: Gate) {
        var clock = 0L
        val guard = BlockLaunchGuard().also { it.nowMs = { clock } }
        var overlayActive = false
        var starts = 0
        var rows = 0
        val startedAtMs = mutableListOf<Long>()
        val verdicts = mutableListOf<BlockLaunchGate.Decision>()
        private var owedClaim: String? = null

        /**
         * `onAccessibilityEvent` up to evaluation: classify, feed the guard, then the
         * `isOverlayActive` branch. @return true when the event reaches evaluation.
         */
        fun deliver(record: AccessibilityEventRecord): Pair<ForegroundSignal, Boolean> {
            clock = record.eventTimeMs
            val signal = classifier.classify(
                record = record,
                currentImePackage = ime,
                launcherPackages = setOf(launcher),
                pipOnlyPackages = emptySet()
            )
            guard.onForegroundSignal(signal)
            if (signal is ForegroundSignal.PipOnly || signal is ForegroundSignal.AwarenessOverlay) {
                return signal to false
            }
            if (overlayActive) {
                if (guard.isGenuineBypass(record.type, signal, floating = false)) {
                    overlayActive = false
                } else {
                    return signal to false
                }
            }
            return signal to true
        }

        /**
         * `maybeEvaluateContentChangeAsAppSwitch`: the content change is promoted to a switch only
         * when its package owns the active window. The capture has no window list, so that binder
         * read is taken from the device's own log line ("foreground switch detected from content
         * change package=...GoogleCamera"); the classification and the guard are real.
         */
        fun verifiedContentChangeSwitch(record: AccessibilityEventRecord): ForegroundSignal {
            val signal = classifier.classifyVerifiedContentChangeAsSwitch(
                record = record,
                currentImePackage = ime,
                launcherPackages = setOf(launcher),
                pipOnlyPackages = emptySet()
            )
            guard.onForegroundSignal(signal)
            return signal
        }

        /** `handleDecision` for Camera's block; the claim is held back when [deferClaim]. */
        fun handleDecision(deferClaim: Boolean = false): BlockLaunchGate.Decision {
            val decision = engine.evaluate(
                packageName = camera,
                activeRules = rules,
                dailyUsageMs = 0L,
                detectedFeature = null,
                includeWholeAppRulesForFeature = true
            ) as BlockDecision.Block
            val keys = BlockLaunchGate.identityOf(decision, camera)
            val fixed = guard.decide(camera, keys.decisionKey)
            val verdict = if (gate == Gate.PRE_FIX && fixed == BlockLaunchGate.Decision.RESHOW_HIDDEN) {
                BlockLaunchGate.Decision.DROP_ALREADY_PENDING
            } else {
                fixed
            }
            verdicts += verdict
            guard.onLaunchAttempt(camera, verdict)
            when (verdict) {
                BlockLaunchGate.Decision.LAUNCH -> guard.onOverlayLaunched(camera, keys.decisionKey)
                BlockLaunchGate.Decision.RESHOW_HIDDEN -> guard.onOverlayReshown(camera)
                else -> return verdict
            }
            overlayActive = true
            starts++
            startedAtMs += clock
            if (deferClaim) owedClaim = keys.confrontationKey else claim(keys.confrontationKey)
            return verdict
        }

        fun landOwedClaim() {
            owedClaim?.let { claim(it) }
            owedClaim = null
        }

        private fun claim(key: String) {
            if (guard.claimConfrontation(camera, key)) rows++
        }
    }

    private fun isCameraWindow(r: AccessibilityEventRecord) =
        r.packageName == camera && r.type == A11yEventType.WINDOW_STATE_CHANGED

    private inner class Replay(val service: Service, val permissionAtMs: Long)

    /**
     * Replay the capture, making the service's decisions where the logcat put them: Camera's first
     * window (the launch), the permission dialog's window (the bypass), and Camera's first event
     * after it, a CONTENT CHANGE the service verified as a switch back (the re-block). Between the
     * first two sits the overlay's own starting window, which the live overlay swallows.
     */
    private fun replay(gate: Gate, landing: ClaimLanding): Replay {
        val service = Service(gate)
        val firstCamera = capture.indexOfFirst { isCameraWindow(it) }
        val permission = (firstCamera until capture.size).first { capture[it].packageName == permissionController }
        val cameraBack = (permission until capture.size).first { capture[it].packageName == camera }

        capture.forEachIndexed { i, record ->
            val overlayActiveBefore = service.overlayActive
            val (signal, reachesEvaluation) = service.deliver(record)
            when (i) {
                firstCamera -> {
                    assertTrue("fixture: Camera's first window reaches evaluation", reachesEvaluation)
                    assertEquals(
                        "Camera's first window launches its block",
                        BlockLaunchGate.Decision.LAUNCH,
                        service.handleDecision(deferClaim = landing == ClaimLanding.AFTER_DISPLACEMENT)
                    )
                }
                permission -> {
                    assertEquals(
                        "fixture: the permission dialog is another app's window, not a system one",
                        ForegroundSignal.AppWindow(permissionController),
                        signal
                    )
                    assertTrue(
                        "fixture: the device logged it as a bypass of the launching overlay",
                        overlayActiveBefore && reachesEvaluation && !service.overlayActive
                    )
                    service.landOwedClaim()
                }
                cameraBack -> {
                    assertEquals(
                        "fixture: Camera comes back as a content change, not a window",
                        A11yEventType.WINDOW_CONTENT_CHANGED,
                        record.type
                    )
                    assertTrue("fixture: nothing marks the overlay active any more", reachesEvaluation)
                    assertEquals(
                        ForegroundSignal.AppWindow(camera),
                        service.verifiedContentChangeSwitch(record)
                    )
                    service.handleDecision()
                }
            }
        }
        return Replay(service, capture[permission].eventTimeMs)
    }

    @Test
    fun `a block buried by its own app's trampoline is re-shown, and the tap counts once`() {
        for (landing in ClaimLanding.values()) {
            val replay = replay(Gate.FIXED, landing)
            val service = replay.service
            assertEquals(
                "claim $landing: launch, then re-show",
                listOf(BlockLaunchGate.Decision.LAUNCH, BlockLaunchGate.Decision.RESHOW_HIDDEN),
                service.verdicts
            )
            assertEquals("claim $landing: the overlay is started again", 2, service.starts)
            assertTrue(
                "claim $landing: the last start comes after the window that buried the overlay",
                service.startedAtMs.last() >= replay.permissionAtMs
            )
            assertEquals("claim $landing: one cold tap, one Blocked row", 1, service.rows)
        }
    }

    /**
     * RULE (d)'s COUNTERFACTUAL. The same events and decisions through the pre-#143 gate, which
     * answered the same block as already pending however it had been buried. It must still leave
     * the overlay buried (one start, before the dialog), or this fixture no longer reproduces the
     * miss and the test above asserts nothing.
     */
    @Test
    fun `counterfactual - the pre-fix gate drops the re-block and the overlay stays buried`() {
        for (landing in ClaimLanding.values()) {
            val replay = replay(Gate.PRE_FIX, landing)
            val service = replay.service
            assertEquals(
                listOf(BlockLaunchGate.Decision.LAUNCH, BlockLaunchGate.Decision.DROP_ALREADY_PENDING),
                service.verdicts
            )
            assertEquals("claim $landing: the device's missed block", 1, service.starts)
            assertTrue(service.startedAtMs.last() < replay.permissionAtMs)
        }
    }
}
