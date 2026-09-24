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

    /**
     * We opened the dropdown and never found the row. Dismiss it.
     *
     * Emitted exactly once, when a pending attempt ages out. Giving up silently is right for the
     * STEER, but not for the MENU: on the bench device a failed attempt left Instagram's dropdown
     * hanging open over the feed indefinitely, needing a manual back press. Nudge opened it, so
     * Nudge closes it -- a half-performed interaction in someone else's app is worse than none.
     */
    data object CloseMenu : SteerAction
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
 * - **a pending menu attempt is resolved BEFORE the surface is even looked at** (see below):
 *   `menuVisible` -> clear pending, `ClickFollowing`; else if `nowMs - pendingSince > menuTimeoutMs`
 *   -> clear pending, `CloseMenu` (gave up, `attempted` STAYS true); else `None`
 * - `OTHER_TAB` -> reset `attempted=false`, clear pending, `None`
 * - `FOLLOWING_FEED` -> `attempted = true`, clear pending, `None`
 * - `UNKNOWN` -> `None`, change nothing (reel player / story / DM thread tells us nothing)
 * - `HOME_FEED` -> if `attempted` then `None`, else `attempted = true`, pending = `nowMs`, `OpenMenu`
 *
 * ## Why the pending attempt is resolved above the surface switch
 *
 * Because we do not know, and cannot know from here, which window the service will be holding while
 * the dropdown is open. `logo-menu.xml` — the real dump taken with the menu up — contains no
 * `title_logo`, no `tab_bar` and no `action_bar_title`, i.e. uiautomator captured the popup window
 * alone. If the accessibility service sees the same thing, [PlatformSurfaces.classify] returns
 * [HostSurface.UNKNOWN] for exactly the moment we are waiting for. Resolving the pending attempt
 * inside the `HOME_FEED` branch would then make [SteerAction.ClickFollowing] unreachable, and Nudge
 * would open Instagram's dropdown and leave it hanging open over the user's feed — worse than not
 * steering at all. If instead the service sees the activity's root, the menu classifies as
 * `HOME_FEED` and both structures work.
 *
 * A pending attempt is a fact about what WE just did, not about what screen is showing, so it does
 * not belong under a branch keyed on the screen. The cost of resolving it above the switch is that
 * an unrelated screen can age out a pending attempt; that is bounded by [menuTimeoutMs] and costs at
 * most one abandoned attempt on this arrival.
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
class FollowingSteer(private val menuTimeoutMs: Long = DEFAULT_MENU_TIMEOUT_MS) {

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

        // A PENDING ATTEMPT IS RESOLVED FIRST, whatever the surface classifies as. The open dropdown
        // may well be the only window we can see, in which case this moment classifies as UNKNOWN —
        // see the class KDoc for why doing this inside the HOME_FEED branch leaves the menu hanging
        // open on the user's screen.
        if (isMenuPending) return resolvePending(menuVisible, nowMs)

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
            // The reel player, a story, a DM thread — and possibly the open dropdown itself, which
            // is why any pending attempt was already resolved above this switch. These tell us
            // nothing about the arrival, so they must not clear `attempted`: doing so would re-steer
            // the user every time they came back from a reel.
            HostSurface.UNKNOWN -> SteerAction.None

            HostSurface.HOME_FEED -> onHomeFeed(nowMs)
        }
    }

    /**
     * Resolve an attempt that is already in flight, WITHOUT claiming to know what screen is showing.
     *
     * Split out so the service can drive it from its fast path: the observation that sees the open
     * dropdown must not wait on the 2s content-change debounce that gates feature detection, or the
     * attempt ages out before [SteerAction.ClickFollowing] can ever be returned (it did, on device).
     * A caller there knows only "is the menu up", not which surface it is over, and fabricating a
     * [HostSurface] to satisfy [onObservation] would be feeding a state machine a premise the caller
     * cannot actually vouch for.
     *
     * [SteerAction.None] when nothing is pending, so it is always safe to ask.
     */
    fun resolvePending(menuVisible: Boolean, nowMs: Long): SteerAction {
        val since = pendingSince ?: return SteerAction.None
        if (menuVisible) {
            pendingSince = null
            return SteerAction.ClickFollowing
        }
        if (nowMs - since > menuTimeoutMs) {
            // Gave up. `attempted` stays true on purpose: one attempt per arrival, never a retry.
            // But the dropdown we opened is still on screen, so close it on the way out.
            pendingSince = null
            return SteerAction.CloseMenu
        }
        return SteerAction.None
    }

    /**
     * The first-attempt decision for this arrival. Reached only with no attempt outstanding — the
     * pending case is resolved by [onObservation] above the surface switch.
     */
    private fun onHomeFeed(nowMs: Long): SteerAction {
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

    companion object {
        /**
         * How long to wait for the dropdown after clicking the entry point.
         *
         * Deliberately several times the ~250 ms the menu actually takes to appear, and deliberately
         * LONGER than `NudgeAccessibilityService.contentChangedDebounceMs` (2 s). The first shipped
         * value was 1.5 s, i.e. SHORTER than that debounce, which made [SteerAction.ClickFollowing]
         * unreachable on a real device: the observation that would have seen the open menu could not
         * arrive until the debounce elapsed, by which point the attempt had already aged out. Nudge
         * opened Instagram's dropdown and never clicked anything.
         *
         * The service no longer relies on that debounced path for a pending attempt (it observes on
         * a fast path while one is in flight), so this margin is belt-and-braces rather than the
         * mechanism -- but `FollowingSteerTimeoutContractTest` pins the relationship anyway, because
         * the failure it prevents is silent and device-only.
         */
        const val DEFAULT_MENU_TIMEOUT_MS = 4_000L
    }
}
