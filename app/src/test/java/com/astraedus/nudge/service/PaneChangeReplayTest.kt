package com.astraedus.nudge.service

import com.astraedus.nudge.domain.engine.FirstUnlockState
import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.NudgeIdentity
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
import com.astraedus.nudge.domain.sitting.SittingEndCause
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.anAccessibilityEvent
import com.astraedus.nudge.testing.anActiveRule

/**
 * The `home-reopen` release-gate flake: one Home press, and YouTube came back by itself.
 *
 * Replayed from `youtube-pane-straggler-after-home.jsonl`. The user is in YouTube with a completed
 * Delay and presses Home. The launcher's first event ends the sitting (WENT_HOME, the grant goes),
 * and ~55ms later YouTube tears down its player PANE: a `TYPE_WINDOW_STATE_CHANGED` from
 * `android.view.ViewGroup` with `CONTENT_CHANGE_TYPE_PANE_DISAPPEARED`, from a window the live list
 * no longer contained. The classifier read the window TYPE and called it YouTube coming forward. A
 * new sitting started, the block gate saw YouTube in front, the Delay overlay launched over the
 * launcher, and when its countdown finished the completion hand-back (#126) reopened YouTube.
 *
 * Layer L2 (recorded-event replay). Everything that decides is real: the classifier, the sitting
 * model, the block guard and its gate, the rule engine and [BlockLaunchGate.identityOf].
 *
 * Two counterfactuals, per rule (d):
 *  - **the pre-fix rule on the same events.** The classifier never read `contentChangeTypes`
 *    before this fix, so it is the same classifier over the same records with that one field
 *    zeroed. It must still start the phantom sitting and launch the overlay, or the capture has
 *    stopped containing the bug and the fixed-rule test proves nothing.
 *  - **the reversed order.** With the straggler delivered BEFORE the Home signal, the old rule is
 *    harmless as well: Home ends the sitting last and nothing restarts it. That is why the case
 *    flaked instead of failing: the pane teardown races the launcher's first event, and only the
 *    "after" side of that race was the bug.
 */
class PaneChangeReplayTest {

    private val nudge = NudgeIdentity.APPLICATION_ID
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val youtube = "com.google.android.youtube"
    private val ime = "com.google.android.inputmethod.latin"

    /** The bench fixture's rule (`device-qa.sh` setup): YouTube, whole app, a 5-second Delay. */
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

    private val capture = A11yCapture.load("youtube-pane-straggler-after-home")

    /** Record 1: the launcher's first event, the content change the device verified as Home (#58). */
    private val homeIndex = 0

    /** Record 3: YouTube's player pane disappearing, after the Home signal. */
    private val stragglerIndex = 2

    private fun classify(record: AccessibilityEventRecord) = classifier.classify(
        record = record,
        currentImePackage = ime,
        launcherPackages = setOf(launcher),
        pipOnlyPackages = emptySet()
    )

    /**
     * `maybeEvaluateContentChangeAsAppSwitch` for the launcher's content change. The capture has no
     * window list, so the binder read that earns the promotion is taken from the same run's
     * logcat: `windows changed [... 8914:application:nexuslauncher:focused:active]` and `foreground
     * switch detected from content change package=...nexuslauncher signal=Home`.
     */
    private fun classifyVerified(record: AccessibilityEventRecord) =
        classifier.classifyVerifiedContentChangeAsSwitch(
            record = record,
            currentImePackage = ime,
            launcherPackages = setOf(launcher),
            pipOnlyPackages = emptySet()
        )

    private class Outcome(
        val sittingEvents: List<SittingEvent>,
        val tracker: SittingTracker,
        val guard: BlockLaunchGuard,
        val stragglerSignal: ForegroundSignal
    )

    /**
     * Deliver [order] (indices into the capture) to the sitting and the guard, starting where the
     * device was: in YouTube, with its sitting open. [preFix] zeroes `contentChangeTypes`, which is
     * exactly the input the classifier ignored before this fix.
     */
    private fun replay(order: List<Int>, preFix: Boolean = false): Outcome {
        val tracker = SittingTracker()
        val guard = BlockLaunchGuard().also { it.nowMs = { 0L } }
        val first = capture[order.min()].eventTimeMs
        tracker.onSignal(ForegroundSignal.AppWindow(youtube), first - 60_000L)
        guard.onForegroundSignal(ForegroundSignal.AppWindow(youtube))

        val events = mutableListOf<SittingEvent>()
        var straggler: ForegroundSignal? = null
        order.forEach { i ->
            val raw = capture[i]
            val record = if (preFix) raw.copy(contentChangeTypes = 0) else raw
            val signal = if (i == homeIndex) classifyVerified(record) else classify(record)
            if (i == stragglerIndex) straggler = signal
            events += tracker.onSignal(signal, record.eventTimeMs)
            guard.onForegroundSignal(signal)
        }
        return Outcome(events, tracker, guard, checkNotNull(straggler))
    }

    /** What the gate says to the Delay the service evaluates for YouTube after these events. */
    private fun launchVerdict(guard: BlockLaunchGuard): BlockLaunchGate.Decision {
        val decision = engine.evaluate(
            packageName = youtube,
            activeRules = rules,
            dailyUsageMs = 0L,
            detectedFeature = null,
            includeWholeAppRulesForFeature = true
        ) as BlockDecision.Block
        return guard.decide(youtube, BlockLaunchGate.identityOf(decision, youtube).decisionKey)
    }

    private val captureOrder get() = listOf(homeIndex, 1, stragglerIndex)

