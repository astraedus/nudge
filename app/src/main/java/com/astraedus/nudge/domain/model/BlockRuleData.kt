package com.astraedus.nudge.domain.model

data class BlockRuleData(
    val id: Long,
    val packageName: String?,
    val groupId: Long?,
    val mode: BlockMode,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: List<Int>? = null,        // 1=Mon..7=Sun
    val scheduleStartMinute: Int? = null,
    val scheduleEndMinute: Int? = null,
    val inAppFeatures: List<String>? = null,
    val grayscale: Boolean = false,
    val webDomains: String? = null,             // comma-separated: "instagram.com,www.instagram.com"
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.tabVanish]. */
    val tabVanish: Boolean = true,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.followingSteer]. */
    val followingSteer: Boolean = false,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.keepPassOnShortTrips]. */
    val keepPassOnShortTrips: Boolean = false,
    /**
     * The name of the group a group rule ([groupId] != null) belongs to, for the rule's label on the
     * block overlay. Null for a direct rule, and for a group rule whose group row is missing.
     * Filled by `RuleSnapshot.of`, which already holds the groups table.
     */
    val groupName: String? = null,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.allApps]. Read the target via [RuleTarget.of]. */
    val allApps: Boolean = false,
    /** An All apps rule's allowed apps, in the order the user added them. Empty for other rules. */
    val exceptPackages: Set<String> = emptySet(),
    /** An All apps rule's allowed groups (live references). Empty for other rules. */
    val exceptGroupIds: Set<Long> = emptySet(),
    /**
     * The names of [exceptGroupIds] that still exist, for the block screen's rule line. Filled by
     * `RuleSnapshot.of`, which holds the groups table, like [groupName]. A stale id has no name.
     */
    val exceptGroupNames: List<String> = emptyList(),
    /**
     * Non-null = active for this many minutes after the first unlock of the logical day (spec 5.3).
     * Null = every other rule. See [com.astraedus.nudge.domain.engine.FirstUnlockWindow].
     */
    val firstUnlockMinutes: Int? = null,
    /**
     * Wall-clock ms the rule was last saved or switched on (arming, spec 5.1). Only read by the
     * first-unlock branch of [com.astraedus.nudge.domain.engine.ScheduleEvaluator]; 0 = always armed.
     */
    val armedAt: Long = 0L
)
