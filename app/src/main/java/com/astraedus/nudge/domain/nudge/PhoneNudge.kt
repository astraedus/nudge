package com.astraedus.nudge.domain.nudge

/**
 * The whole-phone "you've been here a while" pause (issue #123).
 *
 * One SESSION runs from the first unlock of the day's use until the screen goes off, with a grace
 * window for a quick blank (checking the time, glancing at a notification) so five seconds off does
 * not reset the count. All screen-on time in the session counts, across every app, because the
 * failure this exists for is drifting between apps that no per-app rule ever sees.
 *
 * A pause is due every [Config.thresholdMinutes] of session time. The pause length doubles each
 * time from [Config.firstPauseSeconds] and is capped at [MAX_PAUSE_SECONDS]: five seconds at twenty
 * minutes, ten at forty, twenty at sixty, then thirty from there. A fixed five seconds gets dismissed
 * on reflex within a week.
 *
 * In memory only. A process death forgets the session, which is the right direction for a nudge and
 * the same call [com.astraedus.nudge.domain.bounce.BounceDetector] makes.
 *
 * Pure Kotlin, no Android. The caller serialises access (the accessibility service calls it on the
 * main thread); [clock] is monotonic elapsed time in production.
 */
class PhoneNudge(
    private val clock: () -> Long,
    private val onPause: (Pause) -> Unit,
    private val schedule: (delayMs: Long) -> Unit,
    private val cancelSchedule: () -> Unit
) {

    /** User settings. Bounds are the UI's ranges, validated here so an import cannot bypass them. */
    data class Config(
        val enabled: Boolean = false,
        val thresholdMinutes: Int = DEFAULT_THRESHOLD_MINUTES,
        val firstPauseSeconds: Int = DEFAULT_FIRST_PAUSE_SECONDS
    ) {
        init {
            require(thresholdMinutes in MIN_THRESHOLD_MINUTES..MAX_THRESHOLD_MINUTES) {
                "thresholdMinutes must be in $MIN_THRESHOLD_MINUTES..$MAX_THRESHOLD_MINUTES"
            }
            require(firstPauseSeconds in MIN_PAUSE_SECONDS..MAX_PAUSE_SECONDS) {
                "firstPauseSeconds must be in $MIN_PAUSE_SECONDS..$MAX_PAUSE_SECONDS"
            }
        }
    }

    /** What the pause screen shows. [minutes] is the session length, [seconds] how long it holds. */
    data class Pause(val minutes: Int, val seconds: Int)

    @Volatile
    private var config = Config()

    private var sessionStartMs: Long? = null
    private var segmentStartMs: Long? = null
    private var accumulatedMs: Long = 0L
    private var offAtMs: Long? = null
    private var pausesShown: Int = 0

    /** True while a session is being tracked. For tests and logs. */
    val isActive: Boolean @Synchronized get() = sessionStartMs != null

    @Synchronized
    fun setConfig(value: Config) {
        config = value
        if (!value.enabled) {
            clearLocked()
        } else if (sessionStartMs != null && segmentStartMs != null) {
            // Re-arm against the current threshold, so changing it takes effect in this session.
            cancelSchedule()
            armLocked()
        }
    }

    /** The device was unlocked. Starts a session, or resumes one inside the grace window. */
    @Synchronized
    fun onUnlocked() {
        if (!config.enabled) return
        val now = clock()
        val off = offAtMs
        when {
            sessionStartMs == null -> startLocked(now)
            off != null && now - off <= GRACE_MS -> resumeLocked(now)
            off != null -> startLocked(now)
            segmentStartMs == null -> resumeLocked(now)
        }
    }

    /**
     * The screen came on. A brief blank resumes the session; a long one ends it, and the next
     * unlock starts a fresh one. A locked screen is not "using the phone", so this never starts a
     * session on its own.
     */
    @Synchronized
    fun onScreenOn() {
        if (!config.enabled) return
        val off = offAtMs ?: return
        val now = clock()
        if (sessionStartMs == null) return
        if (now - off <= GRACE_MS) resumeLocked(now) else clearLocked()
    }

    /** The screen went off: bank the screen-on time and stop the clock. */
    @Synchronized
    fun onScreenOff() {
        cancelSchedule()
        val now = clock()
        val segment = segmentStartMs ?: return
        accumulatedMs += now - segment
        segmentStartMs = null
        offAtMs = now
    }

    /** The armed timer fired. Shows the pause when it is due, then arms the next one. */
    @Synchronized
    fun onTimerFired() {
        if (!config.enabled) return
        if (segmentStartMs == null || sessionStartMs == null) return
        val now = clock()
        val elapsed = elapsedLocked(now)
        val due = dueMsLocked(pausesShown + 1)
        if (elapsed < due) {
            schedule(due - elapsed)
            return
        }
        pausesShown++
        onPause(
            Pause(
                minutes = pausesShown * config.thresholdMinutes,
                seconds = pauseSeconds(pausesShown, config.firstPauseSeconds)
            )
        )
        armLocked()
    }

    /** Forget the session and the escalation. Used when the feature or Nudge is switched off. */
    @Synchronized
    fun reset() {
        clearLocked()
    }

    private fun startLocked(now: Long) {
        sessionStartMs = now
        segmentStartMs = now
        accumulatedMs = 0L
        offAtMs = null
        pausesShown = 0
        armLocked()
    }

    private fun resumeLocked(now: Long) {
        if (sessionStartMs == null) {
            startLocked(now)
            return
        }
        segmentStartMs = now
        offAtMs = null
        armLocked()
    }

    private fun clearLocked() {
        cancelSchedule()
        sessionStartMs = null
        segmentStartMs = null
        accumulatedMs = 0L
        offAtMs = null
        pausesShown = 0
    }

    private fun armLocked() {
        if (segmentStartMs == null || sessionStartMs == null) return
        val due = dueMsLocked(pausesShown + 1)
        val remaining = due - elapsedLocked(clock())
        schedule(remaining.coerceAtLeast(0L))
    }

    private fun elapsedLocked(now: Long): Long {
        val segment = segmentStartMs ?: return accumulatedMs
        return accumulatedMs + (now - segment).coerceAtLeast(0L)
    }

    private fun dueMsLocked(index: Int): Long =
        index.toLong() * config.thresholdMinutes * 60_000L

    companion object {
        const val DEFAULT_THRESHOLD_MINUTES = 20
        const val DEFAULT_FIRST_PAUSE_SECONDS = 5

        const val MIN_THRESHOLD_MINUTES = 5
        const val MAX_THRESHOLD_MINUTES = 180
        const val MIN_PAUSE_SECONDS = 3
        const val MAX_PAUSE_SECONDS = 30

        /** A blank shorter than this continues the session, the same window `SittingTracker` uses. */
        const val GRACE_MS = 2 * 60_000L

        /**
         * The pause for threshold [index] (1-based). Doubles from [firstSeconds], capped at
         * [MAX_PAUSE_SECONDS]: 5, 10, 20, 30, 30...
         */
        fun pauseSeconds(index: Int, firstSeconds: Int): Int {
            if (index <= 1) return firstSeconds.coerceIn(MIN_PAUSE_SECONDS, MAX_PAUSE_SECONDS)
            var seconds = firstSeconds
            repeat(index - 1) {
                seconds *= 2
                if (seconds >= MAX_PAUSE_SECONDS) return MAX_PAUSE_SECONDS
            }
            return seconds.coerceAtMost(MAX_PAUSE_SECONDS)
        }
    }
}
