package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.ForegroundSignal

/**
 * Whether a block decision that has just finished being computed may still be shown.
 *
 * ## The assumption this deletes
 *
 * Every launch of `BlockOverlayActivity` used to assume *the app this decision is for is still the
 * app in front*. Nothing checked it. A decision is computed on the service's IO scope, a rule
 * lookup, a usage read, sometimes a URL-bar read, while foreground changes keep arriving on the
 * main thread, so the assumption is false whenever the user leaves faster than the database
 * answers. That is [#31](https://github.com/astraedus/nudge/issues/31) verbatim: *"a block/delay
 * decision can finish after the user has already left the target app, and the overlay is shown over
 * Nudge or another foreground app."*
 *
 * It is the same shape as [#19](https://github.com/astraedus/nudge/issues/19) one layer up. There,
 * a package had a *window* while the user was elsewhere and every evaluation path read that as "P is
 * in front". Here, a package *was* in front when the evaluation started and is not when it finishes.
 * Both are the same silent premise, so both get the same treatment: one gate, ahead of every launch
 * site, rather than a check bolted onto the branch that happened to be reported.
 *
 * ## The second condition, and why it lives in the SAME gate
 *
 * [#26](https://github.com/astraedus/nudge/issues/26): *"I changed my mind - have to click twice"*
 * `BlockOverlayActivity.navigateHome` dispatches `GLOBAL_ACTION_HOME` and finishes. The activity is
 * singleInstance in its own task with an empty taskAffinity, so finishing pops back to the task
 * underneath, the blocked app. On a device where that pop wins the race, the blocked app's window
 * genuinely resumes, fires a real `TYPE_WINDOW_STATE_CHANGED`, is a real [ForegroundSignal.AppWindow]
 * for a real foreground app, and re-arms the block the user just declined. Three reporters saw it
 * every time; the bench Pixel 3 never did, because the answer is device and launcher timing.
 *
 * So the foreground check alone cannot fix #26: at the moment the phantom decision lands, the
 * blocked app really IS in front. What is missing is that the service does not know a departure is
 * in flight. Both issues are therefore one question asked at one place: **is this decision still
 * about where the user is going to be?**
 *
 * Pure, so both conditions and every combination of them are unit tests rather than device sessions
 * (`BlockLaunchGateTest`), in the same spirit as [CooldownGate].
 */
object BlockLaunchGate {

    /**
     * How long a dispatched "go home" is allowed to still be in flight.
     *
     * This is a FAIL-SAFE, not the mechanism. In the normal case the window is closed by evidence,
     * not by the clock: [walkAwayAfter] drops it the moment the launcher (or any other app) is
     * observed in front, which on any device is the transition the user is watching. The timeout
     * only covers the case where that evidence never arrives: `GLOBAL_ACTION_HOME` was accepted and
     * did nothing, or the launcher set could not be resolved so a real Home event was classified as
     * something else.
     *
     * 1.5 seconds is chosen against those two failure directions, not against a stopwatch. Too
     * short and the bug comes back on exactly the slow devices that reported it (the three reporters
     * are the evidence that a sub-second assumption is wrong). Too long and a *deliberate* re-open
     * inside the window opens the app with no delay, which is a bypass. Since the evidence path
     * closes the window on the very first foreground event after the transition, the timeout is only
     * ever reached when nothing moved at all, and a user who walks away, sees no transition happen,
     * and re-opens the app within 1.5 seconds is indistinguishable from the bug we are fixing.
     * Missing one block there, once, self-corrects on the next window event; showing the overlay
     * they just dismissed does not.
     */
    const val WALK_AWAY_TRANSITION_MS = 1_500L

    /** A "go home" dispatched by the walk-away path that the platform has not yet honoured. */
    data class WalkAway(val packageName: String, val armedAtMs: Long)

    /**
     * A block overlay we have started but have not yet seen reach the screen.
     *
     * `NudgeAccessibilityService.isOverlayActive` is set synchronously at `startActivity`, which is
     * the right moment for "stop evaluating" but the WRONG moment for "the overlay is covering the
     * app". Between those two moments the blocked app is still starting up underneath, and it goes
     * on firing `TYPE_WINDOW_STATE_CHANGED` for its own windows. See [isGenuineBypass].
     */
    data class PendingOverlay(
        val packageName: String,
        val launchedAtMs: Long,
        val windowShown: Boolean
    )

