package com.astraedus.nudge.ui.screens.groups

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.lock.GroupUsage
import com.astraedus.nudge.domain.lock.GroupWeakening
import com.astraedus.nudge.domain.lock.RuleWeakening
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.ui.components.rule.ScheduleKind

/**
 * The editable half of a group's own rule: what the "Block these apps" switch, How and When show.
 *
 * Pure, so the round trip (rule -> form -> rule) and the Strict Mode decision are JVM-tested
 * without a screen. A group has at most one rule of its own in v1 (spec 3.3), and the editor always
 * writes it, with [blockEnabled] as its `enabled` flag: switching blocking off keeps the How and When
 * values for the next time it is switched on.
 */
data class GroupRuleForm(
    val blockEnabled: Boolean = true,
    val mode: BlockMode = BlockMode.DELAY,
    val waitSeconds: Int = 15,
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitMinutes: Int = 30,
    val scheduled: Boolean = false,
    val scheduleDays: Set<Int> = WEEKDAYS,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
    val firstUnlock: Boolean = false,
    val firstUnlockMinutes: Int = 30,
    /**
     * Whether the user (or the saved rule) has chosen days, so switching the kind must not quietly
     * replace them. False on a new rule, true once a day chip is tapped or a saved rule is loaded.
     */
    val daysTouched: Boolean = false
) {
    /**
     * A schedule of any kind with no day picked would never be active; Save waits for a day.
     * "All the time" is always complete.
     */
    val isComplete: Boolean
        get() = (!scheduled && !firstUnlock) || scheduleDays.isNotEmpty()

    /**
     * The same form switched to another "When" kind. Switching to After first unlock on a rule whose
     * days were never chosen selects every day, because Morning Lock is a daily thing; an existing
     * rule, or one whose days the user edited, keeps them (decision 2026-11-05).
     */
    fun withKind(kind: ScheduleKind): GroupRuleForm = when (kind) {
        ScheduleKind.ALL_TIME -> copy(scheduled = false, firstUnlock = false)
        ScheduleKind.DAYS_TIMES -> copy(scheduled = true, firstUnlock = false)
        ScheduleKind.FIRST_UNLOCK -> copy(
            scheduled = false,
            firstUnlock = true,
            scheduleDays = if (daysTouched) scheduleDays else ALL_DAYS
        )
    }

    companion object {
        val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)
        val ALL_DAYS: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)

        /** The form for a saved rule, or the new-group defaults (blocking on, Delay 15s) for none. */
        fun from(rule: BlockRule?): GroupRuleForm {
            if (rule == null) return GroupRuleForm()
            val mode = runCatching { BlockMode.valueOf(rule.mode) }.getOrNull()
                ?.takeIf { it != BlockMode.NONE }
                ?: BlockMode.DELAY
            val firstUnlock = rule.firstUnlockMinutes != null
            val scheduled = !firstUnlock && rule.scheduleStartMinute != null && rule.scheduleEndMinute != null
            val defaults = GroupRuleForm()
            return GroupRuleForm(
                blockEnabled = rule.enabled,
                mode = mode,
                waitSeconds = rule.delaySeconds,
                dailyLimitEnabled = rule.dailyLimitMinutes != null,
                dailyLimitMinutes = rule.dailyLimitMinutes ?: defaults.dailyLimitMinutes,
                scheduled = scheduled,
                scheduleDays = rule.scheduleDays
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it in 1..7 }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    // No days on a first-unlock rule means every day to the engine, so the editor
                    // must open that way rather than showing Mon-Fri and narrowing it on save
                    // (review nit; a hand-edited import can produce one).
                    ?: if (firstUnlock) ALL_DAYS else defaults.scheduleDays,
                startMinute = rule.scheduleStartMinute ?: defaults.startMinute,
                endMinute = rule.scheduleEndMinute ?: defaults.endMinute,
                firstUnlock = firstUnlock,
                firstUnlockMinutes = rule.firstUnlockMinutes ?: defaults.firstUnlockMinutes,
                // A saved rule already has days (or deliberately has none); either way the user's
                // schedule is not replaced when they change the kind.
                daysTouched = rule.scheduleDays != null
            )
        }
    }
}

