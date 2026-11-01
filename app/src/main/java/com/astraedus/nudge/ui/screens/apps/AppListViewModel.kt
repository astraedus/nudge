package com.astraedus.nudge.ui.screens.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.Immutable
import javax.inject.Inject

@Immutable
data class AppWithBlockStatus(
    val appInfo: InstalledAppsRepository.AppInfo,
    val isBlocked: Boolean,
    val ruleId: Long? = null
)

@Immutable
data class AppListUiState(
    val apps: List<AppWithBlockStatus> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
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

    val uiState: StateFlow<AppListUiState> = combine(
        installedApps,
        blockRuleRepository.getAllRules(),
        searchQuery
    ) { apps, rules, query ->
        val rulesByPackage = rules
            .filter { it.packageName != null }
            .associateBy { it.packageName }

        val filtered = apps.filter {
            query.isBlank() || it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }

        AppListUiState(
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

    /** Called from the challenge dialog; runs the pending weakening action on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }
}
