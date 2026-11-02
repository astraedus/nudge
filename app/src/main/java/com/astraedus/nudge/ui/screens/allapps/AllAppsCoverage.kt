package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.groupResolverOf
import com.astraedus.nudge.data.repository.toRuleData
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.CoveredApps
import com.astraedus.nudge.domain.engine.RuleCoverage
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.ui.components.rule.RuleSummary

/**
 * The rule tables the editor reads live: every rule, every group and every group's members. Live,
 * so a group edited from here (its row opens the group editor) reads right when the user comes back.
 */
data class RuleTables(
    val rules: List<BlockRule> = emptyList(),
    val groups: List<AppGroup> = emptyList(),
    val members: List<AppGroupMember> = emptyList()
) {
    /** The groups that exist: an allowed id outside this set is stale (its group was deleted). */
    val liveGroupIds: Set<Long> = groups.mapTo(HashSet()) { it.id }

    val groupNames: Map<Long, String> = groups.associate { it.id to it.name }

    /** Who is in each group that exists, as the engine resolves it (spec 5.2). */
    val membership: GroupMembershipResolver = groupResolverOf(groups, members)

    /**
     * The members of [groupId] on this device: hand-added apps plus the installed apps of its
     * checked categories, minus "Removed by you" and essentials. What "Work · 6 apps" counts.
     */
    fun memberCount(groupId: Long, device: DeviceApps): Int =
        membership.membersOf(groupId, device.categories, device.essentials, device.browsers).size

    /**
     * Every rule but the one being edited ([editedRuleId], null for a new rule), as the engine
     * reads it. A per-feature rule ("Reels: Hard Block") is left out: it never stops the app from
     * opening, so it must not read as "Also blocked by its own rule".
     */
    fun otherRules(editedRuleId: Long?): List<BlockRuleData> = rules
        .filter { it.id != editedRuleId && it.inAppFeatures.isNullOrBlank() }
        .map { it.toRuleData(groupName = it.groupId?.let(groupNames::get), groupNames = groupNames) }
}

/**
 * What the device holds, read once when the editor opens.
 *
 * [installed] is the installed apps with a launcher entry (labelled, sorted by name), which is what
 * an All apps rule can cover; [essentials] is the one essentials list; [browsers] is the one
 * browser list (a browser joins a group only by hand, never by category); [alwaysAllowed] is the
 * essentials this phone can open (Phone, Messages, Settings, ...), labelled and sorted by name, for
 * the locked row and its sheet.
 */
data class DeviceApps(
    val installed: List<InstalledAppsRepository.AppInfo> = emptyList(),
    val essentials: Set<String> = emptySet(),
    val browsers: Set<String> = emptySet(),
    val alwaysAllowed: List<InstalledAppsRepository.AppInfo> = emptyList()
) {
    val launchable: Set<String> = installed.mapTo(HashSet()) { it.packageName }

    /** Each installed app's category, by package: what a category group matches. */
    val categories: Map<String, AppCategory> = installed.associate { it.packageName to it.category }

    val byPackage: Map<String, InstalledAppsRepository.AppInfo> = installed.associateBy { it.packageName }

    /** Each installed app's label, by package: what an All apps rule's title names. */
    val labels: Map<String, String> = installed.associate { it.packageName to it.appName }

    /** What "Add apps or groups" offers: every installed app but the essentials (spec 3.6). */
    val pickerApps: List<InstalledAppsRepository.AppInfo> =
        installed.filterNot { it.packageName in essentials }

    fun factsFor(packageName: String) = CoverageFacts(
        essential = packageName in essentials,
        launchable = packageName in launchable,
        category = categories[packageName],
        browser = packageName in browsers
    )
}

/**
 * "Blocks N apps" (spec 3.4): the launchable apps [rule] would cover if it were on. Asked of the
 * engine's own coverage ([CoveredApps], over [RuleCoverage]), so the number is exactly what the rule
 * blocks: every launchable app, minus essentials, minus allowed apps, minus the current members of
 * allowed groups (a stale allowed group allows nothing). Schedules do not change the count.
 */
