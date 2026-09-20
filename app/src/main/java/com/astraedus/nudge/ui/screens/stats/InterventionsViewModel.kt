package com.astraedus.nudge.ui.screens.stats

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class InterventionAppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val total: Int,
    val byMode: Map<String, Int>
)

@Immutable
data class InterventionsUiState(
    val range: InsightsRange = InsightsRange.THIRTY_DAYS,
    val insights: InterventionInsights = InterventionInsights(),
    val allTimeTotal: Int = 0,
    val dailyCounts: List<Int> = emptyList(),
    val apps: List<InterventionAppRow> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * Backs the "Interventions" screen — temptation-pattern insights (when/where/how the user
 * gets blocked), as opposed to [StatsViewModel]'s raw screen-time view.
 */
@HiltViewModel
class InterventionsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    val calculator: InsightsCalculator
) : ViewModel() {

    private val _range = MutableStateFlow(InsightsRange.THIRTY_DAYS)

    // Widest window the screen ever needs. Loaded ONCE — the range toggle re-slices this
    // same list via the calculator rather than re-querying Room, so flipping 7d/30d never
    // touches the DB.
    private val windowStartMs: Long = calculator.rangeStartMs(
        System.currentTimeMillis(),
        ZoneId.systemDefault(),
        InsightsRange.THIRTY_DAYS
    )

    private val eventsFlow = usageRepository.getEventsSince(windowStartMs)

    /**
     * All-time confrontations, straight from the one corrected query.
     *
     * This used to be two counts combined here and subtracted by the calculator
     * (`blocked - changedMind`), a correction this screen did and the home dashboard did not.
     * The correction now lives in the query itself, so there is no second count to read and no
     * arithmetic for a screen to get wrong or forget.
     */
    private val allTimeShownFlow = usageRepository.getAllTimeShownCount()

    val uiState: StateFlow<InterventionsUiState> = combine(
        eventsFlow,
        _range,
        allTimeShownFlow
    ) { events, range, allTimeShown ->
        buildUiState(events, range, allTimeShown)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InterventionsUiState())

    fun selectRange(range: InsightsRange) {
        _range.value = range
    }

    private suspend fun buildUiState(
        events: List<UsageEvent>,
        range: InsightsRange,
        allTimeShown: Int
    ): InterventionsUiState {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val insights = calculator.interventions(events, now, zone, range)

        val appRows = insights.apps.take(TOP_APPS_LIMIT).map { stat ->
            InterventionAppRow(
                packageName = stat.packageName,
                label = calculator.appDisplayLabel(
                    stat.packageName,
                    installedAppsRepository.resolveAppName(stat.packageName)
                ),
                icon = installedAppsRepository.resolveIcon(stat.packageName),
                total = stat.total,
                byMode = stat.byMode
            )
        }

        return InterventionsUiState(
            range = range,
            insights = insights,
            // Already de-duplicated by the query, with the same predicate `interventions`
            // classifies by — so the hero number and the bars beneath it count the same thing.
            allTimeTotal = allTimeShown,
            dailyCounts = insights.dailySeries.map { it.count },
            apps = appRows,
            isLoading = false
        )
    }

    companion object {
        private const val TOP_APPS_LIMIT = 8
    }
}
