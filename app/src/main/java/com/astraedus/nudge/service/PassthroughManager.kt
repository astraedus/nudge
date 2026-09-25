package com.astraedus.nudge.service

import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The post-block passthrough: what the user has earned the right to enter without being re-blocked.
 *
 * Two axes, ONE lifetime. [lastPackage] is the app whose delay/breathing exercise was completed;
 * [lastDomain] is the website's equivalent. They are granted together (a web block is completed in a
 * browser, so both are true at once) and cleared together, which is the point of them living here.
 *
 * The web axis used to be a `lastBlockedDomain` field on the accessibility service, set at BLOCK
 * time rather than at completion time -- so walking away from a website's delay, or tabbing out of
 * it, left the pass granted anyway. Every other grant in this app is earned by finishing the
 * exercise ([BlockOverlayActivity.onTimerComplete][com.astraedus.nudge.ui.overlay.BlockOverlayActivity]);
 * this one now is too.
 *
 * ## A grant belongs to a SITTING, not to an event (issue #28)
 *
 * A grant used to die the moment any foreign package fired a window event, via
 * `clearIfAppChanged` on the service's hot path. That read "another package has a window" as "the
 * user left", so every sub-flow an app launches -- a photo picker, a share sheet, a permission
 * dialog, a custom tab, an OEM volume panel -- revoked the pass and re-blocked the user on return.
 * Device-captured; see [SittingTracker] for the log and
 * `app/src/test/resources/a11y-captures/picker-subflow-keeps-sitting.jsonl` for the event stream.
 *
 * The grant's lifetime is now the sitting's lifetime, and [SittingTracker] owns what a sitting is.
 * That is why the tracker lives INSIDE this class rather than beside it: a caller cannot apply a
 * foreground signal without the revocation happening, and cannot grant without the sitting moving to
 * the app it granted for. The two can no longer be updated out of step, because there is only one
 * call for each.
 *
 * There is still deliberately **no time-based expiry on the grant itself**: a naive
 * `now - lastTime > N` would re-block a user mid-use, still inside the app, which is issue #5. What
 * expires is the SITTING, and only when the user has actually been somewhere else.
 */
@Singleton
class PassthroughManager @Inject constructor() {

    /**
     * The one definition of "the user is in a sitting with app X".
     *
     * Its return window is deliberately SHORTER than [InteractionTracker.SESSION_EXPIRY_MS] -- see
     * [SittingTracker]'s constructor doc. Sharing one constant reads tidier and is wrong: the
     * session expiry answers "should the time budget refill" (where generous is safe), while this
     * answers "did the user leave" and governs permission to skip a delay (where generous is a
     * bypass).
     */
    private val sitting = SittingTracker()

    /**
     * What to do when the sitting changes, registered by the accessibility service.
     *
     * A callback rather than the service reacting after each mutation, because the mutations do not
     * all happen in the service: a grant is earned in `BlockOverlayActivity`, in a different class,
     * and `grant()` therefore DISCARDED its transition. Moving the sitting from app A to app B
     * without telling anyone left `InteractionCounter` holding A's scroll sources, so A's primary
     * source blocked B's counter for the whole handover window and A's caption leaked over B's
     * count.
     *
     * Routing every sitting change through one notifying path makes that unforgettable rather than
     * merely fixed: there is no way to move the sitting that does not announce it.
     */
    @Volatile
    private var sittingReaction: ((SittingEvent) -> Unit)? = null

    /** Register the service's reaction. Set on connect, cleared on destroy. */
    fun setSittingReaction(reaction: ((SittingEvent) -> Unit)?) {
        sittingReaction = reaction
    }

    @Volatile var lastPackage: String? = null
        private set
    @Volatile var lastFeature: String? = null
        private set
    @Volatile var lastTime: Long = 0L
        private set

    /**
     * The web domain whose block was completed, normalised (see
     * [com.astraedus.nudge.domain.web.WebSessionKey]). Null when the completed block was not a web
     * one -- a HARD_BLOCK never reaches a completion path at all, so it can never grant.
     */
    @Volatile var lastDomain: String? = null
        private set

    /**
     * Record a completed block. Moves the sitting to [packageName] first: finishing a delay is
     * unambiguous evidence of where the user is, and a grant left attached to the PREVIOUS app's
     * sitting would be revoked by the very next transition.
     */
    fun grant(packageName: String, featureKey: String? = null, webDomain: String? = null) {
        // The sitting moves FIRST, and its transition is announced like any other, so a grant that
        // relocates the user from app A to app B resets A's counter state instead of leaking it.
        val event = sitting.onGrantEarned(packageName)
        lastPackage = packageName
        lastFeature = featureKey
        lastDomain = webDomain
        lastTime = System.currentTimeMillis()
        // Announced AFTER the grant is written, so the reaction observes the state the user is now
        // in. Deliberately NOT routed through `applyAndNotify`, whose job is revoking -- that would
        // clear the grant being handed out.
        sittingReaction?.invoke(event)
    }

