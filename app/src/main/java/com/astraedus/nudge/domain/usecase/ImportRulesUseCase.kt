package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedGroup
import com.astraedus.nudge.data.export.ExportedHistoryEvent
import com.astraedus.nudge.data.export.ExportedRule
import com.astraedus.nudge.data.export.HistoryMerge
import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.definition
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.groups.AppCategoryCsv
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.lock.ImportedGroupMerge
import com.astraedus.nudge.domain.lock.ResolvedGroup
import com.astraedus.nudge.domain.lock.ImportedGroupsWeakening
import com.astraedus.nudge.domain.lock.ImportedSettingsWeakening
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.packages.EssentialPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What an import actually did. The two "skipped" counts are deliberately separate: a duplicate was
 * understood and intentionally not re-added, an invalid entry could not be read at all -- only the
 * second one means the user lost something from their backup, so the UI must not merge them.
 */
data class ImportOutcome(
    val importedCount: Int,
    /** Rules already present in the DB (same target + mode + schedule), so not re-added. */
    val duplicateCount: Int,
    val groupsCreated: Int,
    /**
     * Entries in the file that could not be parsed and were skipped (see [ImportResult]), plus
     * group rules whose group is neither in the file nor on this phone.
     */
    val invalidCount: Int = 0,
    /** One human-readable reason per skipped entry, in file order. */
    val invalidReasons: List<String> = emptyList(),
    /** History events written to `usage_events`. */
    val historyImportedCount: Int = 0,
    /** History events already present (same package + timestamp + flags), so not re-added. */
    val historyDuplicateCount: Int = 0,
    /** History events in the file that could not be read at all. */
    val historyInvalidCount: Int = 0,
    /** True when the file carried app settings and they were written to this device. */
    val settingsApplied: Boolean = false,
    /** Individual settings in the file that could not be read and were left out. */
    val settingsInvalidCount: Int = 0,
    val error: String? = null
)

/**
 * What an import WOULD do, shown to the user before anything is written.
 *
 * [newHistoryCount] is separate from `result.history.size` because history merges: restoring the
 * same backup twice is legitimate and must read as "0 new", not as a threat to double every stat.
 */
data class ImportPreview(
    val result: ImportResult,
    val newHistoryCount: Int
)

