package com.astraedus.nudge.domain.sitting

/**
 * The "keep the pass on short trips" setting (issue [#110](https://github.com/astraedus/nudge/issues/110)),
 * as the one number the preference stores: minutes, 0 = off.
 *
 * Off by default. With it on, a completed delay survives the user leaving the app — to the home
 * screen, through Recents or a gesture swipe, into another app, or with the screen off — as long as
 * they come back within this many minutes. See [SittingTracker.configureShortTrips] for why the
 * launcher needs it and why it is opt-in.
 */
object ShortTrips {

    /** Stored value meaning "off": the shipped model, unchanged. */
    const val OFF = 0

    /** The lengths the Settings row offers, in minutes. */
    val OPTIONS_MINUTES = listOf(1, 2, 3, 5)

    /** What switching it on selects: the length the app-switch window already has. */
    val DEFAULT_MINUTES: Int = (SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS / 60_000L).toInt()

    /**
     * The return window for a stored value, or null for off. A stored value outside the offered
     * options (a hand-edited or future preference) is clamped into them rather than trusted, so the
     * setting can never hold a pass open longer than the longest option.
     */
    fun returnWindowMs(minutes: Int): Long? {
        if (minutes <= OFF) return null
        val clamped = minutes.coerceAtMost(OPTIONS_MINUTES.max())
        return clamped * 60_000L
    }

    /**
     * Whether moving the setting from [fromMinutes] to [toMinutes] loosens protection, which is what
     * Strict Mode gates. Turning it on and lengthening it loosen; shortening it and turning it off
     * do not.
     */
    fun isWeakening(fromMinutes: Int, toMinutes: Int): Boolean =
        normalized(toMinutes) > normalized(fromMinutes)

    private fun normalized(minutes: Int): Int =
        if (minutes <= OFF) OFF else minutes.coerceAtMost(OPTIONS_MINUTES.max())
}
