package com.astraedus.nudge.ui.screens.stats

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.ProportionalBar
import com.astraedus.nudge.ui.screens.stats.charts.BlockedTrendChart
import com.astraedus.nudge.ui.screens.stats.charts.HourlyHeatmap
import com.astraedus.nudge.ui.screens.stats.charts.StreakCounter
import com.astraedus.nudge.ui.screens.stats.charts.WeeklyBarChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAppDetail: (String) -> Unit = {},
    onNavigateToWillpower: () -> Unit = {},
    onNavigateToInterventions: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage Stats") },
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
                DayNavigationHeader(
                    dayLabel = state.dateLabel,
                    rangeLabel = state.weekRangeLabel,
                    canGoForward = state.canGoForward,
                    isToday = state.isToday,
                    onPreviousDay = viewModel::goToPreviousDay,
                    onNextDay = viewModel::goToNextDay,
                    onJumpToToday = viewModel::jumpToToday
                )
            }

            item {
                val cardModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .let { mod ->
                        if (!state.hasUsagePermission) {
                            mod.clickable {
                                context.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            }
                        } else mod
                    }

                Card(
                    modifier = cardModifier,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Screen time · ${state.dateLabel}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            if (state.hasUsagePermission) state.totalFormatted else "--",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (state.hasUsagePermission) {
                            Text(
                                "${state.weekTotalFormatted} · ${state.weekRangeLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        } else {
                            Text(
                                "Tap to enable usage access",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item {
                StreakCounter(
                    streakDays = state.streakDays,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InsightEntryCard(
                        icon = Icons.Outlined.ThumbUp,
                        title = "Willpower",
                        supporting = "How often you walk away, and when you're strongest",
                        onClick = onNavigateToWillpower,
                        modifier = Modifier.weight(1f)
                    )
                    InsightEntryCard(
                        icon = Icons.Outlined.Block,
                        title = "Temptation patterns",
                        supporting = "Which apps pull hardest, and at what hours",
                        onClick = onNavigateToInterventions,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                InsightSection(
                    title = "Screen time",
                    subtitle = "${state.weekRangeLabel} · tap a bar to see that day"
                ) {
                    WeeklyBarChart(
                        days = state.weeklyData,
                        selectedIndex = state.selectedDayIndex,
                        onSelectDay = viewModel::selectDay,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            item {
                InsightSection(
                    title = "Nudge effectiveness",
                    subtitle = "${state.weekRangeLabel} · tap a bar to see that day"
                ) {
                    BlockedTrendChart(
                        days = state.trendData,
                        selectedIndex = state.selectedDayIndex,
                        onSelectDay = viewModel::selectDay,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    SelectedDayNudgeRow(
                        dayLabel = state.dateLabel,
                        blocked = state.selectedDayBlocked,
                        walkedAway = state.selectedDayWalkedAway
                    )
                }
            }

            item {
                InsightSection(
                    title = "Hourly pattern",
                    subtitle = state.dateLabel
                ) {
                    HourlyHeatmap(
                        hourlyMs = state.hourlyMs,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            if (state.appStats.isNotEmpty()) {
                item {
                    Text(
                        "App usage · ${state.dateLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                items(state.appStats, key = { it.packageName }) { stat ->
                    UsageBar(
                        stat = stat,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { onNavigateToAppDetail(stat.packageName) }
                    )
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No app usage recorded · ${state.dateLabel}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * The day header for every day-scoped stats screen.
 *
 * It carries three things the old arrows-only row did not: which day is selected in plain
 * words, which 7-day window the charts below are drawing, and — only when it can do something —
 * a way back to today. Shared by [StatsScreen] and [AppDetailScreen] so the two can never
 * disagree about how a day is announced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayNavigationHeader(
    dayLabel: String,
    rangeLabel: String,
    canGoForward: Boolean,
    isToday: Boolean,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onJumpToToday: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPreviousDay) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous day",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    dayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    rangeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onNextDay,
                enabled = canGoForward
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next day",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alpha(if (canGoForward) 1f else 0.3f)
                )
            }
        }

        // Only rendered when it has somewhere to go, so its presence IS the signal that the
        // screen is not showing today.
        if (!isToday) {
            AssistChip(
                onClick = onJumpToToday,
                label = { Text("Back to today") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Today,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * The selected day's nudge counts, sitting directly under the trend chart.
 *
 * This is the pair of numbers the reported bug was about: tapping a bar highlighted it and
 * these did not move. They are rendered here, next to the chart, precisely so the link is
 * visible without scrolling.
 */
@Composable
private fun SelectedDayNudgeRow(
    dayLabel: String,
    blocked: Int,
    walkedAway: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        DayStat(label = "Blocked · $dayLabel", value = blocked.toString())
        DayStat(label = "Walked away · $dayLabel", value = walkedAway.toString())
    }
}

@Composable
private fun DayStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A doorway from Usage Stats into one of the two insight screens.
 *
 * Willpower and Interventions used to be reachable ONLY by tapping an unlabelled Home tile, so
 * the first person to use this app did not know two whole screens existed. Usage Stats is where
 * someone goes when they want to understand their own numbers, which makes it the one place the
 * interpretation of those numbers has to be offered by name.
 *
 * The supporting line is load-bearing, not decoration: an icon plus a single word is precisely
 * the unlabelled affordance that caused the report, and a card that will not say what is behind
 * it teaches nothing. Both texts carry a fixed line count so the two cards in the row are the
 * same height at every screen width, with no intrinsic-measurement guesswork.
 */
@Composable
private fun InsightEntryCard(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val openLabel = "Open $title"

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            // The click target lives inside the card, not on it: the card's own surface already
            // clips to the corner radius, so the touch indication follows the rounded edge
            // without a clip of our own eating the elevation shadow. mergeDescendants keeps the
            // icon, title and supporting line as ONE TalkBack node that names its destination,
            // rather than a stray unactionable chevron the user can focus and do nothing with.
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                // No contentDescription: the card merges its descendants and already announces
                // `openLabel` as its click action, so describing the chevron says it twice.
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                // minLines only, never maxLines. The floor is what makes the two cards the same
                // height when one title is a single word; a ceiling would silently ellipsise
                // "Temptation patterns" at a large system font scale, and a card that hides half
                // its own name is a worse failure than two cards of unequal height.
                minLines = 2
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 3
            )
        }
    }
}

@Composable
private fun UsageBar(stat: AppUsageStat, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stat.appName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                stat.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ProportionalBar(fraction = stat.fraction, modifier = Modifier.fillMaxWidth())
    }
}
