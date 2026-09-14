package com.astraedus.nudge.service

import android.os.SystemClock
import com.astraedus.nudge.domain.block.BlockLaunchGate
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
     * Apply the ONE classification the service made for this event.
     *
     * Called from the same place, and only from the same place, that feeds the sitting model — so
     * "what is in front" cannot fall out of step with "is the user still in this sitting", and no
     * future early return can skip one but not the other. Pinned by
     * `EventDispatchOrderContractTest`.
     */
    fun onForegroundSignal(signal: ForegroundSignal) {
        foregroundPackage = BlockLaunchGate.foregroundAfter(signal, foregroundPackage)
        walkAway = BlockLaunchGate.walkAwayAfter(signal, walkAway)
    }

    /**
     * The user tapped "I changed my mind" (or pressed back) on the block overlay for [packageName],
     * and a "go home" has been dispatched.
     *
     * [packageName] is the package the user is sitting IN — the browser for a web block — because
     * that is whose window is about to resurface underneath the finishing overlay, and whose
     * re-entry must not be read as a fresh arrival.
     */
    fun onWalkAwayStarted(packageName: String) {
        if (packageName.isBlank()) return
        walkAway = BlockLaunchGate.WalkAway(packageName, nowMs())
    }

    /** Whether a block overlay for [targetPackage] may still be shown. */
    fun decide(targetPackage: String): BlockLaunchGate.Decision =
        BlockLaunchGate.decide(
            target = targetPackage,
            foreground = foregroundPackage,
            walkAway = walkAway,
            nowMs = nowMs()
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
    fun reset() {
        foregroundPackage = null
        walkAway = null
    }
}
