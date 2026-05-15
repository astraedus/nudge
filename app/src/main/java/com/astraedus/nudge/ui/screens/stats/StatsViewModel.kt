package com.astraedus.nudge.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val formattedDuration: String,
    val fraction: Float
)

data class StatsUiState(
    val totalFormatted: String = "0s",
    val appStats: List<AppUsageStat> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val timeTracker: TimeTracker
) : ViewModel() {

    private val todayStart = timeTracker.startOfToday()
    private val todayEnd = todayStart + 24L * 60L * 60L * 1000L

    private val appNameMap: Map<String, String> by lazy {
        installedAppsRepository.getInstalledApps().associate { it.packageName to it.appName }
    }

    val uiState: StateFlow<StatsUiState> = usageRepository.getUsageForDay(todayStart, todayEnd)
        .map { events ->
            val byPackage = events.groupBy { it.packageName }
                .mapValues { (_, evts) -> evts.sumOf { it.durationMs } }
                .entries
                .sortedByDescending { it.value }

            val totalMs = byPackage.sumOf { it.value }
            val maxMs = byPackage.maxOfOrNull { it.value } ?: 1L

            StatsUiState(
                totalFormatted = timeTracker.formatDuration(totalMs),
                appStats = byPackage.map { (pkg, ms) ->
                    AppUsageStat(
                        packageName = pkg,
                        appName = appNameMap[pkg] ?: pkg,
                        durationMs = ms,
                        formattedDuration = timeTracker.formatDuration(ms),
                        fraction = (ms.toFloat() / maxMs.toFloat()).coerceIn(0.05f, 1f)
                    )
                }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())
}
