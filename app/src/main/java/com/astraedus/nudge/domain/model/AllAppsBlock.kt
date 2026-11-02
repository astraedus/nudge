package com.astraedus.nudge.domain.model

/**
 * What the block screen needs when the rule that DECIDED a block is an All apps rule (spec 3.9):
 * the rule line ("All apps except Spotify, Maps") and the "Open an allowed app" icons.
 *
 * Carried from the deciding rule ([ActiveRule.allApps]) onto [BlockDecision.Block.allApps], the same
 * way `tabVanish` is: a block decided by some other rule (a group's hard block over an app the All
 * apps rule also covers) is not an All apps block and carries null.
 *
 * Packages, not labels: the domain has no PackageManager. The overlay resolves labels and icons, and
 * drops anything not installed or not launchable.
 */
data class AllAppsBlock(
    /** The rule's own allowed apps, in the order the user added them. */
    val exceptPackages: List<String>,
    /** The names of the rule's allowed groups that still exist. */
    val exceptGroupNames: List<String>,
    /** The ids of the rule's allowed groups that still exist, in the rule's order. */
    val exceptGroupIds: List<Long>,
    /**
     * Every app this rule leaves open that the engine can name without the device: [exceptPackages],
     * then the allowed groups' hand-added apps. A group's CATEGORY members need the installed apps,
     * which the engine does not hold (nothing per app is stored, spec 5.2); the block screen adds
     * them on the device through [allowedPackagesOf] over [exceptGroupIds], the same resolution
     * coverage uses.
     */
    val allowedPackages: List<String>
) {
    companion object {
        /**
         * The apps an All apps rule leaves open, in display order: its own [exceptPackages] first,
         * then each allowed group's members ([membersOf], by group id) in [exceptGroupIds] order,
         * without repeats. [membersOf] is the group's resolved membership, so an app "Removed by you",
         * an essential, or a browser a category would match is not listed, exactly as it is not
         * allowed by the group.
         */
        fun allowedPackagesOf(
            exceptPackages: List<String>,
            exceptGroupIds: List<Long>,
            membersOf: (Long) -> Collection<String>
        ): List<String> = (exceptPackages + exceptGroupIds.flatMap(membersOf)).distinct()
    }
}