    /**
     * Feed one classified foreground signal to the sitting model, revoking the grant if the sitting
     * ended. Called ONCE per accessibility event, ahead of every early return in
     * [NudgeAccessibilityService.onAccessibilityEvent].
     *
     * Applying it unconditionally is safe precisely because [SittingTracker] makes it impossible for
     * a signal other than an app window or Home to end anything -- which is the property that stops
     * a future early return from re-creating issue #5, #7 or #28. Those bugs were all "a branch
     * returned before the thing that had to happen"; there is now nothing left to skip.
     */
    fun onForegroundSignal(signal: ForegroundSignal, nowMs: Long): SittingEvent =
        applyAndNotify(sitting.onSignal(signal, nowMs))

    /**
     * The screen went off: start measuring the absence.
     *
     * No sitting survives a locked PHONE (backlog F5) — but every sitting must survive a display
     * TIMEOUT, and the two arrive as the same broadcast. [SittingTracker.onScreenOff] therefore
     * starts the away clock instead of ending outright, and the grant is revoked on the user's
     * return only if they were gone past the return window ([issue
     * #54](https://github.com/astraedus/nudge/issues/54)).
     *
     * @param nowMs the same MONOTONIC clock [onForegroundSignal] is given. A wall clock would be
     *   wrong twice over here: it can jump, and the absence being measured is exactly the interval
     *   in which the device may have slept.
     */
    fun onScreenOff(nowMs: Long): SittingEvent = applyAndNotify(sitting.onScreenOff(nowMs))

    /**
     * A view inside [packageName] was clicked or scrolled.
     *
     * The grant's other input is evidence of ABSENCE; this is the evidence of PRESENCE that was
     * missing ([issue #64](https://github.com/astraedus/nudge/issues/64)). Without it, a user
     * scrolling a feed could not cancel an away clock a sub-flow had armed, and their next in-app
     * navigation revoked a hold they had paid for while they sat in the app the whole time.
     *
     * Routed through [applyAndNotify] like every other sitting input, so an interaction that lands
     * past the return window revokes exactly as a window event would — this can only ever revoke
     * sooner, never grant.
     *
     * @param nowMs the same MONOTONIC clock [onForegroundSignal] is given.
     */
    fun onInteraction(packageName: String, nowMs: Long): SittingEvent =
        applyAndNotify(sitting.onInteraction(packageName, nowMs))

    private fun applyAndNotify(event: SittingEvent): SittingEvent {
        revokeIfSittingEnded(event)
        sittingReaction?.invoke(event)
        return event
    }

    /** Drop the sitting AND the grant (global disable). */
    fun resetSitting() {
        sitting.reset()
        clear()
    }

    /**
     * The accessibility service rebound after a gap we could not observe.
     *
     * Deliberately NOT [resetSitting]: see [SittingTracker.onObservationResumed]. A rebind is not
     * evidence the user went anywhere, and on a memory-pressured device it happens often enough that
     * treating it as one re-blocks people mid-session.
     */
    fun onObservationResumed() {
        sitting.onObservationResumed()
    }

    private fun revokeIfSittingEnded(event: SittingEvent) {
        when (event) {
            is SittingEvent.Unchanged -> Unit
            is SittingEvent.Ended -> clear()
            // A Started that replaced nothing is the first sitting of the process; there is no grant
            // to revoke. One that replaced a sitting must revoke it, same as an outright end.
            is SittingEvent.Started -> if (event.ended != null) clear()
        }
    }

    fun isGranted(packageName: String): Boolean = packageName == lastPackage

    fun shouldSkipForegroundEvaluation(packageName: String): Boolean = isGranted(packageName)

    fun shouldSkipFeatureEvaluation(packageName: String, featureKey: String): Boolean =
        isGranted(packageName) && lastFeature == featureKey

    /**
     * Drop only the web axis, leaving an app-level grant alone.
     *
     * Used when the user navigates to a different domain or leaves the browser: they have stopped
     * being on the site they earned entry to, but nothing about the app they are in has changed.
     */
    fun clearWebGrant() {
        lastDomain = null
    }

    fun clear() {
        lastPackage = null
        lastFeature = null
        lastDomain = null
        lastTime = 0L
    }

    fun resetForTests() = clear()
}
