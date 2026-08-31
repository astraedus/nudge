package com.astraedus.nudge.service

import com.astraedus.nudge.domain.autokick.TimeKickEvaluator
import com.astraedus.nudge.domain.logging.NudgeLog

/**
 * The TIME-based auto-kick trigger: "use this app for 30 minutes, then it kicks you out"
 * ([issue #6](https://github.com/astraedus/nudge/issues/6)).
 *
 * The interaction trigger counts accessibility events, so it is blind to passive watching — a user
 * can sit through an hour of autoplaying video without producing a single tap or scroll. This
 * trigger therefore reads a clock. It reuses the one Nudge already has: the UsageStatsManager
 * foreground-time reading behind [UsageProvider] that daily limits and the time-remaining overlay
 * are built on. Nothing new polls the system.
 *
 * Threading: [shouldKick] performs a binder call and MUST run off the main thread. It deliberately
 * does not kick — it returns the verdict so the caller can dispatch the kick (which touches the
 * WindowManager) to the main thread. That split also keeps this class testable with plain fakes.
 */
class AutoKickTimeHandler(
    private val counterCache: CounterCacheRefresher,
    private val interactionTracker: InteractionTracker,
    private val usageProvider: UsageProvider,
    private val logger: NudgeLog
) {

    /**
     * Reads the foreground-time clock for [packageName] and advances the session baseline.
     *
     * @return true when the session has reached the configured threshold and the caller must run
     *   [AutoKickExecutor.kick] on the main thread. False in every other case, including when the
     *   usage read fails — an unreadable clock must never kick the user out of an app.
     */
    fun shouldKick(packageName: String): Boolean {
        val thresholdMinutes = counterCache.getEntry(packageName)?.autoKickAfterMinutes
        if (thresholdMinutes == null) {
            // Was a SILENT `?: return false`. The cache is refreshed from the DB on a 10s timer, so
            // a package can lose its entry mid-session (a rule edit, an empty refresh) and every
            // subsequent tick then returned false saying nothing at all, indistinguishable in
            // logcat from a clock that is ticking and simply has not reached its threshold, which is
            // exactly the ambiguity a device-QA cycle burned on here.
            logger.d("time auto-kick: no threshold configured package=$packageName")
            return false
        }

        val usageMs = try {
            usageProvider.getDailyForegroundTimeMs(packageName)
        } catch (e: Exception) {
            logger.w("time auto-kick: usage read failed package=$packageName", e)
            return false
        }

        val baselineMs = interactionTracker.getSessionUsageBaseline(packageName)

        return when (TimeKickEvaluator.evaluate(thresholdMinutes, baselineMs, usageMs)) {
            TimeKickEvaluator.Decision.DISABLED -> false

            TimeKickEvaluator.Decision.START_SESSION -> {
                interactionTracker.setSessionUsageBaseline(packageName, usageMs)
                logger.d(
                    "time auto-kick: session baseline set package=$packageName " +
                        "baseline=${usageMs}ms threshold=${thresholdMinutes}min"
                )
                false
            }

            TimeKickEvaluator.Decision.REBASELINE -> {
                // Foreground total went backwards -- a day rollover cleared it. Not evidence of
                // overstaying, so re-baseline instead of kicking.
                interactionTracker.setSessionUsageBaseline(packageName, usageMs)
                logger.d("time auto-kick: baseline reset after backwards reading package=$packageName")
                false
            }

            TimeKickEvaluator.Decision.WAIT -> {
                // Also silent before. "Baseline set once, then nothing" is what a HEALTHY clock
                // looks like on this branch, so with no line here there was no way to tell a
                // working clock from a dead one without a build. The numbers are what make the next
                // device run conclusive: if elapsed is not growing, the READING is the problem; if
                // there are no WAIT lines at all, the CLOCK is.
                logger.d(
                    "time auto-kick: waiting package=$packageName " +
                        "elapsed=${usageMs - (baselineMs ?: usageMs)}ms " +
                        "threshold=${thresholdMinutes.toLong() * 60_000L}ms usage=${usageMs}ms"
                )
                false
            }

            TimeKickEvaluator.Decision.KICK -> {
                logger.i(
                    "time auto-kick: threshold reached package=$packageName " +
                        "sessionMs=${usageMs - (baselineMs ?: usageMs)} threshold=${thresholdMinutes}min"
                )
                true
            }
        }
    }
}
