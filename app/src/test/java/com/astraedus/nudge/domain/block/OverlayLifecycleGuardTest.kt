package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.service.BlockLaunchGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayLifecycle] driven against the REAL [BlockLaunchGuard], in the orders the platform
 * actually produces them.
 *
 * `OverlayLifecycleTest` asserts what the state machine returns. This file asserts what the SERVICE
 * then believes — because every bug here was a disagreement between the two objects, never a wrong
 * value inside either one. The guard is the real singleton with its real clock seam; nothing is
 * mocked, so a change to either side that breaks the pairing fails here.
 *
 * **Every scenario carries a counterfactual**, in the repo's existing style
 * (`A11yCaptureReplayTest`, `BlockLaunchGuardReplayTest`): the pre-fix rule is run over the same
 * sequence and asserted to still produce the reported symptom. A regression test whose counterfactual
 * has quietly stopped failing is a green test asserting nothing, and these sequences are exactly the
 * kind that stop reproducing when a constant moves.
 *
 * The orderings modelled here are the ones the platform really produces for a `singleInstance`
 * activity, and they are the reason this bug class shipped roughly once per release:
 *  - a block re-delivered through `onNewIntent`, which NEVER re-runs `onResume`;
 *  - a dying instance's `onDestroy` running AFTER its replacement's `onCreate`/`onResume`;
 *  - a walk-away whose dispatched `GLOBAL_ACTION_HOME` the platform accepted and never honoured.
 *
 * What this file cannot see: whether `BlockOverlayActivity` actually forwards each callback. That
 * residue belongs to the source-level contract tests, and it is the standing argument for an
 * emulator layer later (`docs/testing-strategy.md`).
 */
class OverlayLifecycleGuardTest {

    private companion object {
        const val KEEP = "com.google.android.keep"
        const val INSTAGRAM = "com.instagram.android"
        const val DELAY = "DELAY"
    }

    /** Monotonic, hand-advanced: the guard measures every window on `elapsedRealtime`. */
    private var clock = 10_000L
    private val guard = BlockLaunchGuard().also { it.nowMs = { clock } }

    /**
     * The service's side of a launch, in the order `NudgeAccessibilityService.launchBlockOverlay`
     * does it: mark the overlay started, then start the activity — whose `render` immediately reads
     * back which pending overlay it is.
     */
    private fun serviceLaunches(target: String): Long {
        guard.onOverlayLaunched(target)
        return guard.currentOverlayId()
    }

    /** Apply one effect list to the guard, exactly as `BlockOverlayActivity.runEffects` would. */
    private fun applyToGuard(effects: List<OverlayLifecycle.Effect>) {
        effects.forEach { effect ->
            when (effect) {
                is OverlayLifecycle.Effect.ReportOverlayShown -> guard.onOverlayShown()
                is OverlayLifecycle.Effect.ReportOverlayDismissed ->
                    guard.onOverlayDismissed(effect.overlayId)
                is OverlayLifecycle.Effect.ArmWalkAwayWindow ->
                    guard.onWalkAwayStarted(effect.packageName)
                else -> Unit // everything else is Activity-side and invisible to the guard
            }
        }
    }

    private fun indexOfArm(effects: List<OverlayLifecycle.Effect>) =
        effects.indexOfFirst { it is OverlayLifecycle.Effect.ArmWalkAwayWindow }

    private fun indexOfFinish(effects: List<OverlayLifecycle.Effect>) =
        effects.indexOfFirst { it is OverlayLifecycle.Effect.Finish }

    // ------------------------------------------------------------------------------------------
    // #36 mechanism 2 — a re-delivery must not make a visible overlay look like one still in flight
    // ------------------------------------------------------------------------------------------

