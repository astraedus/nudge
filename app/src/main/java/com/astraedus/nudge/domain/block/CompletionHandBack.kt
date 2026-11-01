package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.events.ForegroundSignal

/**
 * A completed Delay must end in the app it was for, not on the home screen (issue #119, defence in
 * depth).
 *
 * When the countdown finishes, `BlockOverlayActivity` grants the pass and finishes, and the system
 * reveals whatever task was under the overlay. That is the blocked app, as long as its task is still
 * alive. In #119 it was not: Nudge's own BACK press (the floating-window misread, fixed in
 * [com.astraedus.nudge.domain.windows.FloatingWindowSettle]) had destroyed Clock's only activity, so
 * the overlay finished onto the launcher, and the launcher's window ended the sitting as `WENT_HOME`
 * and revoked the pass the user had just waited for. To get into Clock they had to sit through the
 * whole Delay again.
 *
 * This notices that outcome, by the same signals the sitting already reads, and repairs it: the
 * FIRST app-or-home signal after a completion grant tells us what the reveal landed on.
 *  - The granted app's own window: the reveal worked. Disarmed.
 *  - Any other app's window: the user is somewhere else on purpose. Disarmed.
 *  - The home screen, within [windowMs] of the grant: the app's task was gone. Hand back to it.
 *  - The home screen later than that: a real trip home. Disarmed, the sitting decides as before.
 *
 * Everything else (our own windows, the shade, a keyboard, a toast, PiP) says nothing about where
 * the reveal landed, so it leaves the arm alone and the window bounds it.
 *
 * Measured in the #119 capture: the overlay's `finish()` at 56.863, the launcher's window
 * classified as Home at 57.002, 139ms later. [WINDOW_MS] is an order of magnitude above that and
 * still well inside the time it takes a person to see the countdown end and decide to press Home.
 *
 * What it deliberately is NOT:
 *  - **Unconditional.** Starting the app's launch intent on every completion would push a fresh
 *    launcher activity on top of any task that was not started from the launcher (a deep link, a
 *    share target), which is the "app restarts from its splash screen" behaviour. Only a reveal that
 *    has ALREADY landed on Home hands back, and in that state launching the app is exactly what the
 *    user's next tap on its icon would do.
 *  - **For the walk-away (#26).** "I changed my mind" grants nothing, so nothing arms; its go-home
 *    stays a go-home.
 *  - **For a web block.** The pass there is for a domain inside a browser, and the browser's launch
 *    intent opens the browser, not the page; the caller does not arm it.
 *
 * Pure, and synchronized: armed from the overlay activity, read on the accessibility thread, and
 * disarmed from the block launch path, which runs on a background coroutine.
 */
class CompletionHandBack(private val windowMs: Long = WINDOW_MS) {

    private data class Armed(val packageName: String, val atMs: Long)

    private var armed: Armed? = null

    /** A Delay for [packageName] was just completed at [nowMs] (monotonic). */
    @Synchronized
    fun arm(packageName: String, nowMs: Long) {
        armed = Armed(packageName, nowMs)
    }

    @Synchronized
    fun disarm() {
        armed = null
    }

    /** The package a completion is waiting to land in, for logging and tests. */
    @get:Synchronized
    val armedPackage: String?
        get() = armed?.packageName

    /**
     * Feed one classified foreground signal.
     *
     * @return the package to bring back when [signal] is the reveal landing on Home; null otherwise.
     *   Single-shot: the arm is spent by the first app or home signal, whichever it was.
     */
    @Synchronized
    fun onSignal(signal: ForegroundSignal, nowMs: Long): String? {
        val current = armed ?: return null
        return when (signal) {
            is ForegroundSignal.Home -> {
                armed = null
                current.packageName.takeIf { nowMs - current.atMs in 0..windowMs }
            }
            is ForegroundSignal.AppWindow -> {
                armed = null
                null
            }
            is ForegroundSignal.SystemSurface,
            is ForegroundSignal.OwnUi,
            is ForegroundSignal.AwarenessOverlay,
            is ForegroundSignal.Transient,
            is ForegroundSignal.PipOnly,
            is ForegroundSignal.NotForeground -> {
                if (nowMs - current.atMs > windowMs) armed = null
                null
            }
        }
    }

    companion object {
        /** How long after a completion grant a Home signal still counts as the reveal itself. */
        const val WINDOW_MS = 1_500L
    }
}
