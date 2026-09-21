package com.astraedus.nudge.domain.block

/**
 * Every lifecycle DECISION `BlockOverlayActivity` makes, as a pure state machine.
 *
 * ## Why this class exists
 *
 * Three of this repo's shipped bugs were Activity lifecycle ORDERING bugs, and all three were
 * invisible to a suite of ~1550 green unit tests:
 *
 *  - [#8](https://github.com/astraedus/nudge/issues/8) the countdown kept running while the overlay
 *    was stopped, hit zero invisibly and granted passthrough;
 *  - [#15](https://github.com/astraedus/nudge/issues/15) a block re-delivered through `onNewIntent`
 *    inherited the previous block's countdown state;
 *  - [#26](https://github.com/astraedus/nudge/issues/26) the walk-away's `finish()` raced the
 *    dispatched `GLOBAL_ACTION_HOME`, popped the blocked app back to the front, and the block
 *    re-armed over an app the user had just declined. Three reporters saw it every time; the bench
 *    Pixel 3 never reproduced it once.
 *
 * Plus the re-delivery and destroy-ordering mechanisms of
 * [#36](https://github.com/astraedus/nudge/issues/36) (2500 interventions in one day).
 *
 * None of those live in a *value* any test could read while the decisions sat inside an Activity:
 * an Activity is not JVM-constructible (Android lifecycle, Hilt field injection, Compose), so the
 * repo's only tool was a source-level grep that pins a SPELLING. The decisions are ordinary state
 * transitions, though — "is this delivery allowed to record a walk-away", "which pending overlay am
 * I", "may this fail-safe still fire" — so they are extracted here, where a JVM test can drive the
 * real sequence, against the real [BlockLaunchGuard][com.astraedus.nudge.service.BlockLaunchGuard],
 * and assert what the user would see.
 *
 * ## Shape
 *
 * Each method takes the platform facts the Activity owns and cannot be asked for here
 * (`isFinishing`, `isChangingConfigurations`, `isDestroyed`, whether the lifecycle is at least
 * STARTED) and returns the ORDERED [Effect]s the Activity must perform. The order of the returned
 * list is load-bearing and is asserted directly: [Effect.ArmWalkAwayWindow] before [Effect.GoHome]
 * is issue #26's fix, and [Effect.ArmWalkAwayWindow] before [Effect.Finish] on the fail-safe is the
 * same fix at the moment of the pop it deliberately causes.
 *
 * ## What this class deliberately does NOT know
 *
 * The blocked app's package, the block mode, the intent, the Compose tree. Those are the Activity's
 * business; what is here is only the ordering and the per-delivery bookkeeping. And it cannot prove
 * the Activity actually calls it — that half stays a source-level contract
 * (`BlockOverlayLaunchContractTest`, `BlockOverlayWalkAwayContractTest`).
 */
class OverlayLifecycle {

    /** Something the Activity must do. Ordered; see the class doc. */
    sealed interface Effect {

        /** Tell the service no block overlay is covering an app any more. */
        data object MarkOverlayInactive : Effect

        /** `finish()`. */
        data object Finish : Effect

        /** `BlockLaunchGuard.onOverlayShown()` — the overlay is genuinely on screen. */
        data object ReportOverlayShown : Effect

        /**
         * `BlockLaunchGuard.onOverlayDismissed(overlayId)`, carrying the id of the delivery THIS
         * instance adopted, never "whatever is pending now". See [onDelivered].
         */
        data class ReportOverlayDismissed(val overlayId: Long) : Effect

        /** `BlockLaunchGuard.onWalkAwayStarted(packageName)` — a departure is in flight. */
        data class ArmWalkAwayWindow(val packageName: String) : Effect

        /** `RecordWalkAwayUseCase.record(...)` — the one writer of `userChangedMind = true`. */
        data class RecordWalkAway(val packageName: String, val blockMode: String) : Effect

