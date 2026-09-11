package com.astraedus.nudge.domain.sitting

import com.astraedus.nudge.domain.events.ForegroundSignal

/**
 * Why a sitting ended. Each consumer documents its own reaction to each cause — that table is the
 * point of naming them (see the class doc of [SittingTracker]).
 */
enum class SittingEndCause {
    /** The user pressed Home. The one unambiguous "I am leaving" gesture the platform gives us. */
    WENT_HOME,

    /** The screen went off. A sitting cannot span a locked phone (backlog F5). */
    SCREEN_OFF,

    /**
     * A DIFFERENT app held the foreground continuously for longer than the return window. This is
     * what a genuine app switch looks like, as opposed to a sub-flow the app itself launched.
     */
    ANOTHER_APP_HELD_FOREGROUND
}

/** What one signal did to the user's sitting. */
sealed interface SittingEvent {
    /** Nothing changed: the user is still in the same sitting (or the signal was irrelevant). */
    data object Unchanged : SittingEvent

    /** The sitting for [packageName] ended, and nothing has started yet. */
    data class Ended(val packageName: String, val cause: SittingEndCause) : SittingEvent

    /** A sitting for [packageName] began. [ended] is the sitting it replaced, if any. */
    data class Started(val packageName: String, val ended: Ended?) : SittingEvent
}

/**
 * The ONE definition of "the user is in a sitting with app X".
 *
 * ## The bug this replaces
 *
 * Issue [#28](https://github.com/astraedus/nudge/issues/28): *"on Tinder if I try to open 'Add
 * photos' I get the delay screen again, even if I already waited to open the app"*, and *"when
 * scrolling social media apps I am occasionally kicked to the delay screen as if I had just opened
 * the app"*.
 *
 * The old rule was `PassthroughManager.clearIfAppChanged(pkg)`, called for any window event whose
 * package was not the granted app and was not in one of three hardcoded sets. So **every sub-flow an
 * app launches revoked the grant**, and returning re-blocked. Device-captured on a Pixel 3, with a
 * completed DELAY on Google Keep and one `ACTION_GET_CONTENT`:
 *
 * ```
 * 14:43:43  skip evaluation package=com.google.android.keep reason=passthrough      ← grant honoured
 * 14:43:51  passthrough cleared on app switch package=com.google.android.providers.media.module
 * 14:43:58  handling block package=com.google.android.keep mode=DELAY               ← re-blocked
 * ```
 *
 * The defect is the MODEL, not the list. "A foreign package fired a window event" was being read as
 * "the user left". Photo pickers, share sheets (`com.android.intentresolver` on 13+), the Pixel's
 * permission dialog (`com.google.android.permissioncontroller`, which is not the
 * `com.android.permissioncontroller` that was in `SYSTEM_PACKAGES`), custom tabs, OEM volume panels
 * and notification hops are all normal app packages, and no list will ever contain all of them.
 *
 * ## The model
 *
 * A sitting ends for exactly three reasons, none of which is "an unknown package appeared":
 *
 * | Cause | Passthrough grant | Interaction count / time baseline |
 * |---|---|---|
 * | [SittingEndCause.WENT_HOME] | revoked | **untouched** — a trip home must not refill a time budget |
 * | [SittingEndCause.SCREEN_OFF] | revoked | **untouched**, same reason |
 * | [SittingEndCause.ANOTHER_APP_HELD_FOREGROUND] | revoked | reset — this is the same ≥[returnWindowMs] "away long enough" rule [com.astraedus.nudge.service.InteractionTracker] already uses, so the two can no longer disagree about whether this is still the same sitting |
 *
 * A picker, share sheet, permission dialog, custom tab, notification hop or volume panel is
 * therefore a **sub-flow of the sitting by construction**: it is another app in front for a few
 * seconds, which is not long enough to end anything. No package needs to be recognised for that to
 * work, which is what makes this fix different in kind from the four that preceded it.
 *
 * Adding [SittingEndCause.SCREEN_OFF] is deliberate and is what keeps the model from being a net
 * loosening: the return window makes a brief excursion cheaper than it used to be, while a locked
 * phone — the real bypass, backlog F5, *"complete Instagram's delay → lock the phone → unlock hours
 * later straight back into Instagram → no delay"* — now costs a fresh delay where it used to cost
 * nothing.
 *
 * ## What this does NOT do
 *
 * It never decides whether an app should be blocked. A foreign app window is still evaluated
 * immediately and blocked on its own merits — opening a blocked app from a picker blocks at once,
 * not five minutes later. The sitting governs only whose *grant* is alive.
 *
 * Pure Kotlin, no Android imports: every branch below is JVM-tested, and the whole thing replays
 * against captured device event streams (`app/src/test/resources/a11y-captures/`).
 *
 * **Single-threaded by design, and every writer must stay on the MAIN thread.** There are exactly
 * three, and today all three are main: `onAccessibilityEvent` (an accessibility service is
 * dispatched on the main thread), the `ACTION_SCREEN_OFF` receiver (registered with no Handler, so
 * it runs on main), and `BlockOverlayActivity.onTimerComplete` via
 * [com.astraedus.nudge.service.PassthroughManager.grant] — the overlay is in the same process, with
 * no `android:process` in the manifest. Anything calling in from the service's IO scope must hop to
 * Main first, exactly as `onWebDomainForeground` already does for
 * [com.astraedus.nudge.service.InteractionTracker], whose plain maps have the same constraint.
 */
