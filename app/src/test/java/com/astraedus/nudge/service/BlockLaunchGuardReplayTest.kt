package com.astraedus.nudge.service

import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.block.delayKey
import com.astraedus.nudge.domain.events.A11yCapture
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventCodec
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.sitting.SittingEndCause
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Two kinds of sequence live here, and the difference is worth stating.
 *
 * The DUPLICATE-BLOCK case replays a real Pixel 3 stream,
 * `app/src/test/resources/a11y-captures/picker-subflow-keeps-sitting.jsonl`, committed since
 * v1.16.0 and already carrying the defect. Nothing was captured for it; the timings were there all
 * along and nobody had looked at them in this light.
 *
 * The #26, #31 and #58 sequences are SYNTHESISED, and deliberately NOT committed to that directory:
 * it holds real device streams, and a hand-written file sitting among them would be read as device
 * evidence by the next person. What makes them honest instead of hopeful is the COUNTERFACTUAL on
 * each one, the assertion that the pre-fix rule really does launch on this exact sequence. A
 * fixture that quietly stopped reproducing its defect would otherwise leave a green test asserting
 * nothing, the same trap `A11yCaptureReplayTest`'s counterfactuals exist to close.
 *
 * The one thing the synthesised ones cannot prove is the platform ordering that decides whether the
 * phantom window event happens at all. Three reporters see it every time and the bench Pixel 3
 * never does; that is device and launcher timing, and it is why the reproduction lives here.
 */
class BlockLaunchGuardReplayTest {

    private val nudge = "dev.astraedus.nudge"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val blocked = "com.instagram.android"
    private val ime = "com.google.android.inputmethod.latin"

    private val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { null }
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
    private fun event(
        type: A11yEventType,
        packageName: String,
        className: String? = null
    ): ForegroundSignal {
        val record = AccessibilityEventRecord(
            type = type,
            packageName = packageName,
            className = className,
            eventTimeMs = clock
        )
        return apply(classify(record))
    }

    /**
     * Feed ONE signal to the same two consumers of the same single classification the service
     * feeds. Written as one function precisely because that is the invariant: a test that updated
     * the guard without the sitting could not see them drift.
     */
    private fun apply(signal: ForegroundSignal): ForegroundSignal {
        guard.onForegroundSignal(signal)
        when (val moved = sitting.onSignal(signal, clock)) {
            is SittingEvent.Ended -> sittingEnds += moved.cause
            is SittingEvent.Started -> moved.ended?.let { sittingEnds += it.cause }
            SittingEvent.Unchanged -> Unit
        }
        return signal
    }

    private fun classify(record: AccessibilityEventRecord) = classifier.classify(
        record = record,
        currentImePackage = ime,
        launcherPackages = setOf(launcher),
        pipOnlyPackages = emptySet()
    )

    private fun window(packageName: String, className: String? = null) =
        event(A11yEventType.WINDOW_STATE_CHANGED, packageName, className)

    private fun tick(ms: Long) {
        clock += ms
    }

    /** One of Nudge's awareness overlays appearing over whatever the user is already in. */
    private fun overlayWindow() =
        window(nudge, AwarenessOverlayWindow.CLASS_NAME)

