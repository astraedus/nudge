package com.astraedus.nudge.domain.model

data class ActiveRule(
    val mode: BlockMode,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: List<Int>? = null,
    val scheduleStartMinute: Int? = null,
    val scheduleEndMinute: Int? = null,
    val inAppFeatures: List<String>? = null,
    val grayscale: Boolean = false,
    val ruleName: String? = null,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.tabVanish]. */
    val tabVanish: Boolean = true,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.followingSteer]. */
    val followingSteer: Boolean = false,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.keepPassOnShortTrips]. */
    val keepPassOnShortTrips: Boolean = false,
    /**
     * Set when this rule is an All apps rule: what its block screen shows (spec 3.9). Null for an
     * app or group rule. Carried onto [BlockDecision.Block.allApps] only when THIS rule decides.
     */
    val allApps: AllAppsBlock? = null
)
