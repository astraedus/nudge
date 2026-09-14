package com.astraedus.nudge.service

import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.sitting.SittingEndCause
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Replays the two reported event sequences through the REAL pipeline: the real [EventClassifier]
 * the service uses, feeding the real [BlockLaunchGuard] and the real [SittingTracker] from the one
 * signal, exactly as `NudgeAccessibilityService.applyForegroundSignal` does.
 *
 * `BlockLaunchGateTest` proves the gate's function is right. This proves the STREAM produces the
 * state that function needs, which is the gap every bug in
 * `docs/architecture/foreground-detection.md` fell into, because each of those functions was also
 * individually correct.
 *
 * Both sequences are SYNTHESISED rather than device-captured, and deliberately not committed to
 * `app/src/test/resources/a11y-captures/`: that directory is real Pixel 3 streams, and a
 * hand-written file sitting among them would be read as device evidence by the next person. What
 * makes these honest instead of hopeful is the COUNTERFACTUAL on each one, the assertion that the
 * pre-fix rule (launch unconditionally) really does launch on this exact sequence. A fixture that
 * quietly stopped reproducing its defect would otherwise leave a green test asserting nothing, the
 * same trap `A11yCaptureReplayTest`'s counterfactuals exist to close.
 *
 * The one thing these cannot prove is the platform ordering that decides whether the phantom window
 * event happens at all. Three reporters see it every time and the bench Pixel 3 never does; that is
 * device and launcher timing, and it is why the reproduction lives here rather than on a device.
 */
class BlockLaunchGuardReplayTest {

    private val nudge = "dev.astraedus.nudge"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val blocked = "com.instagram.android"
    private val ime = "com.google.android.inputmethod.latin"

