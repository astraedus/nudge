package com.astraedus.nudge.ui.nuke

import com.astraedus.nudge.domain.nuke.NukeTimer

/**
 * Every string the Nuke timer (#184) shows, as pure functions so the copy has a JVM test.
 *
 * The words say what the timer DOES: it locks the code, so the code can't end Nuke yet. Nuke itself
 * does not turn off when it runs out ("Timer" alone read that way on the device, round 1).
 */
object NukeTimerCopy {

    /** The picker row's label in the off card. */
    const val PICKER_LABEL = "Lock code for"

    /** "24 min", "1 h 5 min", "2 h". Rounded UP, so it never says "0 min" while time is left. */
    fun timeLeft(ms: Long): String {
        val (hours, rest) = hoursAndMinutes(ms)
        return when {
            hours == 0L -> "$rest min"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** What a correct scan gets while the code is locked. */
    fun refusal(waitMs: Long): String = "Your code is locked for ${moreTime(waitMs)}. Scan again after that."

    /**
     * The status card's line under "Nuke is on" while the code is locked. Null with no timer AND once
     * it has run out: from then on the card is the plain ON card.
     */
    fun statusLine(leftMs: Long?): String? =
        if (leftMs == null || leftMs <= 0) null else "Code locked · ${timeLeft(leftMs)} left"

    /** The picker's label for a choice in [NukeTimer.CHOICES_MINUTES]. */
    fun choiceLabel(minutes: Int): String = when {
        minutes <= 0 -> "None"
        minutes % 60 == 0 && minutes / 60 == 1 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes min"
    }

    /** "15 more min", "1 more hour", "2 more hours", "1 h 5 min more". Rounded up like [timeLeft]. */
    private fun moreTime(ms: Long): String {
        val (hours, rest) = hoursAndMinutes(ms)
        return when {
            hours == 0L -> "$rest more min"
            rest == 0L -> if (hours == 1L) "1 more hour" else "$hours more hours"
            else -> "$hours h $rest min more"
        }
    }

    private fun hoursAndMinutes(ms: Long): Pair<Long, Long> {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        return minutes / 60 to minutes % 60
    }
}
