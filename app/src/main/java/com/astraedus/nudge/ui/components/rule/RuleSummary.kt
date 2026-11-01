package com.astraedus.nudge.ui.components.rule

import com.astraedus.nudge.data.db.entity.BlockRule

/**
 * The one way a rule is put into words on a list: "Delay 15s · 30min limit · Mon–Fri 9:00–17:00".
 *
 * Manage Apps (app rows and group rows) and App config's "Also covered by" all read their summary
 * lines from here, so one rule never reads two ways on two screens (spec 3.2, "one format
 * everywhere"). Pure: rule rows in, text out.
 */
object RuleSummary {

    private const val SEPARATOR = " · "

    /**
     * Every rule row of ONE target (an app's direct rules, or a group's own rule) as one line.
     *
     * Each rule reads as its mode, its daily limit and its schedule. An app's rules are ordered the
     * way App config lays them out: the always-on app rule, then scheduled app rules, then the
     * per-feature rules ("Reels: Hard Block").
     *
     * Examples:
     *   "Hard Block"
     *   "Delay 15s · 30min limit"
     *   "Delay 15s · Reels: Hard Block"
     *   "Breathing 30s · Mon–Fri 9:00–17:00"
     *   "30min limit" (a NONE rule that only caps the day)
     */
    fun buildSummaryText(rules: List<BlockRule>): String {
        val (featureRules, appRules) = rules.partition { !it.inAppFeatures.isNullOrBlank() }
        val ordered = appRules.sortedBy { scheduleText(it) != null } + featureRules

        val parts = ordered.flatMap { rule ->
            val features = rule.inAppFeatures
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("/") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            val mode = formatMode(rule.mode, rule.delaySeconds)
            listOfNotNull(
                // A NONE rule with a limit is only a limit: "30min limit", not "Not blocked · 30min limit".
                when {
                    features != null -> "$features: $mode"
                    rule.mode == NONE && rule.dailyLimitMinutes != null -> null
                    else -> mode
                },
                rule.dailyLimitMinutes?.let { "${it}min limit" },
                scheduleText(rule)
            )
        }

        return if (parts.isEmpty()) "Configured" else parts.joinToString(SEPARATOR)
    }

    /** A group row's title on Manage Apps: "Social · 9 apps", "Work · 1 app". */
    fun groupTitle(name: String, memberCount: Int): String =
        "$name$SEPARATOR$memberCount ${if (memberCount == 1) "app" else "apps"}"

    /**
     * A group row's one summary line: its own rule in the same words an app's rules use
     * ("Delay 15s · 30min limit"), or "Not blocked" for a group with no rule of its own (an
     * imported one, say). Whether the rule is on is the row's switch, not this text.
     */
    fun groupSummaryText(ownRule: BlockRule?): String =
        if (ownRule == null) "Not blocked" else buildSummaryText(listOf(ownRule))

    /**
     * When [rule] is active, in words: "Mon–Fri 9:00–17:00", "Sat, Sun", "Every day 22:00–6:00".
     * Null when it is active all the time, which a summary leaves out (spec 3.2).
     *
     * Follows [com.astraedus.nudge.domain.engine.ScheduleEvaluator] exactly, so the words never
     * claim a window the engine does not enforce: a time window needs BOTH ends, and no days (or
     * all seven) means every day.
     */
    fun scheduleText(rule: BlockRule): String? = scheduleText(
        days = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
        startMinute = rule.scheduleStartMinute,
        endMinute = rule.scheduleEndMinute
    )

    /** [scheduleText] on raw values; ISO days (1 = Mon .. 7 = Sun), minutes from midnight. */
    fun scheduleText(days: List<Int>?, startMinute: Int?, endMinute: Int?): String? {
        val dayText = dayText(days)
        val timeText = if (startMinute != null && endMinute != null) {
            "${clock(startMinute)}–${clock(endMinute)}"
        } else {
            null
        }
        return when {
            dayText == null && timeText == null -> null
            dayText == null -> "Every day $timeText"
            timeText == null -> dayText
            else -> "$dayText $timeText"
        }
    }

    /** "Mon–Fri", "Sat, Sun", "Mon, Wed, Fri", "Mon–Wed, Sat"; null for every day. */
    private fun dayText(days: List<Int>?): String? {
        val picked = days.orEmpty().filter { it in 1..7 }.toSortedSet()
        if (picked.isEmpty() || picked.size == 7) return null

        val runs = mutableListOf<IntRange>()
        for (day in picked) {
            val last = runs.lastOrNull()
            if (last != null && last.last == day - 1) runs[runs.lastIndex] = last.first..day
            else runs += day..day
        }
        // A run of three or more reads as a range; two days read better as a pair ("Sat, Sun").
        return runs.joinToString(", ") { run ->
            if (run.last - run.first >= 2) "${DAY_NAMES[run.first]}–${DAY_NAMES[run.last]}"
            else run.joinToString(", ") { DAY_NAMES.getValue(it) }
        }
    }

    /** 540 -> "9:00", 1020 -> "17:00", 0 -> "0:00". */
    private fun clock(minute: Int): String {
        val m = minute.mod(24 * 60)
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** The mode in words. Every [com.astraedus.nudge.domain.model.BlockMode] has a branch. */
    private fun formatMode(mode: String, delaySeconds: Int): String = when (mode) {
        // The app itself is not gated; only this rule's feature overrides and daily limit are.
        NONE -> "Not blocked"
        "HARD_BLOCK" -> "Hard Block"
        "DELAY" -> "Delay ${delaySeconds}s"
        "HOLD" -> "Hold ${delaySeconds}s"
        "BREATHING" -> "Breathing ${delaySeconds}s"
        else -> mode
    }

    private const val NONE = "NONE"

    private val DAY_NAMES = mapOf(
        1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun"
    )
}
