package com.astraedus.nudge.domain.windows

/**
 * What the window list says about [FloatingWindowSettle.verdict]'s package, once time is taken
 * into account.
 */
sealed interface FloatingVerdict {
    /** Not on screen as a floating window at all. The overlay blocks it the ordinary way. */
    data object NotFloating : FloatingVerdict

    /**
     * Floating-SIZED in this snapshot, but not for long enough to tell a floating window from a
     * fullscreen one caught mid-animation. Ask again in [recheckInMs].
     */
    data class Unsettled(val bounds: WindowBounds, val recheckInMs: Long) : FloatingVerdict

    /** Floating, and has stayed that way: the #71 cover and its BACK press are licensed. */
    data class Settled(val bounds: WindowBounds) : FloatingVerdict
}

/**
 * A floating window is one that STAYS floating-sized (issue #119).
 *
 * [WindowSnapshot.isFloating] answers from ONE window list, and one window list cannot tell a
 * freeform window from a fullscreen app that is still animating into place. Field capture, Pixel 9a,
 * GrapheneOS, Android 17, Clock resumed from Recents under a Delay rule:
 *
 * ```
 * 41.771  windows changed display=1080x2424 [.., 18:application:com.android.deskclock:focused:active:868x1949]
 * 41.786  floating window blocked, pressing BACK once package=com.android.deskclock window=18
 * ```
 *
 * 868x1949 is 80% of the display on both axes: the Recents-to-fullscreen scale-up, read 17ms before
 * it finished. The platform's own task record had Clock fullscreen (`winMode=1`, `0,0-1080,2424`)
 * throughout. The BACK destroyed Clock's only activity, its task was removed, and when the Delay
 * completed there was nothing under the overlay but Home, which revoked the pass.
 *
 * Lowering [WindowSnapshot.FULLSCREEN_FRACTION] cannot fix that: a launch animation passes through
 * every scale on its way to fullscreen, so any size threshold is crossed at some frame. Time is the
 * difference. A launch animation is over in a few hundred milliseconds and its bounds change while it
 * runs; an OEM floating window sits at one size until the user moves it. So the destructive half of
 * #71 (BACK on the window, a cover over it) waits for the window to have held still at a
 * floating size for [settleMs]. A window that never holds still (a user dragging it continuously)
 * is settled anyway after [maxWaitMs] of being floating-sized, so this delays the #71 cover, it never
 * withdraws it.
 *
 * Fed by [observe] on EVERY window read, before the read is published, so a snapshot a caller holds
 * has always been observed. [verdict] may be asked from the launch path, which runs on a background
 * coroutine, so both are synchronized.
 *
 * Pure: no Android types, the clock is a parameter.
 */
class FloatingWindowSettle(
    private val settleMs: Long = SETTLE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS
) {

    /** One package seen floating-sized: since when, and since when at these exact bounds. */
    private data class Sighting(
        val bounds: WindowBounds,
        val boundsSinceMs: Long,
        val floatingSinceMs: Long
    )

    private val sightings = HashMap<String, Sighting>()

    /**
     * Record a fresh window list. A package that is not floating in it forgets its history, so a
     * window that goes fullscreen and comes back starts its settle over.
     *
     * A null snapshot (the read failed) is no evidence of anything and changes nothing.
     */
    @Synchronized
    fun observe(snapshot: WindowSnapshot?, nowMs: Long) {
        if (snapshot == null) return
        val floatingNow = snapshot.windows
            .asSequence()
            .filter { it.kind == WindowKind.APPLICATION }
            .mapNotNull { it.packageName }
            .distinct()
            .mapNotNull { pkg -> snapshot.floatingBoundsOf(pkg)?.let { pkg to it } }
            .toMap()
        sightings.keys.retainAll(floatingNow.keys)
        for ((pkg, bounds) in floatingNow) {
            val previous = sightings[pkg]
            sightings[pkg] = when {
                previous == null -> Sighting(bounds, boundsSinceMs = nowMs, floatingSinceMs = nowMs)
                previous.bounds == bounds -> previous
                else -> previous.copy(bounds = bounds, boundsSinceMs = nowMs)
            }
        }
    }

    /**
     * Is [packageName] floating in [snapshot], and has it been for long enough to act on?
     *
     * @param covered the #71 cover is already over this package. A window we have already
     *   confirmed and covered does not become unconfirmed by moving: the user dragging a covered
     *   window must not turn its every touch back into a bypass.
     */
    @Synchronized
    fun verdict(
        snapshot: WindowSnapshot?,
        packageName: String,
        nowMs: Long,
        covered: Boolean = false
    ): FloatingVerdict {
        val bounds = snapshot?.floatingBoundsOf(packageName) ?: return FloatingVerdict.NotFloating
        if (covered) return FloatingVerdict.Settled(bounds)
        val sighting = sightings[packageName]
        // Every published snapshot was observed first, so a missing or different sighting means a
        // snapshot older than the history. Not evidence of stillness either way.
        if (sighting == null || sighting.bounds != bounds) {
            return FloatingVerdict.Unsettled(bounds, settleMs)
        }
        val stillFor = nowMs - sighting.boundsSinceMs
        val floatingFor = nowMs - sighting.floatingSinceMs
        if (stillFor >= settleMs || floatingFor >= maxWaitMs) return FloatingVerdict.Settled(bounds)
        val recheckIn = minOf(settleMs - stillFor, maxWaitMs - floatingFor)
        return FloatingVerdict.Unsettled(bounds, recheckIn.coerceAtLeast(1L))
    }

    /** Forget everything (service teardown). */
    @Synchronized
    fun reset() {
        sightings.clear()
    }

    companion object {
        /**
         * How long a floating-sized window must hold still before it counts as floating.
         *
         * Above 500ms on purpose: Android's `AccessibilityWindowsPopulator` holds back window-list
         * updates while windows change on consecutive frames, and only forces one out after 500ms of
         * continuous change (the same capture logs "Windows change within in 2 frames continuously
         * over 500 ms and notify windows changed immediately"). A list that has not changed for less
         * than that may simply be a list the platform has not refreshed yet.
         */
        const val SETTLE_MS = 750L

        /** A floating-sized window that keeps moving is treated as floating after this long. */
        const val MAX_WAIT_MS = 2_500L
    }
}
