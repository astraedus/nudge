package com.astraedus.nudge.domain.surfaces

/**
 * Where on screen the tab cover goes, as a validated rectangle in the window coordinates the
 * overlay layout params want (x, y, width, height) rather than the (left, top, right, bottom) an
 * accessibility node reports.
 *
 * Pure, and constructed only through [of], because the numbers it carries come from a HOST app's
 * node tree — the one input Nudge does not control. An accessibility node whose window has gone
 * away reports `[0,0][0,0]`; a node mid-animation or off-screen reports negatives. Painting a
 * filled rectangle from either of those does not fail quietly: it is an opaque block in the middle
 * of someone's screen, over an app Nudge was never asked to cover, with no visible way to dismiss
 * it. Rejecting junk bounds before a window is ever created is the cheapest place to stop that.
 */
data class TabCoverPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    companion object {
        /**
         * Convert node bounds to a placement, or return null if they cannot be trusted.
         *
         * Rejects:
         * - a zero or negative width or height (`right <= left`, `bottom <= top`) — a collapsed or
         *   detached node, which is what `[0,0][0,0]` is;
         * - a negative origin — a node scrolled or animated off the top/left edge, whose cover
         *   would be anchored outside the screen.
         *
         * Everything else is accepted as-is. There is no clamping and no "best effort" repair: a
         * rectangle we had to guess at is exactly the rectangle that should not be painted.
         */
        fun of(left: Int, top: Int, right: Int, bottom: Int): TabCoverPlacement? {
            if (left < 0 || top < 0) return null
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return null
            return TabCoverPlacement(x = left, y = top, width = width, height = height)
        }
    }
}
