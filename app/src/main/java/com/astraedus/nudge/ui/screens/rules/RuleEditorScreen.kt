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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import com.astraedus.nudge.ui.hasGrayscalePermission
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.domain.model.BlockMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    viewModel: RuleEditorViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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

            // --- Existing Rules Summary ---
            if (state.allRulesForPackage.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Current Rules",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    state.allRulesForPackage.forEach { rule ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (rule.enabled)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    rule.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    color = if (rule.enabled)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { viewModel.toggleRuleEnabled(rule.id, rule.enabled) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
            }

            // --- Block Mode ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Block Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    InfoButton(
                        "Choose how the app is blocked:\n\n" +
                        "Hard Block -- Completely prevents opening the app. You can only go back to the home screen.\n\n" +
                        "Delay -- Shows a countdown timer (5-60 seconds) before letting you in. Gives your brain time to reconsider.\n\n" +
                        "Breathing -- Guides you through a calming breathing exercise before the app opens."
                    )
                }

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

                Text(
                    when (state.blockMode) {
                        BlockMode.HARD_BLOCK -> "Completely blocks the app. You can only go back to the home screen."
                        BlockMode.DELAY -> "Shows a countdown timer before letting you in. Gives you time to reconsider."
                        BlockMode.BREATHING -> "Guides you through a breathing exercise before opening. Calms the impulse."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Daily Time Limit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Set a daily usage budget for this app.\n\n" +
                            "Once you've used the app for this many minutes today, it switches to a hard block for the rest of the day -- regardless of what block mode you chose above.\n\n" +
                            "Example: Delay mode + 30 minute limit = you get a countdown each time you open the app, but after 30 minutes of total usage today, the app is fully blocked."
                        )
                    }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Schedule",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Only apply this rule during specific times.\n\n" +
                            "Select which days the rule is active, and set a start and end time.\n\n" +
                            "Overnight schedules work too -- if the end time is before the start time (e.g. 10 PM to 6 AM), the rule spans midnight."
                        )
                    }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "In-App Blocking",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Block specific features inside the app without blocking the whole thing.\n\n" +
                            "For example, you can block YouTube Shorts but still watch regular videos, or block Instagram Reels but keep DMs and your feed.\n\n" +
                            "This works by detecting which tab or section you're in. If none are checked, the block mode above applies to the whole app."
                        )
                    }

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Grayscale Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            InfoButton(
                                "Makes your phone screen black-and-white while this app is in the foreground.\n\n" +
                                "Color is a major factor in making apps feel rewarding. Removing it makes scrolling feel less engaging.\n\n" +
                                "Requires a one-time setup -- check Settings for the guide."
                            )
                        }
                        Text(
                            "Make screen gray when this app is open",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.grayscale,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasGrayscalePermission(context)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Grayscale requires setup — check Settings"
                                    )
                                }
                            } else {
                                viewModel.setGrayscale(enabled)
                            }
                        }
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
private fun InfoButton(explanation: String) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showDialog = true },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = "More info",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Got it")
                }
            },
            text = {
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
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
