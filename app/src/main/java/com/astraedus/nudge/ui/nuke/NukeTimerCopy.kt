package com.astraedus.nudge.ui.nuke

import com.astraedus.nudge.domain.nuke.NukeTimer

/** Every string the Nuke timer (#184) shows, as pure functions so the copy has a JVM test. */
object NukeTimerCopy {

    /** "24 min", "1 h 5 min", "2 h". Rounded UP, so it never says "0 min" while time is left. */
    fun timeLeft(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$rest min"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** What a correct scan gets while the timer runs. */
    fun refusal(waitMs: Long): String = "Your timer has ${timeLeft(waitMs)} left. Scan again when it ends."

    /** The status card's line under "Nuke is on": null with no timer. */
    fun statusLine(leftMs: Long?): String? = when {
        leftMs == null -> null
        leftMs > 0 -> "${timeLeft(leftMs)} left"
        else -> "Timer done"
    }

    /** The picker's label for a choice in [NukeTimer.CHOICES_MINUTES]. */
    fun choiceLabel(minutes: Int): String = when {
        minutes <= 0 -> "None"
        minutes % 60 == 0 && minutes / 60 == 1 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes min"
    }
}
