package com.astraedus.nudge.service

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.block.BlockLaunchGate.ArrivalVerdict
import com.astraedus.nudge.domain.block.BlockLaunchGate.Decision
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Hard block that a first launch of Google Docs got straight past: Docs' own launcher activity
 * (`NewMainProxyActivity`) starts its `HomescreenActivity` a quarter of a second after our overlay
 * goes up, and the overlay never comes back. Reported by device QA on an "All apps" rule, reproduced
 * 3/9 on the bench Pixel 3.
 *
 * Replayed from `docs-first-launch-trampoline-buries-overlay.jsonl`, whose header carries the same
 * run's `wm_*` lifecycle events. The capture holds the accessibility EVENTS; the service's decisions
 * and the platform's lifecycle are not in it, so this test makes them where the logcat puts them:
 *
 *  - Docs' first window: evaluate, launch the overlay;
 *  - the overlay's own window: it is on screen (`wm_on_resume_called` landed just before it);
 *  - Docs' `HomescreenActivity` window: the overlay is buried, the service reads the bypass and
 *    re-launches, and that delivery reaches the SAME instance the platform has just stopped, whose
 *    queued `onStop` finishes it (`wm_new_intent` +1509ms, `wm_finish_activity` +1522ms);
 *  - nothing after that says Docs is in front: its content changes come from `lastPackage`.
 *
 * Everything that decides is real: the classifier, the guard and its gate, the rule engine,
 * [BlockLaunchGate.identityOf], and the arrival check's verdict. What the screen shows at a check
 * is the device's own observation (Docs held focus, `mCurrentFocus`, 12s later) unless an overlay
 * has resumed since.
 *
 * Layer L2 (recorded-event replay). Rule (d)'s counterfactual is the REVERSED ORDER: the overlay
 * winning the race against Docs' trampoline must leave the check with nothing to do, or the fix is
 * a blind retry rather than an answer to the race.
 */
class OverlayArrivalReplayTest {

    private val nudge = BuildConfig.APPLICATION_ID
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val docs = "com.google.android.apps.docs.editors.docs"
    private val ime = "com.google.android.inputmethod.latin"

    /**
     * START of the overlay to its `wm_on_resume_called` in the capture's logcat (+0.193 -> +0.423),
     * used for an overlay this test lets the platform honour.
     */
    private val overlayResumeMs = 230L