    /**
     * The classifier as it was before issue #41: it knows our package and not our overlay, so it
     * cannot tell a pill drawn over Keep from Nudge coming to the front. Used only by the
     * counterfactual below, which is what proves the fixture reproduces the defect.
     */
    private val preFixClassifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = emptySet(),
        windows = { null }
    )

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
            guard.decide(blocked, delayKey(blocked))
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
            guard.decide(blocked, delayKey(blocked))
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
        assertEquals(BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT, guard.decide(blocked, delayKey(blocked)))

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
            guard.decide(blocked, delayKey(blocked))
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
            guard.decide(blocked, delayKey(blocked))
        )

        tick(400)
        window(blocked)
        assertEquals(
            "and past 1500ms the app really is just in the foreground, so it blocks",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked, delayKey(blocked))
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
            guard.decide(blocked, delayKey(blocked))
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

        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked, delayKey(blocked)))
    }

    @Test
    fun `a decision that lands after a switch to another app does not follow the user there`() {
        window(blocked)
        tick(150)
        window("com.google.android.keep")

        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked, delayKey(blocked)))
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
                fresh.decide(blocked, delayKey(blocked))
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
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(blocked, delayKey(blocked)))
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

        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked, delayKey(blocked)))
        assertEquals("a picker is not the user leaving, issue #28", emptyList<SittingEndCause>(), sittingEnds)

        tick(5_000)
        window(blocked)
        assertEquals(
            "and the return is evaluated normally",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked, delayKey(blocked))
        )
    }

    // --- the duplicate block, replayed from a real device capture -------------------------------

    /**
     * THE DUPLICATE-LAUNCH REGRESSION, driven by a Pixel 3 stream committed since v1.16.0.
     *
     * Device QA on v1.17.1 measured `wasBlocked` rising by 25 across 10 walk-away attempts, with
     * `handling block package=com.google.android.keep` logged twice in 5 of them. The cause is not
     * in the walk-away path at all, and it predates every change on this branch:
     * `picker-subflow-keeps-sitting.jsonl` times a single clean launch of Keep under a DELAY rule as
     *
     * ```
     *  1252ms  keep   android.widget.FrameLayout            <- evaluate, launch the overlay
     *  1445ms  nudge  android.widget.FrameLayout            <- the overlay TASK's first window
     *  1736ms  keep   ...keep.ui.activities.BrowseActivity  <- keep STILL starting up
     *  2056ms  nudge  ...overlay.BlockOverlayActivity       <- the overlay reaches the screen
     * ```
     *
     * and the event at 1736ms is a `WINDOW_STATE_CHANGED` for a real `AppWindow`, 320ms before the
     * overlay arrives. The old rule called that a bypass, cleared the flag and re-evaluated.
     *
     * This test replays those four events and asserts the old rule fires a bypass on the third
     * (the counterfactual, so the fixture provably still contains the defect) while the new one
     * does not, and that a bypass AFTER the overlay is on screen still works, because that is the
     * case the rule exists for.
     */
    @Test
    fun `the blocked app still starting up under a launching overlay is not a bypass`() {
        val keep = "com.google.android.keep"
        val capture = A11yCapture.load("picker-subflow-keeps-sitting")

        // The four window events of the first block, in the order the device delivered them.
        val windows = capture.filter { it.type == A11yEventType.WINDOW_STATE_CHANGED }
        val firstKeep = windows.first { it.packageName == keep }
        val overlayTaskWindow = windows.first { it.packageName == nudge }
        val keepStillStarting = windows.first {
            it.packageName == keep && it.eventTimeMs > overlayTaskWindow.eventTimeMs
        }
        val overlayOnScreen = windows.first {
            it.packageName == nudge && it.className?.contains("BlockOverlayActivity") == true
        }
        assertEquals(
            "the capture must still show keep's own window arriving BEFORE the overlay's, or this " +
                "fixture has stopped reproducing the defect it was chosen for",
            true,
            keepStillStarting.eventTimeMs < overlayOnScreen.eventTimeMs
        )

        clock = firstKeep.eventTimeMs
        window(keep, firstKeep.className)
        guard.onOverlayLaunched(keep, delayKey(keep))

        clock = overlayTaskWindow.eventTimeMs
        window(nudge, overlayTaskWindow.className)

        // The event that used to cost a second block.
        clock = keepStillStarting.eventTimeMs
        val signal = classify(keepStillStarting)
        guard.onForegroundSignal(signal)

        assertEquals(
            "counterfactual: the pre-fix rule really does read this as a bypass",
            true,
            BlockLaunchGate.isGenuineBypass(
                eventType = keepStillStarting.type,
                signal = signal,
                pending = null,
                nowMs = clock
            )
        )
        assertEquals(
            "the app is still starting up under an overlay that has not arrived; not a bypass",
            false,
            guard.isGenuineBypass(keepStillStarting.type, signal, floating = false)
        )
        assertEquals(
            "and a second launch for the same app while the first is still pending is refused, so " +
                "one entry can only ever write one row",
            BlockLaunchGate.Decision.DROP_ALREADY_PENDING,
            guard.decide(keep, delayKey(keep))
        )

        // The overlay reaches the screen, which in production is BlockOverlayActivity.onResume.
        clock = overlayOnScreen.eventTimeMs
        window(nudge, overlayOnScreen.className)
        guard.onOverlayShown()

        // NOW a real app window for the blocked app means the user got past the overlay.
        clock += 4_000
        val backIn = classify(
            AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, keep, eventTimeMs = clock)
        )
        guard.onForegroundSignal(backIn)
        assertEquals(
            "tabbing back into the blocked app past a live overlay must still re-block",
            true,
            guard.isGenuineBypass(A11yEventType.WINDOW_STATE_CHANGED, backIn, floating = false)
        )
    }

    /**
     * The other half of the same rule: a DIFFERENT app coming forward while our overlay is still on
     * its way is a real foreground change and must not be suppressed. Suppressing it would swallow a
     * genuine switch for up to the settle window.
     */
    @Test
    fun `a different app coming forward while the overlay launches is still a bypass`() {
        window(blocked)
        guard.onOverlayLaunched(blocked, delayKey(blocked))
        tick(100)
        val other = "com.google.android.keep"
        val signal = classify(
            AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, other, eventTimeMs = clock)
        )
        guard.onForegroundSignal(signal)
        assertEquals(true, guard.isGenuineBypass(A11yEventType.WINDOW_STATE_CHANGED, signal, floating = false))
    }

    /**
     * The fail-safe. If the overlay never reports itself on screen at all, a `startActivity` the
     * platform dropped, the service must not be left permanently unable to recognise a bypass.
     */
    @Test
    fun `an overlay that never appears stops suppressing once the settle window passes`() {
        window(blocked)
        guard.onOverlayLaunched(blocked, delayKey(blocked))

        tick(BlockLaunchGate.OVERLAY_SETTLE_MS - 1)
        var signal = classify(
            AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, blocked, eventTimeMs = clock)
        )
        guard.onForegroundSignal(signal)
        assertEquals(false, guard.isGenuineBypass(A11yEventType.WINDOW_STATE_CHANGED, signal, floating = false))

        tick(2)
        signal = classify(
            AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, blocked, eventTimeMs = clock)
        )
        guard.onForegroundSignal(signal)
        assertEquals(true, guard.isGenuineBypass(A11yEventType.WINDOW_STATE_CHANGED, signal, floating = false))
    }

    // --- issue #41: the daily-limit tick refused while our own counter is up --------------------

    /**
     * Bench Pixel 3, v1.17.1, scenario S3 of the #36 investigation: Hard Block plus a 1-minute
     * daily limit on Keep, the limit already exceeded, Keep visibly in the foreground. Every 30
     * seconds the clock tick tried to launch the daily-limit block and every one was refused:
     *
     * ```
     * block overlay launch dropped target=com.google.android.keep reason=DROP_FOREGROUND_MOVED
     *   foreground=dev.astraedus.nudge
     * ```
     *
     * Nudge's awareness overlays are `TYPE_ACCESSIBILITY_OVERLAY` windows we own, so every time one
     * is added the platform emits a window event carrying OUR package. That was classified `OwnUi`,
     * `foregroundAfter` moves the foreground to Nudge for `OwnUi`, and nothing moved it back while
     * the user sat still in Keep, because a user sitting still fires no window event for the app
     * they are already in. The block did not merely stutter; it stayed off until the next real Keep
     * window event, which on a phone left face-up is indefinitely.
     *
     * The counterfactual is on the same run: classified by a classifier that does not know the
     * overlay's identity, exactly as production did, the second tick DROPS.
     */
    @Test
    fun `our own counter appearing between two clock ticks does not stop the next block`() {
        val keep = "com.google.android.keep"
        window(keep)
        assertEquals(
            "the first tick blocks: Keep is in front and past its daily limit",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(keep, delayKey(keep))
        )

        tick(30_000)
        overlayWindow()

        tick(30_000)
        assertEquals(
            "the counter is drawn OVER Keep; the user never left, so the next tick must still block",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(keep, delayKey(keep))
        )
    }

    /** The pre-fix rule, on the same event: proof this fixture really does reproduce the defect. */
    @Test
    fun `counterfactual - the pre-fix classification drops the next block`() {
        val keep = "com.google.android.keep"
        val preFix = BlockLaunchGuard().also { it.nowMs = { clock } }
        preFix.onForegroundSignal(
            preFixClassifier.classify(
                AccessibilityEventRecord(A11yEventType.WINDOW_STATE_CHANGED, keep, eventTimeMs = clock),
                ime,
                setOf(launcher),
                emptySet()
            )
        )
        assertEquals(BlockLaunchGate.Decision.LAUNCH, preFix.decide(keep, delayKey(keep)))

        tick(30_000)
        val overlaySignal = preFixClassifier.classify(
            AccessibilityEventRecord(
                A11yEventType.WINDOW_STATE_CHANGED,
                nudge,
                className = AwarenessOverlayWindow.CLASS_NAME,
                eventTimeMs = clock
            ),
            ime,
            setOf(launcher),
            emptySet()
        )
        assertEquals(
            "before the fix, our own pill was indistinguishable from Nudge coming to the front",
            ForegroundSignal.OwnUi(nudge),
            overlaySignal
        )
        preFix.onForegroundSignal(overlaySignal)

        tick(30_000)
        assertEquals(
            "this is the reported bug: a user past their daily limit, sitting in the blocked app, " +
                "with the block refused because our own counter claimed the foreground",
            BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED,
            preFix.decide(keep, delayKey(keep))
        )
    }

    /**
     * The counter updates its text far more often than it appears, and a text change arrives as a
     * `WINDOW_CONTENT_CHANGED`. Our own package is classified ahead of the window-change test, so
     * these events reached `foregroundAfter` too: fixing only the window event would leave the
     * defect firing on every interaction the counter counts.
     */
    @Test
    fun `a counter text update does not claim the foreground either`() {
        val keep = "com.google.android.keep"
        window(keep)
        event(A11yEventType.WINDOW_CONTENT_CHANGED, nudge, AwarenessOverlayWindow.CLASS_NAME)

        assertEquals(keep, guard.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(keep, delayKey(keep)))
    }

    /**
     * The other direction, which must NOT change: Nudge's own app and its block overlay still move
     * the foreground. That is issue #31's whole point, and a fix that made every Nudge window inert
     * would hand #31 back while closing #41.
     */
    @Test
    fun `Nudge's real windows still move the foreground`() {
        val keep = "com.google.android.keep"
        window(keep)
        window(nudge, BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS)
        assertEquals(nudge, guard.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(keep, delayKey(keep)))
    }

    /**
     * An awareness overlay is not a departure either: it cannot end the arrival the #36 count hangs
     * off, or the overlay that appears BECAUSE the user is in a blocked app would make the next
     * block a fresh confrontation and start the count climbing again.
     */
    @Test
    fun `an awareness overlay does not end the arrival, so the count stays honest`() {
        val keep = "com.google.android.keep"
        val key = BlockLaunchGate.confrontationKey(keep, null, null)
        window(keep)
        assertEquals("the first confrontation owes a row", true, guard.claimConfrontation(keep, key))

        overlayWindow()
        tick(30_000)
        assertEquals(
            "our own counter appearing is not the user leaving Keep, so this is the SAME " +
                "confrontation and owes no second row",
            false,
            guard.claimConfrontation(keep, key)
        )
    }

    // --- the guard's own lifecycle --------------------------------------------------------------

    @Test
    fun `a fresh guard has no claim about the foreground and never weakens enforcement`() {
        val fresh = BlockLaunchGuard().also { it.nowMs = { clock } }
        assertNull(fresh.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, fresh.decide(blocked, delayKey(blocked)))
    }

    @Test
    fun `a blank walk-away package is ignored rather than arming a window for the empty string`() {
        window(blocked)
        guard.onWalkAwayStarted("")
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(blocked, delayKey(blocked)))
    }

    @Test
    fun `reset forgets everything`() {
        window(blocked)
        guard.onWalkAwayStarted(blocked)
        guard.reset()
        assertNull(guard.foregroundPackage)
        assertEquals(BlockLaunchGate.Decision.LAUNCH, guard.decide(blocked, delayKey(blocked)))
    }

    // --- issue #58, mechanism 1: the launcher arrives ONLY as a content change ------------------

    /**
     * THE EVENT FROM THE REPORT, decoded by the PRODUCTION codec rather than hand-built.
     *
     * `docs/testing-strategy.md` rule (b): a fixture that re-spells an identity constant is a
     * second definition of it. This is the `EV` line quoted in
     * [#58](https://github.com/astraedus/nudge/issues/58) verbatim, closed at the truncation the
     * issue text shows (`AccessibilityEventCodec` takes the record's defaults for missing keys, so
     * the two fields the report actually carries are the two fields this event has).
     *
     * It is NOT committed under `app/src/test/resources/a11y-captures/`, deliberately, and for the
     * reason this file's class doc already gives for the #26 and #31 sequences: that directory
     * holds whole device streams and a hand-assembled file sitting among them would be read as
     * device evidence by the next person. What makes these honest is the COUNTERFACTUAL on each.
     */
    private val launcherContentChangeFromTheReport: AccessibilityEventRecord =
        requireNotNull(
            AccessibilityEventCodec.decode(
                """{"t":"WINDOW_CONTENT_CHANGED","p":"com.google.android.apps.nexuslauncher"}"""
            )
        ) { "the EV line quoted in issue #58 must decode through the production codec" }

    /** The service's issue #7 fallback: classify, then promote only once it is VERIFIED. */
    private fun verifiedContentChange(
        record: AccessibilityEventRecord,
        launcherPackages: Set<String> = setOf(launcher)
    ): ForegroundSignal = apply(
        classifier.classifyVerifiedContentChangeAsSwitch(
            record = record,
            currentImePackage = ime,
            launcherPackages = launcherPackages,
            pipOnlyPackages = emptySet()
        )
    )

    @Test
    fun `the report's launcher event is the launcher, and it is a content change`() {
        assertEquals(launcher, launcherContentChangeFromTheReport.packageName)
        assertEquals(
            A11yEventType.WINDOW_CONTENT_CHANGED,
            launcherContentChangeFromTheReport.type
        )
        assertEquals(
            "unpromoted it claims nothing -- which is why the sitting never ended",
            ForegroundSignal.NotForeground(launcher),
            classify(launcherContentChangeFromTheReport)
        )
    }

    /**
     * The failing trial, replayed. The user completes a delay, uses the app, presses Home -- and
     * the launcher arrives ONLY as a content change, with no `WINDOW_STATE_CHANGED` anywhere in
     * the buffer. Verified against the real active window it IS the home screen, so the sitting
     * ends the way the passing trials' `sitting ended ... cause=WENT_HOME` line says it should.
     */
    @Test
    fun `a verified launcher content change ends the sitting as WENT_HOME`() {
        window(blocked)
        tick(5_000)

        verifiedContentChange(launcherContentChangeFromTheReport)

        assertEquals(listOf(SittingEndCause.WENT_HOME), sittingEnds)
        assertEquals(launcher, guard.foregroundPackage)
    }

    /**
     * THE COUNTERFACTUAL, and it is the pre-fix code exactly: the promotion function had no
     * launcher set at all, and the stock launchers are in `SYSTEM_PACKAGES`, so the same verified
     * event came back as a [ForegroundSignal.SystemSurface] -- which the sitting model is
     * structurally incapable of acting on, and which the service dropped before it ever read the
     * active window.
     *
     * That is the reported bug: nothing ended, so the completed delay was still granted, and the
     * reopen three seconds later walked straight in.
     */
    @Test
    fun `counterfactual - without the launcher set the same event changes nothing`() {
        assertTrue(
            "this counterfactual is only honest while the stock launcher IS a system package",
            launcher in NudgeAccessibilityService.SYSTEM_PACKAGES
        )

        window(blocked)
        tick(5_000)

        val signal = verifiedContentChange(
            launcherContentChangeFromTheReport,
            launcherPackages = emptySet()
        )

        assertEquals(ForegroundSignal.SystemSurface(launcher), signal)
        assertEquals("nothing ended -- this is the miss", emptyList<SittingEndCause>(), sittingEnds)
        assertEquals(
            "and the guard still believes the user is in the blocked app",
            blocked,
            guard.foregroundPackage
        )

        tick(3_000)
        window(blocked)
        assertEquals(
            "so the reopen three seconds later is still the SAME sitting, and the grant survives",
            emptyList<SittingEndCause>(),
            sittingEnds
        )
    }

    /**
     * The guard rail on the promotion, and the reason it is allowed at all. Launcher widgets tick
     * while the user is inside a fullscreen app; those content changes cannot reach the promotion,
     * because the APP owns the active window. Modelled here as the service models it: the
     * verification is the caller's, and an unverified event never gets promoted.
     */
    @Test
    fun `launcher churn behind a live app never reaches the promotion`() {
        window(blocked)
        tick(5_000)

        // The service's own check: `activeWindowPackageOrNull() != packageName` -> return.
        val activeWindowPackage = blocked
        if (activeWindowPackage == launcherContentChangeFromTheReport.packageName) {
            verifiedContentChange(launcherContentChangeFromTheReport)
        }

        assertEquals(
            "a widget redraw must not revoke a delay somebody earned (#5, #28)",
            emptyList<SittingEndCause>(),
            sittingEnds
        )
    }

    // --- issue #58, mechanism 2: the block we raced ourselves out of ----------------------------

    /**
     * The service's same-package debounce, modelled: `evaluateForegroundPackage` early-returns for
     * `packageName == lastPackage && now - lastEvalTime < DEBOUNCE_MS`.
     *
     * It is here because it is the whole reason a `DROP_FOREGROUND_MOVED` is SILENT rather than
     * self-correcting. The dropped evaluation already set `lastPackage` to the target, so the
     * settle that follows finds nothing to do and the block is simply gone.
     */
    private class EvaluationDebounce(private val debounceMs: Long = 1_000L) {
        private var lastPackage: String? = null
        private var lastEvalTime = 0L

        fun evaluate(packageName: String, nowMs: Long): Boolean {
            if (packageName == lastPackage && nowMs - lastEvalTime < debounceMs) return false
            lastPackage = packageName
            lastEvalTime = nowMs
            return true
        }

        /** What the redemption does: `lastEvalTime = 0L`, so the question may be asked again. */
        fun spend() {
            lastEvalTime = 0L
        }
    }

    /**
     * The second failing trial from the report, replayed. `WENT_HOME` is logged correctly, so the
     * grant really is revoked; the user reopens the app; the evaluation runs on the IO scope while
     * the launcher is still the last thing the main thread saw, and the decision comes back to
     * `block overlay launch dropped ... reason=DROP_FOREGROUND_MOVED`. Nothing re-evaluated on the
     * return, and the app opened free.
     */
    @Test
    fun `a block dropped because we raced ourselves is finished when the target settles`() {
        val debounce = EvaluationDebounce()

        window(blocked)
        tick(3_000)
        window(launcher)
        assertEquals(listOf(SittingEndCause.WENT_HOME), sittingEnds)

        // The reopen. Evaluation starts and spends the debounce...
        tick(2_000)
        assertTrue(debounce.evaluate(blocked, clock))

        // ...and the decision lands while the launcher is still what we last observed.
        val dropped = guard.decide(blocked, delayKey(blocked))
        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, dropped)
        guard.onLaunchAttempt(blocked, dropped)

        // The app's own window settles a moment later. THE ORDER IS THE FIX: redeem, which spends
        // the debounce, and only then evaluate.
        tick(120)
        val settled = window(blocked)
        assertTrue(
            "the settle is what the race was about, so it is what redeems the drop",
            guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled)
        )
        debounce.spend()
        assertTrue("and the question is asked again", debounce.evaluate(blocked, clock))
        assertEquals(
            "with the app genuinely in front, the block this time is shown",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(blocked, delayKey(blocked))
        )
    }

    /**
     * RULE (d)'s REVERSED-ORDER COUNTERFACTUAL. The fix is "redeem before evaluating"; evaluating
     * first and redeeming after is the pre-fix behaviour wearing the new code, and it must still
     * fail. The redemption is spent on a debounce that has already turned the settle away.
     */
    @Test
    fun `counterfactual - redeeming AFTER the evaluation leaves the block swallowed`() {
        val debounce = EvaluationDebounce()

        window(blocked)
        tick(3_000)
        window(launcher)
        tick(2_000)
        assertTrue(debounce.evaluate(blocked, clock))
        val dropped = guard.decide(blocked, delayKey(blocked))
        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, dropped)
        guard.onLaunchAttempt(blocked, dropped)

        tick(120)
        val settled = window(blocked)
        assertFalse(
            "reversed: the settle is evaluated first, and the debounce turns it away",
            debounce.evaluate(blocked, clock)
        )
        assertTrue(guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled))
        debounce.spend()
        // Nothing asks again: the dispatch for this event is over. This is the silent miss.
    }

    /**
     * ...and the counterfactual for the deferral itself: without one, the settle redeems nothing,
     * so there is nothing to spend the debounce and the block stays swallowed. Run on a guard that
     * was never told about the drop, which is the pre-fix guard exactly.
     */
    @Test
    fun `counterfactual - with no deferral the settle redeems nothing`() {
        window(blocked)
        tick(3_000)
        window(launcher)
        tick(2_000)

        // The pre-fix code dropped the launch and recorded nothing but a log line.
        assertEquals(BlockLaunchGate.Decision.DROP_FOREGROUND_MOVED, guard.decide(blocked, delayKey(blocked)))

        tick(120)
        val settled = window(blocked)
        assertFalse(
            "nothing remembers the block, so nothing finishes it",
            guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled)
        )
    }

    /** One drop buys ONE re-evaluation: the deferral is consumed, not a standing licence. */
    @Test
    fun `a redeemed deferral is spent`() {
        window(blocked)
        window(launcher)
        val dropped = guard.decide(blocked, delayKey(blocked))
        guard.onLaunchAttempt(blocked, dropped)

        val settled = window(blocked)
        assertTrue(guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled))
        tick(50)
        val again = window(blocked)
        assertFalse(
            "the second window event of the same arrival owes nothing",
            guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, again)
        )
    }

    /**
     * The user genuinely moving on costs nothing. A drop, then Home, then a return much later is
     * an ordinary fresh arrival that the ordinary path evaluates -- the deferral must not be
     * sitting there waiting to fire a block nobody asked for.
     */
    @Test
    fun `going home cancels a deferred block`() {
        window(blocked)
        window(nudge, BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS)
        val dropped = guard.decide(blocked, delayKey(blocked))
        guard.onLaunchAttempt(blocked, dropped)

        window(launcher)
        tick(200)
        val settled = window(blocked)
        assertFalse(
            "a trip home ends what was owed on the way in",
            guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled)
        )
    }

    /**
     * A screen-off departure reaches the guard as a broadcast and never as a signal (#54), so it
     * has its own route -- and it must cancel the deferral by that route too, or the two ways of
     * leaving an app would disagree.
     */
    @Test
    fun `a departure the stream cannot describe cancels a deferred block`() {
        window(blocked)
        window(nudge, BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS)
        val dropped = guard.decide(blocked, delayKey(blocked))
        guard.onLaunchAttempt(blocked, dropped)

        guard.onDeparture("screen_off")

        val settled = window(blocked)
        assertFalse(
            guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled)
        )
    }

    /**
     * ISSUE #36 IS UNTOUCHED BY ALL OF THIS. The deferral decides whether an overlay is SHOWN; the
     * arrival decides whether a ROW is written, and they are different questions. A redeemed
     * deferral inside one arrival must not buy a second row.
     */
    @Test
    fun `a redeemed deferral does not buy a second row for one arrival`() {
        val key = BlockLaunchGate.confrontationKey(blocked)
        window(blocked)
        assertTrue("the first confrontation owes a row", guard.claimConfrontation(blocked, key))

        window(nudge, "com.astraedus.nudge.ui.overlay.BlockOverlayActivity")
        val dropped = guard.decide(blocked, delayKey(blocked))
        guard.onLaunchAttempt(blocked, dropped)

        val settled = window(blocked)
        assertTrue(guard.redeemDeferredLaunch(A11yEventType.WINDOW_STATE_CHANGED, settled))
        assertFalse(
            "the user never left, so this is the same confrontation and owes no second row",
            guard.claimConfrontation(blocked, key)
        )
    }
}
