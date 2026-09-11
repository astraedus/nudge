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
     * The one definition of "the user is in a sitting with app X", shared with the interaction
     * counter and the time-kick baseline through [InteractionTracker.SESSION_EXPIRY_MS] -- the same
     * constant, so "still the same sitting" cannot mean two different things in one app.
     */
    private val sitting = SittingTracker(returnWindowMs = InteractionTracker.SESSION_EXPIRY_MS)

    /** The app whose sitting is currently live, for logging and diagnostics. */
    val sittingPackage: String? get() = sitting.currentApp
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
        sitting.onGrantEarned(packageName)
        lastPackage = packageName
        lastFeature = featureKey
        lastDomain = webDomain
        lastTime = System.currentTimeMillis()
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
        sitting.onSignal(signal, nowMs).also(::revokeIfSittingEnded)

    /** The screen went off: no sitting survives a locked phone (backlog F5). */
    fun onScreenOff(): SittingEvent = sitting.onScreenOff().also(::revokeIfSittingEnded)

    /** Drop the sitting AND the grant (service disconnect, global disable). */
    fun resetSitting() {
        sitting.reset()
        clear()
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

    /**
     * Drop the grant if it belongs to an app other than [packageName].
     *
     * **No longer the service's app-switch rule.** It used to be called for every foreign window
     * event, which is exactly the defect issue #28 reports; [onForegroundSignal] owns that now. It
     * survives as the primitive the Home path uses, where the semantics really are "the user left".
     */
    fun clearIfAppChanged(packageName: String): Boolean {
        if (lastPackage == null || packageName == lastPackage) return false
        clear()
        return true
    }

    fun clear() {
        lastPackage = null
        lastFeature = null
        lastDomain = null
        lastTime = 0L
    }

    fun resetForTests() = clear()
}