    /** The bench fixture's rule, which "All apps" resolves to for Docs: one whole-app Hard block. */
    private val rules = listOf(
        ActiveRule(mode = BlockMode.HARD_BLOCK, delaySeconds = 0, dailyLimitMinutes = null, enabled = true)
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

    private val capture = A11yCapture.load("docs-first-launch-trampoline-buries-overlay")

    private val overlayClass = "com.astraedus.nudge.ui.overlay.BlockOverlayActivity"

    /**
     * `onAccessibilityEvent`'s classify / guard / `isOverlayActive` branch, `handleDecision` +
     * `launchBlockOverlay` around the real guard, and the arrival check the fix posts.
     */
    private inner class Service(
        private val arrivalCheck: Boolean,
        private val honourRescues: Boolean = true
    ) {
        var clock = 0L
        val guard = BlockLaunchGuard().also { it.nowMs = { clock } }
        var overlayActive = false
        var overlayOnScreen = false
        var rows = 0
        val verdicts = mutableListOf<Decision>()
        val checks = mutableListOf<ArrivalVerdict>()

        /** What happened, in order: "start", "shown", "dismissed". */
        val timeline = mutableListOf<String>()

        private val dueChecks = mutableListOf<Pair<Long, Long>>()
        private val dueResumes = mutableListOf<Long>()

        fun deliver(record: AccessibilityEventRecord): Boolean {
            advanceTo(record.eventTimeMs)
            val signal = classifier.classify(
                record = record,
                currentImePackage = ime,
                launcherPackages = setOf(launcher),
                pipOnlyPackages = emptySet()
            )
            guard.onForegroundSignal(signal)
            if (signal is ForegroundSignal.PipOnly || signal is ForegroundSignal.AwarenessOverlay) return false
            if (overlayActive) {
                if (guard.isGenuineBypass(record.type, signal, floating = false)) {
                    overlayActive = false
                } else {
                    return false
                }
            }
            return signal is ForegroundSignal.AppWindow && record.type == A11yEventType.WINDOW_STATE_CHANGED
        }

        /** `handleDecision` for Docs' block, through `launchBlockOverlay`. @return true on a start. */
        fun handleDecision(): Boolean {
            val decision = engine.evaluate(
                packageName = docs,
                activeRules = rules,
                dailyUsageMs = 0L,
                detectedFeature = null,
                includeWholeAppRulesForFeature = true
            ) as BlockDecision.Block
            val keys = BlockLaunchGate.identityOf(decision, docs)
            val verdict = guard.decide(docs, keys.decisionKey)
            verdicts += verdict
            guard.onLaunchAttempt(docs, verdict)
            when (verdict) {
                Decision.LAUNCH -> guard.onOverlayLaunched(docs, keys.decisionKey)
                Decision.RESHOW_HIDDEN -> guard.onOverlayReshown(docs)
                else -> return false
            }
            overlayActive = true
            if (arrivalCheck) {
                dueChecks += (clock + BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS) to guard.armArrivalCheck(docs)
            }
            timeline += "start"
            if (guard.claimConfrontation(docs, keys.confrontationKey)) rows++
            return true
        }

        /** The platform drew the overlay: `BlockOverlayActivity.onResume`. */
        fun overlayResumed() {
            guard.onOverlayShown()
            overlayOnScreen = true
            timeline += "shown"
        }

        /** The overlay instance finished (`onDestroy` reports its own id). */
        fun overlayDestroyed() {
            guard.onOverlayDismissed(guard.currentOverlayId())
            overlayOnScreen = false
            timeline += "dismissed"
        }

        /** `checkOverlayArrived`. */
        private fun runCheck(seq: Long) {
            val activeWindow = if (overlayOnScreen) nudge else docs
            val verdict = guard.arrivalVerdict(docs, seq, activeWindow)
            checks += verdict
            if (verdict != ArrivalVerdict.RESCUE) return
            guard.onArrivalRescue(docs)
            guard.onForegroundSignal(ForegroundSignal.AppWindow(docs))
            overlayActive = false
            // A re-assertion the platform honours: nothing is dying to deliver it to.
            if (handleDecision() && honourRescues) dueResumes += clock + overlayResumeMs
        }

        /** Run what the main thread's handler would have run before [untilMs], in time order. */
        fun advanceTo(untilMs: Long) {
            while (true) {
                val check = dueChecks.minByOrNull { it.first }?.takeIf { it.first <= untilMs }
                val resume = dueResumes.minOrNull()?.takeIf { it <= untilMs }
                if (check == null && resume == null) break
                if (resume != null && (check == null || resume <= check.first)) {
                    dueResumes.remove(resume)
                    clock = resume
                    overlayResumed()
                } else {
                    dueChecks.remove(check!!)
                    clock = check.first
                    runCheck(check.second)
                }
            }
            clock = maxOf(clock, untilMs)
        }
    }

    private fun isDocsWindow(r: AccessibilityEventRecord) =
        r.packageName == docs && r.type == A11yEventType.WINDOW_STATE_CHANGED

    private fun isHomescreen(r: AccessibilityEventRecord) =
        isDocsWindow(r) && r.className?.endsWith("HomescreenActivity") == true

    private fun isOverlayWindow(r: AccessibilityEventRecord) =
        r.packageName == nudge && r.className == overlayClass && r.type == A11yEventType.WINDOW_STATE_CHANGED

    /** The capture's events in the order the device delivered them. */
    private fun recorded(): List<AccessibilityEventRecord> = capture

    /**
     * The QA report's run, which had no `onResume` at all: the same events with the overlay's own
     * window withheld (Docs buried it before it was drawn).
     */
    private fun neverDrawn(): List<AccessibilityEventRecord> = capture.filterNot { isOverlayWindow(it) }

    /**
     * RULE (d): the overlay WINNING the race. Docs' trampoline window first, the overlay drawn over
     * it after, at the timestamps of the slots they swap into.
     */
    private fun overlayWins(): List<AccessibilityEventRecord> {
        val overlay = capture.indexOfFirst { isOverlayWindow(it) }
        val homescreen = capture.indexOfFirst { isHomescreen(it) }
        val swapped = capture.toMutableList()
        swapped[overlay] = capture[homescreen].copy(eventTimeMs = capture[overlay].eventTimeMs)
        swapped[homescreen] = capture[overlay].copy(eventTimeMs = capture[homescreen].eventTimeMs)
        return swapped
    }

    /**
     * Replay [events], making the decisions where the logcat put them. The platform effects that
     * only the logcat shows: the overlay is drawn at its own window, buried at Docs' next window,
     * and a re-delivery that races the queued stop is finished, not shown.
     */
    private fun replay(events: List<AccessibilityEventRecord>, arrivalCheck: Boolean): Service {
        val service = Service(arrivalCheck)
        val firstDocs = events.indexOfFirst { isDocsWindow(it) }
        val nextDocs = (firstDocs + 1 until events.size).first { isDocsWindow(events[it]) }
        events.forEachIndexed { i, record ->
            val buriesOverlay = i == nextDocs && service.overlayOnScreen
            if (buriesOverlay) service.overlayOnScreen = false
            val reachesEvaluation = service.deliver(record)
            when {
                i == firstDocs -> {
                    assertTrue("fixture: Docs' first window reaches evaluation", reachesEvaluation)
                    assertTrue("Docs' first window launches its block", service.handleDecision())
                }
                isOverlayWindow(record) -> service.overlayResumed()
                i == nextDocs && reachesEvaluation -> {
                    // The device's bypass and re-block (+1.500 / +1.509). The re-block went to the
                    // stopped instance, whose queued onStop finished it (+1.522).
                    assertTrue("fixture: only a buried overlay is bypassed", buriesOverlay)
                    assertTrue(service.handleDecision())
                    service.overlayDestroyed()
                }
            }
        }
        service.advanceTo(events.last().eventTimeMs + 10 * BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS)
        return service
    }

    @Test
    fun `fixture - the recorded order is the device's escape`() {
        val firstDocs = capture.indexOfFirst { isDocsWindow(it) }
        val overlay = capture.indexOfFirst { isOverlayWindow(it) }
        val homescreen = capture.indexOfFirst { isHomescreen(it) }
        assertTrue("Docs, then our overlay, then Docs' own second activity", firstDocs < overlay && overlay < homescreen)
        assertEquals(
            "and no window event after it: nothing in the stream says Docs is in front again",
            homescreen,
            capture.indexOfLast { it.type == A11yEventType.WINDOW_STATE_CHANGED }
        )
    }

    /**
     * The pre-fix service on the recorded events: the device's escape. The last thing that happens
     * is the overlay's dismissal, with Docs in front and no start after it. If this ever stops being
     * true the fixture no longer reproduces the report and the test below asserts nothing.
     */
    @Test
    fun `counterfactual - without the arrival check the re-block dies with its instance`() {
        val service = replay(recorded(), arrivalCheck = false)
        assertEquals(listOf(Decision.LAUNCH, Decision.LAUNCH), service.verdicts)
        assertEquals(listOf("start", "shown", "start", "dismissed"), service.timeline)
        assertFalse(service.overlayOnScreen)
    }

    @Test
    fun `the arrival check re-asserts a block its own app buried, and the tap counts once`() {
        val service = replay(recorded(), arrivalCheck = true)
        assertEquals(
            "the first launch's check stands down for the re-block's; that one finds Docs in front; " +
                "the rescue's own check finds the overlay",
            listOf(ArrivalVerdict.SUPERSEDED, ArrivalVerdict.RESCUE, ArrivalVerdict.NOT_BURIED),
            service.checks
        )
        assertEquals(
            "a fresh start AFTER the dying instance is gone",
            listOf("start", "shown", "start", "dismissed", "start", "shown"),
            service.timeline
        )
        assertTrue(service.overlayOnScreen)
        assertEquals("one first launch, one Blocked row", 1, service.rows)
    }

    /**
     * The QA report's shape: the overlay never reached the screen, so Docs' second window is the
     * app "still starting up underneath" and swallowed, and no other app ever displaced it. The
     * re-block must be a RE-SHOW, which the gate refused as already pending before the rescue.
     */
    @Test
    fun `an overlay buried before it was ever drawn is re-shown, not refused as pending`() {
        val service = replay(neverDrawn(), arrivalCheck = true)
        assertEquals(listOf(Decision.LAUNCH, Decision.RESHOW_HIDDEN), service.verdicts)
        assertEquals(listOf(ArrivalVerdict.RESCUE, ArrivalVerdict.NOT_BURIED), service.checks)
        assertTrue(service.overlayOnScreen)
        assertEquals(1, service.rows)

        val preFix = replay(neverDrawn(), arrivalCheck = false)
        assertEquals("pre-fix: one start, swallowed ever after", listOf(Decision.LAUNCH), preFix.verdicts)
        assertFalse(preFix.overlayOnScreen)
    }

    /**
     * RULE (d)'s REVERSED ORDER. The overlay drawn after Docs' trampoline is the race the overlay
     * wins: it is in front, so the check finds nothing to do and nothing launches twice. A check
     * that relaunched here would be a retry timer, not an answer to the race.
     */
    @Test
    fun `reversed order - an overlay that wins the race is left alone`() {
        val service = replay(overlayWins(), arrivalCheck = true)
        assertEquals(listOf(Decision.LAUNCH), service.verdicts)
        assertEquals(listOf(ArrivalVerdict.NOT_BURIED), service.checks)
        assertEquals(listOf("start", "shown"), service.timeline)
        assertEquals(1, service.rows)
    }

    /** The cap, on the recorded stay: a target that keeps burying every overlay stops costing launches. */
    @Test
    fun `a target that buries every re-assertion is given up on after the cap`() {
        val service = Service(arrivalCheck = true, honourRescues = false)
        val firstDocs = capture.first { isDocsWindow(it) }
        service.deliver(firstDocs)
        assertTrue(service.handleDecision())
        // The platform never draws any of them, and Docs owns the screen throughout.
        service.advanceTo(firstDocs.eventTimeMs + 10 * BlockLaunchGate.OVERLAY_ARRIVAL_CHECK_MS)
        assertEquals(
            List(BlockLaunchGate.MAX_ARRIVAL_RESCUES) { ArrivalVerdict.RESCUE } + ArrivalVerdict.SPENT,
            service.checks
        )
        assertEquals("the launch and one re-show per rescue", BlockLaunchGate.MAX_ARRIVAL_RESCUES + 1, service.verdicts.size)
        assertEquals(1, service.rows)
    }
}
