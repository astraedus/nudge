package com.astraedus.nudge.testing

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.export.ExportedRule
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.toRuleData
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.AllAppsBlock
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.packages.AppCategory
import android.graphics.drawable.Drawable

/*
 * One builder per domain type, for every JVM test.
 *
 * A test names ONLY the fields its assertion is about; everything else is the value the app
 * actually ships. Those values are never typed here: each builder reads them off a production
 * default or a production factory, so when the app's default moves, every test moves with it
 * (CLAUDE.md "fixture honesty"). `BuildersTest` pins each derivation.
 *
 *  - A rule defaults to the row the app writes when the user switches an app on
 *    (`AppListViewModel.toggle`: `BlockRule(mode = DELAY.name)` with the entity's own
 *    `delaySeconds = 15`, `enabled = true`, no target fields beyond the ones you pass).
 *  - [BlockRuleData] is that row through the production mapper `BlockRule.toRuleData()`.
 *  - [ActiveRule] is that same rule as `RuleEvaluator` hands it to the engine (the data fields
 *    copied across; `ruleName` / `allApps` at ActiveRule's own null defaults).
 *  - The one deliberate departure: every wall-clock default (`createdAt`, `timestamp`) is pinned
 *    to [FIXED_EPOCH_MS], so no test reads the real clock through a builder.
 *
 * Fields production REQUIRES stay required here too (an event's package, an accessibility
 * event's type, a group's name): inventing a value for them would be a fixture lie.
 */

/** 2023-11-14T22:13:20Z. The fixed instant every wall-clock default is pinned to. */
const val FIXED_EPOCH_MS: Long = 1_700_000_000_000L

/** The row the app writes when an app is switched on (AppListViewModel.toggle). */
private val SHIPPED_ROW: BlockRule = BlockRule(mode = BlockMode.DELAY.name, createdAt = FIXED_EPOCH_MS)

/** [SHIPPED_ROW] through the production mapper. */
private val SHIPPED_DATA: BlockRuleData = SHIPPED_ROW.toRuleData()

/** ActiveRule's own defaults (ruleName, allApps), read off a constructed instance. */
private val ACTIVE_DEFAULTS: ActiveRule = ActiveRule(
    mode = SHIPPED_DATA.mode,
    delaySeconds = SHIPPED_DATA.delaySeconds,
    dailyLimitMinutes = SHIPPED_DATA.dailyLimitMinutes,
    enabled = SHIPPED_DATA.enabled
)

/** The Room entity, as stored. `mode` is the stored enum NAME, exactly as the column holds it. */
fun aBlockRule(
    id: Long = SHIPPED_ROW.id,
    packageName: String? = SHIPPED_ROW.packageName,
    groupId: Long? = SHIPPED_ROW.groupId,
    mode: String = SHIPPED_ROW.mode,
    delaySeconds: Int = SHIPPED_ROW.delaySeconds,
    dailyLimitMinutes: Int? = SHIPPED_ROW.dailyLimitMinutes,
    enabled: Boolean = SHIPPED_ROW.enabled,
    createdAt: Long = SHIPPED_ROW.createdAt,
    scheduleDays: String? = SHIPPED_ROW.scheduleDays,
    scheduleStartMinute: Int? = SHIPPED_ROW.scheduleStartMinute,
    scheduleEndMinute: Int? = SHIPPED_ROW.scheduleEndMinute,
    inAppFeatures: String? = SHIPPED_ROW.inAppFeatures,
    grayscale: Boolean = SHIPPED_ROW.grayscale,
    showCounter: Boolean = SHIPPED_ROW.showCounter,
    autoKickAfter: Int? = SHIPPED_ROW.autoKickAfter,
    showTimeRemaining: Boolean = SHIPPED_ROW.showTimeRemaining,
    autoKickCooldownSeconds: Int = SHIPPED_ROW.autoKickCooldownSeconds,
    webDomains: String? = SHIPPED_ROW.webDomains,
    webBlockMode: String? = SHIPPED_ROW.webBlockMode,
    autoKickAfterMinutes: Int? = SHIPPED_ROW.autoKickAfterMinutes,
    remindAfterMinutes: Int? = SHIPPED_ROW.remindAfterMinutes,
    tabVanish: Boolean = SHIPPED_ROW.tabVanish,
    followingSteer: Boolean = SHIPPED_ROW.followingSteer,
    keepPassOnShortTrips: Boolean = SHIPPED_ROW.keepPassOnShortTrips,
    allApps: Boolean = SHIPPED_ROW.allApps,
    exceptPackages: String? = SHIPPED_ROW.exceptPackages,
    exceptGroupIds: String? = SHIPPED_ROW.exceptGroupIds
): BlockRule = SHIPPED_ROW.copy(
    id = id,
    packageName = packageName,
    groupId = groupId,
    mode = mode,
    delaySeconds = delaySeconds,
    dailyLimitMinutes = dailyLimitMinutes,
    enabled = enabled,
    createdAt = createdAt,
    scheduleDays = scheduleDays,
    scheduleStartMinute = scheduleStartMinute,
    scheduleEndMinute = scheduleEndMinute,
    inAppFeatures = inAppFeatures,
    grayscale = grayscale,
    showCounter = showCounter,
    autoKickAfter = autoKickAfter,
    showTimeRemaining = showTimeRemaining,
    autoKickCooldownSeconds = autoKickCooldownSeconds,
    webDomains = webDomains,
    webBlockMode = webBlockMode,
    autoKickAfterMinutes = autoKickAfterMinutes,
    remindAfterMinutes = remindAfterMinutes,
    tabVanish = tabVanish,
    followingSteer = followingSteer,
    keepPassOnShortTrips = keepPassOnShortTrips,
    allApps = allApps,
    exceptPackages = exceptPackages,
    exceptGroupIds = exceptGroupIds
)

