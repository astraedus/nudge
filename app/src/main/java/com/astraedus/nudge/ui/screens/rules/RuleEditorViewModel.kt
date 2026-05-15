package com.astraedus.nudge.ui.screens.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.domain.model.BlockMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleEditorUiState(
    val packageName: String = "",
    val blockMode: BlockMode = BlockMode.DELAY,
    val delaySeconds: Int = 15,
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitMinutes: Int = 30,
    val existingRuleId: Long? = null,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false
)

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val blockRuleRepository: BlockRuleRepository
) : ViewModel() {

    private val packageName: String = savedStateHandle.get<String>("packageName") ?: ""

    private val _uiState = MutableStateFlow(RuleEditorUiState(packageName = packageName))
    val uiState: StateFlow<RuleEditorUiState> = _uiState.asStateFlow()

    init {
        loadExistingRule()
    }

    private fun loadExistingRule() {
        viewModelScope.launch {
            val rules = blockRuleRepository.getAllRules().firstOrNull() ?: emptyList()
            val existing = rules.find { it.packageName == packageName }
            if (existing != null) {
                _uiState.value = _uiState.value.copy(
                    blockMode = try {
                        BlockMode.valueOf(existing.mode)
                    } catch (_: IllegalArgumentException) {
                        BlockMode.DELAY
                    },
                    delaySeconds = existing.delaySeconds,
                    dailyLimitEnabled = existing.dailyLimitMinutes != null,
                    dailyLimitMinutes = existing.dailyLimitMinutes ?: 30,
                    existingRuleId = existing.id
                )
            }
        }
    }

    fun setBlockMode(mode: BlockMode) {
        _uiState.value = _uiState.value.copy(blockMode = mode)
    }

    fun setDelaySeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(delaySeconds = seconds)
    }

    fun setDailyLimitEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(dailyLimitEnabled = enabled)
    }

    fun setDailyLimitMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(dailyLimitMinutes = minutes)
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val rule = BlockRule(
                id = state.existingRuleId ?: 0,
                packageName = state.packageName,
                mode = state.blockMode.name,
                delaySeconds = state.delaySeconds,
                dailyLimitMinutes = if (state.dailyLimitEnabled) state.dailyLimitMinutes else null,
                enabled = true
            )
            if (state.existingRuleId != null) {
                blockRuleRepository.updateRule(rule)
            } else {
                blockRuleRepository.addRule(rule)
            }
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }

    fun delete() {
        viewModelScope.launch {
            val ruleId = _uiState.value.existingRuleId ?: return@launch
            blockRuleRepository.deleteRule(ruleId)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }
}
