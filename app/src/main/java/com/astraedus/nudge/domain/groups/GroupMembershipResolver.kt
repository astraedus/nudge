package com.astraedus.nudge.domain.groups

import com.astraedus.nudge.domain.packages.AppCategory

/**
 * Who is in a group (spec 5.2):
 *
 * ```
 * member(p, g) = !essential(p) && !(p in g.excludes) &&
 *                 (p in g.includes || (!browser(p) && category(p) in g.categories))
 * ```
 *
 * The ONE membership answer. Block evaluation, the browser check, Manage Apps, App config, the All
 * apps editor, the group editor's chips and rows, Strict Mode and the backup all ask it, so no two of
 * them can disagree about who is in a group. Built once per rule-table change (the rule snapshot
 * holds one); every question after that is set lookups.
 *
 * Precedence, in order: an essential app is never a member; "Removed by you" beats a category; an
 * app added by hand or matching a checked category is a member. [AppCategory.UNDEFINED] never
 * matches, whatever a definition holds (a category-less app joins a group only by hand).
 *
 * **A browser never joins by category.** Browsers report whatever category their maker picked
 * (Chrome says SOCIAL, Firefox PRODUCTIVITY), and a browser in a blocking group is blocked as an
 * app on every site (spec 5.5). Checking "Social" must not silently take the whole web away, so a
 * browser is a member only when added by hand. Which apps are browsers is the one browser list,
 * [com.astraedus.nudge.domain.packages.EssentialPackages.browsers].
 *
 * **Category unknown.** A `null` category means the caller did not read it, and then only
 * [GroupDefinition.includes] can match. That is correct only when no group the question can reach
 * uses categories: the hot path reads the category exactly when [usesCategories] says a rule's
 * groups need it, and a synchronous caller that cannot read it must fail closed itself.
 */
class GroupMembershipResolver(definitions: List<GroupDefinition>) {

    /** Every group, by id. */
    val definitions: Map<Long, GroupDefinition> = definitions.associateBy { it.id }

    private val groupsByInclude: Map<String, List<Long>> = HashMap<String, MutableList<Long>>().also { index ->
        definitions.forEach { def ->
            def.includes.forEach { pkg -> index.getOrPut(pkg) { ArrayList(1) }.add(def.id) }
        }
    }

    private val groupsByCategory: Map<AppCategory, List<Long>> = HashMap<AppCategory, MutableList<Long>>().also { index ->
        definitions.forEach { def ->
            def.categories.filter { it != AppCategory.UNDEFINED }
                .forEach { category -> index.getOrPut(category) { ArrayList(2) }.add(def.id) }
        }
    }

    /** Does any group match by category at all? */
    val usesCategories: Boolean = groupsByCategory.isNotEmpty()

    /** Does any of [groupIds] match by category? (A stale id has no definition: it does not.) */
    fun usesCategories(groupIds: Collection<Long>): Boolean =
        groupIds.any { id -> this.definitions[id]?.categories?.any { it != AppCategory.UNDEFINED } == true }

    /**
     * The groups [packageName] is a member of, ESSENTIALS NOT APPLIED: [com.astraedus.nudge.domain.engine.RuleCoverage]
     * applies them per rule kind (an explicit app rule on an essential app is honoured, spec 4.2).
     * [category] null = not read: includes only (see the class doc). A [browser] matches no category.
     */
    fun groupIdsOf(packageName: String, category: AppCategory?, browser: Boolean): Set<Long> {
        val byInclude = groupsByInclude[packageName].orEmpty()
        val byCategory = category?.takeUnless { browser }?.let { groupsByCategory[it] }.orEmpty()
        if (byInclude.isEmpty() && byCategory.isEmpty()) return emptySet()
        val ids = HashSet<Long>(byInclude.size + byCategory.size)
        ids += byInclude
        ids += byCategory
        ids.removeAll { id -> packageName in definitions.getValue(id).excludes }
        return ids
    }

    /** Is [packageName] a member of [groupId]? A stale id has no members. */
    fun isMember(
        groupId: Long,
        packageName: String,
        category: AppCategory?,
        essential: Boolean,
        browser: Boolean
    ): Boolean {
        val definition = definitions[groupId] ?: return false
        return isMember(definition, packageName, category, essential, browser)
    }

    /**
     * The members of [groupId] on this device: its includes (installed or not: a hand-added app that
     * is uninstalled stays added, and is a member again when reinstalled) plus every app in
     * [installed] whose category the group checks (never one of [browsers]), minus "Removed by you",
     * minus [essentials]. [installed] maps each installed package to its category. A stale id has no
     * members.
     */
    fun membersOf(
        groupId: Long,
        installed: Map<String, AppCategory>,
        essentials: Set<String>,
        browsers: Set<String>
    ): Set<String> {
        val definition = definitions[groupId] ?: return emptySet()
        return membersOf(definition, installed, essentials, browsers)
    }

    companion object {

        /** No groups. */
        val NONE = GroupMembershipResolver(emptyList())

        /** The membership formula itself (spec 5.2). */
        fun isMember(
            definition: GroupDefinition,
            packageName: String,
            category: AppCategory?,
            essential: Boolean,
            browser: Boolean
        ): Boolean =
            !essential &&
                packageName !in definition.excludes &&
                (packageName in definition.includes || matchesCategory(definition, category, browser))

        /**
         * Would [category] alone make an app a member of [definition]? UNDEFINED and null never do,
         * and neither does any category of a [browser] (see the class doc).
         */
        fun matchesCategory(definition: GroupDefinition, category: AppCategory?, browser: Boolean): Boolean =
            !browser && category != null && category != AppCategory.UNDEFINED && category in definition.categories

        /** [GroupMembershipResolver.membersOf] for one definition. */
        fun membersOf(
            definition: GroupDefinition,
            installed: Map<String, AppCategory>,
            essentials: Set<String>,
            browsers: Set<String>
        ): Set<String> {
            val members = LinkedHashSet<String>()
            definition.includes.forEach { pkg ->
                if (isMember(definition, pkg, installed[pkg], pkg in essentials, pkg in browsers)) members += pkg
            }
            if (definition.categories.isNotEmpty()) {
                installed.forEach { (pkg, category) ->
                    if (isMember(definition, pkg, category, pkg in essentials, pkg in browsers)) members += pkg
                }
            }
            return members
        }
    }
}