    /**
     * ISSUE #36's SECOND MECHANISM. A second block for the same app reaches the `singleInstance`
     * activity through `onNewIntent`, which never re-runs `onResume` — so nothing will ever report
     * the overlay on screen a second time. Before PR #40 the relaunch reset the guard's pending
     * overlay to `windowShown = false` anyway, for an overlay that was sitting right there in front
     * of the user, and nothing could set it back.
     *
     * The observable form of `windowShown` surviving is the overlay ID surviving: the guard keeps
     * the existing `PendingOverlay` (hence its id) if and only if it was already shown. A re-delivery
     * therefore adopts the SAME id, which is what lets the eventual `onDestroy` clear the overlay it
     * actually owns rather than a stranger's.
     */
    @Test
    fun `a second block delivered to an overlay already on screen stays the same overlay`() {
        val overlay = OverlayLifecycle()

        val firstId = serviceLaunches(KEEP)
        overlay.onDelivered(firstId)
        applyToGuard(overlay.onResumed())

        clock += 5_000 // the user has been sitting on the overlay, well past OVERLAY_SETTLE_MS

        val redeliveredId = serviceLaunches(KEEP)
        overlay.onDelivered(redeliveredId)

        assertEquals(
            "a block re-delivered to the overlay that is already on screen is the same overlay, " +
                "so the guard must keep its pending state instead of starting a new one",
            firstId,
            redeliveredId
        )
        assertEquals(firstId, overlay.overlayId)
    }

    /**
     * And the consequence the user meets, which is the reason the state must not lie.
     *
     * `BlockLaunchGate.decide` refuses a launch as `DROP_ALREADY_PENDING` while an overlay for the
     * same app is still on its WAY to the screen — correct, because a second overlay for one entry
     * writes a second `wasBlocked` row. An overlay that has ARRIVED is not in that state, so a later
     * genuinely different confrontation in the same app (a second blocked site in one browser
     * sitting, a Reels block after a whole-app block) must still be allowed to show.
     *
     * Under the pre-#40 reset the re-delivery restarted the settle window, so for the next three
     * seconds every real block for that app was silently dropped.
     */
    @Test
    fun `a genuine block is still shown after a re-delivery to a visible overlay`() {
        val overlay = OverlayLifecycle()
        guard.onForegroundSignal(ForegroundSignal.AppWindow(KEEP))

        overlay.onDelivered(serviceLaunches(KEEP))
        applyToGuard(overlay.onResumed())
        clock += 5_000

        overlay.onDelivered(serviceLaunches(KEEP))
        clock += 500 // well inside OVERLAY_SETTLE_MS of the re-delivery

        assertEquals(
            "an overlay that is on screen is not an overlay still in flight; a further real " +
                "confrontation in this app must still get its block",
            BlockLaunchGate.Decision.LAUNCH,
            guard.decide(KEEP)
        )

        // COUNTERFACTUAL: the pre-PR-#40 rule, which reset an on-screen overlay to "not yet shown".
        val preFixPending = BlockLaunchGate.PendingOverlay(
            packageName = KEEP,
            launchedAtMs = clock - 500,
            windowShown = false,
            id = 1L
        )
        assertEquals(
            "the old rule really does drop it: this is the block the user walks into and never sees",
            BlockLaunchGate.Decision.DROP_ALREADY_PENDING,
            BlockLaunchGate.decide(
                target = KEEP,
                foreground = KEEP,
                walkAway = null,
                nowMs = clock,
                pendingOverlay = preFixPending
            )
        )
    }

    // ------------------------------------------------------------------------------------------
    // #36 mechanism 3 — a dying overlay must not clear the live one's state
    // ------------------------------------------------------------------------------------------