        /** Dispatch `GLOBAL_ACTION_HOME`, with the HOME intent as the fallback. */
        data object GoHome : Effect

        /**
         * The user waited the timer out, so the app may be opened: `PassthroughManager.grant(...)`.
         * Emitted ONLY when the overlay is at least STARTED — a countdown that somehow completed
         * while backgrounded must never open the app (issue #8).
         */
        data object GrantPassthrough : Effect

        /** Post [token]'s fail-safe finish [delayMs] from now. */
        data class ScheduleFailSafeFinish(val token: Int, val delayMs: Long) : Effect

        /** Drop any fail-safe left over from a previous delivery. */
        data object CancelPendingFailSafe : Effect

        /** One `w` line: the dispatched go-home never stopped us. */
        data object LogFailSafeFired : Effect
    }

    /**
     * Which pending overlay in `BlockLaunchGuard` this ACTIVITY INSTANCE is, adopted once per
     * delivery.
     *
     * The dying instance must not clear the live one's state. An overlay that finishes itself from
     * `onStop` while the service launches a replacement has its `onDestroy` run AFTER the
     * replacement's `onResume`, so an unconditional clear wiped the live overlay's pending state and
     * handed the newly blocked app's own start-up window events straight back to the bypass rule —
     * a second overlay and a second `wasBlocked` row for one entry. Part of issue #36.
     */
    var overlayId: Long = BlockLaunchGate.NO_OVERLAY_ID
        private set

    /**
     * Incremented on every delivery, so each delivered block composes under a fresh Compose `key`
     * and discards the previous block's remembered countdown state (issue #15: the overlay showed
     * the NEW app's name over the PREVIOUS app's remaining seconds, and the progress ring froze
     * because 30 remaining over a 15s delay is 2.0).
     *
     * A COUNTER rather than the block's contents: two different apps can legitimately share a
     * package/mode/delay triple, and re-delivering the *same* block is still a new attempt that must
     * start from full. It doubles as the fail-safe's identity — see [onFailSafeFired].
     */
    var renderToken: Int = 0
        private set

    /**
     * Set when THIS DELIVERY has terminated as a walk-away, so the path runs exactly once no matter
     * how many times it is reached (the button, the back gesture, or a double tap).
     *
     * Per delivery, not per activity. It used to be latched for the life of the activity, which was
     * correct only while `navigateHome` finished immediately: a re-delivered block always arrived on
     * a fresh instance. Deferring that finish (see [WALK_AWAY_FINISH_FAILSAFE_MS]) made it false —
     * the activity now stays RESUMED for up to 1200ms after a walk-away, and a block for a DIFFERENT
     * package delivered inside that window renders on this same instance. Against a latched flag its
     * "I changed my mind" and its back gesture would both be dead, and on a `HARD_BLOCK`, which has
     * no completion path, that is a user with no way off the screen.
     */
    private var walkedAway = false

    companion object {
        /**
         * How long the walk-away path waits for the dispatched go-home to stop us, before finishing
         * anyway.
         *
         * The normal path is not this timer: `GLOBAL_ACTION_HOME` brings the launcher forward, the
         * activity is stopped, and `onStop` finishes it — from the BACKGROUND, where finishing pops
         * nothing forward. This exists only so a go-home the platform accepted and never honoured
         * cannot strand the user on an overlay whose buttons are already spent.
         *
         * Deliberately SHORTER than [BlockLaunchGate.WALK_AWAY_TRANSITION_MS] (1500ms). The two
         * numbers are one mechanism: if this fail-safe fires, its finish pops back to the blocked
         * app's task — exactly the resurfacing issue #26 is about — and it must land while the
         * service's walk-away window is still open to suppress the block it would otherwise re-arm.
         * Both are now plain constants, so `BlockOverlayLaunchContractTest` compares the VALUES
         * instead of regexing one of them out of a source file.
         */
        const val WALK_AWAY_FINISH_FAILSAFE_MS = 1_200L
    }

