package com.astraedus.nudge.service

import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE [#36](https://github.com/astraedus/nudge/issues/36), "over counting interventions":
 * *"Statistics page registered 2500 interventions on one day when baseline is a couple of hundred
 * usually."*
 *
 * ## What this file asserts, and why it is phrased in ROWS
 *
 * The Interventions screen counts `usage_events` rows. 2500 of them in a day is not a double count,
 * it is a loop: something re-launched a block overlay, over and over, with nobody touching the
 * phone, and every launch wrote a row. So every test here ends on the same question the user's
 * screen asks — **how many rows did this stream write** — and never on which internal branch ran.
 * A test that asserted "the bypass check returned false" would have passed on v1.17.1 too.
 *
 * ## The harness
 *
 * [Device] is the dispatch of `NudgeAccessibilityService.onAccessibilityEvent` and the lifecycle of
 * `BlockOverlayActivity`, in the order the real ones run them, around the REAL [EventClassifier],
 * the REAL [BlockLaunchGuard] and the REAL [BlockLaunchGate]. Nothing that decides anything is
 * re-implemented here: the model supplies only the plumbing a JVM test cannot have (the framework
 * event, the activity's lifecycle callbacks, the static overlay-active flag, the row insert).
 *
 * ## The counterfactual, and why it is `launches`
 *
 * Before this change, `handleDecision` wrote one row for every launch that the gate allowed. So on
 * any stream, **the number of launches IS the number of rows the old code would have written**, and
 * each test asserts both: the launches the stream really produces, and the one row it now costs.
 * That is a counterfactual measured on the same run rather than a second implementation of the bug,
 * which cannot drift out of step with the code it is a counterfactual for.
 */
class InterventionCountReplayTest {

    private val nudge = "dev.astraedus.nudge"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val blocked = "com.instagram.android"
    private val otherApp = "com.google.android.keep"
    private val browser = "com.android.chrome"
    private val ime = "com.google.android.inputmethod.latin"
    private val shade = "com.android.systemui"

    /** The overlay's own window, as the device delivers it once the overlay is really up. */
    private val overlayClass = "com.astraedus.nudge.ui.overlay.BlockOverlayActivity"

    /**
     * The overlay TASK's first window, ~600ms ahead of the overlay itself, carrying a FRAMEWORK
     * class name (measured in `picker-subflow-keeps-sitting.jsonl`). It is Nudge UI and it is not
     * the app — which is exactly why "Nudge and not the overlay" cannot be asked negatively.
     */
    private val overlayTaskClass = "android.widget.FrameLayout"

    private val mainActivityClass = BlockLaunchGate.MAIN_APP_ACTIVITY_CLASS

    /** Mirrors `NudgeAccessibilityService.DEBOUNCE_MS`, which is private to that file. */
    private val debounceMs = 1_000L

    private var clock = 10_000L

    private fun tick(ms: Long) {
        clock += ms
    }

    private val classifier = EventClassifier(
        ownPackageName = nudge,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = NudgeAccessibilityService.IME_PACKAGES,
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE
    )

    /** What a block is: who it blocks re-entry to, and what the user actually ran into. */
    private data class Block(
        val label: String,
        val target: String,
        val attributed: String,
        val featureKey: String? = null,
        val webDomain: String? = null
    )

    // ------------------------------------------------------------------------------- the harness

    /**
     * The service's event dispatch and the overlay's lifecycle, faithfully ordered.
     *
     * The branches modelled are the ones a block can reach: the picture-in-picture gate, the
     * overlay-active swallow (and its genuine-bypass escape), the own-UI branch, transient windows,
     * Home / system surfaces, and the window-change evaluation with its 1-second same-package
     * debounce. `clearOverlays(ownPackage, "block_overlay_active")` advancing `lastPackage` to
     * Nudge's package is modelled too, because that is what stops the debounce absorbing the
     * blocked app's next event — without it these loops would look slower than they are.
     */
    private inner class Device(private val blocks: Map<String, Block>) {

        val guard = BlockLaunchGuard().also { it.nowMs = { clock } }

        /** What the Interventions screen counts. */
        var rows = 0
            private set

        /** What the pre-fix code counted: one row per allowed launch. */
        var launches = 0
            private set

        val storms = mutableListOf<BlockLaunchGate.StormReport>()

        private var overlayActive = false
        private var lastPackage: String? = null
        private var lastEvalTime = -debounceMs
        private var overlay: Overlay? = null

        /** The live `BlockOverlayActivity` instance, or null when none exists. */
        val liveOverlay: Overlay?
            get() = overlay?.takeIf { !it.destroyed }

        fun window(packageName: String, className: String? = null) =
            event(A11yEventType.WINDOW_STATE_CHANGED, packageName, className)

        fun event(type: A11yEventType, packageName: String, className: String? = null) {
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

            if (signal is ForegroundSignal.PipOnly) return

            if (overlayActive) {
                if (guard.isGenuineBypass(record.type, signal)) {
                    overlayActive = false
                } else {
                    lastPackage = nudge
                    return
                }
            }

            if (signal is ForegroundSignal.OwnUi) {
                if (BlockLaunchGate.isOwnMainAppWindow(record.type, record.className)) {
                    guard.onDeparture("own_app_window")
                }
                return
            }

            if (signal is ForegroundSignal.Transient) return

            if (signal is ForegroundSignal.Home || signal is ForegroundSignal.SystemSurface) {
                lastPackage = packageName
                return
            }

            if (record.type.isWindowChange) evaluate(packageName)
        }

        /** `ACTION_SCREEN_OFF` is a broadcast, not an accessibility event. */
        fun screenOff() {
            guard.onDeparture("screen_off")
        }

        private fun evaluate(packageName: String) {
            if (packageName == lastPackage && clock - lastEvalTime < debounceMs) return
            lastPackage = packageName
            lastEvalTime = clock
            blocks[packageName]?.let { handleBlockDecision(it) }
        }

        /** `handleDecision`'s `BlockDecision.Block` branch: launch, then claim, then write. */
        fun handleBlockDecision(block: Block) {
            if (!launchBlockOverlay(block.target)) return
            val counted = guard.claimConfrontation(
                targetPackage = block.target,
                key = BlockLaunchGate.confrontationKey(
                    attributedPackage = block.attributed,
                    featureKey = block.featureKey,
                    webDomain = block.webDomain
                )
            )
            if (counted) rows++
        }

        private fun launchBlockOverlay(target: String): Boolean {
            val decision = guard.decide(target)
            guard.onLaunchAttempt(target, decision)?.let { storms += it }
            if (decision != BlockLaunchGate.Decision.LAUNCH) return false
            launches++
            overlayActive = true
            guard.onOverlayLaunched(target)
            startOverlayActivity(target)
            return true
        }

        /**
         * `startActivity` on a `singleInstance` activity: a live instance is re-delivered through
         * `onNewIntent` and never re-runs `onResume`; otherwise a fresh instance is created,
         * rendered and resumed.
         */
        private fun startOverlayActivity(target: String) {
            val live = liveOverlay
            if (live != null) {
                live.render()
            } else {
                val fresh = Overlay(this, target)
                overlay = fresh
                fresh.render()
                fresh.resume()
            }
        }

        fun markOverlayInactive() {
            overlayActive = false
        }

        /** The two windows the device delivers as an overlay comes up, in that order. */
        fun deliverOverlayWindows() {
            window(nudge, overlayTaskClass)
            window(nudge, overlayClass)
        }
    }

    /** `BlockOverlayActivity`, reduced to the callbacks that touch [BlockLaunchGuard]. */
    private inner class Overlay(private val device: Device, private val target: String) {

        private var overlayId = BlockLaunchGate.NO_OVERLAY_ID
        var destroyed = false
            private set

        private var walkAwayArmedAtMs: Long? = null

        /** `render()`, called synchronously from `onCreate` and from `onNewIntent`. */
        fun render() {
            overlayId = device.guard.currentOverlayId()
        }

        fun resume() {
            device.guard.onOverlayShown()
        }

        /**
         * `onStop()` with `isFinishing == false`: the screen went off, or the blocked app's task
         * came back to the front on a device whose launcher lost that race. The activity clears the
         * overlay flag and finishes itself, which destroys it.
         */
        fun stopWithoutFinishing() {
            device.markOverlayInactive()
            destroy()
        }

        /** `onTimerComplete()`: the user waited the delay out, so the app is let through. */
        fun completeAndFinish() {
            device.markOverlayInactive()
            destroy()
        }

        /** The user taps "I changed my mind". */
        fun walkAway() {
            device.markOverlayInactive()
            walkAwayArmedAtMs = clock
            device.guard.onWalkAwayStarted(target)
        }

        /**
         * The walk-away fail-safe at `WALK_AWAY_FINISH_FAILSAFE_MS`: a `GLOBAL_ACTION_HOME` the
         * platform accepted and never honoured, so this finish pops the blocked app forward on
         * purpose. It re-arms the window at the moment of the pop.
         */
        fun failSafeFinish() {
            device.guard.onWalkAwayStarted(target)
            destroy()
        }

        /** The same fail-safe as it was before this fix: pop the app, re-arm nothing. */
        fun failSafeFinishWithoutRearming() {
            destroy()
        }

        /** What the walk-away window would have looked like armed only at the tap. */
        fun walkAwayArmedAtTap(): BlockLaunchGate.WalkAway =
            BlockLaunchGate.WalkAway(target, walkAwayArmedAtMs ?: clock)

        fun destroy() {
            if (destroyed) return
            destroyed = true
            device.guard.onOverlayDismissed(overlayId)
        }
    }

    private fun deviceBlocking(vararg blocks: Block): Device =
        Device(blocks.associateBy { it.target })

    private val delayRule = Block("rule DELAY", target = blocked, attributed = blocked)

    // ------------------------------------------------------ H1: the overlay dies, the app resumes

    /**
     * **H1, THE PROVEN MECHANISM.** Zero user input after the first entry.
     *
     * The overlay reaches the screen. Something stops it without finishing it — the screen going
     * off, or the blocked app's own task coming back to the front on a device whose launcher loses
     * that race. `onStop` clears the overlay flag and `finish()`es. The blocked app resumes
     * underneath and fires a genuine `TYPE_WINDOW_STATE_CHANGED`, and at that instant every gate in
     * the service is telling the truth: a real app is really in front, there is really no overlay
     * up, and the 1-second debounce was defeated by the overlay's own window moving `lastPackage`
     * to Nudge. So it blocks again, writes again, and the overlay it puts up is stopped again.
     *
     * One row per iteration, a second or two per iteration. 2500 of them is about two hours.
     */
    @Test
    fun `an overlay that keeps being stopped and an app that keeps resuming is one intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()
        assertEquals("the first block is a real confrontation and must count", 1, device.rows)

        repeat(100) {
            device.liveOverlay!!.stopWithoutFinishing()
            tick(1_100)
            device.window(blocked)
            device.deliverOverlayWindows()
        }

        assertTrue(
            "counterfactual: the stream really does re-launch the overlay, over and over, with " +
                "nobody touching the phone — and before this fix every one of those launches " +
                "wrote a row, which is the 2500-a-day report (launches=${device.launches})",
            device.launches > 100
        )
        assertEquals(
            "the user arrived in this app ONCE, so the Interventions screen owes exactly one",
            1,
            device.rows
        )
    }

    /**
     * The loop still has to end the moment the user genuinely leaves — otherwise the fix would be
     * "stop counting this app", which is a worse lie than over-counting.
     */
    @Test
    fun `going home and coming back into the blocked app is a second intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()
        repeat(20) {
            device.liveOverlay!!.stopWithoutFinishing()
            tick(1_100)
            device.window(blocked)
            device.deliverOverlayWindows()
        }
        assertEquals(1, device.rows)

        device.liveOverlay!!.stopWithoutFinishing()
        tick(2_000)
        device.window(launcher, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")

        tick(30_000)
        device.window(blocked)
        assertEquals(
            "a fresh entry after the launcher is a fresh confrontation and owes its own row",
            2,
            device.rows
        )
    }

    /**
     * The other half of the same property: a screen-off is a departure even though it is a
     * broadcast and never appears in the accessibility stream at all. Without it, unlocking
     * straight back into a blocked app would be counted as the same confrontation the user met
     * before they put the phone down.
     */
    @Test
    fun `unlocking back into the blocked app is a second intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()
        device.liveOverlay!!.stopWithoutFinishing()
        device.screenOff()

        tick(4 * 60 * 60 * 1000L)
        device.window(blocked)

        assertEquals("a locked phone ends the arrival, so this is a new one", 2, device.rows)
    }

    // ----------------------------------------------------- H2: the walk-away fail-safe pops back

    /**
     * **H2, PROVEN AND FIXED AT SOURCE.**
     *
     * The user taps "I changed my mind". `GLOBAL_ACTION_HOME` is accepted and never honoured, so
     * at 1200ms the overlay's fail-safe finishes itself — which pops this singleInstance task and
     * reveals the blocked app, deliberately reproducing issue #26's own bug as a last resort. On a
     * slow device the app's resume and its window event land 400ms after that, i.e. 1600ms after
     * the tap, and `BlockLaunchGate.WALK_AWAY_TRANSITION_MS` is 1500ms: the window armed at the tap
     * has already closed, so the block re-arms over an app the user just declined, and counts.
     *
     * The fix re-arms the window at the moment of the pop, which is the event it is actually about.
     */
    @Test
    fun `a walk-away whose go-home never lands does not count a second intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()
        assertEquals(1, device.rows)

        tick(15_000)
        val overlay = device.liveOverlay!!
        overlay.walkAway()

        tick(1_200)
        overlay.failSafeFinish()

        tick(400)
        device.window(blocked)

        assertEquals(
            "counterfactual: armed only at the tap, this window event lands 1600ms later, past " +
                "the 1500ms transition window, and the block re-arms over an app the user declined",
            BlockLaunchGate.Decision.LAUNCH,
            BlockLaunchGate.decide(
                target = blocked,
                foreground = blocked,
                walkAway = overlay.walkAwayArmedAtTap(),
                nowMs = clock
            )
        )
        assertEquals(
            "the pop the fail-safe caused is part of the walk-away, not a fresh arrival",
            1,
            device.rows
        )
    }

    /**
     * H2 is also a SEED for H1: the pop puts the app back in front with the overlay gone, and if
     * the two then start fighting, the old code counted every round of it. The invariant holds for
     * the whole chain, not just the first event of it.
     */
    @Test
    fun `a fail-safe pop that turns into a fight with the app is still one intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()
        tick(15_000)
        val overlay = device.liveOverlay!!
        overlay.walkAway()
        tick(1_200)
        overlay.failSafeFinishWithoutRearming()

        repeat(50) {
            tick(1_100)
            device.window(blocked)
            device.deliverOverlayWindows()
            device.liveOverlay?.stopWithoutFinishing()
        }

        assertTrue(
            "counterfactual: the fight really does re-launch (launches=${device.launches})",
            device.launches > 20
        )
        assertEquals("and the user still only ever arrived once", 1, device.rows)
    }

    // ------------------------------------------------- H3: the re-delivery starves the bypass rule

    /**
     * **H3, PROVEN AT THE RULE AND FIXED.**
     *
     * A re-delivered block for the same target reaches a `singleInstance` activity through
     * `onNewIntent`, which never re-runs `onResume`. The old `onOverlayLaunched` reset
     * `windowShown` to false anyway — for an overlay that was sitting right there on the screen —
     * and nothing could ever set it back. Three seconds later every trailing window event from the
     * blocked app became a "bypass", each one worth a fresh evaluation, a fresh launch and (before
     * this fix) a fresh row, once per settle window, indefinitely.
     */
    @Test
    fun `a re-delivered block does not put a visible overlay back in flight`() {
        val shownAt = clock
        val onScreen = BlockLaunchGate.PendingOverlay(blocked, shownAt, windowShown = true, id = 7)

        val preFix = BlockLaunchGate.PendingOverlay(blocked, clock, windowShown = false, id = 8)
        tick(BlockLaunchGate.OVERLAY_SETTLE_MS + 1)
        assertTrue(
            "counterfactual: the old rule declares a bypass for an overlay that is on the screen, " +
                "and does it again every settle window for as long as the app keeps firing events",
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = ForegroundSignal.AppWindow(blocked),
                pending = preFix,
                nowMs = clock
            )
        )

        val kept = BlockLaunchGate.pendingOverlayAfterLaunch(
            pending = onScreen,
            target = blocked,
            nowMs = clock,
            id = 9
        )
        assertEquals(
            "a re-delivery through onNewIntent leaves the overlay exactly where it is: on screen",
            onScreen,
            kept
        )
    }

    /**
     * The same thing end to end: whatever the app underneath does while an overlay is live, the
     * count belongs to the one arrival the user made.
     */
    @Test
    fun `an app that keeps firing window events under a live overlay is one intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()

        repeat(100) {
            tick(BlockLaunchGate.OVERLAY_SETTLE_MS + 100)
            device.window(blocked)
        }

        assertEquals("the user arrived once", 1, device.rows)
        assertTrue(
            "and the overlay was still kept in front of them the whole time, which is what " +
                "enforcement means (launches=${device.launches})",
            device.launches >= 1
        )
    }

    // ------------------------------------------------------------------- H4: the other block paths

    /**
     * A decision that finishes while a live overlay is on screen — the shape H4 asks about — was
     * already dropped by the foreground and pending gates. What this pins is the outcome the user
     * sees: it never becomes a second number.
     */
    @Test
    fun `a decision landing while the overlay is already up is not a second intervention`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()

        repeat(10) {
            tick(200)
            device.handleBlockDecision(delayRule)
        }

        assertEquals("one entry, one row, however many decisions land behind it", 1, device.rows)
    }

    /**
     * The feature block is a DIFFERENT confrontation from the whole-app block on the same app, and
     * must stay one: the user waited out Instagram's delay and then went looking for Reels, which
     * is a second thing they ran into and a second thing the Willpower page should show them.
     * Bounded by construction — one row per kind per arrival, not one per event.
     */
    @Test
    fun `a feature block after a whole-app block in the same sitting is its own intervention`() {
        val reels = Block("feature", target = blocked, attributed = blocked, featureKey = "reels")
        val device = deviceBlocking()

        device.window(blocked)
        device.handleBlockDecision(delayRule)
        device.deliverOverlayWindows()
        assertEquals(1, device.rows)

        // The user waits the delay out. The overlay lets them through and the app comes back.
        tick(20_000)
        device.liveOverlay!!.completeAndFinish()
        device.window(blocked)

        // ...and then goes looking for Reels, which the feature rule blocks, over and over as the
        // detector re-fires on every debounced content change for the whole visit.
        repeat(20) {
            tick(300)
            device.handleBlockDecision(reels)
        }

        assertEquals(
            "the whole-app block and the feature block are two confrontations, and twenty " +
                "detections of the same feed are still one of them",
            2,
            device.rows
        )
    }

    /**
     * The web axis. The arrival is keyed on the BROWSER (that is the app the user is sitting in and
     * whose departure ends it), but the confrontation is keyed on the DOMAIN — so a second blocked
     * site in the same Chrome sitting is a second confrontation, while sitting on one blocked site
     * while it re-fires is one.
     */
    @Test
    fun `two blocked sites in one browser sitting are two interventions and no more`() {
        val instagram = Block("web", browser, attributed = blocked, webDomain = "instagram.com")
        val reddit = Block("web", browser, attributed = "com.reddit.frontpage", webDomain = "reddit.com")
        val device = deviceBlocking()

        device.window(browser)
        repeat(15) {
            tick(300)
            device.handleBlockDecision(instagram)
        }
        assertEquals("one site, one confrontation", 1, device.rows)

        repeat(15) {
            tick(300)
            device.handleBlockDecision(reddit)
        }
        assertEquals("navigating to a second blocked site is a second confrontation", 2, device.rows)
    }

    // -------------------------------------------------------------------- the arrival matrix

    /**
     * THE INVARIANT, over every departure the system can observe and every kind of block that
     * writes a row.
     *
     * Rows: what happened between the two blocks. Columns: which kind of block. The answer is the
     * same in every cell and that is the point — the rule is about the ARRIVAL, not about the
     * mechanism that produced the launch, which is why a sixth loop cannot bring the count back.
     */
    @Test
    fun `a second block counts only when the user genuinely left and came back`() {
        val kinds = listOf(
            Block("a rule DELAY", target = blocked, attributed = blocked),
            Block("a rule HARD_BLOCK", target = blocked, attributed = blocked),
            Block("a daily-limit HARD_BLOCK", target = blocked, attributed = blocked),
            Block("a feature block", target = blocked, attributed = blocked, featureKey = "reels"),
            Block("a web block", target = blocked, attributed = blocked, webDomain = "instagram.com")
        )

        // name -> (what happens between the two blocks, whether it is a departure)
        val arrivals: List<Triple<String, (Device) -> Unit, Boolean>> = listOf(
            Triple("the user went home", { d: Device ->
                d.window(launcher, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
            }, true),
            Triple("the user used another app", { d: Device -> d.window(otherApp) }, true),
            Triple("the user opened Nudge itself", { d: Device ->
                d.window(nudge, mainActivityClass)
            }, true),
            Triple("the screen went off", { d: Device -> d.screenOff() }, true),
            Triple("only our own block overlay appeared", { d: Device ->
                d.deliverOverlayWindows()
            }, false),
            Triple("the notification shade came down", { d: Device -> d.window(shade) }, false),
            Triple("a keyboard came up", { d: Device -> d.window(ime) }, false),
            Triple("nothing at all happened", { _: Device -> }, false)
        )

        for (kind in kinds) {
            for ((what, between, isDeparture) in arrivals) {
                val device = deviceBlocking()
                device.window(blocked)
                device.handleBlockDecision(kind)
                assertEquals("$what / ${kind.label}: the first block always counts", 1, device.rows)

                device.liveOverlay?.stopWithoutFinishing()
                tick(2_000)
                between(device)
                tick(2_000)
                device.window(blocked)
                device.handleBlockDecision(kind)

                val expected = if (isDeparture) 2 else 1
                assertEquals(
                    "$what, then ${kind.label} again: " +
                        if (isDeparture) {
                            "the user left and came back, which is a new confrontation"
                        } else {
                            "the user never left, so this is the same confrontation re-shown"
                        },
                    expected,
                    device.rows
                )
            }
        }
    }

    // ----------------------------------------------------------------- the storm diagnostic

    /**
     * The invariant makes the COUNT safe whatever loops — and makes the loop invisible, because the
     * symptom that used to arrive as "2500 interventions" now arrives as nothing at all. The storm
     * line is what keeps the next report diagnosable, and it must be ONE line: a loop firing every
     * second would otherwise fill logcat with the evidence of itself and push out everything that
     * explains it.
     */
    @Test
    fun `a loop that writes no rows still says so, exactly once`() {
        val device = deviceBlocking(delayRule)

        device.window(blocked)
        device.deliverOverlayWindows()
        repeat(40) {
            device.liveOverlay!!.stopWithoutFinishing()
            tick(1_100)
            device.window(blocked)
            device.deliverOverlayWindows()
        }

        assertEquals("one row, as the invariant promises", 1, device.rows)
        assertEquals(
            "and exactly one storm line for the whole run, naming the app it is about",
            1,
            device.storms.size
        )
        val storm = device.storms.single()
        assertEquals(blocked, storm.targetPackage)
        assertEquals(
            "reported the moment the run crosses the threshold, not after it has finished",
            BlockLaunchGate.STORM_LAUNCH_THRESHOLD,
            storm.launches
        )
        assertTrue(
            "and it names what the gate said, so the next report carries its own mechanism",
            storm.decisions.isNotEmpty()
        )
    }

    /**
     * Ordinary use must never produce one. Four blocks across four separate visits to an app is a
     * person having a bad afternoon, not a loop.
     */
    @Test
    fun `ordinary repeated visits to a blocked app never raise a storm`() {
        val device = deviceBlocking(delayRule)

        repeat(6) {
            device.window(blocked)
            device.deliverOverlayWindows()
            device.liveOverlay!!.stopWithoutFinishing()
            tick(30_000)
            device.window(launcher, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
            tick(30_000)
        }

        assertEquals("six genuine arrivals, six interventions", 6, device.rows)
        assertEquals("and nothing that looks like a loop", 0, device.storms.size)
    }

    // ------------------------------------------------------------------------ the cap holds

    /**
     * THE STRUCTURAL PROMISE, and the reason the cap is a CEILING rather than an eviction policy.
     *
     * Keeping "the last 32 keys" would bound what we remember and leave the rows unbounded: an
     * evicted key looks new again next time round, so a loop that manufactured distinct keys would
     * be back at 2500 a day with a tidier data structure. What issue #36 needs is a number one
     * arrival cannot exceed no matter what fires — including a mechanism nobody has thought of yet.
     */
    @Test
    fun `no arrival can write more rows than the cap, whatever fires`() {
        val device = deviceBlocking()
        device.window(blocked)

        repeat(500) { i ->
            tick(50)
            device.handleBlockDecision(
                Block("feature", target = blocked, attributed = blocked, featureKey = "surface$i")
            )
        }

        assertEquals(
            "500 distinct confrontations inside one arrival must not become 500 rows",
            BlockLaunchGate.MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL,
            device.rows
        )

        // ...and leaving really does reopen the count, so the ceiling is not a permanent mute.
        device.window(launcher, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
        tick(5_000)
        device.window(blocked)
        device.handleBlockDecision(delayRule)
        assertEquals(
            "a genuine departure starts a fresh arrival with a fresh budget",
            BlockLaunchGate.MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL + 1,
            device.rows
        )
    }
}
