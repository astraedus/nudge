package com.astraedus.nudge.ui.screens.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.screens.stats.charts.BarSegment
import com.astraedus.nudge.ui.screens.stats.charts.RateBar
import com.astraedus.nudge.ui.screens.stats.charts.RateBarChart
import com.astraedus.nudge.ui.screens.stats.charts.SegmentedBar
import com.astraedus.nudge.ui.screens.stats.charts.Sparkline
import com.astraedus.nudge.ui.screens.stats.charts.WeekHourHeatmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterventionsScreen(
    viewModel: InterventionsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val calculator = viewModel.calculator

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interventions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InsightsRangeToggle(
                    selected = state.range,
                    onSelect = viewModel::selectRange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp)
                )
            }

            item {
                HeroTotalsCard(state = state)
            }

            item {
                InsightSection(
                    title = "Temptation Clock",
                    subtitle = "When you're most likely to hit a block"
                ) {
                    val hourlyBars = remember(state.insights.hourly) {
                        buildHourlyBars(state.insights.hourly, calculator)
                    }
                    RateBarChart(
                        bars = hourlyBars,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        emptyMessage = "No blocks yet in this period"
                    )
                    // With no data the chart states it; a second identical line under it
                    // would just read as a rendering glitch.
                    state.insights.peakHour?.let { peakHour ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Your danger hour is ${calculator.hourLabel(peakHour)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                InsightSection(
                    title = "Day of Week",
                    subtitle = "Which days test your willpower most"
                ) {
                    val weekdayBars = remember(state.insights.weekday) {
                        buildWeekdayBars(state.insights.weekday, calculator)
                    }
                    RateBarChart(
                        bars = weekdayBars,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        emptyMessage = "No blocks yet in this period"
                    )
                    state.insights.peakWeekday?.let { peakWeekday ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Toughest day: ${calculator.weekdayFullLabel(peakWeekday)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                InsightSection(
                    title = "Top Blocked Apps",
                    subtitle = "Which apps trigger the most interventions, and how"
                ) {
                    if (state.apps.isEmpty()) {
                        InsightEmptyState("No blocks yet in this period")
                    } else {
                        // Only legend the modes actually present, so a user who never used
                        // breathing mode is not shown a swatch for it.
                        val presentModes = MODE_ORDER.filter { mode ->
                            state.apps.any { (it.byMode[mode] ?: 0) > 0 }
                        }
                        ModeLegend(
                            modes = presentModes,
                            calculator = calculator,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            state.apps.forEach { row ->
                                InterventionAppRowItem(row = row, calculator = calculator)
                            }
                        }
                    }
                }
            }

            item {
                InsightSection(
                    title = "Temptation Fingerprint",
                    subtitle = "Darker means more blocks at that hour."
                ) {
                    WeekHourHeatmap(
                        grid = state.insights.heatmap,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun HeroTotalsCard(state: InterventionsUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroStat("Today", state.insights.todayTotal.toString())
                HeroStat("7 days", state.insights.weekTotal.toString())
                HeroStat("30 days", state.insights.monthTotal.toString())
                HeroStat("All time", state.allTimeTotal.toString())
            }
            Sparkline(values = state.dailyCounts, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HeroStat(caption: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ModeLegend(
    modes: List<String>,
    calculator: InsightsCalculator,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        modes.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(modeColor(mode))
                )
                Text(
                    calculator.modeLabel(mode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InterventionAppRowItem(
    row: InterventionAppRow,
    calculator: InsightsCalculator,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val icon = row.icon
            if (icon != null) {
                val bitmap = remember(icon) { icon.toBitmap(64, 64).asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = row.label,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Spacer(Modifier.size(32.dp))
            }
            Text(
                row.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                row.total.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // byMode always sums to total (the calculator normalises unknown modes to OTHER),
        // so filtering to present modes here still leaves a visually complete bar.
        val presentModes = MODE_ORDER.filter { (row.byMode[it] ?: 0) > 0 }
        SegmentedBar(
            segments = presentModes.map { mode ->
                BarSegment(weight = (row.byMode[mode] ?: 0).toFloat(), color = modeColor(mode))
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            presentModes.joinToString(" · ") { mode ->
                "${calculator.modeLabel(mode)} ${row.byMode[mode] ?: 0}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One shared mapping from mode to color, so the legend, bars and chips can never disagree. */
@Composable
private fun modeColor(mode: String): Color = when (mode) {
    "HARD_BLOCK" -> MaterialTheme.colorScheme.error
    "DELAY" -> MaterialTheme.colorScheme.primary
    // HOLD is DELAY's sibling, so it takes the primary family rather than a fifth hue: the two
    // cost the same wait, and the chart reads as three kinds of block plus "other", not four.
    "HOLD" -> MaterialTheme.colorScheme.secondary
    "BREATHING" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.surfaceVariant
}

private fun buildHourlyBars(hourly: List<Int>, calculator: InsightsCalculator): List<RateBar> {
    val max = hourly.maxOrNull() ?: 0
    return hourly.mapIndexed { hour, count ->
        RateBar(
            label = if (hour % 6 == 0) calculator.hourLabel(hour) else "",
            fraction = if (max > 0) count.toFloat() / max.toFloat() else 0f,
            confidence = 1f,
            readout = "${calculator.hourLabel(hour)}: $count blocks"
        )
    }
}

private fun buildWeekdayBars(weekday: List<Int>, calculator: InsightsCalculator): List<RateBar> {
    val max = weekday.maxOrNull() ?: 0
    return weekday.mapIndexed { index, count ->
        RateBar(
            label = calculator.weekdayLabel(index),
            fraction = if (max > 0) count.toFloat() / max.toFloat() else 0f,
            confidence = 1f,
            readout = "${calculator.weekdayLabel(index)}: $count blocks"
        )
    }
}

/**
 * Legend and stacking order. Derived from [InsightsCalculator.KNOWN_MODES] rather than re-listed,
 * so a block mode cannot be counted by the calculator and then be invisible on the chart drawn from
 * it: a mode missing from this list has no bar, no legend row and no chip, with nothing failing.
 */
private val MODE_ORDER =
    InsightsCalculator.KNOWN_MODES.toList() + InsightsCalculator.OTHER_MODE