    /**
     * ISSUE #36's THIRD MECHANISM, in the ordering the platform actually produces: an overlay that
     * finishes itself from `onStop` has its `onDestroy` run AFTER the replacement instance's
     * `onResume`.
     *
     * `onDestroy` used to clear the guard's pending overlay unconditionally, so the instance that
     * was already gone wiped the state of the one on screen.
     */
    @Test
    fun `an old overlay's destruction after its replacement is on screen leaves the replacement alone`() {
        val dying = OverlayLifecycle()
        val live = OverlayLifecycle()

        val dyingId = serviceLaunches(KEEP)
        dying.onDelivered(dyingId)
        applyToGuard(dying.onResumed())

        // The user's task re-fronts (or the screen goes off): onStop finishes this overlay.
        assertEquals(
            listOf(OverlayLifecycle.Effect.MarkOverlayInactive, OverlayLifecycle.Effect.Finish),
            dying.onStopped(isFinishing = false, isChangingConfigurations = false)
        )

        clock += 200
        val liveId = serviceLaunches(INSTAGRAM)
        live.onDelivered(liveId)
        applyToGuard(live.onResumed())
        assertNotEquals("a different app is a different overlay", dyingId, liveId)

        // ...and only NOW does the old instance finally die.
        applyToGuard(dying.onDestroyed())

        assertEquals(
            "only the instance that owns the pending overlay may clear it",
            liveId,
            guard.currentOverlayId()
        )
    }

    /**
     * The same ordering, in the window where the unconditional clear actually costs a row: the
     * replacement has been STARTED but has not reached the screen yet, which is the only moment its
     * target's own start-up window events are protected from being read as a bypass.
     *
     * With the protection wiped, Instagram's own first window — fired while the overlay is still on
     * its way — reads as the user having got past the block, so the service marks the overlay
     * inactive, re-evaluates, launches a second overlay and (before #36's arrival invariant) wrote a
     * second `wasBlocked` row for one entry into one app.
     */
    @Test
    fun `a dying overlay does not hand the newly blocked app's start-up windows back to the bypass rule`() {
        val dying = OverlayLifecycle()
        val live = OverlayLifecycle()

        dying.onDelivered(serviceLaunches(KEEP))
        applyToGuard(dying.onResumed())
        dying.onStopped(isFinishing = false, isChangingConfigurations = false)

        clock += 200
        live.onDelivered(serviceLaunches(INSTAGRAM))
        applyToGuard(dying.onDestroyed()) // the dying instance reports ITS OWN id

        clock += 150 // Instagram is still starting up under an overlay that has not arrived
        assertTrue(
            "the replacement's protection must survive the finish of the overlay it replaced",
            !guard.isGenuineBypass(
                A11yEventType.WINDOW_STATE_CHANGED,
                ForegroundSignal.AppWindow(INSTAGRAM)
            )
        )

        // COUNTERFACTUAL: the pre-fix unconditional clear, i.e. the dying instance reporting
        // "whatever is pending" instead of the overlay it owns.
        guard.onOverlayDismissed(guard.currentOverlayId())
        assertTrue(
            "and the old rule really does expose it: with the pending overlay wiped, Instagram's " +
                "own start-up window reads as the user getting past a block that is still in flight",
            guard.isGenuineBypass(
                A11yEventType.WINDOW_STATE_CHANGED,
                ForegroundSignal.AppWindow(INSTAGRAM)
            )
        )
    }

    /**
     * The positive control for the scoping above: identifying the dismissal must not amount to
     * never clearing it. When the overlay that owns the pending state is the one destroyed, the
     * state really does go, and the blocked app's next window is a genuine bypass again.
     */
    @Test
    fun `the last overlay's destruction does stop suppressing bypasses`() {
        val overlay = OverlayLifecycle()
        overlay.onDelivered(serviceLaunches(KEEP))

        applyToGuard(overlay.onDestroyed())

        assertEquals(BlockLaunchGate.NO_OVERLAY_ID, guard.currentOverlayId())
        assertTrue(
            "with nothing pending, a window event for the blocked app is the user in that app",
            guard.isGenuineBypass(
                A11yEventType.WINDOW_STATE_CHANGED,
                ForegroundSignal.AppWindow(KEEP)
            )
        )
    }

    // ------------------------------------------------------------------------------------------
    // #26 — "I changed my mind" must not hand the foreground back on the way out
    // ------------------------------------------------------------------------------------------

