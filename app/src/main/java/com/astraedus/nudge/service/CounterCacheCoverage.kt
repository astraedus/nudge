package com.astraedus.nudge.service

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.RuleCoverage
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.model.WebBlockMode
import com.astraedus.nudge.domain.packages.AppCategory

/**
 * The device facts group and All apps coverage read beyond the rule tables, whole, as a screen has
 * them: [launchable] is every installed app with a launcher entry, with its category
 * (`InstalledAppsRepository`); [essentials] and [browsers] are the one essentials list and the one
 * browser list (`EssentialApps`). The same three inputs as
 * [com.astraedus.nudge.domain.engine.CoveredApps].
 */
data class CounterCacheDevice(
    val launchable: Map<String, AppCategory>,
    val essentials: Set<String>,
    val browsers: Set<String>
)

/**
 * Which packages the counter cache holds an entry for, and what each entry says, from a
 * [RuleSnapshot] (issue [#146](https://github.com/astraedus/nudge/issues/146)).
 *
 * **Why this exists.** The cache used to be built from the rules that NAME a package
 * ([CounterCacheRefresher.appEntryFor]). A group rule and an All apps rule name none, so their
 * members got no entry, so no foreground clock ever started for them, so a group's daily limit was
 * only enforced when the app was next opened. Bench: a one-app group, Hard, 2 min/day, Keep left in
 * front for ~178 s, never blocked; `clock started` never logged.
 *
 * **One coverage answer.** Which apps a rule covers is asked of [RuleCoverage.covers], the resolver
 * block evaluation ([com.astraedus.nudge.domain.engine.RuleEvaluator]), browser routing and the
 * Home count all use, with the snapshot's own membership (category groups, "Removed by you",
 * essentials never under a group or All apps rule). So the clock runs for exactly the apps whose
 * evaluation will find the rule, and `enforceExhaustedBudget`'s re-derive agrees with the clock
 * instead of logging `rules_disagree`. `CounterCacheCoverageDriftTest` pins that agreement.
 *
 * **Strictest wins**, as in [com.astraedus.nudge.domain.engine.BlockEngine]: every enabled rule
 * covering an app counts together, a group rule never shadows a direct one or vice versa (rules
 * only add, spec 1). [CounterCacheRefresher.mergeEntries] then takes the lowest limit and
 * threshold, any overlay asked for, which is the limit the engine would block on first.
 *
 * **The budget is per app**, as at open time: the clock compares each app's own usage today
 * against the merged limit (`TimeRemainingHandler`), exactly the number `BlockEngine` compares, and
 * the group editor's "Daily limit for each app". A SHARED group budget is spec follow-up F3, not
 * this.
 *
 * **Battery (#63).** A package gets an entry only if a covering rule wants something here
 * ([CounterCacheRefresher.entryFor]), and the device lists are read only when such a rule can reach
 * past its own package ([needs]). A phone with app rules only reads nothing new.
 */
object CounterCacheCoverage {

    /**
     * Which device facts building the cache from a snapshot can consult. [essentials] when a rule
     * that wants an entry targets a group or All apps (an essential app is never covered by
     * either); [launchable] (with the browser list, which only matters once a category is known)
     * when one is an All apps rule or a group rule whose group matches by category, because only
     * then can a package with no row of its own be covered.
     */
    data class Needs(val essentials: Boolean, val launchable: Boolean)

    fun needs(snapshot: RuleSnapshot): Needs {
        var essentials = false
        var launchable = false
        for ((data, _) in templatedRules(snapshot)) {
            when (val target = RuleTarget.of(data)) {
                is RuleTarget.Group -> {
                    essentials = true
                    if (snapshot.membership.usesCategories(listOf(target.groupId))) launchable = true
                }
                is RuleTarget.AllApps -> {
                    essentials = true
                    launchable = true
                }
                is RuleTarget.App, null -> Unit
            }
        }
        return Needs(essentials = essentials, launchable = launchable)
    }

    /**
     * The whole cache for [snapshot]: per-app entries for every package a rule covers, plus the
     * website entries, merged strictest-wins. The device lists are read through the three readers,
     * and only when [needs] says a rule can consult them.
     */
    suspend fun load(
        snapshot: RuleSnapshot,
        readLaunchable: suspend () -> Map<String, AppCategory>,
        readEssentials: suspend () -> Set<String>,
        readBrowsers: suspend () -> Set<String>
    ): Map<String, CounterCacheEntry> {
        val needs = needs(snapshot)
        val device = CounterCacheDevice(
            launchable = if (needs.launchable) readLaunchable() else emptyMap(),
            essentials = if (needs.essentials) readEssentials() else emptySet(),
            browsers = if (needs.launchable) readBrowsers() else emptySet()
        )
        return CounterCacheRefresher.mergeEntries(appEntries(snapshot, device) + webEntries(snapshot.enabledRules))
    }

    /**
     * One (package, entry) per enabled rule that wants an entry and covers that package, unmerged.
     *
     * Candidates are the packages a rule can reach, the same set [com.astraedus.nudge.domain.engine.CoveredApps]
     * enumerates: a direct rule's package, every group's hand-added apps, and (only with a category
     * group or an All apps rule in play) every launchable app. Each is then asked of
     * [RuleCoverage.covers], never decided here.
     */
    fun appEntries(snapshot: RuleSnapshot, device: CounterCacheDevice): List<Pair<String, CounterCacheEntry>> {
        val rules = templatedRules(snapshot)
        if (rules.isEmpty()) return emptyList()

        val candidates = LinkedHashSet<String>()
        rules.mapNotNullTo(candidates) { (data, _) -> data.packageName }
        snapshot.membership.definitions.values.forEach { candidates += it.includes }
        candidates += device.launchable.keys

        val out = ArrayList<Pair<String, CounterCacheEntry>>()
        for (pkg in candidates) {
            val facts = CoverageFacts(
                essential = pkg in device.essentials,
                launchable = pkg in device.launchable,
                category = device.launchable[pkg],
                browser = pkg in device.browsers
            )
            val groupIds = RuleCoverage.groupIdsOf(pkg, snapshot.membership, facts)
            for ((data, entry) in rules) {
                if (RuleCoverage.covers(data, pkg, groupIds, facts)) out += pkg to entry
            }
        }
        return out
    }

    /** [CounterCacheRefresher.webEntriesFor] over every rule, unchanged by #146. */
    fun webEntries(rules: List<BlockRule>): List<Pair<String, CounterCacheEntry>> = rules.flatMap { rule ->
        CounterCacheRefresher.webEntriesFor(
            webDomains = rule.webDomains,
            webEnforces = WebBlockMode.resolve(rule.mode, rule.webBlockMode) != BlockMode.NONE,
            autoKickAfterMinutes = rule.autoKickAfterMinutes,
            autoKickCooldownSeconds = rule.autoKickCooldownSeconds
        )
    }

    /**
     * The enabled rules that want an entry, each with the entry it contributes to every app it
     * covers. The row carries the counter fields; the mapped [BlockRuleData] (same index,
     * [RuleSnapshot.ruleData]) carries the target the resolver reads.
     */
    private fun templatedRules(snapshot: RuleSnapshot): List<Pair<BlockRuleData, CounterCacheEntry>> =
        snapshot.enabledRules.zip(snapshot.ruleData).mapNotNull { (row, data) ->
            if (!data.enabled) return@mapNotNull null
            CounterCacheRefresher.entryFor(row)?.let { data to it }
        }
}