    /**
     * Fail-safe only: how long an overlay may stay "pending" before its target's window events are
     * allowed to mean a bypass again.
     *
     * The real end of pending is evidence, `BlockOverlayActivity.onResume` reporting that it is on
     * screen. This covers the case where that never happens at all, a `startActivity` the platform
     * dropped, which would otherwise leave the service permanently unable to recognise a bypass.
     *
     * Measured on the Pixel 3 from `picker-subflow-keeps-sitting.jsonl`: the blocked app's first
     * window event to the overlay's own window is 804ms. Three seconds is comfortably clear of that
     * on a colder or slower device, and being generous here is the safe direction: a missed bypass
     * self-corrects (the overlay's own `onStop` finishes it and clears the flag), while a bypass
     * recognised too eagerly is the duplicate-launch bug this constant exists to end.
     */
    const val OVERLAY_SETTLE_MS = 3_000L

    /** Why a launch was allowed or refused. Named so logcat can say which condition fired. */
    enum class Decision {
        /** The decision is still about the app in front. Show it. */
        LAUNCH,

        /** The user has already moved on: another app, Nudge itself, or the launcher is in front. */
        DROP_FOREGROUND_MOVED,

        /** A walk-away is mid-transition; this app's window is leaving, not arriving. */
        DROP_WALK_AWAY_IN_FLIGHT,

        /**
         * An overlay for this same app is already launched and still on its way to the screen.
         * Showing a second one would log a second block for a single entry.
         */
        DROP_ALREADY_PENDING
    }

    /**
     * @param target the package a launch would block **re-entry to**, the app the user is sitting
     *   in, which for a web block is the BROWSER and not the rule's app. That distinction already
     *   exists as `EXTRA_PASSTHROUGH_PACKAGE` vs `EXTRA_PACKAGE_NAME`: the rule's app is what the
     *   block is attributed to (its label, its `UsageEvent`), and comparing THAT against the
     *   foreground would drop every web block ever, because the user is never in Instagram when
     *   instagram.com is blocked in Chrome.
     * @param foreground what the service last observed in front, or null when it has observed
     *   nothing yet. Null means "we have no claim", and the gate does not weaken enforcement on a
     *   claim it does not have.
     * @param walkAway a go-home dispatched by the walk-away path, if one is outstanding.
     */
    fun decide(
        target: String,
        foreground: String?,
        walkAway: WalkAway?,
        nowMs: Long,
        transitionMs: Long = WALK_AWAY_TRANSITION_MS,
        pendingOverlay: PendingOverlay? = null
    ): Decision = when {
        // Asked before anything else because it is about THIS launch being redundant rather than
        // about where the user is. Two overlays for one entry into an app write two `UsageEvent`
        // rows and inflate the count the home screen shows.
        pendingOverlay != null &&
            !pendingOverlay.windowShown &&
            pendingOverlay.packageName == target &&
            nowMs - pendingOverlay.launchedAtMs < OVERLAY_SETTLE_MS -> Decision.DROP_ALREADY_PENDING

        // Asked FIRST because it is the more specific answer, and because in the #26 case the
        // foreground check cannot help: the blocked app really is in front at that instant.
        walkAway != null &&
            walkAway.packageName == target &&
            nowMs - walkAway.armedAtMs < transitionMs -> Decision.DROP_WALK_AWAY_IN_FLIGHT

        foreground != null && foreground != target -> Decision.DROP_FOREGROUND_MOVED

        else -> Decision.LAUNCH
    }

    /**
     * What the foreground package becomes after [signal], given [previous].
     *
     * Only three signals move it, and the four that do not are the whole reason this is a function
     * rather than an assignment at the event site. A system surface, a keyboard, a framework popup,
     * a picture-in-picture bubble and a non-window event all carry a package that is NOT the app the
     * user is in, reading any of them as a foreground change would drop legitimate blocks whenever
     * the notification shade, a permission dialog or the volume panel happened to land inside the
     * few milliseconds a rule lookup takes. That is `SYSTEM_PACKAGES` answering a question it cannot
     * answer, the grouped-constant trap `docs/architecture/foreground-detection.md` records three
     * separate sprints of.
     *
     * [ForegroundSignal.OwnUi] deliberately DOES move it, and it is the case issue #31's reporter
     * cares about most: an overlay landing on top of Nudge's own screens is the most visible form of
     * the bug. Our own block overlay is also `OwnUi`, which is correct rather than unfortunate: a
     * second decision arriving while an overlay is already up has nothing to add, and if the user
     * bypasses that overlay back into the app, the bypass itself is an `AppWindow` that moves the
     * foreground back before the re-evaluation runs.
     */
    fun foregroundAfter(signal: ForegroundSignal, previous: String?): String? = when (signal) {
        is ForegroundSignal.AppWindow -> signal.packageName
        is ForegroundSignal.Home -> signal.packageName
        is ForegroundSignal.OwnUi -> signal.packageName

        is ForegroundSignal.SystemSurface,
        is ForegroundSignal.Transient,
        is ForegroundSignal.PipOnly,
        is ForegroundSignal.NotForeground -> previous
    }

