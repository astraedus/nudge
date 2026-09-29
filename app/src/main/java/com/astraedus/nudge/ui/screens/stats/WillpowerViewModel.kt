package com.astraedus.nudge.ui.screens.stats

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.engine.TimeTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Immutable
data class WillpowerAppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val walkAways: Int,
    val gaveIn: Int,
    val rate: Float,
    val ratePercent: String
)

@Immutable
data class WillpowerUiState(
    val range: InsightsRange = InsightsRange.THIRTY_DAYS,
    val insights: WillpowerInsights = WillpowerInsights(),
    val timeReclaimed: TimeReclaimed = TimeReclaimed(),
    val timeReclaimedFormatted: String = "0m",
    val apps: List<WillpowerAppRow> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * Feeds the "Willpower" insights screen. Loads `usage_events` ONCE for the widest
 * (30-day) window — which is also the retention bound — and lets [InsightsCalculator]
 * slice that single list per selected [InsightsRange] rather than re-subscribing to a
 * new Room query on every toggle flip.
 */
@HiltViewModel
class WillpowerViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    val calculator: InsightsCalculator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _range = MutableStateFlow(InsightsRange.THIRTY_DAYS)

    private val eventsFlow = usageRepository.getEventsSince(
        calculator.rangeStartMs(System.currentTimeMillis(), ZoneId.systemDefault(), InsightsRange.THIRTY_DAYS)
    )

    val uiState: StateFlow<WillpowerUiState> = combine(eventsFlow, _range) { events, range ->
        buildUiState(events, range)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WillpowerUiState())

    fun selectRange(range: InsightsRange) {
        _range.value = range
    }

    private suspend fun buildUiState(events: List<UsageEvent>, range: InsightsRange): WillpowerUiState {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val insights = calculator.willpower(events, now, zone, range)

        val rangeStartMs = calculator.rangeStartMs(now, zone, range)
        val avgSessionMsByPackage = withContext(ioDispatcher) {
            screenTimeProvider.getPerAppSessionStats(rangeStartMs, now)
        }.mapNotNull { (pkg, stats) -> stats.averageMs?.let { pkg to it } }.toMap()

        val timeReclaimed = calculator.estimateTimeReclaimed(insights.apps, avgSessionMsByPackage)

        val appRows = insights.apps.take(APP_ROW_LIMIT).map { app ->
            val resolvedName = installedAppsRepository.resolveAppName(app.packageName)
            val icon = installedAppsRepository.resolveIcon(app.packageName)
            WillpowerAppRow(
                packageName = app.packageName,
                label = calculator.appDisplayLabel(app.packageName, resolvedName),
                icon = icon,
                walkAways = app.walkAways,
                gaveIn = app.gaveIn,
                rate = app.rate,
                ratePercent = calculator.formatPercent(app.rate)
            )
        }

        return WillpowerUiState(
            range = range,
            insights = insights,
            timeReclaimed = timeReclaimed,
            timeReclaimedFormatted = timeTracker.formatDuration(timeReclaimed.totalMs),
            apps = appRows,
            isLoading = false
        )
    }

    companion object {
        private const val APP_ROW_LIMIT = 8
    }
}