/** A group-target rule row: [aBlockRule] aimed at [groupId]. */
fun aGroupRule(groupId: Long, mode: String = SHIPPED_ROW.mode): BlockRule =
    aBlockRule(groupId = groupId, mode = mode)

/** The rule as block evaluation reads it (`BlockRule.toRuleData()`). */
fun aBlockRuleData(
    id: Long = SHIPPED_DATA.id,
    packageName: String? = SHIPPED_DATA.packageName,
    groupId: Long? = SHIPPED_DATA.groupId,
    mode: BlockMode = SHIPPED_DATA.mode,
    delaySeconds: Int = SHIPPED_DATA.delaySeconds,
    dailyLimitMinutes: Int? = SHIPPED_DATA.dailyLimitMinutes,
    enabled: Boolean = SHIPPED_DATA.enabled,
    scheduleDays: List<Int>? = SHIPPED_DATA.scheduleDays,
    scheduleStartMinute: Int? = SHIPPED_DATA.scheduleStartMinute,
    scheduleEndMinute: Int? = SHIPPED_DATA.scheduleEndMinute,
    inAppFeatures: List<String>? = SHIPPED_DATA.inAppFeatures,
    grayscale: Boolean = SHIPPED_DATA.grayscale,
    webDomains: String? = SHIPPED_DATA.webDomains,
    tabVanish: Boolean = SHIPPED_DATA.tabVanish,
    followingSteer: Boolean = SHIPPED_DATA.followingSteer,
    keepPassOnShortTrips: Boolean = SHIPPED_DATA.keepPassOnShortTrips,
    groupName: String? = SHIPPED_DATA.groupName,
    allApps: Boolean = SHIPPED_DATA.allApps,
    exceptPackages: Set<String> = SHIPPED_DATA.exceptPackages,
    exceptGroupIds: Set<Long> = SHIPPED_DATA.exceptGroupIds,
    exceptGroupNames: List<String> = SHIPPED_DATA.exceptGroupNames
): BlockRuleData = SHIPPED_DATA.copy(
    id = id,
    packageName = packageName,
    groupId = groupId,
    mode = mode,
    delaySeconds = delaySeconds,
    dailyLimitMinutes = dailyLimitMinutes,
    enabled = enabled,
    scheduleDays = scheduleDays,
    scheduleStartMinute = scheduleStartMinute,
    scheduleEndMinute = scheduleEndMinute,
    inAppFeatures = inAppFeatures,
    grayscale = grayscale,
    webDomains = webDomains,
    tabVanish = tabVanish,
    followingSteer = followingSteer,
    keepPassOnShortTrips = keepPassOnShortTrips,
    groupName = groupName,
    allApps = allApps,
    exceptPackages = exceptPackages,
    exceptGroupIds = exceptGroupIds,
    exceptGroupNames = exceptGroupNames
)

