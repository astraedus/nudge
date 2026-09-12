package com.astraedus.nudge.ui.widget

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Rate-limits widget refreshes without ever losing the LAST one.
 *
 * ## Why this shape
 *
 * Widgets are refreshed because something they display changed. Two properties are in tension: a
 * user hitting a wall of blocks can produce several changes a second, and each refresh is a real
 * RemoteViews build plus a binder call into the launcher - but the Protection widget is push-only
 * (`updatePeriodMillis="0"`), so a refresh that is merely *skipped* leaves it misrepresenting live
 * state with no later tick to save it.
 *
 * The first version was a leading-edge debounce, which gets the first property and fails the
 * second: a state change arriving inside a window opened by an unrelated block event was dropped
 * with nothing scheduled. Device QA caught the Protection widget stuck on a stale frame.
 *
 * A CONFLATED channel gets both. [request] never suspends and never blocks its caller; requests
 * arriving while a refresh is in flight collapse into a single pending one, which the loop picks up
 * after its cooldown. A burst of N therefore produces two refreshes - one immediately, one carrying
 * the final state - never N, and never none.
 *
 * ## Testability
 *
 * [run] is the whole policy, a plain suspending loop over an injected `onRefresh`, so it is
 * exercised on the JVM against a `TestScope`'s virtual clock. The part that cannot be tested here -
 * Glance's `updateAll` - is deliberately on the far side of that lambda.
 */
class WidgetRefreshCoalescer(
    private val cooldownMs: Long,
    private val onRefresh: suspend () -> Unit
) {

    /**
     * CONFLATED: capacity one, newest wins, `trySend` always succeeds.
     *
     * Deliberately not a `MutableSharedFlow`: one with `replay = 0` buffers nothing until a
     * subscriber exists, so a request racing [run]'s start would vanish. A channel buffers whether
     * or not anyone is receiving yet, which removes that race rather than making it unlikely.
     */
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** Non-suspending. Safe to call from anywhere, including an observer on a hot path. */
    fun request() {
        requests.trySend(Unit)
    }

    /**
     * Consumes requests forever; cancelled with the scope that launched it.
     *
     * The cooldown comes AFTER the refresh, so the first request in a quiet period is served
     * immediately - latency matters most for the change the user just caused - and the rate limit
     * applies only to what follows it.
     */
    suspend fun run() {
        for (ignored in requests) {
            onRefresh()
            delay(cooldownMs)
        }
    }

    // NOTE ON SCOPE: this coalesces the usage_events stream ONLY. Protection state bypasses it and
    // refreshes immediately - see NudgeWidgetUpdater.start(). Deferring a refresh means running it
    // after the user has likely left the app, and a deferral is only ever worth it for a source
    // that actually bursts.
}
