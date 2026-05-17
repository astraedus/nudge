package com.astraedus.nudge.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.Immutable
import javax.inject.Inject

@Immutable
data class HomeUiState(
    val isGlobalEnabled: Boolean = true,
    val todayTotalUsageFormatted: String = "0s",
    val activeRuleCount: Int = 0,
    val blockedCountToday: Int = 0,
    val changedMindCount: Int = 0,
    val hasUsagePermission: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val nudgePreferences: NudgePreferences,
    private val usageRepository: UsageRepository,
    private val blockRuleRepository: BlockRuleRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker
) : ViewModel() {

    private val todayStart = timeTracker.startOfToday()
    private val todayEnd = todayStart + 24L * 60L * 60L * 1000L

    /**
     * Emits the current screen time every 30 seconds.
     * UsageStatsManager is not reactive (no Flow), so we poll.
     */
    private val screenTimeFlow = flow {
        while (true) {
            emit(screenTimeProvider.getTotalScreenTimeToday())
            delay(30_000L)
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        nudgePreferences.isGlobalEnabled,
        blockRuleRepository.getEnabledRules(),
        usageRepository.getBlockedCountForDay(todayStart, todayEnd),
        usageRepository.getChangedMindCountForDay(todayStart, todayEnd),
        screenTimeFlow
    ) { enabled, rules, blockedCount, changedMind, screenTimeMs ->
        HomeUiState(
            isGlobalEnabled = enabled,
            todayTotalUsageFormatted = timeTracker.formatDuration(screenTimeMs),
            activeRuleCount = rules.size,
            blockedCountToday = blockedCount,
            changedMindCount = changedMind,
            hasUsagePermission = screenTimeProvider.hasPermission()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun toggleGlobalEnabled() {
        viewModelScope.launch {
            val current = uiState.value.isGlobalEnabled
            nudgePreferences.setGlobalEnabled(!current)
        }
    }
}
