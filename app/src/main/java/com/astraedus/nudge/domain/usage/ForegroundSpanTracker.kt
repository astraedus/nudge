package com.astraedus.nudge.domain.usage

/**
 * Turns a raw `UsageEvents` stream into **non-overlapping** foreground spans.
 *
 * **Why this exists.** Every screen-time number in the app used to pair `ACTIVITY_RESUMED` with
 * `ACTIVITY_PAUSED` through a `package -> startTime` map — three separate copies of it, one per
 * read path. A map keyed by package can hold several open spans at once, and each one was then
 * credited *from its RESUMED all the way to now*. Two consequences, both reported from a real
 * daily-driver phone as **~17 hours of screen time before lunchtime**:
 *
 * 1. Nothing said only ONE app can be in the foreground. Whenever a PAUSED went missing — a
 *    killed process, a dropped event, an activity the platform stopped without pausing — that
 *    app's span stayed open while the next app opened its own. Their totals then covered the
 *    same wall-clock minutes, so a day could add up to far more than 24 hours.
 * 2. An open span had no upper bound. A RESUMED from three days ago with no matching close was
 *    worth three days of "screen time", spread across every bar in between.
 *
 * This tracker is the single answer to "which app is in the foreground, from when":
 *
 * - **One span at a time.** A RESUMED for a different package closes the open one *at that
 *   instant* — the app the user just left stopped being foreground exactly when the next one
 *   started. Sum over apps of foreground time in any interval can therefore never exceed the
 *   interval itself, which is what makes the "a day cannot exceed 24 hours" guarantee structural
 *   rather than a clamp bolted on afterwards.
 * - **Everything that ends the foreground ends the span**: PAUSED, the STOPPED of the very
 *   activity that opened it, the screen going non-interactive, the keyguard appearing, the device
 *   shutting down. The screen turning off is the important one — it is the moment a phone left in
 *   a pocket stops accruing time, and the old code had no idea it had happened.
 * - **An open span is inferred, so it is capped** ([openTailLimitMs]). Only the *start* of that
 *   span was observed; extending it to "now" with no limit is how a lost close event became a
 *   day of usage. Time we actually measured (both endpoints seen) is never capped.
 *
 * Pure Kotlin, no Android types — the platform loop lives in `ScreenTimeProvider`, which feeds
 * every read path (weekly bars, hourly heatmap, Willpower's session stats) through this one
 * class. JVM-tested in `ForegroundSpanTrackerTest`.
 *
 * Not thread-safe. Build one, feed it in timestamp order, [finish] it, discard it.
 *
 * @param openTailLimitMs how far a still-open span may be extended towards "now".
 * @param onSpan receives each closed span as `[startMs, endMs)`, in order, never overlapping.
 */
class ForegroundSpanTracker(
    private val openTailLimitMs: Long = DEFAULT_OPEN_TAIL_LIMIT_MS,
    private val onSpan: (packageName: String, startMs: Long, endMs: Long) -> Unit
) {

    private var openPackage: String? = null
    private var openClassName: String? = null
    private var openStartMs = 0L

    /** End of the last emitted span — spans are a partition of time, so none may reach back past it. */
    private var lastEmittedEndMs = Long.MIN_VALUE

    /**
     * An activity moved to the foreground.
     *
     * A different package taking the foreground **closes** whatever was open, at this timestamp.
     * A second RESUMED for the package already open keeps the span open and moves its start
     * forward ([kotlin.math.max], so an out-of-order event cannot lengthen it): the later
     * timestamp is the one the app is demonstrably foreground from, and crediting the earlier one
     * would invent time the user did not spend. That is the reading the single-day path has
     * always used for a malformed sequence.
     */
    fun onResumed(packageName: String, className: String?, timestampMs: Long) {
        if (packageName == openPackage) {
            openStartMs = maxOf(openStartMs, timestampMs)
            openClassName = className
            return
        }
        closeOpenSpan(timestampMs)
        openPackage = packageName
        openClassName = className
        openStartMs = timestampMs
    }

    /** A PAUSED for anything other than the open package has no start to measure from — ignored. */
    fun onPaused(packageName: String, timestampMs: Long) {
        if (packageName != openPackage) return
        closeOpenSpan(timestampMs)
    }

    /**
     * An activity became invisible (`ACTIVITY_STOPPED`) — a backstop for a PAUSED the platform
     * never delivered.
     *
     * Matched on the **class**, not just the package. Moving between two activities of one app
     * emits `A PAUSED, B RESUMED, A STOPPED`; treating that trailing STOPPED as "this package is
     * no longer foreground" would close B's freshly opened span and lose the whole visit. Only
     * the STOPPED of the exact activity that opened the span ends it.
     */
    fun onStopped(packageName: String, className: String?, timestampMs: Long) {
        if (packageName != openPackage) return
        if (className == null || className != openClassName) return
        closeOpenSpan(timestampMs)
    }

    /**
     * Nothing is in the foreground any more, whatever was: the screen went non-interactive, the
     * keyguard came up, or the device shut down.
     *
     * These are device-level events (the platform reports them under package `"android"`), and
     * they are the reason a phone that goes into a pocket mid-session stops accruing time even
     * when the app's own PAUSED never arrives.
     */
    fun onForegroundEnded(timestampMs: Long) {
        closeOpenSpan(timestampMs)
    }

    /**
     * Closes the stream.
     *
     * A span still open is time we have **not** measured — only its start was observed — so it is
     * counted only when the caller's window actually reaches the present
     * ([extendOpenSpanToNow]), and then only as far as [openTailLimitMs] past its start. For a
     * window that has already ended, an open span means the closing event fell outside the query,
     * and inventing an end would credit a past day with time we cannot see.
     *
     * Idempotent: the open span is consumed, so a second call adds nothing.
     */
    fun finish(nowMs: Long, extendOpenSpanToNow: Boolean) {
        if (openPackage != null && extendOpenSpanToNow) {
            closeOpenSpan(minOf(nowMs, openStartMs + openTailLimitMs))
        }
        openPackage = null
        openClassName = null
    }

    private fun closeOpenSpan(endMs: Long) {
        val packageName = openPackage ?: return
        val startMs = openStartMs
        openPackage = null
        openClassName = null

        // Never reach back into an already-emitted span: consumers add spans up, so an overlap
        // would be double-counted. Nothing above can produce one; this keeps that true if
        // something later does.
        val start = maxOf(startMs, lastEmittedEndMs)
        if (start >= endMs) return
        lastEmittedEndMs = endMs
        onSpan(packageName, start, endMs)
    }

    companion object {
        /**
         * How far a still-open span may be extended towards "now" — 4 hours.
         *
         * With screen-off, keyguard, shutdown and STOPPED all closing spans, a span still open
         * means the screen has been on and this one app foreground the whole time since its
         * RESUMED. Four hours covers a genuinely long film or gaming session; past that, the far
         * likelier explanation is a close event we never received, and the honest answer is to
         * stop counting rather than to keep billing an app for a phone that is not even on.
         *
         * This caps only what is INFERRED. A span whose end we actually observed is never
         * shortened, however long it is.
         */
        const val DEFAULT_OPEN_TAIL_LIMIT_MS = 4L * 60L * 60L * 1000L
    }
}
