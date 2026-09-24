package com.astraedus.nudge.domain.interaction

/**
 * "Did WE just cause that click?"
 *
 * The Following steer performs `ACTION_CLICK` inside the host app, and the platform reports that the
 * same way it reports a finger: a real `TYPE_VIEW_CLICKED` event carrying the host app's package. The
 * interaction counter cannot tell the difference, so on the bench device Nudge's own two taps were
 * counted as the user's ("interaction counted ... reason=click session=14"), inflating the very
 * number the counter exists to make honest.
 *
 * That is the same failure this repo already paid for from the other direction: counting autoplaying
 * video as user input (issue #28). A counter that includes the blocker's own actions is measuring
 * itself.
 *
 * ## Why a time window rather than the node
 *
 * Matching on the clicked node's view id was the obvious alternative and is worse here. The id we
 * click (`action_bar_title_view`, a menu row) is a legitimate thing for the USER to tap too, so an
 * id-based rule would keep suppressing their taps for as long as they stayed on that screen. A window
 * keyed on the moment we dispatched is bounded by construction: it opens when we act, and it closes
 * whether or not the event we expected ever arrives.
 *
 * The window is deliberately short. It is sized for the gap between `performAction` returning and the
 * resulting accessibility event being delivered -- tens of milliseconds in practice -- with enough
 * margin for a loaded device, and nowhere near long enough to swallow a second, genuine tap. Erring
 * long would under-count the user, which is the direction this counter must never fail in.
 *
 * Pure and clock-injected, so the boundaries are ordinary JVM assertions rather than sleeps.
 */
class SyntheticClickWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var dispatchedAtMs: Long? = null

    /** Called immediately BEFORE performing a click of our own. */
    fun onDispatch(nowMs: Long) {
        dispatchedAtMs = nowMs
    }

    /**
     * Whether a click arriving at [nowMs] should be treated as ours and not counted.
     *
     * CONSUMES the window on a match: we dispatch one click at a time, so one click is all a single
     * dispatch may excuse. Without that, a burst of genuine taps inside the window would all be
     * swallowed by a single synthetic dispatch.
     */
    fun shouldSuppress(nowMs: Long): Boolean {
        val at = dispatchedAtMs ?: return false
        if (nowMs < at || nowMs - at > windowMs) return false
        dispatchedAtMs = null
        return true
    }

    /** Forget any outstanding dispatch, e.g. when the user leaves the app. */
    fun reset() {
        dispatchedAtMs = null
    }

    companion object {
        /** Generous enough for event delivery on a loaded device, far short of a second real tap. */
        const val DEFAULT_WINDOW_MS = 1_000L
    }
}
