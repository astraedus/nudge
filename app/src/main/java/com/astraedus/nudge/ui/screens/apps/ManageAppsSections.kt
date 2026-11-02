package com.astraedus.nudge.ui.screens.apps

import androidx.compose.runtime.Immutable
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
import com.astraedus.nudge.data.repository.groupResolverOf
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import com.astraedus.nudge.ui.components.rule.RuleSummary
import com.astraedus.nudge.ui.components.rule.allAppsRuleItems
import com.astraedus.nudge.ui.screens.config.blockingGroupsOf
import com.astraedus.nudge.ui.screens.groups.ownGroupRule

/** One group on Manage Apps: "Social · 9 apps", its rule in one line, and its switch. */
@Immutable
data class GroupRow(
    val groupId: Long,
    val title: String,
    val summary: String,
    /** The group's own rule is on. */
    val blocking: Boolean,
    /** The group's own rule, or null for a group that has none. */
    val rule: BlockRule?
)

/**
 * One app on Manage Apps.
 *
 * [summary] is its own rules in one line ("Delay 15s · 30min limit"), null for an app with none.
 * [isOn] is what its switch shows: at least one of its own rules is on (Active Rules' meaning,
 * spec 3.2), not whether a group also covers it.
 * [coveredBy] is the second line of an app with no rule of its own that a blocking group covers
 * ("In Social"), so it does not read as unblocked while the group blocks it. Null otherwise.
 */
@Immutable
data class AppRow(
    val appInfo: AppInfo,
    val summary: String?,
    val isOn: Boolean,
    val coveredBy: String? = null
) {
    /** The row's second line: its own rules, else the groups that block it, else nothing. */
    val supportingText: String? get() = summary ?: coveredBy

    val hasRule: Boolean get() = summary != null
    val packageName: String get() = appInfo.packageName
}

/**
 * Manage Apps' sections, in screen order (spec 3.2). Each section shows only when it has rows.
 *
 * [allApps] is rendered first inside the top row, above [groups]; nothing below has to move for it.
 */
@Immutable
data class ManageAppsSections(
    /** The "All apps" section (spec 3.2): one row per All apps rule, empty when there are none. */
    val allApps: List<AllAppsRuleItem> = emptyList(),
    val groups: List<GroupRow> = emptyList(),
    /** The "Block several apps" row. Hidden while a search is showing (it matches nothing). */
    val showNewGroup: Boolean = true,
    val appsWithRules: List<AppRow> = emptyList(),
    val otherApps: List<AppRow> = emptyList(),
    /** "Apps" for someone with no group and no app rule yet (first run), else "Other apps". */
    val otherAppsTitle: String = APPS_TITLE
) {
    /** Nothing at all to show: no group and no app (a search that matched nothing). */
    val nothingMatches: Boolean
        get() = allApps.isEmpty() && groups.isEmpty() && appsWithRules.isEmpty() && otherApps.isEmpty()

    companion object {
        const val APPS_TITLE = "Apps"
        const val OTHER_APPS_TITLE = "Other apps"
        const val APPS_WITH_RULES_TITLE = "Apps with rules"
    }
}

/**
 * Builds the sections from the tables (spec 3.2). Pure: everything it needs is passed in.
 *
 * - **All apps:** the All apps rules in [rules], oldest first, titled from the allowed groups'
 *   names and the allowed apps' labels in [installed]. A search keeps the rules whose TITLE matches.
 * - **Groups:** sorted by name; a search keeps the groups whose NAME matches.
 * - **Apps with rules:** every package with a direct rule (on or off), sorted by name. A package
 *   that is not in [installed] (uninstalled, or no launcher entry) still has its rule, so it is
 *   listed under its name from [extraApps], or skipped until that lookup lands.
 * - **Other apps:** the rest of [installed], in the order given. One that a blocking group covers
 *   says so on its second line ("In Social", "In Social, Work", "In 3 groups"; groups whose rule is
 *   off do not count), from [blockingGroupsOf], the same answer App config's "Also covered by"
 *   gives. Its switch still means "give this app a rule of its own".
 * - The other apps' heading is "Apps" until the user has a group or an app rule (first run), so a
 *   new user is not shown "Other" than nothing.
 * - [keepInPlace]: apps switched on from this screen during this visit. They keep their place in
 *   the other apps (now with their summary and switch on) instead of jumping up into "Apps with
 *   rules", so the row under the user's finger does not change between two taps (device QA, PR 3:
 *   a quick second tap switched on the NEXT app, which had slid into the same spot). They move up
 *   the next time Manage Apps opens.
 *
 * A search matches app names and package names in both app sections, and group names.
 */
