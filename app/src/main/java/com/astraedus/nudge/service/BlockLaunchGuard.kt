package com.astraedus.nudge.service

import android.os.SystemClock
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.ForegroundSignal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the state [BlockLaunchGate] decides on: what is in front, and whether a walk-away's
 * "go home" is still in flight.
 *
 * A `@Singleton` rather than fields on the accessibility service because it has two writers in two
 * classes. The foreground comes from the service's one classification; the walk-away is armed by
 * `BlockOverlayActivity` at the instant the user taps "I changed my mind", which is a different
 * object with a different lifetime. That is exactly the split
 * [PassthroughManager] already has (a grant is earned in the activity, the sitting is moved by the
 * service), and it is held the same way for the same reason: the two must not be able to drift.
 *
 * Every field is `@Volatile` and every mutation is a single reference write. The writers are on the
 * main thread (accessibility dispatch, and the activity), the reader is on the service's IO scope
 * where a decision finishes, so a read is a snapshot and can be nothing else.
 */
@Singleton
class BlockLaunchGuard @Inject constructor() {

    /**
     * The last package observed genuinely in front, or null before the first such observation.
     *
     * Deliberately NOT `NudgeAccessibilityService.lastPackage`, which looks like the same thing and
     * is not: that field is the debounce cursor for `evaluateForegroundPackage`, it is advanced by
     * `clearOverlays` for reasons that have nothing to do with what is on screen, and it is left
     * deliberately untouched by paths that early-return. Reusing it here would be one field
     * answering two questions, which is the trap this subsystem has sprung on three times already
     * (`docs/architecture/foreground-detection.md`).
     */
    @Volatile
    var foregroundPackage: String? = null
        private set

    @Volatile
    private var walkAway: BlockLaunchGate.WalkAway? = null

    /**
     * The overlay we have started and not yet seen on screen.
     *
     * Resolved by evidence from the overlay itself ([onOverlayShown], called from its `onResume`)
     * rather than by watching for a Nudge window in the accessibility stream. The stream cannot tell
     * us: the overlay TASK's first window arrives ~600ms before the overlay does and carries a
     * framework class name, and `className.startsWith(ownPackageName)` cannot separate them either,
     * because this app's applicationId (`dev.astraedus.nudge`) is not the prefix of its class names
     * (`com.astraedus.nudge.*`). The activity knows when it is on screen; asking it is both simpler
     * and correct.
     */
    @Volatile
    private var pendingOverlay: BlockLaunchGate.PendingOverlay? = null

    /**
     * The confrontations already COUNTED for the arrival the user is currently in (issue #36).
     *
     * Every other field here governs whether an overlay is SHOWN. This one governs only whether a
     * `UsageEvent` is WRITTEN, which is a different question that had no owner until 2500
     * interventions landed in one day: a row was written whenever a launch happened, so every
     * mechanism that could re-launch an overlay on its own could also re-count it. See
     * [BlockLaunchGate.Arrival].
     */
    @Volatile
    private var arrival: BlockLaunchGate.Arrival? = null

    /** Launch attempts for one target with no departure in between. [BlockLaunchGate.LaunchStorm]. */
    @Volatile
    private var storm: BlockLaunchGate.LaunchStorm? = null

    /** Why the last arrival ended. Diagnostic only; it appears in the storm line. */
    @Volatile
    private var lastDepartureReason: String = "none"

    /** Hands out [BlockLaunchGate.PendingOverlay.id]s. Monotonic, so an id is never reused. */
    private val overlayIds = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Apply the ONE classification the service made for this event.
     *
     * Called from the same place, and only from the same place, that feeds the sitting model, so
     * "what is in front" cannot fall out of step with "is the user still in this sitting", and no
     * future early return can skip one but not the other. Pinned by
     * `EventDispatchOrderContractTest`.
     */
    fun onForegroundSignal(signal: ForegroundSignal) {
        foregroundPackage = BlockLaunchGate.foregroundAfter(signal, foregroundPackage)
        walkAway = BlockLaunchGate.walkAwayAfter(signal, walkAway)
        arrival = BlockLaunchGate.arrivalAfterSignal(signal, arrival)
        storm = BlockLaunchGate.stormAfterSignal(signal, storm)
    }

    /**
     * The foreground left the blocked app by a route the accessibility stream cannot describe.
     *
     * There are exactly two, and both are real departures the user made: Nudge's own MAIN app
     * window coming forward (the stream classifies that as [ForegroundSignal.OwnUi], which is also
     * what our block overlay is, and only `isOwnAppWindowEvent` can tell them apart), and the
     * screen going off (`ACTION_SCREEN_OFF`, which is a broadcast and not an accessibility event at
     * all -- the same evidence `SittingTracker.SCREEN_OFF` runs on).
     *
     * Both end the ARRIVAL, which means the next block for that app is a fresh confrontation and
     * owes a row. Enforcement is unaffected either way.
     */
    @Synchronized
    fun onDeparture(reason: String) {
        if (arrival == null && storm == null) return
        arrival = null
        storm = null
        lastDepartureReason = reason
    }

    /**
     * The user tapped "I changed my mind" (or pressed back) on the block overlay for [packageName],
     * and a "go home" has been dispatched.
     *
     * [packageName] is the package the user is sitting IN, the browser for a web block, because
     * that is whose window is about to resurface underneath the finishing overlay, and whose
     * re-entry must not be read as a fresh arrival.
     */
    fun onWalkAwayStarted(packageName: String) {
        if (packageName.isBlank()) return
        walkAway = BlockLaunchGate.WalkAway(packageName, nowMs())
    }