    /**
     * A block has been delivered to this instance — through `onCreate` for the first one, through
     * `onNewIntent` for every later one, since the activity is `singleInstance` and `onCreate` never
     * runs a second time.
     *
     * A NEW delivery is a NEW attempt: it gets its own walk-away budget, its own timers, and its own
     * Compose key. [pendingOverlayId] is read from the guard by the caller at this exact moment
     * because a delivery runs synchronously from `onCreate`/`onNewIntent`, i.e. immediately after
     * the service's `startActivity`, so the guard's current pending overlay IS this delivery's.
     *
     * The fail-safe is dropped here as well as guarded by [renderToken]; cancelling means nobody has
     * to know about the token guard to reason about a re-delivery.
     */
    fun onDelivered(pendingOverlayId: Long): List<Effect> {
        walkedAway = false
        overlayId = pendingOverlayId
        renderToken++
        return listOf(Effect.CancelPendingFailSafe)
    }

    /**
     * The delivered block cannot be rendered (`BlockMode.NONE` blocks nothing, so `BlockEngine`
     * never produces one and this activity should never be launched for it). Dismiss rather than
     * render: there is no "no-op overlay", and showing any of the three would gate an app the user
     * explicitly chose not to gate.
     */
    fun onUnrenderableBlock(): List<Effect> = listOf(Effect.MarkOverlayInactive, Effect.Finish)

    /**
     * The overlay is genuinely ON SCREEN, which is a different fact from "we started it".
     *
     * The service sets `isOverlayActive` at `startActivity`, hundreds of milliseconds before this,
     * and in that gap the blocked app is still starting up and still firing window events of its
     * own. Reporting from here is the only reliable evidence of when the gap closes: the
     * accessibility stream cannot supply it, because the overlay TASK's first window arrives ~600ms
     * early carrying a framework class name. See [BlockLaunchGate.isGenuineBypass].
     */
    fun onResumed(): List<Effect> = listOf(Effect.ReportOverlayShown)

    /**
     * Leaving the overlay ABANDONS the block attempt (issue #8).
     *
     * This activity is `singleInstance` in its own task with an empty `taskAffinity`, so tabbing out
     * (Home, a recents switch, screen off) only STOPPED it — it stayed alive in the background with
     * its countdown still running, hit zero invisibly, granted passthrough, and the blocked app then
     * opened with no delay at all.
     *
     * @param isFinishing a completion path (timer, walk-away, emergency pass) already finished us,
     *   so finishing again would be a second mark-inactive for one dismissal.
     * @param isChangingConfigurations a ROTATION must not dismiss a live block.
     */
    fun onStopped(isFinishing: Boolean, isChangingConfigurations: Boolean): List<Effect> =
        if (isFinishing || isChangingConfigurations) {
            emptyList()
        } else {
            listOf(Effect.MarkOverlayInactive, Effect.Finish)
        }

    /**
     * The user waited the timer out.
     *
     * @param hasPassthroughTarget false when the delivery carried no package to grant to.
     * @param atLeastStarted whether the overlay is at least STARTED. A countdown that somehow
     *   reached zero while backgrounded must never open the app — that silent grant WAS issue #8's
     *   bypass, and this is the belt-and-braces layer behind the lifecycle-gated ticker and
     *   [onStopped].
     */
    fun onTimerCompleted(hasPassthroughTarget: Boolean, atLeastStarted: Boolean): List<Effect> =
        buildList {
            if (hasPassthroughTarget && atLeastStarted) add(Effect.GrantPassthrough)
            add(Effect.MarkOverlayInactive)
            add(Effect.Finish)
        }

