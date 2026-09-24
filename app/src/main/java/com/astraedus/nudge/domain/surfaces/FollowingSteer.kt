package com.astraedus.nudge.domain.surfaces

/**
 * What the steer executor should click next.
 *
 * Only ever one click at a time. The two-step sequence is deliberately NOT returned as a batch: the
 * menu takes real time to animate in, and "click the entry point then click Following" issued as one
 * instruction would dispatch the second tap at coordinates that are still empty.
 */
sealed interface SteerAction {
    /** Do nothing. By far the most common answer, and the correct one whenever in doubt. */
    data object None : SteerAction

    /** Click the title dropdown's entry point. */
    data object OpenMenu : SteerAction

    /** The menu is up; click its "Following" row. */
    data object ClickFollowing : SteerAction
}

/**
 * The once-per-arrival state machine behind the experimental Following steer: on arrival at a host
 * app's home feed, open the title dropdown and click through to the chronological following feed.
 *
 * Pure. The clock is a `nowMs` parameter on every call and is never read from
 * `System.currentTimeMillis()` inside, so the timeout is an ordinary JVM assertion rather than a
 * sleep.
 *
 * ## The policy, verbatim
 *
 * - `!inHostApp` (the user left the app) -> reset `attempted=false`, clear any pending menu, `None`
 * - `OTHER_TAB` -> reset `attempted=false`, clear pending, `None`
 * - `FOLLOWING_FEED` -> `attempted = true`, clear pending, `None`
 * - `UNKNOWN` -> `None`, change nothing (reel player / story / DM thread tells us nothing)
 * - `HOME_FEED`:
 *     - if a menu attempt is pending: `menuVisible` -> clear pending, `ClickFollowing`; else if
 *       `nowMs - pendingSince > menuTimeoutMs` -> clear pending, `None` (gave up silently,
 *       `attempted` STAYS true); else `None`
 *     - else if `attempted` -> `None`
 *     - else -> `attempted = true`, pending = `nowMs`, `OpenMenu`
 *
 * ## Why `attempted` survives `FOLLOWING_FEED`
 *
 * A user who backs out of Following to Home did it on purpose and must not be steered again until
 * they leave the host app or visit another tab. Observing the Following feed is therefore not a
 * "done, forget it" — it is proof that this arrival has already been steered, so it SETS `attempted`
 * rather than clearing it. Without that, backing out would look identical to a fresh arrival and the
 * user would be dragged straight back to the feed they just left. The only two things that clear the
 * memory are the two gestures that genuinely end an arrival: leaving the app, and visiting another
 * tab.
 *
 * Note the timeout path also leaves `attempted` true. Giving up is silent and final for this
 * arrival: this is the first time Nudge acts *inside* another app, so a failed attempt must never
 * become a retry loop tapping at a host app's chrome.
 *
 * @param menuTimeoutMs how long to wait for the dropdown after clicking the entry point.
 */
class FollowingSteer(private val menuTimeoutMs: Long = 1_500L) {

    /** Has this arrival already been steered (or already had its one attempt)? */
    private var attempted = false

    /** When the entry point was clicked, or null when no attempt is in flight. */
    private var pendingSince: Long? = null

    /** Test/diagnostic view of the memory that decides whether a new arrival may steer. */
    val hasAttempted: Boolean
        get() = attempted

    /** Test/diagnostic view of whether a menu attempt is still in flight. */
    val isMenuPending: Boolean
        get() = pendingSince != null

    /**
     * Fold one observation into the machine and get the single click to dispatch.
     *
     * @param surface which host-app screen is showing, from [PlatformSurfaces.classify].
     * @param inHostApp whether the host app is still the foreground app at all.
     * @param menuVisible whether the dropdown's menu rows are present in the tree right now.
     * @param nowMs the caller's clock, used only to age out a pending attempt.
     */
    fun onObservation(
        surface: HostSurface,
        inHostApp: Boolean,
        menuVisible: Boolean,
        nowMs: Long
    ): SteerAction {
        if (!inHostApp) {
            reset()
            return SteerAction.None
        }
        return when (surface) {
            // Another tab: a real navigation away from the feed. This arrival is over, so the next
            // return to Home is a fresh one and may steer again.
            HostSurface.OTHER_TAB -> {
                reset()
                SteerAction.None
            }
            // We are where the steer was trying to get to — whether we put the user here or they
            // navigated themselves. Either way this arrival is spent. See the class KDoc.
            HostSurface.FOLLOWING_FEED -> {
                attempted = true
                pendingSince = null
                SteerAction.None
            }
            // The reel player, a story, a DM thread. These tell us nothing about the arrival, so
            // they must not clear `attempted` (that would re-steer on every return from a reel) and
            // must not age out a pending menu against a screen the menu was never on.
            HostSurface.UNKNOWN -> SteerAction.None

            HostSurface.HOME_FEED -> onHomeFeed(menuVisible, nowMs)
        }
    }

    private fun onHomeFeed(menuVisible: Boolean, nowMs: Long): SteerAction {
        val pending = pendingSince
        if (pending != null) {
            if (menuVisible) {
                pendingSince = null
                return SteerAction.ClickFollowing
            }
            if (nowMs - pending > menuTimeoutMs) {
                // Gave up. `attempted` stays true on purpose: one attempt per arrival, never a retry.
                pendingSince = null
            }
            return SteerAction.None
        }
        if (attempted) return SteerAction.None
        attempted = true
        pendingSince = nowMs
        return SteerAction.OpenMenu
    }

    /**
     * Forget everything about the current arrival: no attempt made, no menu in flight.
     *
     * Public because the service needs it on the two events that end an arrival without producing an
     * observation to fold in — the master toggle going OFF (a globally-disabled Nudge must behave as
     * if uninstalled, so the "already steered this visit" memory is about a visit that no longer
     * exists) and the service being torn down or restarted. Calling it is always safe: the worst it
     * can do is allow one more steer on the next Home-feed arrival.
     */
    fun reset() {
        attempted = false
        pendingSince = null
    }
}
