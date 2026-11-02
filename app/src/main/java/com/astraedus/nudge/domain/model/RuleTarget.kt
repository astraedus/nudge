package com.astraedus.nudge.domain.model

/**
 * What one rule covers: one app, one group, or all apps except a few (spec 5.1).
 *
 * The rule tables hold this as three columns (`packageName`, `groupId`, `allApps`) of which exactly
 * one is set. This sealed type is what every reader works from, so no caller re-derives "which kind
 * of rule is this" from which columns happen to be null.
 */
sealed interface RuleTarget {

    data class App(val packageName: String) : RuleTarget

    data class Group(val groupId: Long) : RuleTarget

    /**
     * Every launchable, non-essential app except [exceptPackages] and the current members of the
     * groups in [exceptGroupIds] (spec 4.1). Being listed here means "this rule does not cover it",
     * never "always allowed": another rule can still block it.
     */
    data class AllApps(
        val exceptPackages: Set<String>,
        val exceptGroupIds: Set<Long>
    ) : RuleTarget

    companion object {

        /**
         * The target of a rule's columns, or null when the row names none (it covers nothing, as
         * such a row always has).
         *
         * A corrupt row with more than one target set reads as the NARROWEST one (app, then group,
         * then all apps): the reading that covers the fewest apps, so damage can never widen a rule
         * into "block everything".
         */
        fun of(
            packageName: String?,
            groupId: Long?,
            allApps: Boolean,
            exceptPackages: Set<String>,
            exceptGroupIds: Set<Long>
        ): RuleTarget? = when {
            packageName != null -> App(packageName)
            groupId != null -> Group(groupId)
            allApps -> AllApps(exceptPackages, exceptGroupIds)
            else -> null
        }

        fun of(rule: BlockRuleData): RuleTarget? = of(
            packageName = rule.packageName,
            groupId = rule.groupId,
            allApps = rule.allApps,
            exceptPackages = rule.exceptPackages,
            exceptGroupIds = rule.exceptGroupIds
        )

        /** Exactly one target set: the invariant the repository enforces on every write. */
        fun isWellFormed(packageName: String?, groupId: Long?, allApps: Boolean): Boolean =
            listOf(packageName != null, groupId != null, allApps).count { it } == 1
    }
}

/**
 * The comma-separated columns of an All apps rule (`exceptPackages`, `exceptGroupIds`), parsed and
 * written in one place. Order is kept (the order the user added them is the order the block screen
 * names them), blanks and duplicates are dropped, and an empty list is stored as null.
 */
object ExceptCsv {

    fun packages(csv: String?): Set<String> =
        csv?.split(",")
            ?.map { it.trim() }
            ?.filterTo(LinkedHashSet()) { it.isNotEmpty() }
            ?: emptySet()

    /** Unparseable ids are dropped; a dropped allowed group only makes the rule stricter. */
    fun groupIds(csv: String?): Set<Long> =
        csv?.split(",")
            ?.mapNotNullTo(LinkedHashSet()) { it.trim().toLongOrNull() }
            ?: emptySet()

    fun ofPackages(packages: Collection<String>): String? =
        packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(",").ifEmpty { null }

    fun ofGroupIds(ids: Collection<Long>): String? =
        ids.distinct().joinToString(",").ifEmpty { null }
}
