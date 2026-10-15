package com.astraedus.nudge.domain.windows

/** What the floating-window cover should do, decided purely; the manager only does window work. */
sealed interface FloatingCoverEffect {
    /** Leave the cover exactly as it is (including "there is none"). */
    data object None : FloatingCoverEffect

    /** Remove the cover. */
    data object Hide : FloatingCoverEffect

    /** Put the cover over [bounds] for [packageName], adding the window or moving it. */
    data class Show(val packageName: String, val bounds: WindowBounds) : FloatingCoverEffect
}

/**
 * When a blocked app is FLOATING, the block is a cover over its window, not only the overlay
 * ([#71](https://github.com/astraedus/nudge/issues/71)).
 *
 * ## Why the overlay alone cannot block a floating window
 *
 * The block overlay is a fullscreen activity. An OEM freeform window (Huawei, Samsung pop-up view,
 * AOSP freeform) is stacked ABOVE every fullscreen activity, ours included, so the overlay renders
 * underneath the app it is meant to stop and the app stays usable. Worse, each touch on that window
 * fires `WINDOW_STATE_CHANGED` for the blocked package, which the service used to read as the user
 * getting past the overlay: mark it inactive, relaunch it, underneath again, forever. The reporter
 * saw the overlay "refresh" on every touch while the floating app worked normally.
 *
 * An accessibility overlay IS stacked above freeform windows, so a touch-consuming
 * `TYPE_ACCESSIBILITY_OVERLAY` drawn over the window's bounds is what actually blocks it. HOME does
 * not dismiss a floating window; BACK, delivered while it holds input focus, usually closes it, so
 * that is tried once per window.
 *
 * ## What this decides, and what it does not
 *
 * Only WHERE a cover goes and WHEN it must come off. Whether the app is blocked at all stays the
 * engine's decision: a cover is only ever shown from the one launch site, behind a block decision.
 * Like the tab cover, it counts nothing: a touch the cover eats is not a confrontation.
 */
object FloatingWindowCover {

    /**
     * Where to cover [target] as it is blocked, or null when it is not floating (or the window list
     * is unavailable, which never licenses a cover).
     */
    fun coverFor(snapshot: WindowSnapshot?, target: String): WindowBounds? =
        snapshot?.floatingBoundsOf(target)

    /**
     * The window to press BACK on for [target], or null: only a floating window of the target that
     * holds focus right now (BACK goes to the focused window, and pressing it on anything else
     * would act on an app we were not asked to block), and only once per window id, so a cover
     * that keeps being re-asserted cannot turn into a BACK storm.
     */
    fun backTarget(snapshot: WindowSnapshot?, target: String, alreadyPressed: Set<Int>): Int? {
        val windowId = snapshot?.focusedFloatingWindowOf(target) ?: return null
        return windowId.takeIf { it !in alreadyPressed }
    }

    /**
     * Keep, move or remove the cover after the window list changed.
     *
     * @param covered the package the cover is over, or null when none is up.
     * @param shownBounds where the cover currently is.
     * @param grantAlive the user has earned their way into [covered] (a completed delay, an
     *   emergency pass): the block is over, so the cover must be too.
     * @param snapshot null when the list could not be read. That is no evidence the window moved
     *   or went away, so the cover stays; it still comes off on a grant, a disable or a screen-off.
     */
    fun maintain(
        snapshot: WindowSnapshot?,
        covered: String?,
        shownBounds: WindowBounds?,
        grantAlive: Boolean
    ): FloatingCoverEffect {
        if (covered == null) return FloatingCoverEffect.None
        if (grantAlive) return FloatingCoverEffect.Hide
        if (snapshot == null) return FloatingCoverEffect.None
        // Gone, or maximised to fullscreen: either way there is no floating window left to cover.
        // A fullscreen app is blocked by the overlay the ordinary way, on its next window event.
        val bounds = snapshot.floatingBoundsOf(covered) ?: return FloatingCoverEffect.Hide
        if (bounds == shownBounds) return FloatingCoverEffect.None
        return FloatingCoverEffect.Show(covered, bounds)
    }
}