fun buildManageAppsSections(
    installed: List<AppInfo>,
    groups: List<AppGroup>,
    members: List<AppGroupMember>,
    rules: List<BlockRule>,
    essentials: Set<String>,
    query: String,
    extraApps: Map<String, AppInfo> = emptyMap(),
    keepInPlace: Set<String> = emptySet()
): ManageAppsSections {
    val needle = query.trim()
    fun AppInfo.matches() = needle.isEmpty() ||
        appName.contains(needle, ignoreCase = true) || packageName.contains(needle, ignoreCase = true)

    val rulesByPackage: Map<String, List<BlockRule>> = rules
        .filter { it.packageName != null && it.groupId == null }
        .groupBy { it.packageName!! }
    val installedByPackage = installed.associateBy { it.packageName }
    val membership = groupResolverOf(groups, members)
    val categories = installed.associate { it.packageName to it.category }

    fun row(info: AppInfo): AppRow {
        val pkgRules = rulesByPackage[info.packageName] ?: return AppRow(
            appInfo = info,
            summary = null,
            isOn = false,
            coveredBy = RuleSummary.coveredByText(
                blockingGroupsOf(
                    packageName = info.packageName,
                    facts = CoverageFacts(
                        essential = info.packageName in essentials,
                        launchable = true,
                        category = categories[info.packageName]
                    ),
                    groups = groups,
                    membership = membership,
                    rules = rules
                ).map { (group, _) -> group.name }
            )
        )
        return AppRow(
            appInfo = info,
            summary = RuleSummary.buildSummaryText(pkgRules),
            isOn = pkgRules.any { it.enabled }
        )
    }
    fun belowWithRules(pkg: String) = pkg in rulesByPackage && pkg !in keepInPlace

    val appsWithRules = rulesByPackage.keys
        .filter(::belowWithRules)
        .mapNotNull { pkg -> installedByPackage[pkg] ?: extraApps[pkg] }
        .filter { it.matches() }
        .sortedBy { it.appName.lowercase() }
        .map(::row)

    val otherApps = installed
        .filter { !belowWithRules(it.packageName) && it.matches() }
        .map(::row)

    val allAppsAll = allAppsRuleItems(rules, groups, installed.associate { it.packageName to it.appName })
    val firstRun = allAppsAll.isEmpty() && groups.isEmpty() && rulesByPackage.keys.none(::belowWithRules)
    return ManageAppsSections(
        allApps = allAppsAll.filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) },
        groups = buildGroupRows(groups, membership, categories, essentials, rules, needle),
        showNewGroup = needle.isEmpty(),
        appsWithRules = appsWithRules,
        otherApps = otherApps,
        otherAppsTitle = if (firstRun) ManageAppsSections.APPS_TITLE else ManageAppsSections.OTHER_APPS_TITLE
    )
}

/**
 * The group rows, sorted by name. A search keeps the groups whose NAME matches: a group is found by
 * what the user called it, not by the apps inside it.
 */
fun buildGroupRows(
    groups: List<AppGroup>,
    membership: GroupMembershipResolver,
    installed: Map<String, AppCategory>,
    essentials: Set<String>,
    rules: List<BlockRule>,
    query: String
): List<GroupRow> {
    fun count(groupId: Long) = membership.membersOf(groupId, installed, essentials).size
    val needle = query.trim()
    return groups
        .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
        .map { group ->
            val rule = ownGroupRule(group.id, rules)
            GroupRow(
                groupId = group.id,
                title = RuleSummary.groupTitle(group.name, count(group.id)),
                summary = RuleSummary.groupSummaryText(rule),
                blocking = rule?.enabled == true,
                rule = rule
            )
        }
}