    /**
     * ISSUE #26, *"I changed my mind — have to click twice"*: three reporters, every time, and never
     * once on the bench Pixel 3, because the ordering that produces it is device and launcher
     * timing.
     *
     * The tap arms the window BEFORE the go-home is dispatched, so the guard is already refusing
     * blocks for that app by the time its window can resurface on the very next frame.
     */
    @Test
    fun `walking away stops the app the user just declined from being blocked again`() {
        val overlay = OverlayLifecycle()
        guard.onForegroundSignal(ForegroundSignal.AppWindow(KEEP))
        overlay.onDelivered(serviceLaunches(KEEP))
        applyToGuard(overlay.onResumed())

        clock += 6_000 // the user reads the overlay, then taps "I changed my mind"
        val effects = overlay.onWalkAwayRequested(KEEP, DELAY, KEEP)
        applyToGuard(effects)

        clock += 100 // the finishing overlay's pop reveals Keep, which fires a real window event
        assertEquals(
            "a departure is in flight; this window is Keep leaving, not the user arriving",
            BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT,
            guard.decide(KEEP)
        )

        // COUNTERFACTUAL: the same instant on a guard that was never told a walk-away started —
        // the pre-fix behaviour, where the block re-armed over an app the user had just declined
        // and only the second tap ever worked.
        val unarmed = BlockLaunchGuard().also { it.nowMs = { clock } }
        unarmed.onForegroundSignal(ForegroundSignal.AppWindow(KEEP))
        assertEquals(
            "without the armed window the block really does come straight back",
            BlockLaunchGate.Decision.LAUNCH,
            unarmed.decide(KEEP)
        )
    }

    /**
     * ISSUE #26 AT THE FAIL-SAFE, which is the half that a window armed only at the tap cannot
     * cover.
     *
     * The fail-safe finish deliberately reproduces #26's pop: it exists for a `GLOBAL_ACTION_HOME`
     * the platform accepted and never honoured, and the only way off that overlay is to pop the
     * blocked app forward. Armed once at the tap, the 1500ms window had whatever was left after the
     * 1200ms spent waiting — 300ms for the pop, the app's resume and its window event to all land,
     * on precisely the slow devices that reported #26 in the first place.
     *
     * So the fail-safe re-arms the window at the moment of the pop, and the effect ORDER is the
     * mechanism: arm, then finish.
     */
    @Test
    fun `the walk-away fail-safe re-arms the window at the pop it causes`() {
        val overlay = OverlayLifecycle()
        guard.onForegroundSignal(ForegroundSignal.AppWindow(KEEP))
        overlay.onDelivered(serviceLaunches(KEEP))
        applyToGuard(overlay.onResumed())

        val tapAt = clock
        val walkAway = overlay.onWalkAwayRequested(KEEP, DELAY, KEEP)
        applyToGuard(walkAway)
        val scheduled = walkAway
            .filterIsInstance<OverlayLifecycle.Effect.ScheduleFailSafeFinish>()
            .single()

        clock = tapAt + scheduled.delayMs // the go-home never landed; the fail-safe fires
        val failSafe = overlay.onFailSafeFired(
            token = scheduled.token,
            isFinishing = false,
            isDestroyed = false,
            walkAwayPackage = KEEP
        )
        assertTrue(
            "the window must be re-armed BEFORE the finish that pops the blocked app forward",
            indexOfArm(failSafe) in 0 until indexOfFinish(failSafe)
        )
        applyToGuard(failSafe)

        // The pop, Keep's resume and its window event, on a slow device.
        clock += 400
        assertEquals(
            "the block must not come back over an app the user declined 1.6 seconds ago",
            BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT,
            guard.decide(KEEP)
        )

        // COUNTERFACTUAL: the same run with the window armed only at the tap.
        val tapOnly = BlockLaunchGuard().also { it.nowMs = { clock } }
        tapOnly.onForegroundSignal(ForegroundSignal.AppWindow(KEEP))
        tapOnly.nowMs = { tapAt }
        tapOnly.onWalkAwayStarted(KEEP)
        tapOnly.nowMs = { clock }
        assertEquals(
            "armed only at the tap, the window has expired by the time the pop it caused lands " +
                "(${clock - tapAt}ms elapsed vs a ${BlockLaunchGate.WALK_AWAY_TRANSITION_MS}ms window)",
            BlockLaunchGate.Decision.LAUNCH,
            tapOnly.decide(KEEP)
        )
    }

