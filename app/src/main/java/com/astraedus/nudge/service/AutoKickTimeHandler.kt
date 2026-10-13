package com.astraedus.nudge.service

import com.astraedus.nudge.domain.autokick.TimeKickEvaluator
import com.astraedus.nudge.domain.autokick.UseReminderEvaluator
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
 * **It also drives the usage reminder ([#77](https://github.com/astraedus/nudge/issues/77))**, through
 * [tick]: the reminder is measured on this exact clock, against this exact session baseline, so it
 * resets exactly when the kick does and one usage read per tick serves both. [shouldKick] is the
 * kick-only view of the same pass, for the web clock, whose entries never carry a reminder.
 *
 * Threading: [tick] and [shouldKick] perform a binder call and MUST run off the main thread. It
 * deliberately does not kick — it returns the verdict so the caller can dispatch the kick (which touches the
 * WindowManager) to the main thread. That split also keeps this class testable with plain fakes.
 */
class AutoKickTimeHandler(
    private val counterCache: CounterCacheRefresher,
    private val interactionTracker: InteractionTracker,
    private val usageProvider: UsageProvider,
    private val logger: NudgeLog
) {

    /** What one pass of the session clock asks the caller to do. */
    sealed interface Verdict {
        /** Nothing due (or nothing configured, or the clock was unreadable). */
        data object None : Verdict

        /** Run [AutoKickExecutor.kick] on the main thread. */
        data object Kick : Verdict

        /**
         * Post the usage reminder for [minutes] (the configured threshold). Already recorded as
         * this session's one reminder, so the caller posts it and nothing else.
         */
        data class Remind(val minutes: Int) : Verdict
    }

    /**
     * Reads the foreground-time clock for [packageName] and advances the session baseline.
     *
     * @return true when the session has reached the configured threshold and the caller must run
     *   [AutoKickExecutor.kick] on the main thread. False in every other case, including when the
     *   usage read fails, an unreadable clock must never kick the user out of an app.
     *   Never fires a reminder: this is the WEB clock's entry point, and web entries carry none
     *   (`CounterCacheRefresher.webEntriesFor`).
     */
    fun shouldKick(packageName: String): Boolean =
        evaluate(packageName, withReminder = false) == Verdict.Kick

    /**
     * One pass of the APP clock: the time-based kick and the usage reminder, from ONE usage read.
     *
     * The kick wins a tie: if both are due on the same tick, only [Verdict.Kick] comes back and the
     * reminder is left unrecorded (the kick resets the session anyway). A [Verdict.Remind] is marked
     * on [InteractionTracker] before it is returned, so a caller that fails to post it does not get
     * a second one 30s later -- one gentle notification per session, never a nag.
     */
    fun tick(packageName: String): Verdict = evaluate(packageName, withReminder = true)

    private fun evaluate(packageName: String, withReminder: Boolean): Verdict {
        val entry = counterCache.getEntry(packageName)
        val thresholdMinutes = entry?.autoKickAfterMinutes
        val remindMinutes = if (withReminder) entry?.remindAfterMinutes else null
        val reminderLive = UseReminderEvaluator.effectiveMinutes(remindMinutes, thresholdMinutes) != null
        if (thresholdMinutes == null && !reminderLive) {
            // Was a SILENT `?: return false`. The cache is refreshed from the DB on a 10s timer, so
            // a package can lose its entry mid-session (a rule edit, an empty refresh) and every
            // subsequent tick then returned false saying nothing at all, indistinguishable in
            // logcat from a clock that is ticking and simply has not reached its threshold, which is
            // exactly the ambiguity a device-QA cycle burned on here.
            logger.d("time auto-kick: no threshold configured package=$packageName")
            return Verdict.None
        }

        val usageMs = try {
            usageProvider.getDailyForegroundTimeMs(packageName)
        } catch (e: Exception) {
            logger.w("time auto-kick: usage read failed package=$packageName", e)
            return Verdict.None
        }

        val baselineMs = interactionTracker.getSessionUsageBaseline(packageName)
        val kick = TimeKickEvaluator.evaluate(thresholdMinutes, baselineMs, usageMs)
        val remind = UseReminderEvaluator.evaluate(
            remindAfterMinutes = remindMinutes,
            autoKickAfterMinutes = thresholdMinutes,
            baselineUsageMs = baselineMs,
            currentUsageMs = usageMs,
            alreadyReminded = interactionTracker.isSessionReminded(packageName)
        )

        // Baseline bookkeeping first, and once, whichever consumer asked for it: the two evaluators
        // read the same baseline with the same rules, so they agree on these two branches.
        if (kick == TimeKickEvaluator.Decision.START_SESSION ||
            remind == UseReminderEvaluator.Decision.START_SESSION
        ) {
            interactionTracker.setSessionUsageBaseline(packageName, usageMs)
            logger.d(
                "time auto-kick: session baseline set package=$packageName " +
                    "baseline=${usageMs}ms threshold=${thresholdMinutes}min remind=${remindMinutes}min"
            )
            return Verdict.None
        }
        if (kick == TimeKickEvaluator.Decision.REBASELINE ||
            remind == UseReminderEvaluator.Decision.REBASELINE
        ) {
            // Foreground total went backwards -- a day rollover cleared it. Not evidence of
            // overstaying, so re-baseline instead of kicking.
            interactionTracker.setSessionUsageBaseline(packageName, usageMs)
            logger.d("time auto-kick: baseline reset after backwards reading package=$packageName")
            return Verdict.None
        }

        val elapsedMs = usageMs - (baselineMs ?: usageMs)
        if (kick == TimeKickEvaluator.Decision.KICK) {
            logger.i(
                "time auto-kick: threshold reached package=$packageName " +
                    "sessionMs=$elapsedMs threshold=${thresholdMinutes}min"
            )
            return Verdict.Kick
        }

        if (remind == UseReminderEvaluator.Decision.REMIND && remindMinutes != null) {
            interactionTracker.markSessionReminded(packageName)
            logger.i(
                "usage reminder: threshold reached package=$packageName " +
                    "sessionMs=$elapsedMs remind=${remindMinutes}min"
            )
            return Verdict.Remind(remindMinutes)
        }

        // Also silent before. "Baseline set once, then nothing" is what a HEALTHY clock looks like
        // on this branch, so with no line here there was no way to tell a working clock from a dead
        // one without a build. The numbers are what make the next device run conclusive: if elapsed
        // is not growing, the READING is the problem; if there are no WAIT lines at all, the CLOCK is.
        logger.d(
            "time auto-kick: waiting package=$packageName elapsed=${elapsedMs}ms " +
                "threshold=${thresholdMinutes?.let { it.toLong() * 60_000L }}ms " +
                "remind=$remind usage=${usageMs}ms"
        )
        return Verdict.None
    }
}
