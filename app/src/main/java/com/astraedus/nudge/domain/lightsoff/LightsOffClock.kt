package com.astraedus.nudge.domain.lightsoff

import java.util.Calendar

/**
 * Clock helpers shared by everything that has to say "the lights are off until 7:00": the block
 * overlay's rule label, the persistent status notification, and the "start now until …" button.
 *
 * Pure JVM (only [Calendar]) so every case is unit-testable with an explicit "now" — no
 * `System.currentTimeMillis()` reads hidden inside a decision path.
 */
object LightsOffClock {

    /** Label prefix used on the block overlay and the status notification. */
    const val RULE_NAME = "Lights Off"

    /**
     * 24h `H:MM` rendering of a minute-of-day, e.g. `420` → `"7:00"`. Values outside 0..1439 wrap,
     * so `1440` renders as `"0:00"` rather than throwing.
     */
    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val normalized = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "${normalized / 60}:${(normalized % 60).toString().padStart(2, '0')}"
    }

    /** Same rendering for an absolute timestamp, in [now]'s timezone. */
    fun formatTimestamp(epochMs: Long, now: Calendar): String {
        val cal = (now.clone() as Calendar).apply { timeInMillis = epochMs }
        return formatMinuteOfDay(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
    }

    /**
     * The overlay / notification label: `"Lights Off · until 7:00"`, or just `"Lights Off"` when we
     * have no end time to promise (a schedule with no window, say). Never invents a time.
     */
    fun ruleName(untilLabel: String?): String =
        if (untilLabel.isNullOrBlank()) RULE_NAME else "$RULE_NAME · until $untilLabel"

    /**
     * Absolute time of the next occurrence of [minuteOfDay], strictly AFTER [now] — used by the
     * manual "Start Lights Off now until 07:00" button so the manual window ends at the profile's
     * normal wake time rather than at an arbitrary duration.
     */
    fun nextOccurrenceOf(minuteOfDay: Int, now: Calendar): Long {
        val normalized = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val candidate = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, normalized / 60)
            set(Calendar.MINUTE, normalized % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (candidate.timeInMillis <= now.timeInMillis) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate.timeInMillis
    }

    /**
     * True while a manual window is still open. `null`/past = closed. This is the ONLY place the
     * manual window's open/closed rule lives, so the engine inputs, the notification and the UI can
     * never disagree about it.
     */
    fun isManualWindowOpen(manualUntilMs: Long?, nowMs: Long): Boolean =
        manualUntilMs != null && manualUntilMs > nowMs

    private const val MINUTES_PER_DAY = 24 * 60
}
