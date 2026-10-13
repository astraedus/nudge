package com.astraedus.nudge.domain.autokick

/**
 * Pure decision logic for the usage reminder (`BlockRule.remindAfterMinutes`,
 * [#77](https://github.com/astraedus/nudge/issues/77)): "after N minutes in this app, tell me, but
 * don't kick me out".
 *
 * It is deliberately the time-based auto-kick's twin and reads the SAME session clock
 * ([TimeKickEvaluator]): one foreground-time baseline per session, taken from
 * `UsageProvider.getDailyForegroundTimeMs`, cleared in the same places (`InteractionTracker`'s
 * 5-minute expiry and the kick's `resetSession`). So the reminder and the kick can never disagree
 * about which sitting this is, time in other apps or with the screen off never counts, and a quick
 * tab-out-and-back continues the session instead of re-arming the reminder.
 *
 * The decisions mirror [TimeKickEvaluator.Decision] on purpose, plus the two things only a reminder
 * has:
 *  - **one per session**: once it has fired, [Decision.ALREADY_REMINDED] until the session resets;
 *  - **the kick wins**: when the same package also has a time-based kick at or below the reminder,
 *    the reminder is [Decision.SUPERSEDED_BY_KICK] and never fires. A notification announcing the
 *    minute the user is thrown out (or after it) says nothing the kick does not.
 *
 * No Android imports, fully unit-testable on the JVM.
 */
object UseReminderEvaluator {

    enum class Decision {
        /** No reminder configured (null or non-positive minutes). */
        OFF,

        /** A time-based auto-kick at or below the reminder threshold makes the reminder moot. */
        SUPERSEDED_BY_KICK,

        /** No baseline for the current session yet; the caller records the current reading. */
        START_SESSION,

        /** The reading went backwards (day rollover); re-baseline, never fire. */
        REBASELINE,

        /** This session already got its reminder. */
        ALREADY_REMINDED,

        /** Threshold not reached yet. */
        WAIT,

        /** Session foreground time has reached the threshold: post the reminder now. */
        REMIND
    }

    /**
     * The reminder threshold that can actually fire, or null when there is none: no reminder, a
     * non-positive one (a stored 0 must never mean "remind immediately", the same rule as the
     * kick's), or one the kick makes moot.
     *
     * A non-positive kick is treated as no kick, exactly as [TimeKickEvaluator] treats it, so a
     * disabled kick can never silently suppress a reminder.
     */
    fun effectiveMinutes(remindAfterMinutes: Int?, autoKickAfterMinutes: Int?): Int? {
        if (remindAfterMinutes == null || remindAfterMinutes <= 0) return null
        val kick = autoKickAfterMinutes?.takeIf { it > 0 } ?: return remindAfterMinutes
        return remindAfterMinutes.takeIf { it < kick }
    }

    /**
     * @param remindAfterMinutes `BlockRule.remindAfterMinutes` for this package (merged across rules).
     * @param autoKickAfterMinutes the package's time-based kick threshold, for precedence only.
     * @param baselineUsageMs the session's foreground-time baseline, or null if it has none yet.
     * @param currentUsageMs the foreground-time reading now.
     * @param alreadyReminded whether this session has already been reminded.
     */
    fun evaluate(
        remindAfterMinutes: Int?,
        autoKickAfterMinutes: Int?,
        baselineUsageMs: Long?,
        currentUsageMs: Long,
        alreadyReminded: Boolean
    ): Decision {
        if (remindAfterMinutes == null || remindAfterMinutes <= 0) return Decision.OFF
        val threshold = effectiveMinutes(remindAfterMinutes, autoKickAfterMinutes)
            ?: return Decision.SUPERSEDED_BY_KICK
        if (baselineUsageMs == null) return Decision.START_SESSION
        if (currentUsageMs < baselineUsageMs) return Decision.REBASELINE
        if (alreadyReminded) return Decision.ALREADY_REMINDED

        val elapsedMs = currentUsageMs - baselineUsageMs
        return if (elapsedMs >= threshold.toLong() * 60_000L) Decision.REMIND else Decision.WAIT
    }
}