/** The rule as the engine evaluates it (what `RuleEvaluator.resolveRulesForPackage` emits). */
fun anActiveRule(
    mode: BlockMode = ACTIVE_DEFAULTS.mode,
    delaySeconds: Int = ACTIVE_DEFAULTS.delaySeconds,
    dailyLimitMinutes: Int? = ACTIVE_DEFAULTS.dailyLimitMinutes,
    enabled: Boolean = ACTIVE_DEFAULTS.enabled,
    scheduleDays: List<Int>? = SHIPPED_DATA.scheduleDays,
    scheduleStartMinute: Int? = SHIPPED_DATA.scheduleStartMinute,
    scheduleEndMinute: Int? = SHIPPED_DATA.scheduleEndMinute,
    inAppFeatures: List<String>? = SHIPPED_DATA.inAppFeatures,
    grayscale: Boolean = SHIPPED_DATA.grayscale,
    ruleName: String? = ACTIVE_DEFAULTS.ruleName,
    tabVanish: Boolean = SHIPPED_DATA.tabVanish,
    followingSteer: Boolean = SHIPPED_DATA.followingSteer,
    keepPassOnShortTrips: Boolean = SHIPPED_DATA.keepPassOnShortTrips,
    allApps: AllAppsBlock? = ACTIVE_DEFAULTS.allApps
): ActiveRule = ActiveRule(
    mode = mode,
    delaySeconds = delaySeconds,
    dailyLimitMinutes = dailyLimitMinutes,
    enabled = enabled,
    scheduleDays = scheduleDays,
    scheduleStartMinute = scheduleStartMinute,
    scheduleEndMinute = scheduleEndMinute,
    inAppFeatures = inAppFeatures,
    grayscale = grayscale,
    ruleName = ruleName,
    tabVanish = tabVanish,
    followingSteer = followingSteer,
    keepPassOnShortTrips = keepPassOnShortTrips,
    allApps = allApps
)

/** A rule as a backup file carries it: [rule] exported and read back by the production [RuleExporter]. */
fun anExportedRule(rule: BlockRule = aBlockRule()): ExportedRule {
    val exporter = RuleExporter()
    return exporter.importRules(exporter.exportRules(listOf(rule), emptyList(), emptyMap())).rules.single()
}

private val USAGE_DEFAULTS = UsageEvent(packageName = "", timestamp = FIXED_EPOCH_MS)

/** A usage-history row. [packageName] is required, as it is in production. */
fun aUsageEvent(
    packageName: String,
    timestamp: Long = USAGE_DEFAULTS.timestamp,
    wasBlocked: Boolean = USAGE_DEFAULTS.wasBlocked,
    blockMode: String? = USAGE_DEFAULTS.blockMode,
    userChangedMind: Boolean = USAGE_DEFAULTS.userChangedMind,
    id: Long = USAGE_DEFAULTS.id
): UsageEvent = UsageEvent(
    id = id,
    packageName = packageName,
    timestamp = timestamp,
    wasBlocked = wasBlocked,
    blockMode = blockMode,
    userChangedMind = userChangedMind
)

private val A11Y_DEFAULTS = AccessibilityEventRecord(type = A11yEventType.OTHER, packageName = "")

/** A pure accessibility event record. [type] and [packageName] are required, as in production. */
fun anAccessibilityEvent(
    type: A11yEventType,
    packageName: String,
    className: String? = A11Y_DEFAULTS.className,
    eventTimeMs: Long = A11Y_DEFAULTS.eventTimeMs,
    windowId: Int = A11Y_DEFAULTS.windowId,
    contentChangeTypes: Int = A11Y_DEFAULTS.contentChangeTypes,
    sourceViewId: String? = A11Y_DEFAULTS.sourceViewId,
    isScrollable: Boolean = A11Y_DEFAULTS.isScrollable
): AccessibilityEventRecord = A11Y_DEFAULTS.copy(
    type = type,
    packageName = packageName,
    className = className,
    eventTimeMs = eventTimeMs,
    windowId = windowId,
    contentChangeTypes = contentChangeTypes,
    sourceViewId = sourceViewId,
    isScrollable = isScrollable
)

private val APP_DEFAULTS = InstalledAppsRepository.AppInfo(packageName = "", appName = "", icon = null)

/**
 * An installed app row. [appName] defaults to the package name: what the repository shows for a
 * package it cannot label (`InstalledAppsRepository.resolveAppName`).
 */
fun anApp(
    packageName: String,
    appName: String = packageName,
    icon: Drawable? = APP_DEFAULTS.icon,
    category: AppCategory = APP_DEFAULTS.category
): InstalledAppsRepository.AppInfo = InstalledAppsRepository.AppInfo(
    packageName = packageName,
    appName = appName,
    icon = icon,
    category = category
)

private val GROUP_DEFAULTS = AppGroup(name = "")

/** An app group row. [name] is required, as it is in production. */
fun anAppGroup(
    name: String,
    id: Long = GROUP_DEFAULTS.id,
    iconName: String? = GROUP_DEFAULTS.iconName,
    color: Long = GROUP_DEFAULTS.color,
    categories: String? = GROUP_DEFAULTS.categories
): AppGroup = AppGroup(id = id, name = name, iconName = iconName, color = color, categories = categories)

/** A group membership row: [packageName] hand-added to [groupId] (or "Removed by you" when [excluded]). */
fun aGroupMember(
    groupId: Long,
    packageName: String,
    excluded: Boolean = AppGroupMember(groupId = 0, packageName = "").excluded
): AppGroupMember = AppGroupMember(groupId = groupId, packageName = packageName, excluded = excluded)
