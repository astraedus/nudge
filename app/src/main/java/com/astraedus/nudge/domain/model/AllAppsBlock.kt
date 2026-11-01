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
    /** Every app this rule leaves open: [exceptPackages], then the allowed groups' current members. */
    val allowedPackages: List<String>
)