/**
 * The rule row this form saves. Built by COPYING [existing] when there is one, so every field the
 * editor does not show (auto-kick, counter, reminder, ...) survives a save instead of being reset
 * to a constructor default. `groupId` is set by the repository on save; `packageName` is always
 * null (a group rule targets the group).
 */
fun GroupRuleForm.toRule(existing: BlockRule?): BlockRule {
    val base = existing ?: BlockRule(mode = mode.name)
    return base.copy(
        packageName = null,
        mode = mode.name,
        delaySeconds = waitSeconds,
        dailyLimitMinutes = if (dailyLimitEnabled) dailyLimitMinutes else null,
        enabled = blockEnabled,
        scheduleDays = if (scheduled || firstUnlock) scheduleDays.sorted().joinToString(",") else null,
        scheduleStartMinute = if (scheduled) startMinute else null,
        scheduleEndMinute = if (scheduled) endMinute else null,
        firstUnlockMinutes = if (firstUnlock) firstUnlockMinutes else null
    )
}

/**
 * Whether saving this edit makes the group protect less, so it needs the Strict Mode challenge
 * (spec 5.6): a member leaving a group that blocks ([GroupWeakening]), or the group's own rule
 * getting softer ([RuleWeakening]).
 *
 * Members are RESOLVED on this device ([installed], [essentials], [browsers]) before they are compared, so
 * unchecking a category that brings in five apps is five members leaving, unchecking one no app
 * here is in is free, and "Removed by you" on a category app is that app leaving.
 *
 * A rule that was OFF protected nothing, so editing it while switching it on is never weakening,
 * whatever its old mode was (switching it off is caught by `enabled` while it was on). A brand-new
 * group ([old] null) only ever adds.
 */
fun groupSaveWeakens(
    old: GroupDefinition?,
    new: GroupDefinition,
    usage: GroupUsage,
    oldRule: BlockRule?,
    newRule: BlockRule,
    installed: Map<String, AppCategory>,
    essentials: Set<String>,
    browsers: Set<String>
): Boolean {
    if (old == null) return false
    if (GroupWeakening.isWeakening(old, new, usage, installed, essentials, browsers)) return true
    return oldRule != null && oldRule.enabled && RuleWeakening.isWeakening(oldRule, newRule)
}

/** Whether deleting the group needs the challenge: every member leaves it at once. */
fun groupDeleteWeakens(
    old: GroupDefinition,
    usage: GroupUsage,
    installed: Map<String, AppCategory>,
    essentials: Set<String>,
    browsers: Set<String>
): Boolean = GroupWeakening.isWeakening(old, GroupDefinition(old.id), usage, installed, essentials, browsers)

/**
 * A plain package set after the picker returns [picked]: the picker's choice, plus any entry the
 * picker could not show ([pickable] lacks it: an essential app, or one since uninstalled). Dropping
 * those silently would remove apps without the user ever seeing them go, and without the Strict Mode
 * check that a visible removal gets. The All apps editor's allowed apps use it; a group's members
 * go through [GroupMembersDraft.afterPick], which also knows categories and "Removed by you".
 */
fun membersAfterPick(current: Set<String>, picked: Set<String>, pickable: Set<String>): Set<String> =
    picked + current.filterNot { it in pickable }

/**
 * The group's own whole-group rule: the one the editor shows and saves, and the one the Manage Apps
 * switch turns on and off (spec 3.3). One definition, so the two screens can never disagree about
 * which row is "the group's".
 */
fun ownGroupRule(groupId: Long, rules: List<BlockRule>): BlockRule? =
    rules.firstOrNull {
        it.groupId == groupId && it.packageName == null && it.inAppFeatures.isNullOrBlank()
    }
