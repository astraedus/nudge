package com.astraedus.nudge.domain.surfaces

/**
 * What the cover manager should do to its window, as data.
 *
 * A sealed interface rather than a boolean pair so the caller's `when` is exhaustive and so
 * "reposition" and "create" are the same instruction — the window manager's update call is the same
 * either way, and collapsing them here is what keeps the service side free of state.
 */
sealed interface TabCoverEffect {
    /** Create the cover, or move the existing one, to [placement]. */
    data class Show(val placement: TabCoverPlacement) : TabCoverEffect

    /** Tear the cover down. Only emitted when one is actually up. */
    data object Hide : TabCoverEffect

    /** Do nothing at all. The common case, and the reason this class exists. */
    data object None : TabCoverEffect
}

/**
 * The pure show/hide/reposition decision for the tab cover.
 *
 * The state it decides over is passed in, not held: the caller owns "is a cover up, and where", and
 * this class owns the rule. That keeps it a function of its arguments — trivially JVM-testable, and
 * impossible to desynchronise from the real window, which is the failure mode a decider holding its
 * own copy of `shown` would invite.
 *
 * ## The rule that matters: unchanged bounds produce [TabCoverEffect.None]
 *
 * The service asks this question on content-change events, and a scrolling Instagram feed fires those
 * continuously while `tab_bar` stays exactly where it is. Re-issuing `Show` on each one would churn
 * the window through the window manager dozens of times a second: visible flicker over the nav bar,
 * and a window repeatedly torn down and rebuilt is a window that is briefly not eating the tap it
 * exists to eat. Same bounds means nothing to do.
 */
class TabCoverDecider {

    /**
     * @param vanishRequested whether a rule covering this app's feature resolves to a hard block
     *   right now. Comes from the DECIDING rule, not from any rule.
     * @param bounds where the tab is, or null when the tab node was not found or reported junk
     *   bounds (see [TabCoverPlacement.of]).
     * @param shown where the cover currently is, or null when none is up.
     */
    fun decide(
        vanishRequested: Boolean,
        bounds: TabCoverPlacement?,
        shown: TabCoverPlacement?
    ): TabCoverEffect {
        // No cover wanted, or wanted but we do not know where to put it. Both mean the same thing:
        // nothing may be on screen. `bounds == null` deliberately hides rather than freezing the
        // existing cover in place — a tab node that has vanished from the tree is the Following
        // screen or the reel player, where there is no nav bar to cover.
        if (!vanishRequested || bounds == null) {
            return if (shown != null) TabCoverEffect.Hide else TabCoverEffect.None
        }
        // Wanted, and we know where. Show it, unless it is already exactly there.
        return if (shown == bounds) TabCoverEffect.None else TabCoverEffect.Show(bounds)
    }
}
