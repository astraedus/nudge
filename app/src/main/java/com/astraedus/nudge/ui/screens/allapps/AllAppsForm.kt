package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.lock.RuleWeakening
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.ui.screens.groups.GroupRuleForm
import com.astraedus.nudge.ui.screens.groups.toRule

/**
 * What the All apps editor's Enabled switch, How and When show (spec 3.4). Mode and wait only: an
 * All apps rule has no daily limit, auto-kick, reminder, counter, grayscale, web or feature rules.
 *
 * A new rule starts on, as Delay 15s (the app's standard default), all the time: it blocks from the
 * moment it is saved, and Delay is not Hard block, so saving the defaults asks no question.
 *
 * Pure, so the round trip (rule -> form -> rule) and every save decision are JVM-tested.
 */
data class AllAppsForm(
    val enabled: Boolean = true,
    val mode: BlockMode = BlockMode.DELAY,
    val waitSeconds: Int = 15,
    val scheduled: Boolean = false,
    val scheduleDays: Set<Int> = GroupRuleForm.WEEKDAYS,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60
) {
    /** A days-and-times window with no day picked would never be active; Save waits for a day. */
    val isComplete: Boolean get() = !scheduled || scheduleDays.isNotEmpty()

    /** The wait time chips show for Delay and Hold only (spec 3.4). */
    val showsWaitTime: Boolean get() = mode == BlockMode.DELAY || mode == BlockMode.HOLD

    /**
     * "All the time" with Hard block, while on: the one combination that asks "Block N apps all the
     * time?" before it saves (spec 3.4). A rule that is off blocks nothing, so it asks nothing.
     */
    val confirmsOnSave: Boolean
        get() = enabled && !scheduled && mode == BlockMode.HARD_BLOCK

    companion object {
        /**
         * The form for a saved rule, or the new-rule defaults for none. Read through the group
         * editor's form, so the two editors parse a rule's mode and schedule the same way.
         */
        fun from(rule: BlockRule?): AllAppsForm {
            if (rule == null) return AllAppsForm()
            val parsed = GroupRuleForm.from(rule)
            return AllAppsForm(
                enabled = parsed.blockEnabled,
                mode = parsed.mode,
                waitSeconds = parsed.waitSeconds,
                scheduled = parsed.scheduled,
                scheduleDays = parsed.scheduleDays,
                startMinute = parsed.startMinute,
                endMinute = parsed.endMinute
            )
        }
    }
}

/** Everything a save writes, in the shape the editor's baseline is kept in. */
data class AllAppsDraft(
    val form: AllAppsForm,
    val allowedPackages: Set<String>,
    val allowedGroupIds: Set<Long>
) {
    /** This draft with only the allowed groups that still exist: a stale id is never shown or saved. */
    fun withLiveGroups(liveGroupIds: Set<Long>): AllAppsDraft =
        copy(allowedGroupIds = allowedGroupIds.filterTo(LinkedHashSet()) { it in liveGroupIds })
}

/**
 * The rule row a save writes. Built by COPYING [existing] when there is one, so a field the editor
 * does not show survives the save. The target is forced to All apps (no package, no group), and
 * "keep the pass on short trips" is forced off: it is hidden on these rules (spec 3.4).
 *
 * [allowedPackages] is written whole, including allowed apps that are no longer installed (the
 * editor hides them; reinstalled, they are allowed again). [allowedGroupIds] must already be only
 * the groups that exist ([AllAppsDraft.withLiveGroups]).
 */
fun AllAppsForm.toRule(
    existing: BlockRule?,
    allowedPackages: Collection<String>,
    allowedGroupIds: Collection<Long>
): BlockRule {
    val schedule = GroupRuleForm(
        blockEnabled = enabled,
        mode = mode,
        waitSeconds = waitSeconds,
        dailyLimitEnabled = false,
        scheduled = scheduled,
        scheduleDays = scheduleDays,
        startMinute = startMinute,
        endMinute = endMinute
    ).toRule(existing)
    return schedule.copy(
        packageName = null,
        groupId = null,
        allApps = true,
        // A daily limit is not offered here; an existing one (an imported rule, say) is kept.
        dailyLimitMinutes = existing?.dailyLimitMinutes,
        keepPassOnShortTrips = false,
        exceptPackages = ExceptCsv.ofPackages(allowedPackages),
        exceptGroupIds = ExceptCsv.ofGroupIds(allowedGroupIds)
    )
}

/**
 * Whether saving [new] over [old] makes the rule protect less, so it needs the Strict Mode challenge
 * (spec 5.6). [RuleWeakening] holds every axis (off, softer mode, shorter wait, an allowed app or
 * group added, a shorter window); this only adds the two editor facts: a new rule ([old] null) only
 * adds, and a rule that was off protected nothing, so editing it is never weakening (switching it
 * off is caught while it was on).
 */
fun allAppsSaveWeakens(old: BlockRule?, new: BlockRule): Boolean =
    old != null && old.enabled && RuleWeakening.isWeakening(old, new)

/** Whether deleting [rule] needs the challenge: only a rule that is on protects anything. */
fun allAppsDeleteWeakens(rule: BlockRule): Boolean = rule.enabled