    /** A block overlay for [packageName] has been started, and has not reached the screen yet. */
    @Synchronized
    fun onOverlayLaunched(packageName: String) {
        val current = pendingOverlay
        pendingOverlay = BlockLaunchGate.pendingOverlayAfterLaunch(
            pending = current,
            target = packageName,
            nowMs = nowMs(),
            id = if (current != null && current.packageName == packageName && current.windowShown) {
                current.id
            } else {
                overlayIds.incrementAndGet()
            }
        )
    }

    /**
     * The id of the overlay currently in flight, read by `BlockOverlayActivity` as it renders so it
     * can later say which overlay it was. [BlockLaunchGate.NO_OVERLAY_ID] when there is none.
     */
    fun currentOverlayId(): Long = pendingOverlay?.id ?: BlockLaunchGate.NO_OVERLAY_ID

    /**
     * The block overlay is on screen (reported from `BlockOverlayActivity.onResume`).
     *
     * Only from here on can a window event for the blocked app mean the user got PAST the overlay;
     * before it, the app is simply still starting up underneath one that has not arrived.
     */
    fun onOverlayShown() {
        pendingOverlay = BlockLaunchGate.pendingOverlayAfter(pendingOverlay, overlayShown = true)
    }

    /**
     * The overlay instance holding [overlayId] is gone; there is nothing pending to protect.
     *
     * Identified rather than unconditional: see [BlockLaunchGate.pendingOverlayAfterDismissal].
     */
    @Synchronized
    fun onOverlayDismissed(overlayId: Long) {
        pendingOverlay = BlockLaunchGate.pendingOverlayAfterDismissal(pendingOverlay, overlayId)
    }

    /**
     * Claim the right to write a `UsageEvent` for this confrontation, or report that this arrival
     * has already been counted for it (issue #36).
     *
     * **This never refuses a block, only a row.** The caller has already shown the overlay by the
     * time it gets here, and it must go on showing it: someone still sitting in a blocked app
     * should keep meeting the block. What must not keep happening is the COUNT rising for a
     * confrontation the user never walked into.
     *
     * @param targetPackage the app the user is sitting in (the browser, for a web block) -- the
     *   same package [decide] compares against the foreground, and the one whose departure opens
     *   the next arrival.
     * @param key [BlockLaunchGate.confrontationKey] for what the user actually ran into.
     * @return true when a row is owed.
     */
    @Synchronized
    fun claimConfrontation(targetPackage: String, key: String): Boolean {
        if (!BlockLaunchGate.isNewConfrontation(arrival, targetPackage, key)) return false
        arrival = BlockLaunchGate.arrivalAfterConfrontation(arrival, targetPackage, key)
        return true
    }

    /**
     * Record one launch ATTEMPT for [targetPackage] and return the storm line to log, if this is
     * the attempt that crosses [BlockLaunchGate.STORM_LAUNCH_THRESHOLD].
     *
     * Attempts, not launches: a run the gate keeps dropping writes no rows and so is invisible in
     * the stats, which after this change is exactly where a new loop would hide.
     */
    @Synchronized
    fun onLaunchAttempt(
        targetPackage: String,
        decision: BlockLaunchGate.Decision
    ): BlockLaunchGate.StormReport? {
        val now = nowMs()
        val next = BlockLaunchGate.stormAfterLaunch(storm, targetPackage, decision, now)
        val report = BlockLaunchGate.stormReport(next, now)
        storm = if (report == null) next else next.copy(reported = true)
        return report
    }

    /** One line of everything the storm log needs that is not in the report itself. */
    fun stateDescription(): String {
        val pending = pendingOverlay
        return "foreground=$foregroundPackage " +
            "pendingOverlay=" + (
                pending?.let {
                    "${it.packageName}(shown=${it.windowShown},age=${nowMs() - it.launchedAtMs}ms)"
                } ?: "none"
                ) +
            " countedThisArrival=${arrival?.countedKeys?.size ?: 0}" +
            " lastDeparture=$lastDepartureReason"
    }

    /**
     * Is this event the user getting back past a live overlay, or the blocked app still settling
     * under one that has not appeared? See [BlockLaunchGate.isGenuineBypass].
     */
    fun isGenuineBypass(eventType: A11yEventType, signal: ForegroundSignal): Boolean =
        BlockLaunchGate.isGenuineBypass(
            eventType = eventType,
            signal = signal,
            pending = pendingOverlay,
            nowMs = nowMs()
        )

    /** Whether a block overlay for [targetPackage] may still be shown. */
    fun decide(targetPackage: String): BlockLaunchGate.Decision =
        BlockLaunchGate.decide(
            target = targetPackage,
            foreground = foregroundPackage,
            walkAway = walkAway,
            nowMs = nowMs(),
            pendingOverlay = pendingOverlay
        )

    /**
     * The clock the walk-away window is measured on, and a test seam.
     *
     * Monotonic for the same reason `SittingTracker`'s return window is: the window decides whether
     * a block is suppressed, and an epoch clock can be moved backwards from the Settings app, which
     * would turn 1.5 seconds into an indefinite bypass.
     *
     * It lives HERE rather than as a `nowMs` parameter on the two mutators, because the two callers
     * are in different classes and a caller that reached for `System.currentTimeMillis()` would
     * reopen that bypass silently. One clock, one place, one decision about which clock.
     */
    internal var nowMs: () -> Long = { SystemClock.elapsedRealtime() }

    /** Test seam / global-disable: forget everything. */
    @Synchronized
    fun reset() {
        foregroundPackage = null
        walkAway = null
        pendingOverlay = null
        // An observation gap (a service rebind) or a global disable means we cannot claim the user
        // is still in the arrival we were counting, and "no claim" must never SUPPRESS a row for a
        // confrontation that really is fresh. Same fail-toward-honesty direction the foreground
        // claim is dropped in.
        arrival = null
        storm = null
        lastDepartureReason = "reset"
    }
}
