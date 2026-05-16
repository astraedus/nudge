package com.astraedus.nudge.ui.screens.rules

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActiveRulesGroup(
    val packageName: String,
    val appName: String,
    val rules: List<RuleSummary>
)

data class ActiveRulesUiState(
    val groups: List<ActiveRulesGroup> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class ActiveRulesViewModel @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActiveRulesUiState())
    val uiState: StateFlow<ActiveRulesUiState> = _uiState.asStateFlow()

    init {
        loadActiveRules()
    }

    private fun loadActiveRules() {
        viewModelScope.launch {
            blockRuleRepository.getEnabledRules().collect { rules ->
                val appInfoMap = installedAppsRepository.getInstalledApps()
                    .associateBy { it.packageName }

                val grouped = rules
                    .filter { it.packageName != null }
                    .groupBy { it.packageName!! }
                    .map { (pkg, pkgRules) ->
                        val appName = appInfoMap[pkg]?.appName ?: pkg
                        val summaries = pkgRules.map { rule ->
                            val modeLabel = when (rule.mode) {
                                "HARD_BLOCK" -> "Hard Block"
                                "DELAY" -> "Delay ${rule.delaySeconds}s"
                                "BREATHING" -> "Breathing ${rule.delaySeconds}s"
                                else -> rule.mode
                            }
                            val features = rule.inAppFeatures?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                            val featureLabel = if (features.isNotEmpty()) {
                                features.joinToString(", ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                            } else "Whole app"

                            val extras = buildList {
                                if (rule.dailyLimitMinutes != null) add("${rule.dailyLimitMinutes}min/day limit")
                                if (rule.scheduleDays != null) add("Scheduled")
                                if (rule.grayscale) add("Grayscale")
                                if (rule.showCounter) add("Counter")
                                if (rule.autoKickAfter != null) add("Auto-close@${rule.autoKickAfter}")
                            }
                            val extraStr = if (extras.isNotEmpty()) " + ${extras.joinToString(", ")}" else ""

                            RuleSummary(
                                id = rule.id,
                                mode = rule.mode,
                                enabled = rule.enabled,
                                description = "$featureLabel: $modeLabel$extraStr"
                            )
                        }
                        ActiveRulesGroup(
                            packageName = pkg,
                            appName = appName,
                            rules = summaries
                        )
                    }
                    .sortedBy { it.appName.lowercase() }

                _uiState.value = ActiveRulesUiState(groups = grouped, isLoading = false)
            }
        }
    }

    fun toggleRuleEnabled(ruleId: Long, currentlyEnabled: Boolean) {
        viewModelScope.launch {
            val rules = blockRuleRepository.getAllRules().firstOrNull() ?: return@launch
            val rule = rules.find { it.id == ruleId } ?: return@launch
            blockRuleRepository.updateRule(rule.copy(enabled = !currentlyEnabled))
        }
    }
}