    private val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = NudgeAccessibilityService.IME_PACKAGES,
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE
    )

    private var clock = 10_000L
    private val guard = BlockLaunchGuard().also { it.nowMs = { clock } }
    private val sitting = SittingTracker()
    private val sittingEnds = mutableListOf<SittingEndCause>()

    /**
     * One accessibility event, through the same two consumers of the same single classification the
     * service feeds. Written as one function precisely because that is the invariant: a test that
     * updated the guard without the sitting could not see them drift.
     */
    private fun event(type: A11yEventType, packageName: String, className: String? = null) {
        val record = AccessibilityEventRecord(
            type = type,
            packageName = packageName,
            className = className,
            eventTimeMs = clock
        )
        val signal = classifier.classify(
            record = record,
            currentImePackage = ime,
            launcherPackages = setOf(launcher),
            pipOnlyPackages = emptySet()
        )
        guard.onForegroundSignal(signal)
        when (val moved = sitting.onSignal(signal, clock)) {
            is SittingEvent.Ended -> sittingEnds += moved.cause
            is SittingEvent.Started -> moved.ended?.let { sittingEnds += it.cause }
            SittingEvent.Unchanged -> Unit
        }
    }

    private fun window(packageName: String, className: String? = null) =
        event(A11yEventType.WINDOW_STATE_CHANGED, packageName, className)

    private fun tick(ms: Long) {
        clock += ms
    }

    /** What the pre-fix code did at a launch site: nothing. Kept honest by the tests below. */
    private fun preFixDecision() = BlockLaunchGate.Decision.LAUNCH

    // --- issue #26: "I changed my mind" - have to click twice -----------------------------------

    /**
     * The reported sequence, in the order a device that reproduces it delivers them.
     *
     * The user opens a blocked app, the overlay goes up, they tap "I changed my mind".
     * `navigateHome` dispatches `GLOBAL_ACTION_HOME`, and on these devices the blocked app's task
     * surfaces underneath the finishing overlay BEFORE the launcher lands. That is a real window
     * event, from a real app, genuinely in front, with no overlay up: every gate in the service says
     * "evaluate this", the one-second debounce is long past because the user was sitting on the
     * overlay, and the block re-arms. The second tap "works" only because by then the launcher is
     * where the pop lands.
     */
    @Test
    fun `the blocked app resurfacing under a finishing walk-away must not re-arm the block`() {
        window(blocked)
        assertEquals(
            "the first block is legitimate and must launch",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked)
        )

        // The overlay goes up. Its own window is Nudge UI.
        tick(50)
        window(nudge, "com.astraedus.nudge.ui.overlay.BlockOverlayActivity")

        // The user reads it and turns around fifteen seconds later.
        tick(15_000)
        guard.onWalkAwayStarted(blocked)

        // ...and the blocked app's task pops forward before the launcher does.
        tick(60)
        window(blocked)

        assertEquals(
            "this is issue #26: a real foreground event for a real app that is on its way OUT",
            BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT,
            guard.decide(blocked)
        )
        assertEquals(
            "counterfactual, the pre-fix code launched here, which is the reported bug",
            preFixDecision(),
            BlockLaunchGate.decide(
                target = blocked,
                foreground = guard.foregroundPackage,
                walkAway = null,
                nowMs = clock
            )
        )
    }

    /**
     * The window is closed by EVIDENCE, not by waiting out a timer: once the launcher is actually in
     * front, the departure has happened and a fresh open of the blocked app blocks immediately.
     * Without this the fix would be "ignore this app for a second and a half", which is a bypass
     * anyone could learn.
     */
    @Test
    fun `re-opening the app after the launcher lands blocks again immediately`() {
        window(blocked)
        tick(50)
        window(nudge, "com.astraedus.nudge.ui.overlay.BlockOverlayActivity")
        tick(15_000)
        guard.onWalkAwayStarted(blocked)

        tick(60)
        window(blocked)
        assertEquals(BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT, guard.decide(blocked))

        // The go-home lands.
        tick(120)
        window(launcher, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
        assertEquals(
            "the sitting ends on Home, exactly as it did before this change",
            listOf(SittingEndCause.WENT_HOME),
            sittingEnds
        )

        // The user changes their mind about changing their mind, 200ms later, well inside the
        // 1500ms fail-safe, which must no longer be in force.
        tick(200)
        window(blocked)
        assertEquals(
            "a deliberate re-open after the transition completed is a fresh attempt",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked)
        )
    }

    /**
     * The fail-safe path: `GLOBAL_ACTION_HOME` was accepted and nothing happened, so no Home event
     * ever arrives to close the window. It closes on the clock instead, and the number is chosen so
     * that `BlockOverlayActivity`'s own 1200ms fail-safe `finish()`, the thing that would pop the
     * blocked app forward in this scenario, lands while the window is still open.
     */
    @Test
    fun `a go-home that never lands still expires the window, after the overlay's own fail-safe`() {
        window(blocked)
        tick(50)
        window(nudge, "com.astraedus.nudge.ui.overlay.BlockOverlayActivity")
        guard.onWalkAwayStarted(blocked)

        // The overlay's fail-safe finish fires at 1200ms and pops the blocked app forward.
        tick(1_200)
        window(blocked)
        assertEquals(
            "the overlay fail-safe must land inside the service window, or #26 returns for this case",
            BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT,
            guard.decide(blocked)
        )

        tick(400)
        window(blocked)
        assertEquals(
            "and past 1500ms the app really is just in the foreground, so it blocks",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked)
        )
    }

    // --- issue #31: the overlay lands after the user has already left ---------------------------

    /**
     * Press Home immediately after opening a blocked app. The evaluation is already in flight on the
     * IO scope; by the time the rule lookup answers, the launcher is in front, and the overlay used
     * to be shown over it.
     */
    @Test
    fun `a decision that lands after Home does not put the overlay over the launcher`() {
        window(blocked)                       // evaluation starts here
        tick(150)
        window(launcher, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")

        assertEquals(
            BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED,
            guard.decide(blocked)
        )
        assertEquals(
            "counterfactual, the pre-fix code launched here",
            preFixDecision(),
            BlockLaunchGate.decide(blocked, foreground = null, walkAway = null, nowMs = clock)
        )
    }

    /** The reporter's most visible case: the overlay appearing on top of Nudge itself. */
    @Test
    fun `a decision that lands after the user opened Nudge does not put the overlay over Nudge`() {
        window(blocked)
        tick(150)
        window(nudge, "com.astraedus.nudge.MainActivity")

        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked))
    }

    @Test
    fun `a decision that lands after a switch to another app does not follow the user there`() {
        window(blocked)
        tick(150)
        window("com.google.android.keep")

        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked))
    }

    /**
     * THE FALSE-POSITIVE SUITE, and the reason `foregroundAfter` is a function over the classified
     * signal instead of an assignment from `event.packageName`.
     *
     * Each of these lands routinely inside the few milliseconds a rule lookup takes, and each of
     * them carries a package that is not the app the user is in. Reading any one as "the user left"
     * would silently stop blocking whenever the shade was open, a keyboard was up, a permission
     * dialog was showing or something was playing in a bubble, a far worse bug than the one being
     * fixed, and the exact shape of issue #5.
     */
    @Test
    fun `the shade, a keyboard, the framework and a PiP bubble never suppress a pending block`() {
        listOf(
            "the notification shade" to "com.android.systemui",
            "the active keyboard" to ime,
            "the framework's paste popup" to NudgeAccessibilityService.FRAMEWORK_PACKAGE,
            "a permission dialog" to "com.android.permissioncontroller"
        ).forEach { (what, pkg) ->
            val fresh = BlockLaunchGuard().also { it.nowMs = { clock } }
            fresh.onForegroundSignal(
                classifier.classify(
                    AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, blocked),
                    ime, setOf(launcher), emptySet()
                )
            )
            fresh.onForegroundSignal(
                classifier.classify(
                    AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, pkg),
                    ime, setOf(launcher), emptySet()
                )
            )
            assertEquals(
                "$what must not suppress a block for the app underneath it",
                BlockLaunchGate.Decision.LAUNCH,
                fresh.decide(blocked)
            )
        }
    }

    /**
     * A scroll or a content change inside the blocked app is not a foreground claim either, and
     * this is the one that would break the in-app feature blocks (Reels, Shorts, TikTok), which are
     * driven entirely by content changes.
     */
    @Test
    fun `content changes and scrolls inside the app leave the pending block alone`() {
        window(blocked)
        tick(20)
        event(A11yEventType.WINDOW_CONTENT_CHANGED, blocked)
        event(A11yEventType.VIEW_SCROLLED, blocked)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(blocked))
    }

    /**
     * Issue #28's sub-flow, one layer up, recorded so the interaction between the two fixes is
     * deliberate rather than discovered.
     *
     * A photo picker IS an ordinary app window, so it DOES move the foreground and a block decision
     * landing behind it is dropped. That is right: the overlay would have covered the picker. It
     * costs nothing, because the sitting is untouched (which is #28's actual fix) and the user's
     * return to the app fires a window event that evaluates again.
     */
    @Test
    fun `a sub-flow drops a late block but leaves the sitting intact`() {
        window(blocked)
        tick(30)
        window("com.google.android.providers.media.module")

        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked))
        assertEquals("a picker is not the user leaving, issue #28", emptyList<SittingEndCause>(), sittingEnds)

        tick(5_000)
        window(blocked)
        assertEquals(
            "and the return is evaluated normally",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked)
        )
    }

    // --- the guard's own lifecycle --------------------------------------------------------------

    @Test
    fun `a fresh guard has no claim about the foreground and never weakens enforcement`() {
        val fresh = BlockLaunchGuard().also { it.nowMs = { clock } }
        assertNull(fresh.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, fresh.decide(blocked))
    }

    @Test
    fun `a blank walk-away package is ignored rather than arming a window for the empty string`() {
        window(blocked)
        guard.onWalkAwayStarted("")
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(blocked))
    }

    @Test
    fun `reset forgets everything`() {
        window(blocked)
        guard.onWalkAwayStarted(blocked)
        guard.reset()
        assertNull(guard.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(blocked))
    }
}
