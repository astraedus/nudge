package com.astraedus.nudge.ui.screens.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.model.BlockMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppWithBlockStatus(
    val appInfo: InstalledAppsRepository.AppInfo,
    val isBlocked: Boolean,
    val ruleId: Long? = null
)

data class AppListUiState(
    val apps: List<AppWithBlockStatus> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val blockRuleRepository: BlockRuleRepository
) : ViewModel() {

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
            if (currentlyBlocked && ruleId != null) {
                blockRuleRepository.deleteRule(ruleId)
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
}
