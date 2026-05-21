package com.astraedus.nudge.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@Immutable
data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val formattedDuration: String,
    val fraction: Float
)

@Immutable
data class StatsUiState(
    val totalFormatted: String = "0s",
    val appStats: List<AppUsageStat> = emptyList(),
    val weeklyData: List<DayData> = emptyList(),
    val trendData: List<TrendDay> = emptyList(),
    val hourlyMs: List<Long> = List(24) { 0L },
    val streakDays: Int = 0,
    val hasUsagePermission: Boolean = true,
    val isToday: Boolean = true,
    val dateLabel: String = "Today"
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val statsCalculator: StatsCalculator
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    private val appNameCache = mutableMapOf<String, String>()

    private fun resolveAppName(packageName: String): String {
        return appNameCache.getOrPut(packageName) {
            installedAppsRepository.resolveAppName(packageName)
        }
    }

    fun goToPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun goToNextDay() {
        val next = _selectedDate.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) {
            _selectedDate.value = next
        }
    }

    private val weekEventsFlow = _selectedDate.flatMapLatest { date ->
        val dayStartMs = date.toEpochMs()
        val weekStart = dayStartMs - 6 * DAY_MS
        usageRepository.getEventsSince(weekStart)
    }

    private val screenTimeFlow = _selectedDate.flatMapLatest { date ->
        flow {
            while (true) {
                val dayStartMs = date.toEpochMs()
                val isToday = date == LocalDate.now()
                val dayEndMs = if (isToday) System.currentTimeMillis() else dayStartMs + DAY_MS
                val total = screenTimeProvider.getTotalScreenTime(dayStartMs, dayEndMs)
                val weekly = screenTimeProvider.getDailyScreenTimesForWeek(dayStartMs)
                val hourly = screenTimeProvider.getHourlyScreenTime(dayStartMs, dayEndMs)
                val perApp = screenTimeProvider.getPerAppScreenTime(dayStartMs, dayEndMs)
                emit(ScreenTimeSnapshot(total, weekly, hourly, perApp))
                delay(30_000L)
            }
        }
    }

    val uiState: StateFlow<StatsUiState> = combine(
        weekEventsFlow,
        screenTimeFlow,
        _selectedDate
    ) { weekEvents, screenTime, date ->
        buildUiState(weekEvents, screenTime, date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private fun buildUiState(
        weekEvents: List<UsageEvent>,
        screenTime: ScreenTimeSnapshot,
        date: LocalDate
    ): StatsUiState {
        val hasPermission = screenTimeProvider.hasPermission()
        val isToday = date == LocalDate.now()
        val dayStartMs = date.toEpochMs()

        val byPackage = screenTime.perApp.entries
            .sortedByDescending { it.value }

        val maxMs = byPackage.maxOfOrNull { it.value } ?: 1L

        val appStats = byPackage
            .filter { it.value > 0L }
            .map { (pkg, ms) ->
                AppUsageStat(
                    packageName = pkg,
                    appName = resolveAppName(pkg),
                    durationMs = ms,
                    formattedDuration = timeTracker.formatDuration(ms),
                    fraction = (ms.toFloat() / maxMs.toFloat()).coerceIn(0.05f, 1f)
                )
            }

        val weeklyData = statsCalculator.buildWeeklyDataFromTotals(screenTime.weeklyDailyTotals, dayStartMs)

        val totalFormatted = if (hasPermission && screenTime.totalTodayMs < 60_000L) {
            "< 1m"
        } else {
            timeTracker.formatDuration(screenTime.totalTodayMs)
        }

        val dateLabel = if (isToday) "Today" else formatDateLabel(date)

        return StatsUiState(
            totalFormatted = totalFormatted,
            appStats = appStats,
            weeklyData = weeklyData,
            trendData = statsCalculator.buildTrendData(weekEvents, dayStartMs),
            hourlyMs = screenTime.hourlyTodayMs,
            streakDays = statsCalculator.calculateStreak(weekEvents, dayStartMs),
            hasUsagePermission = hasPermission,
            isToday = isToday,
            dateLabel = dateLabel
        )
    }

    private data class ScreenTimeSnapshot(
        val totalTodayMs: Long,
        val weeklyDailyTotals: List<Long>,
        val hourlyTodayMs: List<Long>,
        val perApp: Map<String, Long>
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        fun LocalDate.toEpochMs(): Long =
            atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun formatDateLabel(date: LocalDate): String {
            val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            return "$dayOfWeek, $month ${date.dayOfMonth}"
        }
    }
}