class SittingTracker(
    /**
     * How long another app must hold the foreground before the sitting it interrupted is over.
     *
     * **Deliberately NOT `InteractionTracker.SESSION_EXPIRY_MS`,** though the temptation to share
     * one constant is strong and was the first thing tried. The two answer different questions:
     *
     *  - `SESSION_EXPIRY_MS` (5 minutes) answers *"should the time budget refill?"*. Generous is
     *    right there, because the failure mode is a user farming a fresh budget by tabbing out.
     *  - This answers *"did the user leave?"*, and the grant it governs is permission to skip a
     *    delay the user asked to be given. Generous is WRONG here: at five minutes, a four-minute
     *    excursion into another app keeps the pass alive, and ping-ponging A → B → A under the
     *    window keeps it alive indefinitely, because every return clears the away clock. That is an
     *    ordinary app switch, and re-blocking it is the behaviour the user configured.
     *
     * The value is a judgement about how long a SUB-FLOW lasts, and a sub-flow is bounded by the
     * user's task inside it, not by the few seconds a scripted capture happens to take. Browsing a
     * gallery for the right photo, or working through a crop screen, routinely runs past thirty
     * seconds — so a short window would reintroduce the reported bug for exactly the user who takes
     * their time. Conversely a notification hop into another app that lasts more than a couple of
     * minutes is a real switch and should re-arm the delay. [PASSTHROUGH_RETURN_WINDOW_MS] sits
     * between those.
     *
     * A timer is the approximation, not the goal. `UsageEvents.Event.getTaskRootPackageName()`
     * (API 29+) can distinguish a sub-flow from a real switch OUTRIGHT — a picker launched from the
     * granted app has that app as its task root — which would demote this window to a fallback for
     * API 26-28 rather than the mechanism. Recorded in `docs/BACKLOG.md`.
     */
    private val returnWindowMs: Long = PASSTHROUGH_RETURN_WINDOW_MS
) {

    /** The app whose sitting is currently alive, or null when there is none. */
    var currentApp: String? = null
        private set

    /**
     * When the sitting's app stopped being the foreground app, or null while it is in front.
     *
     * Only another **app** window starts this clock. A system surface, a keyboard, a framework popup
     * and our own overlay do not, because none of them means the user stopped looking at the app —
     * which is the entire lesson of issue #5 and of the foreground-time clock regression before it.
     */
    var awaySinceMs: Long? = null
        private set

    fun onSignal(signal: ForegroundSignal, nowMs: Long): SittingEvent = when (signal) {
        is ForegroundSignal.Home -> end(SittingEndCause.WENT_HOME)

        is ForegroundSignal.AppWindow -> onAppWindow(signal.packageName, nowMs)

        // Everything else is, by construction, incapable of ending a sitting. Listed exhaustively
        // rather than behind an `else` so that adding a signal forces a decision here instead of
        // silently inheriting "does nothing".
        is ForegroundSignal.SystemSurface,
        is ForegroundSignal.OwnUi,
        is ForegroundSignal.Transient,
        is ForegroundSignal.PipOnly,
        is ForegroundSignal.NotForeground -> SittingEvent.Unchanged
    }

    /** The screen went off: no sitting can survive it (backlog F5). */
    fun onScreenOff(): SittingEvent = end(SittingEndCause.SCREEN_OFF)

    /**
     * The user completed a block for [packageName], so they are unambiguously sitting with it now.
     *
     * Called when a grant is earned. Without this, completing a delay for app B while the tracker
     * still believed the sitting belonged to app A would leave B's brand-new grant attached to A's
     * sitting, and the next transition would revoke it.
     */
    fun onGrantEarned(packageName: String): SittingEvent {
        if (currentApp == packageName) {
            awaySinceMs = null
            return SittingEvent.Unchanged
        }
        val ended = currentApp?.let { SittingEvent.Ended(it, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND) }
        currentApp = packageName
        awaySinceMs = null
        return SittingEvent.Started(packageName, ended)
    }

    /** Drop all state (service disconnect, global disable, process teardown). */
    fun reset() {
        currentApp = null
        awaySinceMs = null
    }

    private fun onAppWindow(packageName: String, nowMs: Long): SittingEvent {
        val current = currentApp
        if (current == null) {
            currentApp = packageName
            awaySinceMs = null
            return SittingEvent.Started(packageName, null)
        }

        if (packageName == current) {
            // The user came back. Whether that ends the sitting depends only on how long they were
            // gone — the SAME question InteractionTracker asks, so the two answers cannot diverge.
            val awaySince = awaySinceMs
            awaySinceMs = null
            if (awaySince != null && nowMs - awaySince >= returnWindowMs) {
                return SittingEvent.Started(
                    packageName,
                    SittingEvent.Ended(current, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND)
                )
            }
            return SittingEvent.Unchanged
        }

        // A different app is in front. Start (or keep) the away clock; the sitting survives until
        // that clock passes the return window. THIS is the line that fixes #28: a picker, a share
        // sheet or a permission dialog is just an app that will not be in front for five minutes.
        val awaySince = awaySinceMs ?: nowMs.also { awaySinceMs = it }
        if (nowMs - awaySince < returnWindowMs) return SittingEvent.Unchanged

        currentApp = packageName
        awaySinceMs = null
        return SittingEvent.Started(
            packageName,
            SittingEvent.Ended(current, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND)
        )
    }

    private fun end(cause: SittingEndCause): SittingEvent {
        val current = currentApp ?: return SittingEvent.Unchanged
        currentApp = null
        awaySinceMs = null
        return SittingEvent.Ended(current, cause)
    }

    companion object {
        /**
         * How long another app must hold the foreground before the sitting it interrupted ends.
         *
         * Two minutes: long enough to browse a gallery or finish a crop screen without losing a pass
         * that was already earned, short enough that going off to use another app costs a fresh
         * delay. See the constructor doc for why this is NOT `InteractionTracker.SESSION_EXPIRY_MS`
         * — the two windows answer different questions and are deliberately different lengths.
         */
        const val PASSTHROUGH_RETURN_WINDOW_MS = 2L * 60L * 1000L
    }
}
