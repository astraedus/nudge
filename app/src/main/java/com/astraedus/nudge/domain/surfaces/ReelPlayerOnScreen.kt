package com.astraedus.nudge.domain.surfaces

/**
 * A rectangle in screen coordinates, as four plain ints.
 *
 * Not `android.graphics.Rect`: that class is a throwing stub in this repo's JVM unit tests (see
 * `HostNodeFinder.findPlacement` for why the default-values flag stays off), so the decision below
 * is written against pure data and the one `Rect` read stays in `InAppDetector`.
 */
data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    /** Zero for an empty or inverted rectangle, which is what a detached node reports (`[0,0][0,0]`). */
    val height: Int get() = if (right <= left || bottom <= top) 0 else bottom - top

    /** The overlap with [other]; empty (height 0) when they do not overlap. */
    fun intersect(other: ScreenBounds): ScreenBounds = ScreenBounds(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom)
    )
}

/**
 * Does a node carrying one of Instagram's reel-player container ids mean the user is WATCHING the
 * reel player right now? (issue #190)
 *
 * `findAccessibilityNodeInfosByViewId` answers a weaker question than detection needs. The framework
 * returns every node with that id whose view is `VISIBLE` up its parent chain, and that includes
 * nodes that are not on screen at all: a pager page next to the current one, a player kept attached
 * behind another screen, a node scrolled out of view. It also says nothing about size, so a player
 * embedded in another screen matches the same as the full-screen one. Before this check, any such
 * node made the whole screen count as Reels, and a Reels rule fired where the user was not
 * watching reels.
 *
 * The player detection is documented as "the full-screen Reels viewer" (README; and the id list's
 * own KDoc says the ids were chosen because they do not appear in ordinary browsing). So a match
 * counts only when the node is visible to the user AND its on-screen part is at least
 * [MIN_HEIGHT_FRACTION] of the window's height.
 *
 * ## Why height, and why 0.8
 *
 * Measured on the recorded Instagram trees (`surface-fixtures/home.xml`, `coldstart.xml`; Pixel 3,
 * window `[0,0][1080,2028]`): the bottom tab bar starts at y=1896, so the Reels-tab player, which
 * runs from the top of the window to the tab bar, is 93% of the window's height, and a player in a
 * modal (a reel opened from a DM, a link or a profile) is the whole window. The Home feed's inline
 * video on the same trees is `[0,611][1080,1896]` and `[0,578][1080,1896]`: 63% and 65%. Instagram
 * caps feed media at 4:5, so an inline card is at most 1350 px tall on a 1080-wide screen, which is
 * 67% of this window and 75% of a 16:9 phone's (about 1800 px). 0.8 sits between the two.
 *
 * Height, not area: the full-screen player is always the window's full height, but on a wide window
 * (a tablet in landscape) its width may be a centred column, and an area rule would miss it there.
 *
 * ## Fails toward the old behaviour
 *
 * This check only ever NARROWS detection. When the bounds cannot be read (a read that threw, a
 * window with no height) it falls back to visibility alone, so a measurement problem can never turn
 * a real player into a miss. A node that is not visible to the user is never the player they are
 * watching, so rejecting it cannot cost a true positive.
 */
object ReelPlayerOnScreen {

    /** Fraction of the window's height the node's on-screen part must cover. See the class KDoc. */
    const val MIN_HEIGHT_FRACTION = 0.8

    /**
     * @param visibleToUser the node's `isVisibleToUser`.
     * @param node the node's bounds in screen coordinates, or null if they could not be read.
     * @param window the window root's bounds in screen coordinates, or null if they could not be read.
     */
    fun counts(visibleToUser: Boolean, node: ScreenBounds?, window: ScreenBounds?): Boolean {
        if (!visibleToUser) return false
        if (node == null || window == null) return true
        val windowHeight = window.height
        if (windowHeight == 0) return true
        val shownHeight = node.intersect(window).height
        return shownHeight >= windowHeight * MIN_HEIGHT_FRACTION
    }
}
