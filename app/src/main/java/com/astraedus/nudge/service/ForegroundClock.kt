package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The periodic clock behind everything time-based: the time-based auto-kick and the time-remaining
 * overlay, for an app OR for a blocked website.
 *
 * Every other awareness path in this service is edge-triggered by accessibility events, which is
 * fine for counting taps and useless for a user watching passively. So there is a timer, and this
 * class owns it — one instance per clock, at most one job each.
 *
 * **Why this is a class and not two `while (isActive)` loops in the service.** Both loops used to be
 * written inline, and both had the same two holes:
 *
 * 1. **One exception ended the clock permanently, in silence.** The loop body reaches a binder read
 *    and the WindowManager; anything that throws outside `AutoKickTimeHandler`'s own try/catch left
 *    `while` for good. Nothing logged, nothing restarted it, and the user simply never got kicked
 *    again — a dead watchdog that still looks alive from the outside. A tick is now individually
 *    guarded: a failure is logged and the NEXT tick still happens.
 * 2. **Nothing was logged at all.** Not the start, not the stop, not the reason. Combined with the
 *    silent `false` returns downstream, "the clock is ticking and simply hasn't reached the
 *    threshold" and "the clock has been dead for ten minutes" produced *identical* logcat: nothing.
 *    That ambiguity is what a device-QA cycle burned on here, and it is the same ambiguity that cost
 *    the v1.12.0 picture-in-picture release (see `docs/architecture/foreground-detection.md`). Every
 *    transition is now logged unconditionally, with the reason.
 *
 * [start] is idempotent per key: re-calling it for the key already running leaves the job alone.
 * That matters because the service re-enters its evaluation on debounced events and on the issue-#7
 * content-change fallback — restarting the job each time would keep resetting [delay] and the clock
 * would never actually reach a tick.
 */
class ForegroundClock(
    private val scope: CoroutineScope,
    private val tickIntervalMs: Long,
    private val logger: NudgeLog,
    /** Name used in logs, so the app clock and the web clock are tellable apart in logcat. */
    private val label: String
) {

    /** The package (or `web:` key) currently being clocked, or null when the clock is stopped. */
    @Volatile
    var trackedKey: String? = null
        private set

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Start clocking [key], or leave a live clock for the same key alone.
     *
     * The first tick runs immediately, so a session's baseline is taken at (near) session start
     * rather than one interval in.
     */
    fun start(key: String, tick: suspend (String) -> Unit) {
        if (key == trackedKey && isRunning) return

        stop("restart")
        trackedKey = key
        logger.i("$label clock started key=$key intervalMs=$tickIntervalMs")
        job = scope.launch {
            var exitReason = "cancelled"
            try {
                while (isActive) {
                    try {
                        tick(key)
                    } catch (e: CancellationException) {
                        // Cooperative cancellation is not a failure — let it end the loop.
                        throw e
                    } catch (e: Throwable) {
                        // The whole point of this class: one bad tick must not end the clock.
                        logger.w("$label clock tick failed key=$key — continuing", e)
                    }
                    delay(tickIntervalMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                exitReason = "crashed"
                logger.e("$label clock crashed key=$key", e)
                throw e
            } finally {
                logger.i("$label clock exited key=$key reason=$exitReason")
            }
        }
    }

    /** Stop the clock. [reason] is logged, because "why did it stop" was the unanswerable question. */
    fun stop(reason: String) {
        val key = trackedKey
        val had = job != null
        job?.cancel()
        job = null
        trackedKey = null
        if (had) logger.i("$label clock stopped key=$key reason=$reason")
    }
}