    @Test
    fun `the fixture really holds Home and then YouTube's pane teardown`() {
        val home = capture[homeIndex]
        val straggler = capture[stragglerIndex]
        assertEquals(launcher, home.packageName)
        assertEquals(A11yEventType.WINDOW_CONTENT_CHANGED, home.type)
        assertEquals(youtube, straggler.packageName)
        assertEquals(A11yEventType.WINDOW_STATE_CHANGED, straggler.type)
        assertEquals(AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED, straggler.contentChangeTypes)
        assertTrue("the straggler arrives AFTER the Home event", straggler.eventTimeMs > home.eventTimeMs)
        assertEquals(ForegroundSignal.Home(launcher), classifyVerified(home))
    }

    @Test
    fun `one Home press ends the YouTube sitting and nothing starts another`() {
        val outcome = replay(captureOrder)

        assertEquals(ForegroundSignal.NotForeground(youtube), outcome.stragglerSignal)
        assertEquals(
            listOf<SittingEvent>(SittingEvent.Ended(youtube, SittingEndCause.WENT_HOME)),
            outcome.sittingEvents.filterNot { it is SittingEvent.Unchanged }
        )
        assertEquals(null, outcome.tracker.currentApp)
        assertEquals(launcher, outcome.guard.foregroundPackage)
        assertEquals(
            "no Delay overlay may land on the launcher",
            BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED,
            launchVerdict(outcome.guard)
        )
    }

    @Test
    fun `the pre-fix rule really did start a sitting and launch the overlay over home`() {
        val outcome = replay(captureOrder, preFix = true)

        assertEquals(ForegroundSignal.AppWindow(youtube), outcome.stragglerSignal)
        assertTrue(
            "the old rule must start a phantom YouTube sitting after Home: ${outcome.sittingEvents}",
            outcome.sittingEvents.last() == SittingEvent.Started(youtube, ended = null)
        )
        assertEquals(youtube, outcome.guard.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, launchVerdict(outcome.guard))
    }

    @Test
    fun `reversed order - a straggler BEFORE Home was harmless under both rules, which is why it flaked`() {
        val reversed = listOf(stragglerIndex, homeIndex, 1)
        listOf(false, true).forEach { preFix ->
            val outcome = replay(reversed, preFix)
            val changes = outcome.sittingEvents.filterNot { it is SittingEvent.Unchanged }
            assertEquals(
                "preFix=$preFix: Home ends the sitting last and nothing restarts it",
                SittingEvent.Ended(youtube, SittingEndCause.WENT_HOME),
                changes.last()
            )
            assertEquals(launcher, outcome.guard.foregroundPackage)
            assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, launchVerdict(outcome.guard))
        }
    }

    // --- the class, not the instance ------------------------------------------------------------

    /** Every pane event any device has given us claims nothing, whatever app it came from. */
    @Test
    fun `no pane change in any committed capture claims the foreground`() {
        var panes = 0
        A11yCapture.names().forEach { name ->
            A11yCapture.load(name).filter { it.isPaneChange }.forEach { record ->
                panes++
                assertEquals(
                    "$name: ${record.packageName} ${record.className} cct=${record.contentChangeTypes}",
                    ForegroundSignal.NotForeground(record.packageName),
                    classify(record)
                )
            }
        }
        assertTrue("the captures must contain pane changes for this to assert anything", panes >= 3)
    }

    /** The fix reads one field for one shape of event; every other event classifies as before. */
    @Test
    fun `every event that is not a pane change classifies exactly as the pre-fix rule did`() {
        A11yCapture.names().forEach { name ->
            A11yCapture.load(name).filterNot { it.isPaneChange }.forEach { record ->
                assertEquals(
                    "$name: $record",
                    classify(record.copy(contentChangeTypes = 0)),
                    classify(record)
                )
            }
        }
    }

    // --- the predicate --------------------------------------------------------------------------

    @Test
    fun `the pane mask is the framework's three pane change types`() {
        assertEquals(
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED,
            AccessibilityEventRecord.PANE_CHANGE_TYPES
        )
    }

    @Test
    fun `only a window STATE change carrying a pane type is a pane change`() {
        val pane = AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
        fun record(type: A11yEventType, cct: Int) =
            anAccessibilityEvent(type = type, packageName = youtube, contentChangeTypes = cct)

        assertTrue(record(A11yEventType.WINDOW_STATE_CHANGED, pane).isPaneChange)
        assertTrue(
            record(A11yEventType.WINDOW_STATE_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED)
                .isPaneChange
        )
        assertTrue(
            record(A11yEventType.WINDOW_STATE_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE)
                .isPaneChange
        )
        assertFalse("an activity coming forward", record(A11yEventType.WINDOW_STATE_CHANGED, 0).isPaneChange)
        assertFalse(
            "a subtree change is not a pane",
            record(
                A11yEventType.WINDOW_STATE_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            ).isPaneChange
        )
        assertFalse(record(A11yEventType.WINDOW_CONTENT_CHANGED, pane).isPaneChange)
        assertFalse(record(A11yEventType.WINDOWS_CHANGED, pane).isPaneChange)
    }

    /**
     * The launcher is not exempt. A launcher pane change is not the home screen coming forward
     * either, and the fail direction is the one `EventClassifier` documents: a missed Home is a
     * missed revoke, never a false re-block.
     */
    @Test
    fun `a pane change is not Home even from the launcher`() {
        val record = anAccessibilityEvent(
            type = A11yEventType.WINDOW_STATE_CHANGED,
            packageName = launcher,
            className = "android.view.ViewGroup",
            contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
        )
        assertEquals(ForegroundSignal.NotForeground(launcher), classify(record))
    }
}
