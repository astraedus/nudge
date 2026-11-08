package com.astraedus.nudge.service

import com.astraedus.nudge.testing.TestClock

/**
 * [ScheduleBoundaryWatch.Scheduler] for JVM tests: holds the ONE pending task and how long it asked
 * to wait, and runs it when the test says the wait is over. [posts] counts every post, which is how
 * a test proves there is one timer, not a tick.
 */
class FakeBoundaryScheduler(private val clock: TestClock) : ScheduleBoundaryWatch.Scheduler {
    var pendingDelayMs: Long? = null
        private set
    private var task: (() -> Unit)? = null
    var posts = 0
        private set

    override fun post(delayMs: Long, task: () -> Unit) {
        posts++
        pendingDelayMs = delayMs
        this.task = task
    }

    override fun cancel() {
        pendingDelayMs = null
        task = null
    }

    /** Let the posted wait elapse on the wall clock too (the device stayed awake), then run it. */
    fun elapse() {
        val wait = checkNotNull(pendingDelayMs) { "nothing is posted" }
        clock.advanceBy(wait)
        runNow()
    }

    /** Run the posted task WITHOUT moving the clock: uptime ran ahead of the wall clock. */
    fun runNow() {
        val run = checkNotNull(task) { "nothing is posted" }
        task = null
        pendingDelayMs = null
        run()
    }
}
