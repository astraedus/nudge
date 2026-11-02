package com.astraedus.nudge.domain.nudge

/**
 * The whole-phone "you've been here a while" pause (issue #123, review #136).
 *
 * One SESSION runs from the first unlock until the screen goes off for longer than [GRACE_MS]. All
 * screen-on time counts, but only while the user is actually there: [onIdle] stops the clock after
 * [IDLE_MS] without a touch, so a screen left on over ADB, or a video nobody is touching, cannot
 * accumulate a sitting. [onInteraction] resumes it.
 *
 * A pause is due one [Config.thresholdMinutes] after the LAST pause (not on a fixed grid), which is
 * what stops a lowered threshold firing a catch-up burst. [onTimerFired] is given the interruption
 * decision from [PhoneNudgePolicy]: when it says no, the pause is deferred (retried) and does not
 * count as shown.
 *
 * The pause doubles from [Config.firstPauseSeconds] to [MAX_PAUSE_SECONDS]. In memory only; a
 * process death forgets the session, the right direction for a nudge.
 *
 * Pure Kotlin, no Android. The caller serialises access (the service calls it on the main thread);
 * [clock] is monotonic elapsed time in production.
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

    /** What the pause screen shows. [minutes] is the real session time, [seconds] how long it holds. */
    data class Pause(val minutes: Int, val seconds: Int)

    @Volatile
    private var config = Config()

    private var sessionStartMs: Long? = null
    private var segmentStartMs: Long? = null
    private var accumulatedMs: Long = 0L
    private var offAtMs: Long? = null
    private var idleAtMs: Long? = null
    private var lastInteractionMs: Long = 0L
    private var pausesShown: Int = 0
    private var lastPauseAtMs: Long = 0L

    /** True while a session is being tracked, including while it is idle. For tests and logs. */
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

    /** The device was unlocked. Starts a session, or resumes one after a brief blank or an idle. */
    @Synchronized
    fun onUnlocked() {
        if (!config.enabled) return
        val now = clock()
        when {
            sessionStartMs == null -> startLocked(now)
            offAtMs != null && now - offAtMs!! <= GRACE_MS -> resumeLocked(now)
            offAtMs != null -> startLocked(now)
            idleAtMs != null -> resumeLocked(now)
            segmentStartMs == null -> resumeLocked(now)
        }
        lastInteractionMs = now
    }

    /** A user-driven touch, scroll or text change. Resumes the clock after an idle stretch. */
    @Synchronized
    fun onInteraction() {
        if (!config.enabled) return
        if (sessionStartMs == null) return
        val now = clock()
        lastInteractionMs = now
        if (idleAtMs != null) {
            idleAtMs = null
            segmentStartMs = now
            armLocked()
        }
    }

    /**
     * No user interaction for [IDLE_MS]. The session is kept but its clock stops, so a screen left
     * on does not accumulate time. The next interaction resumes it.
     */
    @Synchronized
    fun onIdle() {
        if (!config.enabled) return
        val now = clock()
        val segment = segmentStartMs ?: return
        accumulatedMs += now - segment
        segmentStartMs = null
        idleAtMs = now
        cancelSchedule()
    }

    /** The screen went off: bank the screen-on time, stop the clock and the idle timer. */
    @Synchronized
    fun onScreenOff() {
        cancelSchedule()
        val now = clock()
        idleAtMs = null
        val segment = segmentStartMs ?: return
        accumulatedMs += now - segment
        segmentStartMs = null
        offAtMs = now
    }

    /**
     * The armed timer fired. Shows the pause when it is due AND the platform says it may interrupt;
     * otherwise defers and retries, without advancing the escalation.
     */
    @Synchronized
    fun onTimerFired(canInterrupt: Boolean) {
        if (!config.enabled) return
        if (segmentStartMs == null || sessionStartMs == null) return
        val now = clock()
        val elapsed = elapsedLocked(now)
        val due = lastPauseAtMs + thresholdMs()
        if (elapsed < due) {
            schedule(due - elapsed)
            return
        }
        if (!canInterrupt) {
            schedule(DEFER_MS)
            return
        }
        pausesShown++
        onPause(
            Pause(
                minutes = (elapsed / 60_000L).toInt().coerceAtLeast(1),
                seconds = pauseSeconds(pausesShown, config.firstPauseSeconds)
            )
        )
        lastPauseAtMs = elapsed
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
        idleAtMs = null
        lastInteractionMs = now
        pausesShown = 0
        lastPauseAtMs = 0L
        armLocked()
    }

    private fun resumeLocked(now: Long) {
        if (sessionStartMs == null) {
            startLocked(now)
            return
        }
        segmentStartMs = now
        offAtMs = null
        idleAtMs = null
        armLocked()
    }

    private fun clearLocked() {
        cancelSchedule()
        sessionStartMs = null
        segmentStartMs = null
        accumulatedMs = 0L
        offAtMs = null
        idleAtMs = null
        lastInteractionMs = 0L
        pausesShown = 0
        lastPauseAtMs = 0L
    }

    private fun armLocked() {
        if (segmentStartMs == null || sessionStartMs == null) return
        val due = lastPauseAtMs + thresholdMs()
        val remaining = due - elapsedLocked(clock())
        schedule(remaining.coerceAtLeast(0L))
    }

    private fun elapsedLocked(now: Long): Long {
        val segment = segmentStartMs ?: return accumulatedMs
        return accumulatedMs + (now - segment).coerceAtLeast(0L)
    }

    private fun thresholdMs(): Long = config.thresholdMinutes * 60_000L

    companion object {
        const val DEFAULT_THRESHOLD_MINUTES = 20
        const val DEFAULT_FIRST_PAUSE_SECONDS = 5

        const val MIN_THRESHOLD_MINUTES = 5
        const val MAX_THRESHOLD_MINUTES = 180
        const val MIN_PAUSE_SECONDS = 5
        const val MAX_PAUSE_SECONDS = 30

        /** A blank shorter than this continues the session, the same window `SittingTracker` uses. */
        const val GRACE_MS = 2 * 60_000L

        /** No touch for this long stops the session clock. A screen left on is not a sitting. */
        const val IDLE_MS = 2 * 60_000L

        /** How soon a deferred pause retries. */
        const val DEFER_MS = 30_000L

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
