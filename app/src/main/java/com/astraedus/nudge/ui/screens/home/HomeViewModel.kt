package com.astraedus.nudge.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.ui.lock.StrictModeGate
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
    val changedMindCountToday: Int = 0,
    val allTimeBlockedCount: Int = 0,
    val allTimeChangedMindCount: Int = 0,
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

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

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

    // Combine today + all-time counts into a single flow to stay within combine's 5-param limit
    private val countsFlow = combine(
        usageRepository.getBlockedCountForDay(todayStart, todayEnd),
        usageRepository.getChangedMindCountForDay(todayStart, todayEnd),
        usageRepository.getAllTimeBlockedCount(),
        usageRepository.getAllTimeChangedMindCount()
    ) { blockedToday, changedMindToday, allTimeBlocked, allTimeChangedMind ->
        CountsSnapshot(blockedToday, changedMindToday, allTimeBlocked, allTimeChangedMind)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        nudgePreferences.isGlobalEnabled,
        blockRuleRepository.getEnabledRules(),
        countsFlow,
        screenTimeFlow
    ) { enabled, rules, counts, screenTimeMs ->
        val hasPermission = screenTimeProvider.hasPermission()
        HomeUiState(
            isGlobalEnabled = enabled,
            todayTotalUsageFormatted = if (hasPermission && screenTimeMs < 60_000L) {
                "< 1m"
            } else {
                timeTracker.formatDuration(screenTimeMs)
            },
            activeRuleCount = rules
                .mapNotNull { it.packageName }
                .distinct()
                .size,
            blockedCountToday = counts.blockedToday,
            changedMindCountToday = counts.changedMindToday,
            allTimeBlockedCount = counts.allTimeBlocked,
            allTimeChangedMindCount = counts.allTimeChangedMind,
            hasUsagePermission = hasPermission
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private data class CountsSnapshot(
        val blockedToday: Int,
        val changedMindToday: Int,
        val allTimeBlocked: Int,
        val allTimeChangedMind: Int
    )

    fun toggleGlobalEnabled() {
        viewModelScope.launch {
            val current = uiState.value.isGlobalEnabled
            // Turning protection ON is free; only ON -> OFF (weakening) is gated by Strict Mode.
            if (current) {
                strictModeGate.run(prompt = "Turn off all blocking") {
                    nudgePreferences.setGlobalEnabled(false)
                }
            } else {
                nudgePreferences.setGlobalEnabled(true)
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
