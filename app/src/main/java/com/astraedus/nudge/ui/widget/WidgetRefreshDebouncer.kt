package com.astraedus.nudge.ui.widget

import java.util.concurrent.atomic.AtomicLong

/**
 * "May I run a widget refresh right now?" — the whole of [NudgeWidgetUpdater]'s coalescing, as a
 * pure object with an injected clock.
 *
 * Split out on purpose. The updater itself cannot be JVM-tested (it calls into Glance, which needs
 * a real `Context` and a real AppWidgetManager) but the rule that decides whether it runs at all
 * can be, exactly. A debounce written inline in the updater would be the kind of thing this repo
 * has been burned by before: a silent early return in a loop, with no test and no log.
 *
 * ## Why a monotonic clock, not the wall clock
 *
 * Callers pass `SystemClock.elapsedRealtime()`. A wall-clock reading can jump — a timezone change,
 * an NTP correction, the user setting the date — and a backwards jump would lock refreshes out for
 * however far back it went, which on this code path means the widgets quietly stop updating and
 * nothing anywhere says so.
 *
 * ## Why it fails OPEN
 *
 * The FIRST call always runs, and any call far enough past the last one runs. The failure direction
 * of a debounce bug should be "refreshed more often than needed" (a wasted RemoteViews update),
 * never "stopped refreshing" (a widget showing yesterday's numbers with no way to tell).
 */
class WidgetRefreshDebouncer(private val windowMs: Long) {

    /** Far enough below any real `elapsedRealtime` that the first call is always outside the window. */
    private val lastRunAtMs = AtomicLong(NEVER)

    /**
     * Claims the next refresh slot, returning true when the caller should proceed.
     *
     * Atomic `compareAndSet`, not a plain read-then-write: `logEvent` is called from the
     * accessibility service's coroutines and from the walk-away path, so two threads can arrive
     * together. A racy check would let both through, which is harmless, or — with the write in the
     * wrong order — let neither through until the window elapsed twice, which is not.
     */
    fun tryAcquire(nowMs: Long): Boolean {
        while (true) {
            val last = lastRunAtMs.get()
            if (last != NEVER && nowMs - last < windowMs) return false
            if (lastRunAtMs.compareAndSet(last, nowMs)) return true
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