    /**
     * The user walked away: "I changed my mind", or the back gesture.
     *
     * Returns an EMPTY list for a second call within the same delivery — the once-only gate. A
     * double tap, or a tap racing the back button, cannot log two walk-aways for one attempt.
     *
     * The order is the fix for issue #26, and every step of it matters:
     *
     *  1. record the walk-away (through a process-lifetime use case, so the insert is not tied to an
     *     activity that is destroyed microseconds later);
     *  2. clear the overlay flag — the block is over the moment the user turns around;
     *  3. **arm the walk-away window BEFORE the go-home is dispatched**, never after: the whole
     *     point is to be in place when the blocked app's window resurfaces, and that can happen on
     *     the very next frame;
     *  4. dispatch the go-home;
     *  5. schedule the fail-safe.
     *
     * There is deliberately no [Effect.Finish]. `finish()` on this singleInstance activity pops
     * straight back to the task underneath — the blocked app — and `GLOBAL_ACTION_HOME` is
     * dispatched asynchronously, so an inline finish was in a race it could lose. On a device where
     * the pop won, the blocked app genuinely resumed, fired a real window event, and the service
     * re-blocked it. [onStopped] finishes us instead, once the launcher has genuinely stopped this
     * activity, and a finish from the background pops nothing forward.
     *
     * There is also deliberately no [Effect.GrantPassthrough]: turning around is not permission to
     * enter, so the next attempt gets a fresh, full block.
     *
     * @param attributedPackage the app the block is recorded against (the rule's app; for a web
     *   block that is Instagram, not the browser).
     * @param walkAwayPackage the package the user is sitting IN — the BROWSER for a web block —
     *   because that is whose window is about to resurface underneath the finishing overlay, and
     *   whose re-entry must not be read as a fresh arrival.
     */
    fun onWalkAwayRequested(
        attributedPackage: String,
        blockMode: String,
        walkAwayPackage: String
    ): List<Effect> {
        if (walkedAway) return emptyList()
        walkedAway = true
        return listOf(
            Effect.RecordWalkAway(attributedPackage, blockMode),
            Effect.MarkOverlayInactive,
            Effect.ArmWalkAwayWindow(walkAwayPackage),
            Effect.GoHome,
            Effect.ScheduleFailSafeFinish(renderToken, WALK_AWAY_FINISH_FAILSAFE_MS)
        )
    }

    /**
     * The scheduled fail-safe fired: the dispatched go-home never stopped us.
     *
     * Refused when the activity is already on its way out, and when [token] is not the CURRENT
     * [renderToken] — a new block delivered through `onNewIntent` during the transition must not be
     * killed by the previous attempt's timer; that block belongs to a different app or a different
     * rule and the user has not walked away from it.
     *
     * **[Effect.ArmWalkAwayWindow] comes before [Effect.Finish], and that is the whole point.** This
     * finish is the old #26 bug reproduced deliberately: it pops the blocked app forward, that app
     * fires a real window event, and the only thing that stops the service re-blocking an app the
     * user just declined is the walk-away window. Armed only at the tap, the window had whatever was
     * left of 1500ms minus the 1200ms spent waiting — 300ms for the pop, the app's resume and its
     * window event to all land, on precisely the slow devices that reported #26 in the first place.
     * Re-arming measures the window from the event it is actually about.
     */
    fun onFailSafeFired(
        token: Int,
        isFinishing: Boolean,
        isDestroyed: Boolean,
        walkAwayPackage: String
    ): List<Effect> =
        if (isFinishing || isDestroyed || token != renderToken) {
            emptyList()
        } else {
            listOf(
                Effect.LogFailSafeFired,
                Effect.ArmWalkAwayWindow(walkAwayPackage),
                Effect.Finish
            )
        }

    /**
     * This instance is gone.
     *
     * The dismissal is IDENTIFIED by [overlayId], never unconditional: a replacement instance is
     * already on screen by the time a finished one is destroyed, and clearing its pending overlay
     * from here is the third mechanism of issue #36.
     */
    fun onDestroyed(): List<Effect> = listOf(
        Effect.CancelPendingFailSafe,
        Effect.MarkOverlayInactive,
        Effect.ReportOverlayDismissed(overlayId)
    )
}
