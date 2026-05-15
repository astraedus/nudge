package com.astraedus.nudge.ui.screens.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.domain.model.BlockMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    viewModel: RuleEditorViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved, state.isDeleted) {
        if (state.isSaved || state.isDeleted) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Rule") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                state.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // --- Block Mode ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Block Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BlockMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.blockMode == mode,
                            onClick = { viewModel.setBlockMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = BlockMode.entries.size
                            )
                        ) {
                            Text(
                                when (mode) {
                                    BlockMode.HARD_BLOCK -> "Hard Block"
                                    BlockMode.DELAY -> "Delay"
                                    BlockMode.BREATHING -> "Breathing"
                                }
                            )
                        }
                    }
                }
            }

            if (state.blockMode == BlockMode.DELAY || state.blockMode == BlockMode.BREATHING) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Delay Duration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 15, 30, 60).forEach { seconds ->
                            FilterChip(
                                selected = state.delaySeconds == seconds,
                                onClick = { viewModel.setDelaySeconds(seconds) },
                                label = { Text("${seconds}s") }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Daily Time Limit ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Daily Time Limit",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.dailyLimitEnabled,
                        onCheckedChange = { viewModel.setDailyLimitEnabled(it) }
                    )
                }

                if (state.dailyLimitEnabled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h").forEach { (minutes, label) ->
                            FilterChip(
                                selected = state.dailyLimitMinutes == minutes,
                                onClick = { viewModel.setDailyLimitMinutes(minutes) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Schedule ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.scheduleEnabled,
                        onCheckedChange = { viewModel.setScheduleEnabled(it) }
                    )
                }

                if (state.scheduleEnabled) {
                    Text(
                        "Active days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val dayLabels = listOf(
                            1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                            5 to "Fri", 6 to "Sat", 7 to "Sun"
                        )
                        dayLabels.forEach { (day, label) ->
                            FilterChip(
                                selected = day in state.scheduleDays,
                                onClick = { viewModel.toggleScheduleDay(day) },
                                label = { Text(label) }
                            )
                        }
                    }

                    // Time pickers - start time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Start time", style = MaterialTheme.typography.bodyMedium)
                        TimeSelector(
                            hour = state.scheduleStartHour,
                            minute = state.scheduleStartMinuteOfHour,
                            onTimeSelected = { h, m -> viewModel.setScheduleStartTime(h, m) }
                        )
                    }

                    // Time pickers - end time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("End time", style = MaterialTheme.typography.bodyMedium)
                        TimeSelector(
                            hour = state.scheduleEndHour,
                            minute = state.scheduleEndMinuteOfHour,
                            onTimeSelected = { h, m -> viewModel.setScheduleEndTime(h, m) }
                        )
                    }

                    if (state.scheduleDays.isEmpty()) {
                        Text(
                            "No days selected - rule will apply every day during the time window",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // --- In-App Blocking (only for supported apps) ---
            if (state.supportsInAppBlocking) {
                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "In-App Blocking",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        "Block specific features instead of the whole app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val pkg = state.packageName

                    // Instagram features
                    if (pkg == "com.instagram.android") {
                        InAppCheckbox(
                            label = "Block Reels",
                            checked = state.inAppReels,
                            onCheckedChange = { viewModel.setInAppReels(it) }
                        )
                        InAppCheckbox(
                            label = "Block Explore",
                            checked = state.inAppExplore,
                            onCheckedChange = { viewModel.setInAppExplore(it) }
                        )
                    }

                    // YouTube features
                    if (pkg == "com.google.android.youtube") {
                        InAppCheckbox(
                            label = "Block Shorts",
                            checked = state.inAppShorts,
                            onCheckedChange = { viewModel.setInAppShorts(it) }
                        )
                    }

                    // TikTok features
                    if (pkg == "com.zhiliaoapp.musically" || pkg == "com.ss.android.ugc.trill") {
                        InAppCheckbox(
                            label = "Block TikTok Feed",
                            checked = state.inAppTikTokFeed,
                            onCheckedChange = { viewModel.setInAppTikTokFeed(it) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // --- Grayscale ---
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Grayscale Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Make screen gray when this app is open",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.grayscale,
                        onCheckedChange = { viewModel.setGrayscale(it) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Rule")
            }

            if (state.existingRuleId != null) {
                OutlinedButton(
                    onClick = { viewModel.delete() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Rule")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InAppCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/**
 * Simple time selector using hour/minute FilterChips.
 * Displays current time as a button, cycles through preset options.
 */
@Composable
private fun TimeSelector(
    hour: Int,
    minute: Int,
    onTimeSelected: (Int, Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hour selector
        FilterChip(
            selected = true,
            onClick = {
                // Cycle hour: +1, wrap at 24
                onTimeSelected((hour + 1) % 24, minute)
            },
            label = { Text(String.format("%02d", hour)) }
        )
        Text(":", style = MaterialTheme.typography.bodyLarge)
        // Minute selector
        FilterChip(
            selected = true,
            onClick = {
                // Cycle minute in 15-min increments
                onTimeSelected(hour, (minute + 15) % 60)
            },
            label = { Text(String.format("%02d", minute)) }
        )
    }
}
