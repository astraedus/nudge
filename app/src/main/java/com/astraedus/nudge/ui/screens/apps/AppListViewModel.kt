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
import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class AppListUiState(
    val sections: ManageAppsSections = ManageAppsSections(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    /**
     * A group that appeared while this screen was away (just saved in the editor): the screen
     * scrolls back to the groups, then calls [AppListViewModel.onGroupRevealed].
     */
    val revealGroupId: Long? = null
)

private data class GroupData(
    val groups: List<AppGroup>,
    val members: List<AppGroupMember>,
    val rules: List<BlockRule>
)

/**
 * Manage Apps: one list of every rule the user has made and every app they could make one for
 * (spec 3.2; it replaced the separate Active Rules screen). The sections are built by the pure
 * [buildManageAppsSections]; this class feeds it the tables and owns the switches.
 */
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

    /** The launcher apps; null until PackageManager has answered once (cached after that). */
    private val installedApps = MutableStateFlow<List<AppInfo>?>(null)

    private val revealGroupId = MutableStateFlow<Long?>(null)

    /** Group ids seen in the last emission; null before the first. Outlives a trip to the editor. */
    private var knownGroupIds: Set<Long>? = null

    /** Packages whose "no rule yet" switch was just flipped: one tap makes one rule. */
    private val creating = HashSet<String>()

    /**
     * Apps switched on from this screen during this visit. They stay where they were in the list
     * (see `keepInPlace` on [buildManageAppsSections]) until Manage Apps is opened again.
     */
    private val switchedOnHere = MutableStateFlow<Set<String>>(emptySet())

    private val filters = combine(searchQuery, switchedOnHere, ::Pair)

    private val groupData = combine(
        blockRuleRepository.getAllGroups(),
        blockRuleRepository.getAllGroupMembers(),
        blockRuleRepository.getAllRules(),
        ::GroupData
    ).onEach { data ->
        newlyAddedGroupId(knownGroupIds, data.groups)?.let { revealGroupId.value = it }
        knownGroupIds = data.groups.mapTo(HashSet()) { it.id }
    }

    /**
     * Names and icons for packages that have a rule but are not in the launcher list (uninstalled,
     * say). Active Rules listed those, so the merged list must too, or the rule disappears from
     * view while the engine still applies it on a reinstall. Looked up once the launcher list is in,
     * through the repository's per-package cache.
     */
    private val extraApps = combine(
        installedApps.filterNotNull(),
        blockRuleRepository.getAllRules()
    ) { installed, rules ->
        val launcher = installed.mapTo(HashSet()) { it.packageName }
        rules.mapNotNullTo(sortedSetOf()) { rule -> rule.packageName?.takeIf { it !in launcher } }
    }
        .distinctUntilChanged()
        .map { missing ->
            missing.associateWith { pkg ->
                AppInfo(
                    packageName = pkg,
                    appName = installedAppsRepository.resolveAppName(pkg),
                    icon = installedAppsRepository.resolveIcon(pkg)
                )
            }
        }
        .onStart { emit(emptyMap()) }

    val uiState: StateFlow<AppListUiState> = combine(
        installedApps,
        groupData,
        filters,
        revealGroupId,
        extraApps
    ) { installed, data, (query, keepInPlace), reveal, extras ->
        AppListUiState(
            sections = buildManageAppsSections(
                installed = installed.orEmpty(),
                groups = data.groups,
                members = data.members,
                rules = data.rules,
                query = query,
                extraApps = extras,
                keepInPlace = keepInPlace
            ),
            searchQuery = query,
            isLoading = false,
            revealGroupId = reveal
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

    /**
     * An app row's switch, with Active Rules' meaning (spec 3.2). Off is a weakening and Strict
     * Mode gated, from whichever section the row is in. On re-enables the app's own rules. An app
     * with no rule gets Delay 15s, as the switch always did.
     */
    fun toggleApp(row: AppRow) {
        val pkg = row.packageName
        viewModelScope.launch {
            when {
                !row.hasRule -> {
                    // A second tap before the list catches up would add a second rule.
                    if (!creating.add(pkg)) return@launch
                    // Before the write, so the first list with the rule already keeps the row put.
                    switchedOnHere.value = switchedOnHere.value + pkg
                    try {
                        blockRuleRepository.addRule(
                            BlockRule(
                                packageName = pkg,
                                mode = BlockMode.DELAY.name,
                                delaySeconds = 15,
                                enabled = true
                            )
                        )
                    } finally {
                        creating.remove(pkg)
                    }
                }
                row.isOn -> strictModeGate.run(prompt = "Turn off blocking for this app") {
                    blockRuleRepository.setEnabledForPackage(pkg, false)
                }
                else -> blockRuleRepository.setEnabledForPackage(pkg, true)
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

    /**
     * An All apps row's switch. Off is a weakening, gated like a group's (spec 5.6); on re-enables
     * the rule. Written through [BlockRuleRepository.saveAllAppsRule], which keeps the one-target
     * invariant.
     */
    fun toggleAllApps(item: AllAppsRuleItem) {
        viewModelScope.launch {
            val rule = item.rule
            if (item.enabled) {
                strictModeGate.run(prompt = "Turn off blocking for these apps") {
                    blockRuleRepository.saveAllAppsRule(rule.copy(enabled = false))
                }
            } else {
                blockRuleRepository.saveAllAppsRule(rule.copy(enabled = true))
            }
        }
    }

    /** The screen has scrolled [AppListUiState.revealGroupId] into view (or could not: a search hides it). */
    fun onGroupRevealed() {
        revealGroupId.value = null
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
         * The group to bring into view after the list changes: one whose id was not there last time,
         * first by name. Null on the first load (nothing was "added", the list just opened).
         *
         * Why this exists: a group saved in the editor must be on screen when the user comes back,
         * or the list looks like it dropped what they just made (device QA, PR 2). The groups live in
         * the list's first row ([ManageAppsRow.Top]), so the screen scrolls back to the top.
         */
        fun newlyAddedGroupId(known: Set<Long>?, groups: List<AppGroup>): Long? {
            if (known == null) return null
            return groups.filter { it.id !in known }.minByOrNull { it.name.lowercase() }?.id
        }
    }
}