fun blockedCount(rule: BlockRule, tables: RuleTables, device: DeviceApps): Int =
    CoveredApps.packages(
        ruleData = listOf(rule.copy(enabled = true).toRuleData(groupNames = tables.groupNames)),
        membership = tables.membership,
        launchable = device.categories,
        essentials = device.essentials,
        browsers = device.browsers
    ).size

/**
 * The names of the other ENABLED rules that block [packageName] whatever this rule allows (rules
 * only add): a group rule by its group's name, an All apps rule by the title every other screen
 * gives it ("All apps except Maps", [RuleSummary.allAppsTitle]), the app's own rule as "its own
 * rule". In [otherRules] order, without repeats.
 */
fun alsoBlockedBy(
    packageName: String,
    otherRules: List<BlockRuleData>,
    tables: RuleTables,
    device: DeviceApps
): List<String> = RuleCoverage
    .coveringRules(packageName, otherRules, tables.membership, device.factsFor(packageName))
    .mapNotNull { rule ->
        when (RuleTarget.of(rule)) {
            is RuleTarget.App -> OWN_RULE
            is RuleTarget.Group -> rule.groupName
            is RuleTarget.AllApps -> RuleSummary.allAppsTitle(rule, device.labels)
            null -> null
        }
    }
    .distinct()

/**
 * "Also blocked by Social", "Also blocked by its own rule and All apps except Maps", "Also blocked
 * by 3 rules"; null for none. Two names are joined with "and", never a comma: an All apps title
 * carries its own commas ("All apps except Work, Maps"), so a comma between rules would read as one
 * more allowed app.
 */
fun alsoBlockedByText(names: List<String>): String? = when (names.size) {
    0 -> null
    1, 2 -> "Also blocked by ${names.joinToString(" and ")}"
    else -> "Also blocked by ${names.size} rules"
}

/**
 * How many installed members of [groupId] another enabled rule still blocks: an allowed group's
 * "2 still blocked by other rules". A member that is not installed opens nothing, so it is not
 * counted.
 */
fun stillBlockedCount(
    groupId: Long,
    otherRules: List<BlockRuleData>,
    tables: RuleTables,
    device: DeviceApps
): Int = tables.membership
    .membersOf(groupId, device.categories, device.essentials, device.browsers)
    .filter { it in device.launchable }
    .count { alsoBlockedBy(it, otherRules, tables, device).isNotEmpty() }

/** "1 still blocked by another rule", "2 still blocked by other rules"; null for none. */
fun stillBlockedText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 still blocked by another rule"
    else -> "$count still blocked by other rules"
}

/** The line under Enabled: "Blocks 47 apps", or "Would block 47 apps" while the rule is off. */
fun blocksText(count: Int, enabled: Boolean): String {
    val apps = appCount(count)
    return if (enabled) "Blocks $apps" else "Would block $apps"
}

/** The save confirm's title: "Block 47 apps all the time?". */
fun confirmTitle(count: Int): String = "Block ${appCount(count)} all the time?"

/**
 * The locked row: "Always allowed: Phone, Messages, Settings and 5 more". [labels] in the order to
 * name them. Before the list resolves (or on a phone where none can be opened) it names no app.
 */
fun alwaysAllowedText(labels: List<String>): String = when (labels.size) {
    0 -> "Always allowed: your essential apps"
    1 -> "Always allowed: ${labels[0]}"
    2, 3 -> "Always allowed: ${labels.dropLast(1).joinToString(", ")} and ${labels.last()}"
    else -> "Always allowed: ${labels.take(3).joinToString(", ")} and ${labels.size - 3} more"
}

private fun appCount(count: Int): String = if (count == 1) "1 app" else "$count apps"

private const val OWN_RULE = "its own rule"
