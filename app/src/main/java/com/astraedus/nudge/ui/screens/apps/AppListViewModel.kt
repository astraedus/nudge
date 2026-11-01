package com.astraedus.nudge.ui.screens.apps

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.lock.StrictModeGate
import com.astraedus.nudge.ui.screens.groups.ownGroupRule
import com.astraedus.nudge.ui.screens.rules.ActiveRulesViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class AppWithBlockStatus(
    val appInfo: InstalledAppsRepository.AppInfo,
    val isBlocked: Boolean,
    val ruleId: Long? = null
)

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

@Immutable
data class AppListUiState(
    val groups: List<GroupRow> = emptyList(),
    val apps: List<AppWithBlockStatus> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

private data class GroupData(
    val groups: List<AppGroup>,
    val members: List<AppGroupMember>,
    val rules: List<BlockRule>
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val blockRuleRepository: BlockRuleRepository,
    nudgePreferences: NudgePreferences
) : ViewModel() {

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if turning a block off is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    private val searchQuery = MutableStateFlow("")
    private val installedApps = MutableStateFlow<List<InstalledAppsRepository.AppInfo>>(emptyList())

    private val groupData = combine(
        blockRuleRepository.getAllGroups(),
        blockRuleRepository.getAllGroupMembers(),
        blockRuleRepository.getAllRules(),
        ::GroupData
    )

    val uiState: StateFlow<AppListUiState> = combine(
        installedApps,
        groupData,
        searchQuery
    ) { apps, data, query ->
        val rulesByPackage = data.rules
            .filter { it.packageName != null }
            .associateBy { it.packageName }

        val filtered = apps.filter {
            query.isBlank() || it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }

        AppListUiState(
            groups = buildGroupRows(data.groups, data.members, data.rules, query),
            apps = filtered.map { app ->
                val rule = rulesByPackage[app.packageName]
                AppWithBlockStatus(
                    appInfo = app,
                    isBlocked = rule != null && rule.enabled,
                    ruleId = rule?.id
                )
            },
            searchQuery = query,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppListUiState())

    init {
        viewModelScope.launch {
            installedApps.value = installedAppsRepository.getInstalledApps()
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun toggleBlockRule(packageName: String, currentlyBlocked: Boolean, ruleId: Long?) {
        viewModelScope.launch {
            if (ruleId != null) {
                if (currentlyBlocked) {
                    // Turning a block off weakens protection: same gate as Active Rules.
                    strictModeGate.run(prompt = "Disable blocking for this app") {
                        blockRuleRepository.setEnabledForPackage(packageName, false)
                    }
                } else {
                    blockRuleRepository.setEnabledForPackage(packageName, true)
                }
            } else {
                blockRuleRepository.addRule(
                    BlockRule(
                        packageName = packageName,
                        mode = BlockMode.DELAY.name,
                        delaySeconds = 15,
                        enabled = true
                    )
                )
            }
        }
    }

    /**
     * A group row's switch. Off is a weakening, gated like an app's switch (spec 5.6). On re-enables
     * the group's own rule, or gives a rule-less group (an imported one) the same Delay 15s an app's
     * switch starts with.
     */
    fun toggleGroup(row: GroupRow) {
        viewModelScope.launch {
            val rule = row.rule
            when {
                rule == null -> blockRuleRepository.addRule(
                    BlockRule(groupId = row.groupId, mode = BlockMode.DELAY.name, delaySeconds = 15)
                )
                row.blocking -> strictModeGate.run(prompt = "Turn off blocking for this group") {
                    blockRuleRepository.updateRule(rule.copy(enabled = false))
                }
                else -> blockRuleRepository.updateRule(rule.copy(enabled = true))
            }
        }
    }

    /** Called from the challenge dialog; runs the pending weakening action on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }

    companion object {
        /**
         * The group rows, sorted by name. A search keeps the groups whose NAME matches: a group is
         * found by what the user called it, not by the apps inside it.
         */
        fun buildGroupRows(
            groups: List<AppGroup>,
            members: List<AppGroupMember>,
            rules: List<BlockRule>,
            query: String
        ): List<GroupRow> {
            val countByGroup = members.groupingBy { it.groupId }.eachCount()
            val needle = query.trim()
            return groups
                .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
                .map { group ->
                    val rule = ownGroupRule(group.id, rules)
                    GroupRow(
                        groupId = group.id,
                        title = ActiveRulesViewModel.groupTitle(group.name, countByGroup[group.id] ?: 0),
                        summary = ActiveRulesViewModel.groupSummaryText(rule),
                        blocking = rule?.enabled == true,
                        rule = rule
                    )
                }
        }
    }
}
