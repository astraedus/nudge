package com.astraedus.nudge.domain.autokick

/**
 * The words of one usage reminder ([#77](https://github.com/astraedus/nudge/issues/77)).
 *
 * The voice is the overlay's (`NudgeMessages`): a friend tapping your shoulder, never a scold. It
 * states a fact the user asked to be told, then asks one open question, and it never says
 * "too long", "wasted" or "again". Pure, so the exact copy is pinned by a JVM test.
 *
 * @param appLabel the user-visible app name ("Instagram"); falls back to the package name upstream.
 * @param minutes the reminder threshold the user configured, which is what they asked to hear back.
 *   It is the configured N rather than the measured elapsed time on purpose: the clock ticks every
 *   30s, so the measurement can read 20.4 minutes, and "20 min" is the number the user chose.
 */
data class UseReminder(val appLabel: String, val minutes: Int) {

    val title: String get() = "You've been on $appLabel for ${formatDuration(minutes)}"

    val body: String get() = BODY

    companion object {
        const val BODY = "Just a heads-up. Still what you came here for?"

        /** "20 min", "1 hr", "1 hr 30 min". */
        fun formatDuration(minutes: Int): String {
            val safe = minutes.coerceAtLeast(1)
            val hours = safe / 60
            val rest = safe % 60
            return when {
                hours == 0 -> "$rest min"
                rest == 0 -> "$hours hr"
                else -> "$hours hr $rest min"
            }
        }
    }
}
