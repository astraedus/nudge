package com.astraedus.nudge.domain.block

/**
 * May the block screen stay in a split-screen half, a floating window or picture-in-picture?
 * No: it is relaunched fullscreen. This is the one place that decides it.
 *
 * ## Why ([#71](https://github.com/astraedus/nudge/issues/71))
 *
 * A Huawei on Android 10 could drag the block screen ITSELF out of fullscreen with the
 * multi-window dock gesture, and once it was a small window the blocked app behind it was usable.
 * Before 1.22.0 the gesture appeared refused: the blocked app's window events behind the shrinking
 * overlay read as a bypass and re-blocked. 1.22.0 (#86) taught the classifier that an app window
 * which is NOT focused, while another app's window is, is a panel rather than the app the user is in.
 * Our own floating block window holding focus is exactly that shape, so the blocked app's events went
 * quiet and nothing ever re-asserted the block.
 *
 * The manifest refuses multi-window (`resizeableActivity="false"`), but OEM floating windows and
 * large screens on Android 12+ ignore that flag, so the activity checks for itself and relaunches.
 *
 * ## No loop
 *
 * A device that puts EVERY new task in a window (desktop windowing, an OEM that keeps the
 * freeform mode for the new task) would relaunch forever. So a delivery that is itself one of our
 * relaunches, still in a window within [RELAUNCH_SETTLE_MS] of being started, gives up: the
 * platform put it there and another relaunch would land in the same place. A user dragging the
 * relaunched screen out again LATER is a new attempt and is relaunched again.
 */
object OverlayWindowMode {

    /**
     * How long after one of our fullscreen relaunches a still-windowed result is read as "the
     * platform launched it windowed" rather than "the user dragged it out again". A drag gesture
     * takes well over this from a standing start; a launch settles well within it.
     */
    const val RELAUNCH_SETTLE_MS = 1_500L

    enum class Decision {
        /** Fullscreen, or nothing to do: leave it alone. */
        STAY,

        /** Windowed: finish this task and start the same block again in a fresh, fullscreen one. */
        RELAUNCH_FULLSCREEN,

        /** Windowed straight after our own relaunch: the platform will not give us fullscreen. */
        GIVE_UP
    }

    /**
     * @param inMultiWindow `Activity.isInMultiWindowMode`: split-screen, freeform/floating and
     *   picture-in-picture all count (each is a windowing mode other than fullscreen).
     * @param isFinishing the activity is already on its way out (a completion path, a walk-away, or
     *   a relaunch already in flight). Never relaunch a block the user has finished with.
     * @param relaunchInFlight this instance already handed its block to a fullscreen replacement.
     * @param msSinceOwnRelaunch milliseconds since THIS delivery was started as one of our
     *   relaunches, or null when it was delivered by the service as usual.
     */
    fun decide(
        inMultiWindow: Boolean,
        isFinishing: Boolean,
        relaunchInFlight: Boolean,
        msSinceOwnRelaunch: Long?
    ): Decision = when {
        !inMultiWindow || isFinishing || relaunchInFlight -> Decision.STAY
        msSinceOwnRelaunch != null && msSinceOwnRelaunch in 0 until RELAUNCH_SETTLE_MS -> Decision.GIVE_UP
        else -> Decision.RELAUNCH_FULLSCREEN
    }
}
