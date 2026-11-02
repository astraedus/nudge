package com.astraedus.nudge.ui.components.rule

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.ExceptCsv

/**
 * The one way a rule is put into words on a list: "Delay 15s Mon–Fri 9:00–17:00 · 30min limit".
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
     * Each rule reads as its mode, then its daily limit. A rule's schedule belongs to that rule, so
     * it follows the rule's mode with a plain space ("Hard Block Sat, Sun"); " · " only ever
     * separates one rule, or a limit, from the next. Joined by the separator instead, an app with
     * two rules read as two modes and a stray schedule ("Delay 30s · Hard Block · Sat, Sun",
     * orchestrator review of PR 3). An app's rules are ordered the way App config lays them out:
     * the always-on app rule, then scheduled app rules, then the per-feature rules
     * ("Reels: Hard Block").
     *
     * Examples:
     *   "Hard Block"
     *   "Delay 15s · 30min limit"
     *   "Delay 15s · Reels: Hard Block"
     *   "Hard Block Mon–Fri 9:00–17:00"
     *   "Delay 30s · Hard Block Sat, Sun" (an always-on rule and a weekend rule)
     *   "30min limit" (a NONE rule that only caps the day)
     *   "30min limit Sat, Sun" (the same, at weekends: the schedule follows the limit)
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
            val pieces = listOfNotNull(
                // A NONE rule with a limit is only a limit: "30min limit", not "Not blocked · 30min limit".
                when {
                    features != null -> "$features: $mode"
                    rule.mode == NONE && rule.dailyLimitMinutes != null -> null
                    else -> mode
                },
                rule.dailyLimitMinutes?.let { "${it}min limit" }
            )
            // The schedule joins the rule's first piece (its mode, or its limit when that is all it
            // is) with a space, so it can never read as a rule of its own.
            val schedule = scheduleText(rule) ?: return@flatMap pieces
            listOf("${pieces.first()} $schedule") + pieces.drop(1)
        }

        return if (parts.isEmpty()) "Configured" else parts.joinToString(SEPARATOR)
    }

    /**
     * The second line of an app that has no rule of its own but is in a group that is blocking:
     * "In Social", "In Social, Work", "In 3 groups". Null when no such group (the app is not
     * blocked, and the line must not suggest it is). [groupNames] are the covering groups in the
     * order they should read.
     */
    fun coveredByText(groupNames: List<String>): String? = when (groupNames.size) {
        0 -> null
        1, 2 -> "In ${groupNames.joinToString(", ")}"
        else -> "In ${groupNames.size} groups"
    }

    /** A group row's title on Manage Apps: "Social · 9 apps", "Work · 1 app". */
    fun groupTitle(name: String, memberCount: Int): String =
        "$name$SEPARATOR$memberCount ${if (memberCount == 1) "app" else "apps"}"

    /**
     * An All apps rule's title, everywhere it is named (Manage Apps, the Home lock card, the block
     * screen's rule line, App config's "Also covered by"): "All apps", "All apps except Spotify",
     * "All apps except Work, Spotify", "All apps except Work, Spotify +3".
     *
     * [allowedNames] are the allowed groups' names, then the allowed apps' labels, in that order
     * (groups first, as the editor lists them). The caller passes only names it can resolve: an
     * allowed app that is not installed is left out, as the editor hides it.
     */
    fun allAppsTitle(allowedNames: List<String>): String = when (allowedNames.size) {
        0 -> ALL_APPS
        1, 2 -> "$ALL_APPS except ${allowedNames.joinToString(", ")}"
        else -> "$ALL_APPS except ${allowedNames.take(2).joinToString(", ")} +${allowedNames.size - 2}"
    }

    /**
     * [allAppsTitle] for a rule row: its allowed groups that still exist ([groupNames], by id, in
     * the order the rule lists them), then its allowed apps that are installed ([appLabels], by
     * package). One place resolves the names, so Manage Apps, App config, the group editor and the
     * Home card cannot name one rule two ways.
     */
    fun allAppsTitle(
        rule: BlockRule,
        groupNames: Map<Long, String>,
        appLabels: Map<String, String>
    ): String = allAppsTitle(
        ExceptCsv.groupIds(rule.exceptGroupIds).mapNotNull { groupNames[it] } +
            ExceptCsv.packages(rule.exceptPackages).mapNotNull { appLabels[it] }
    )

    /** [allAppsTitle] for the domain form of a rule, whose allowed groups are already named. */
    fun allAppsTitle(rule: BlockRuleData, appLabels: Map<String, String>): String = allAppsTitle(
        rule.exceptGroupNames + rule.exceptPackages.mapNotNull { appLabels[it] }
    )

    /**
     * An All apps rule's one summary line: its mode and schedule in the words every rule uses
     * ("Hard Block Mon–Fri 9:00–17:00", "Delay 15s"; "All the time" is left out). An All apps
     * rule has no daily limit and no in-app features (spec 3.4), so nothing else can appear.
     */
    fun allAppsSummaryText(rule: BlockRule): String = buildSummaryText(listOf(rule))

    /**
     * "Until 8:42 AM" / "Until 17:00": the end of the window a scheduled block is in (spec 3.8, 3.9).
     * [clockText] is the time already formatted in the user's 12/24-hour setting (the caller has the
     * Android `DateFormat`; this stays pure).
     */
    fun untilText(clockText: String): String = "Until $clockText"

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
    fun scheduleText(rule: BlockRule): String? {
        val days = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        val firstUnlockMinutes = rule.firstUnlockMinutes
        if (firstUnlockMinutes != null) return firstUnlockText(days, firstUnlockMinutes)
        return scheduleText(
            days = days,
            startMinute = rule.scheduleStartMinute,
            endMinute = rule.scheduleEndMinute
        )
    }

    /**
     * A first-unlock rule's window in words: "30min after first unlock", "1h after first unlock on
     * Mon–Fri" never happens — the duration is always minutes, like "30min limit" above, so the
     * summary cannot read as two different durations for one rule.
     *
     * The days are the logical day's (spec 3.5), which is what the engine checks; no time range is
     * shown because the window's start is the user's own unlock, not a clock time.
     */
    fun firstUnlockText(days: List<Int>?, minutes: Int): String {
        val dayText = dayText(days)
        val window = "${minutes}min after first unlock"
        return if (dayText == null) window else "$window on $dayText"
    }

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

    private const val ALL_APPS = "All apps"

    private val DAY_NAMES = mapOf(
        1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun"
    )
}