    /**
     * The two constants are one mechanism, and they live in two files. If the overlay ever gave up
     * AFTER the service stopped covering for it, the fail-safe's own pop would be the #26 bug with
     * nothing left to suppress it.
     *
     * Asserted as VALUES rather than by reading one of them out of a source file, which is what this
     * relationship used to be pinned by.
     */
    @Test
    fun `the overlay gives up before the service stops covering for it`() {
        assertTrue(
            "fail-safe ${OverlayLifecycle.WALK_AWAY_FINISH_FAILSAFE_MS}ms must be shorter than " +
                "the walk-away window ${BlockLaunchGate.WALK_AWAY_TRANSITION_MS}ms",
            OverlayLifecycle.WALK_AWAY_FINISH_FAILSAFE_MS <
                BlockLaunchGate.WALK_AWAY_TRANSITION_MS
        )
    }

    /**
     * A block for a DIFFERENT app delivered during the walk-away's transition renders on this same
     * instance, because the activity is `singleInstance` and now survives a walk-away for up to
     * `WALK_AWAY_FINISH_FAILSAFE_MS`. The previous attempt's fail-safe must then do nothing at all:
     * it would otherwise finish a block the user has not walked away from, and arm a departure
     * window for an app they are not leaving.
     */
    @Test
    fun `a spent attempt's fail-safe cannot finish or arm anything for the block that replaced it`() {
        val overlay = OverlayLifecycle()
        guard.onForegroundSignal(ForegroundSignal.AppWindow(KEEP))
        overlay.onDelivered(serviceLaunches(KEEP))
        applyToGuard(overlay.onResumed())

        val scheduled = overlay.onWalkAwayRequested(KEEP, DELAY, KEEP)
            .filterIsInstance<OverlayLifecycle.Effect.ScheduleFailSafeFinish>()
            .single()

        clock += 300 // a block for Instagram arrives through onNewIntent, mid-transition
        guard.onForegroundSignal(ForegroundSignal.AppWindow(INSTAGRAM))
        overlay.onDelivered(serviceLaunches(INSTAGRAM))

        clock += 900
        val stale = overlay.onFailSafeFired(
            token = scheduled.token,
            isFinishing = false,
            isDestroyed = false,
            walkAwayPackage = KEEP
        )

        assertEquals(
            "the previous attempt's timer must not act on the block that replaced it",
            emptyList<OverlayLifecycle.Effect>(),
            stale
        )
        applyToGuard(stale)
        assertNotEquals(
            "and Instagram's own block must still be showable: the user never walked away from it",
            BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT,
            guard.decide(INSTAGRAM)
        )
    }

    /**
     * A web block is attributed to the rule's app and armed for the browser, and the two must not be
     * collapsed: the window that has to be suppressed belongs to the app the user is SITTING in, and
     * that is whose window resurfaces underneath the finishing overlay.
     */
    @Test
    fun `a web block arms the browser's window, not the blocked app's`() {
        val overlay = OverlayLifecycle()
        val chrome = "com.android.chrome"
        guard.onForegroundSignal(ForegroundSignal.AppWindow(chrome))
        overlay.onDelivered(serviceLaunches(chrome))
        applyToGuard(overlay.onResumed())

        applyToGuard(
            overlay.onWalkAwayRequested(
                attributedPackage = INSTAGRAM, // the overlay says "Instagram", the row lands there
                blockMode = DELAY,
                walkAwayPackage = chrome // ...but the browser is what resurfaces
            )
        )

        clock += 100
        assertEquals(
            BlockLaunchGate.Decision.DROP_WALK_AWAY_IN_FLIGHT,
            guard.decide(chrome)
        )
    }
}
