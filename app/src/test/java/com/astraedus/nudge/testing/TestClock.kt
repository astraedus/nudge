package com.astraedus.nudge.testing

/**
 * A controllable millisecond clock for unit tests: tests never read the wall clock and never sleep.
 *
 * Production takes time as a `() -> Long` (see `InteractionHandler`) or a `nowMs: Long`
 * parameter, so hand this over as `clock.asSource()` or `clock.now`.
 */
class TestClock(start: Long = DEFAULT_START_MS) {

    /** The current instant, in epoch milliseconds. */
    var now: Long = start
        private set

    fun advanceBy(ms: Long) {
        now += ms
    }

    fun set(ms: Long) {
        now = ms
    }

    /** A `() -> Long` view that always reads the current value. */
    fun asSource(): () -> Long = { now }

    companion object {
        /** 2023-11-14T22:13:20Z: a fixed, arbitrary instant. */
        const val DEFAULT_START_MS = 1_700_000_000_000L
    }
}
