package com.astraedus.nudge.service

import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.events.ForegroundSignal

/**
 * The service-side adapter for the "Bro. wtf." check-in: holds the cached on/off switch, the one
 * [BounceDetector], and hands a fired alert to [notify].
 *
 * Exists so that what the accessibility service owes this feature is two one-line calls on paths
 * it already runs ([onForegroundSignal] from the one classification, [onWall] from the one overlay
 * launch and the one auto-kick), and so the toggle-off path is testable without Android: nothing in
 * here imports an Android type.
 *
 * ## Cost when off
 * [enabled] is a cached `@Volatile` boolean the service keeps in step with the preference (the same
 * pattern as the master toggle: a DataStore read per accessibility event is exactly what the hot
 * path must never do). Off means the detector is never fed, and turning it off drops whatever state
 * it held.
 *
 * ## Threading
 * Foreground signals arrive on the main thread; walls arrive from the overlay launch, which runs on
 * the service's IO coroutine scope after a rule lookup. The detector is not thread-safe, so every
 * entry point is `@Synchronized`. The lock is uncontended in practice and held for a constant-time
 * update, never across [notify].
 *
 * @param notify posts the check-in. Called outside the lock, at most once per cooldown.
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime` in production), so a wall-clock
 *   change can neither stretch a streak nor end a cooldown early.
 */
class BounceCheckIn(
    private val detector: BounceDetector,
    private val notify: (BounceAlert) -> Unit,
    private val clock: () -> Long
) {

    @Volatile
    var enabled: Boolean = false
        private set

    /** Keep in step with the preference. Switching off forgets any streak and any cooldown. */
    fun setEnabled(value: Boolean) {
        synchronized(this) {
            enabled = value
            if (!value) detector.reset()
        }
    }

    /** Forget everything without changing the switch (Nudge's master toggle turned off). */
    @Synchronized
    fun reset() = detector.reset()

    /** True while a streak is being tracked. For tests and logs. */
    val isTracking: Boolean @Synchronized get() = detector.isArmed

    /** Every classified foreground signal, from the service's single classification point. */
    fun onForegroundSignal(signal: ForegroundSignal) {
        if (!enabled) return
        val alert = synchronized(this) {
            // Re-checked under the lock: a switch-off between the fast check above and here must
            // not feed a detector it has just reset.
            if (!enabled || !detector.isArmed) return
            when (signal) {
                is ForegroundSignal.AppWindow -> detector.onAppOpened(signal.packageName, clock())
                is ForegroundSignal.Home -> {
                    detector.onWentHome(clock())
                    null
                }
                // Every other signal says nothing about the user opening an app: a keyboard, the
                // shade, a dialog host, a PiP bubble, Nudge's own windows, a content change.
                is ForegroundSignal.SystemSurface,
                is ForegroundSignal.OwnUi,
                is ForegroundSignal.AwarenessOverlay,
                is ForegroundSignal.Transient,
                is ForegroundSignal.PipOnly,
                is ForegroundSignal.NotForeground -> null
            }
        }
        alert?.let(notify)
    }

    /** Nudge just stopped [packageName]: an overlay went up for it, or it was auto-kicked. */
    fun onWall(packageName: String) {
        if (!enabled) return
        val alert = synchronized(this) {
            if (!enabled) return
            detector.onWall(packageName, clock())
        }
        alert?.let(notify)
    }
}