    /**
     * The outstanding walk-away after [signal]: null once the departure has visibly happened.
     *
     * The window closes on EVIDENCE. Anything in front that is not the app being left is proof the
     * transition completed, which is what keeps this from being a blanket "ignore this app for 1.5
     * seconds", a genuine re-entry after the launcher has appeared blocks normally, immediately,
     * because by then there is no window left to be inside.
     *
     * A signal that carries no claim about what is in front (a system surface, a keyboard, a scroll)
     * leaves the window exactly as it was, for the same reason it leaves the foreground alone.
     */
    /**
     * Is this event really the user getting BACK INTO the blocked app past a live overlay, or is it
     * the blocked app still settling underneath an overlay that has not arrived yet?
     *
     * ## The duplicate block, measured
     *
     * `NudgeAccessibilityService` treats "a real app window came forward while an overlay is up" as
     * proof the overlay was bypassed: the user tapped the app's icon or picked it out of Recents,
     * orphaning the overlay in its own task. It clears the flag and re-evaluates, which is right for
     * that case and wrong for the far more common one.
     *
     * `picker-subflow-keeps-sitting.jsonl`, a Pixel 3 capture committed since v1.16.0, times a
     * single clean launch of Google Keep under a DELAY rule:
     *
     * ```
     *  1252ms  keep   android.widget.FrameLayout                     <- evaluate, launch the overlay
     *  1445ms  nudge  android.widget.FrameLayout                     <- the overlay TASK's first window
     *  1736ms  keep   ...keep.ui.activities.BrowseActivity           <- keep STILL starting up
     *  2056ms  nudge  ...overlay.BlockOverlayActivity                <- the overlay actually on screen
     * ```
     *
     * The event at 1736ms is Keep's own second window, 320ms before the overlay reaches the screen.
     * It is a `WINDOW_STATE_CHANGED` for a real `AppWindow`, so the old rule called it a bypass,
     * cleared the flag and re-evaluated. The 1000ms debounce did not absorb it either, because the
     * overlay's task window at 1445ms had already moved `lastPackage` to Nudge's own package. Result:
     * a second decision, a second `UsageEvent`, a second `startActivity`, for one entry into one app.
     * Device QA measured `wasBlocked` rising by 25 across 10 launches.
     *
     * The fix is not a longer debounce. It is that an overlay we have STARTED is not yet an overlay
     * the user can be past. Until it reports itself on screen, its target's own window events are the
     * app being covered, and nothing else. A different app coming forward in that gap is still a real
     * foreground change and still counts.
     *
     * @param pending the overlay we have launched and not yet seen, or null when none is in flight.
     */
    fun isGenuineBypass(
        eventType: A11yEventType,
        signal: ForegroundSignal,
        pending: PendingOverlay?,
        nowMs: Long,
        settleMs: Long = OVERLAY_SETTLE_MS
    ): Boolean {
        // Unchanged from the rule this replaces: only a new activity in front can be a bypass, and
        // only a real application window. Our own overlay, the launcher, a keyboard and a
        // picture-in-picture bubble are excluded by construction rather than by package set.
        if (eventType != A11yEventType.WINDOW_STATE_CHANGED) return false
        if (signal !is ForegroundSignal.AppWindow) return false

        if (pending == null) return true
        if (pending.windowShown) return true
        // Some OTHER app really did come forward. That is a foreground change whatever our overlay
        // is doing, and suppressing it would swallow a genuine switch.
        if (signal.packageName != pending.packageName) return true
        return nowMs - pending.launchedAtMs >= settleMs
    }

    /** The pending overlay after [signal], which only the overlay reporting itself can resolve. */
    fun pendingOverlayAfter(pending: PendingOverlay?, overlayShown: Boolean): PendingOverlay? =
        if (pending != null && overlayShown) pending.copy(windowShown = true) else pending

    fun walkAwayAfter(signal: ForegroundSignal, pending: WalkAway?): WalkAway? {
        if (pending == null) return null
        return when (signal) {
            // The go-home landed. This is the ordinary end of the window.
            is ForegroundSignal.Home -> null

            // The app being left is back in front: this is the pop that #26 is about, so the window
            // must survive it. Any OTHER app in front means the departure happened.
            is ForegroundSignal.AppWindow ->
                if (signal.packageName == pending.packageName) pending else null

            // Nudge's own UI in front is not the blocked app, so the departure did happen, but the
            // walk-away path itself is Nudge UI on its way out, so this must NOT close the window,
            // or the overlay's own dying window event would close it before the transition starts.
            is ForegroundSignal.OwnUi,
            is ForegroundSignal.SystemSurface,
            is ForegroundSignal.Transient,
            is ForegroundSignal.PipOnly,
            is ForegroundSignal.NotForeground -> pending
        }
    }
}