class ImportRulesUseCase @Inject constructor(
    private val repository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val preferences: NudgePreferences,
    private val exporter: RuleExporter,
    private val installedApps: InstalledAppsRepository,
    private val essentialPackages: EssentialPackages
) {

    /**
     * Whether applying [result]'s settings or groups would REDUCE this device's protection — the question the
     * caller must ask before writing anything, because an import that weakens protection has to
     * pass the Strict Mode unlock challenge exactly like every other weakening action.
     *
     * An export file is plain, hand-editable JSON. Without this gate, `"strictModeEnabled": false`
     * typed into a text editor would be a one-tap way out of the commitment lock.
     *
     * Reads the LIVE preferences rather than anything captured at preview time, for the same reason
     * [execute] re-derives the new-history count instead of trusting the preview's: the user may
     * have changed a setting between seeing the dialog and confirming it.
     */
    suspend fun weakensProtection(result: ImportResult): Boolean {
        if (groupMergeWeakens(result.groups)) return true
        val incoming = result.settings ?: return false
        return ImportedSettingsWeakening.isWeakening(
            preferences.exportableSettings(),
            incoming,
            hasEnabledFirstUnlockRule = hasEnabledFirstUnlockRule()
        )
    }

    /**
     * Whether this phone has an ENABLED rule on the first-unlock schedule. That rule is the only
     * thing `dayStartMinute` means anything to (spec 5.6), so it is the fact the settings gate needs
     * before it may treat a day-start change as weakening. Read live, like the rest of the gate.
     */
    private suspend fun hasEnabledFirstUnlockRule(): Boolean =
        repository.getAllRules().firstOrNull()
            ?.any { it.enabled && it.firstUnlockMinutes != null } == true

    /**
     * The group half of [weakensProtection]: merging the file's groups into a group here that an
     * enabled All apps rule allows takes the apps that join it out of that rule
     * ([ImportedGroupsWeakening]). Rules in the file never weaken (an import only adds rules), so
     * they are not asked about.
     *
     * Existing and merged membership are RESOLVED on this device (installed apps + essentials), so a
     * category the group gains counts for the apps it brings in here, not for the setting. Only the
     * groups the file names are read back, through the same [groupIdsByName] map [execute] merges
     * into, and merged by the same [ImportedGroupMerge], so the gate judges exactly what the write does.
     */
    private suspend fun groupMergeWeakens(incoming: List<ExportedGroup>): Boolean {
        if (incoming.isEmpty()) return false
        val entriesByName = incoming.groupBy { it.name }
        val allGroups = repository.getAllGroups().firstOrNull() ?: emptyList()
        val idsByName = groupIdsByName(allGroups)
        val existingGroups = allGroups.filter { it.name in entriesByName && idsByName[it.name] == it.id }
        if (existingGroups.isEmpty()) return false
        val device = deviceFacts()
        val installed = device.installed
        val existing = HashMap<String, ResolvedGroup>()
        val merged = HashMap<String, ResolvedGroup>()
        for (group in existingGroups) {
            val current = definitionOf(group)
            val after = ImportedGroupMerge.merge(current, entriesByName.getValue(group.name), installed)
            existing[group.name] = resolved(current, device)
            merged[group.name] = resolved(after, device)
        }
        val rules = repository.getAllRules().firstOrNull() ?: emptyList()
        return ImportedGroupsWeakening.isWeakening(existing, merged, rules)
    }

    /** Installed app -> category, the essentials and the browsers: what membership resolves against here. */
    private class DeviceFacts(
        val installed: Map<String, AppCategory>,
        val essentials: Set<String>,
        val browsers: Set<String>
    )

    private suspend fun deviceFacts() = DeviceFacts(
        installed = installedApps.getInstalledApps().associate { it.packageName to it.category },
        essentials = essentialPackages.current(),
        browsers = essentialPackages.browsers()
    )

    private suspend fun definitionOf(group: AppGroup): GroupDefinition =
        group.definition(repository.getGroupMembers(group.id).firstOrNull() ?: emptyList())

    private fun resolved(definition: GroupDefinition, device: DeviceFacts) = ResolvedGroup(
        definition.id,
        GroupMembershipResolver.membersOf(definition, device.installed, device.essentials, device.browsers)
    )

    /**
     * Parses and validates JSON without inserting, and asks the DB how much of the file's history
     * is actually new.
     *
     * Suspending and off-main: history is unbounded (retention is not enforced), so parsing a
     * heavy user's backup on the UI thread is an ANR.
     */
    suspend fun preview(json: String): ImportPreview {
        val result = withContext(Dispatchers.Default) { exporter.importRules(json) }
        if (result.error != null) return ImportPreview(result, newHistoryCount = 0)
        return ImportPreview(result, newHistoryCount = newHistoryEvents(result.history).size)
    }

    /**
     * Imports rules from a validated ImportResult into the database.
     * - Creates groups that don't exist yet (by name).
     * - Skips duplicate rules (same target + mode + schedule).
     * - Assigns new IDs to all imported rules. An All apps rule's allowed groups are matched by
     *   name after the groups are merged; a name with no group is dropped (stricter, never wider).
     * - Skips a group rule whose group is neither in the file nor here, counted as invalid: written
     *   without its group it would target nothing.
     * - Applies the file's app settings, if it carries any, in ONE transaction.
     * - MERGES usage history, skipping events already present (see [HistoryMerge]).
     * - Carries forward the entries the parser had to skip, so the UI can report them.
     *
     * The CALLER is responsible for having passed [weakensProtection] through the Strict Mode gate
     * first: this method writes.
     */
    suspend fun execute(result: ImportResult): ImportOutcome {
        if (result.error != null) {
            return ImportOutcome(
                importedCount = 0,
                duplicateCount = 0,
                groupsCreated = 0,
                invalidCount = result.invalidCount,
                invalidReasons = result.invalidReasons,
                historyInvalidCount = result.invalidHistoryCount,
                settingsInvalidCount = result.invalidSettingsCount,
                error = result.error
            )
        }

        // Step 1: Resolve groups - find existing by name or create new ones, merging the file's
        // definition in ([ImportedGroupMerge]: rules only add, so nothing here removes a member).
        val existingGroups = repository.getAllGroups().firstOrNull() ?: emptyList()
        val groupNameToId = groupIdsByName(existingGroups).toMutableMap()
        val existingById = existingGroups.associateBy { it.id }
        var groupsCreated = 0
        val installed = if (result.groups.isEmpty()) emptyMap() else deviceFacts().installed

        for ((name, entries) in result.groups.groupBy { it.name }) {
            val existing = groupNameToId[name]?.let { existingById[it] }
            val current = existing?.let { definitionOf(it) } ?: GroupDefinition(id = 0)
            val merged = ImportedGroupMerge.merge(current, entries, installed)
            val categories = AppCategoryCsv.format(merged.categories)

            val groupId = existing?.id
                ?: repository.createGroup(AppGroup(name = name, categories = categories)).also {
                    groupNameToId[name] = it
                    groupsCreated++
                }
            if (existing != null && categories != existing.categories) {
                repository.setGroupCategories(groupId, categories)
            }
            // A row written over an existing one replaces it, which is how an include flips a
            // "Removed by you" row. Rows already as wanted are not rewritten.
            for (pkg in merged.includes - current.includes) {
                repository.addToGroup(AppGroupMember(groupId = groupId, packageName = pkg))
            }
            for (pkg in merged.excludes - current.excludes) {
                repository.addToGroup(AppGroupMember(groupId = groupId, packageName = pkg, excluded = true))
            }
        }

        // Step 2: Get existing rules for duplicate detection
        val existingRules = repository.getAllRules().firstOrNull() ?: emptyList()

        // Step 3: Insert rules, skipping duplicates
        var imported = 0
        var duplicates = 0
        val unplaced = mutableListOf<String>()

        for (exportedRule in result.rules) {
            val blockRule = toBlockRule(exportedRule, groupNameToId)
            if (blockRule == null) {
                unplaced += "Rule for group \"${exportedRule.groupName}\": no group by that name"
                continue
            }

            if (isDuplicate(blockRule, existingRules)) {
                duplicates++
                continue
            }

            // Adding is free under Strict Mode, an All apps rule included: a new rule only ever
            // covers more. saveAllAppsRule stores it in its one normalised shape.
            if (blockRule.allApps) repository.saveAllAppsRule(blockRule) else repository.addRule(blockRule)
            imported++
        }

        // Step 4: Apply the file's app settings, if it carries any. After the rules (which protect
        // the user) and before history (which only feeds statistics). All-or-nothing: one DataStore
        // transaction writes every carried key or none -- see [NudgePreferences.applyImportedSettings].
        //
        // Any weakening this payload represents was gated BEFORE we got here (see
        // [weakensProtection]); by this point the user has either not been weakening anything or has
        // already typed the unlock challenge.
        result.settings?.let { preferences.applyImportedSettings(it) }

        // Step 5: Merge usage history. Deliberately last -- rules are what protect the user, and a
        // history restore must never be able to stand between them and their rules.
        val fresh = newHistoryEvents(result.history)
        if (fresh.isNotEmpty()) {
            usageRepository.insertEvents(
                withContext(Dispatchers.Default) { fresh.map(HistoryMerge::toEntity) }
            )
        }

        return ImportOutcome(
            importedCount = imported,
            duplicateCount = duplicates,
            groupsCreated = groupsCreated,
            invalidCount = result.invalidCount + unplaced.size,
            invalidReasons = result.invalidReasons + unplaced,
            historyImportedCount = fresh.size,
            historyDuplicateCount = result.history.size - fresh.size,
            historyInvalidCount = result.invalidHistoryCount,
            settingsApplied = result.settings != null,
            settingsInvalidCount = result.invalidSettingsCount
        )
    }

    /**
     * The events in [history] that are not already stored.
     *
     * Only the file's own timestamp window is read back, so restoring one week of a backup does not
     * pull an entire history into memory. Used by BOTH [preview] and [execute] -- the count the
     * user is shown and the rows actually written come from the same policy, and execute re-reads
     * rather than trusting the preview's number.
     */
    private suspend fun newHistoryEvents(
        history: List<ExportedHistoryEvent>
    ): List<ExportedHistoryEvent> {
        val range = HistoryMerge.timestampRange(history) ?: return emptyList()
        val existing = usageRepository.getEventKeysInRange(range.first, range.last).toHashSet()
        return withContext(Dispatchers.Default) { HistoryMerge.selectNew(history, existing) }
    }

    /**
     * The row [exported] is written as, with its group (or an All apps rule's allowed groups)
     * resolved by name; null when a group rule's group is not in [groupNameToId].
     */
    private fun toBlockRule(exported: ExportedRule, groupNameToId: Map<String, Long>): BlockRule? {
        val groupId = exported.groupName?.let { groupNameToId[it] ?: return null }
        return BlockRule(
            packageName = exported.packageName,
            groupId = groupId,
            allApps = exported.allApps,
            exceptPackages = ExceptCsv.ofPackages(exported.exceptPackages),
            exceptGroupIds = ExceptCsv.ofGroupIds(exported.exceptGroups.mapNotNull { groupNameToId[it] }),
            mode = exported.mode,
            delaySeconds = exported.delaySeconds,
            dailyLimitMinutes = exported.dailyLimitMinutes,
            enabled = exported.enabled,
            scheduleDays = exported.scheduleDays,
            scheduleStartMinute = exported.scheduleStartMinute,
            scheduleEndMinute = exported.scheduleEndMinute,
            inAppFeatures = exported.inAppFeatures,
            grayscale = exported.grayscale,
            showCounter = exported.showCounter,
            autoKickAfter = exported.autoKickAfter,
            showTimeRemaining = exported.showTimeRemaining,
            autoKickCooldownSeconds = exported.autoKickCooldownSeconds,
            webDomains = exported.webDomains,
            autoKickAfterMinutes = exported.autoKickAfterMinutes,
            remindAfterMinutes = exported.remindAfterMinutes,
            webBlockMode = exported.webBlockMode,
            tabVanish = exported.tabVanish,
            followingSteer = exported.followingSteer,
            keepPassOnShortTrips = exported.keepPassOnShortTrips,
            // The window survives the move, but not `armedAt`: the file never carries it, and the
            // repository stamps this phone's clock on write (spec 5.8), so a restored rule starts at
            // the NEXT first unlock here.
            firstUnlockMinutes = exported.firstUnlockMinutes
        )
    }

    /**
     * A rule is a duplicate if an existing rule has the same target, mode, schedule and
     * first-unlock window (spec 5.8). The target is compared as the engine reads it
     * ([RuleTarget.of]): an All apps rule matches only an All apps rule with the same allowed apps
     * and the same allowed groups, in any order. The window is part of the identity because two
     * rules for the same app and mode but different first-unlock lengths are different rules -- one
     * of them is a shorter lockout, not a repeat of the other.
     */
    private fun isDuplicate(candidate: BlockRule, existingRules: List<BlockRule>): Boolean {
        val target = targetOf(candidate)
        return existingRules.any { existing ->
            targetOf(existing) == target &&
                existing.mode == candidate.mode &&
                existing.scheduleDays == candidate.scheduleDays &&
                existing.scheduleStartMinute == candidate.scheduleStartMinute &&
                existing.scheduleEndMinute == candidate.scheduleEndMinute &&
                existing.firstUnlockMinutes == candidate.firstUnlockMinutes
        }
    }

    private fun targetOf(rule: BlockRule): RuleTarget? = RuleTarget.of(
        packageName = rule.packageName,
        groupId = rule.groupId,
        allApps = rule.allApps,
        exceptPackages = ExceptCsv.packages(rule.exceptPackages),
        exceptGroupIds = ExceptCsv.groupIds(rule.exceptGroupIds)
    )

    private companion object {
        /**
         * This device's groups by name, the one map both the Strict Mode gate and the merge use.
         * Two groups sharing a name resolve to the later one, as they always have.
         */
        fun groupIdsByName(groups: List<AppGroup>): Map<String, Long> =
            groups.associateBy({ it.name }, { it.id })
    }
}
