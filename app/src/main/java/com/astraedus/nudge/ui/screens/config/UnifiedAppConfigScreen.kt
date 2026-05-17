package com.astraedus.nudge.ui.screens.config

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import com.astraedus.nudge.domain.model.FeatureMode
import com.astraedus.nudge.ui.hasGrayscalePermission
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnifiedAppConfigScreen(
    viewModel: UnifiedAppConfigViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.appName.ifEmpty { state.packageName })
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) {
                        Text("Save")
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

            // ═══ MASTER TOGGLE ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
            }

            HorizontalDivider()

            // ═══ ALWAYS ACTIVE ═══
            SectionHeader("Always Active")

            // Daily Time Limit
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
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Set a daily usage budget for this app.\n\n" +
                            "Once you've used the app for this many minutes today, it switches to a hard block for the rest of the day."
                        )
                    }
                    Switch(
                        checked = state.dailyLimitEnabled,
                        onCheckedChange = viewModel::setDailyLimitEnabled
                    )
                }

                if (state.dailyLimitEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h").forEach { (minutes, label) ->
                            FilterChip(
                                selected = state.dailyLimitMinutes == minutes,
                                onClick = { viewModel.setDailyLimitMinutes(minutes) },
                                label = { Text(label) }
                            )
                        }
                    }

                    // Show time remaining sub-toggle
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
                                "Show time remaining",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            InfoButton(
                                "Displays remaining daily time as a floating overlay.\n\n" +
                                "Changes color as time runs out: green (>50%), orange (25-50%), red (<25%)."
                            )
                        }
                        Switch(
                            checked = state.showTimeRemaining,
                            onCheckedChange = viewModel::setShowTimeRemaining
                        )
                    }
                }
            }

            // Interaction counter
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
                        "Interaction counter",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    InfoButton(
                        "Shows a floating counter while you use this app.\n\n" +
                        "For YouTube/Instagram/TikTok: counts Reels or Shorts scrolled.\n" +
                        "For other apps: counts screen taps.\n\n" +
                        "Turns orange at 10, deep orange at 20, red at 30."
                    )
                }
                Switch(
                    checked = state.showCounter,
                    onCheckedChange = viewModel::setShowCounter
                )
            }

            // Grayscale
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
                            "Grayscale",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Makes your screen black-and-white while this app is open.\n\n" +
                            "Removes the color reward that makes scrolling engaging.\n\n" +
                            "Requires one-time ADB setup -- check Settings for the guide."
                        )
                    }
                    Text(
                        "Requires ADB permission setup",
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
                                    "Grayscale requires setup -- check Settings"
                                )
                            }
                        } else {
                            viewModel.setGrayscale(enabled)
                        }
                    }
                )
            }

            // Web domain blocking
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
                            "Block on web too",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Also blocks this app's website in Chrome.\n\n" +
                            "When you visit the website in Chrome, the same overlay (delay, " +
                            "breathing, or hard block) will appear.\n\n" +
                            "Currently supports Chrome only."
                        )
                    }
                    Switch(
                        checked = state.webDomainEnabled,
                        onCheckedChange = viewModel::setWebDomainEnabled
                    )
                }

                if (state.webDomainEnabled) {
                    OutlinedTextField(
                        value = state.webDomains,
                        onValueChange = viewModel::setWebDomains,
                        label = { Text("Domains (comma-separated)") },
                        placeholder = { Text("instagram.com, www.instagram.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Subdomains like www. and m. are matched automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // ═══ DEFAULT BEHAVIOR ═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SectionHeader("Default Behavior")
                InfoButton("These settings apply whenever no scheduled override is active.")
            }

            // Block mode segmented button
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BlockMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.defaultMode == mode,
                            onClick = { viewModel.setDefaultMode(mode) },
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
                    when (state.defaultMode) {
                        BlockMode.HARD_BLOCK -> "Completely blocks the app. You can only go back to the home screen."
                        BlockMode.DELAY -> "Shows a countdown timer before letting you in."
                        BlockMode.BREATHING -> "Guides you through a breathing exercise before opening."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Delay duration (if applicable)
            if (state.defaultMode != BlockMode.HARD_BLOCK) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Delay Duration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60).forEach { seconds ->
                            FilterChip(
                                selected = state.defaultDelaySeconds == seconds,
                                onClick = { viewModel.setDefaultDelaySeconds(seconds) },
                                label = { Text("${seconds}s") }
                            )
                        }
                    }
                }
            }

            // Auto-kick
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
                            "Auto-kick",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Sends you to the home screen after a set number of scrolls or taps.\n\n" +
                            "The counter resets when you re-open the app. This is the nuclear option for stopping infinite scroll."
                        )
                    }
                    Switch(
                        checked = state.defaultAutoKickEnabled,
                        onCheckedChange = viewModel::setDefaultAutoKickEnabled
                    )
                }

                if (state.defaultAutoKickEnabled) {
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "After ${state.defaultAutoKickAfter} interactions",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = state.defaultAutoKickAfter.toFloat(),
                            onValueChange = { viewModel.setDefaultAutoKickAfter(it.toInt()) },
                            valueRange = 5f..100f,
                            steps = 18,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            "Cooldown: ${state.defaultAutoKickCooldownSeconds}s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Wait time before you can re-open the app after auto-close",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.defaultAutoKickCooldownSeconds.toFloat(),
                            onValueChange = { viewModel.setDefaultAutoKickCooldownSeconds(it.toInt()) },
                            valueRange = 0f..300f,
                            steps = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("5m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ═══ FEATURE OVERRIDES ═══
            if (state.supportsFeatures) {
                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SectionHeader("Feature Rules")
                    InfoButton("Override behavior for specific app features. 'Inherit' uses the default behavior above.")
                }

                state.availableFeatures.forEach { feature ->
                    FeatureOverrideCard(
                        featureName = feature.displayName,
                        override = state.featureOverrides[feature.key] ?: FeatureOverride(),
                        onUpdate = { viewModel.setFeatureOverride(feature.key, it) }
                    )
                }
            }

            HorizontalDivider()

            // ═══ SCHEDULED OVERRIDE ═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SectionHeader("Scheduled Override")
                InfoButton("Apply different settings during specific times. Outside this schedule, the default behavior above is used.")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enable schedule",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = state.scheduledOverrideEnabled,
                    onCheckedChange = viewModel::setScheduledOverrideEnabled
                )
            }

            if (state.scheduledOverrideEnabled) {
                // Day selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Active days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val dayLabels = remember {
                            listOf(
                                1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                                5 to "Fri", 6 to "Sat", 7 to "Sun"
                            )
                        }
                        dayLabels.forEach { (day, label) ->
                            FilterChip(
                                selected = day in state.scheduleDays,
                                onClick = {
                                    val newDays = state.scheduleDays.toMutableSet()
                                    if (day in newDays) newDays.remove(day) else newDays.add(day)
                                    viewModel.setScheduleDays(newDays)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    // Start time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Start time", style = MaterialTheme.typography.bodyMedium)
                        TimeSelector(
                            hour = state.scheduleStartHour,
                            minute = state.scheduleStartMinute,
                            onTimeSelected = { h, m -> viewModel.setScheduleStartTime(h, m) }
                        )
                    }

                    // End time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("End time", style = MaterialTheme.typography.bodyMedium)
                        TimeSelector(
                            hour = state.scheduleEndHour,
                            minute = state.scheduleEndMinute,
                            onTimeSelected = { h, m -> viewModel.setScheduleEndTime(h, m) }
                        )
                    }

                    // Scheduled mode
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        BlockMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.scheduledMode == mode,
                                onClick = { viewModel.setScheduledMode(mode) },
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

                    // Scheduled delay duration
                    if (state.scheduledMode != BlockMode.HARD_BLOCK) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 15, 30, 60).forEach { seconds ->
                                FilterChip(
                                    selected = state.scheduledDelaySeconds == seconds,
                                    onClick = { viewModel.setScheduledDelaySeconds(seconds) },
                                    label = { Text("${seconds}s") }
                                )
                            }
                        }
                    }

                    // Scheduled feature overrides
                    if (state.supportsFeatures) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Feature overrides during schedule",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        state.availableFeatures.forEach { feature ->
                            FeatureOverrideCard(
                                featureName = feature.displayName,
                                override = state.scheduledFeatureOverrides[feature.key]
                                    ?: FeatureOverride(),
                                onUpdate = { viewModel.setScheduledFeatureOverride(feature.key, it) }
                            )
                        }
                    }
                }
            }

            // ═══ DANGER ZONE ═══
            if (state.hasExistingRules) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = viewModel::showDeleteConfirmation,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove All Rules")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Delete confirmation dialog
    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("Remove all rules?") },
            text = {
                Text(
                    "This will delete all rules for ${state.appName.ifEmpty { state.packageName }}. " +
                    "The app will no longer be blocked."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissDeleteConfirmation()
                        viewModel.deleteAllRules()
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ═══ Reusable composables ═══

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium
    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureOverrideCard(
    featureName: String,
    override: FeatureOverride,
    onUpdate: (FeatureOverride) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                featureName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            // Mode selector
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FeatureMode.entries.forEach { mode ->
                    FilterChip(
                        selected = override.mode == mode,
                        onClick = { onUpdate(override.copy(mode = mode)) },
                        label = {
                            Text(
                                when (mode) {
                                    FeatureMode.INHERIT -> "Inherit"
                                    FeatureMode.BLOCK -> "Block"
                                    FeatureMode.DELAY -> "Delay"
                                    FeatureMode.BREATHING -> "Breathing"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                }
            }

            // Expanded settings for DELAY and BREATHING
            if (override.mode == FeatureMode.DELAY || override.mode == FeatureMode.BREATHING) {
                // Delay duration chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { s ->
                        FilterChip(
                            selected = override.delaySeconds == s,
                            onClick = { onUpdate(override.copy(delaySeconds = s)) },
                            label = { Text("${s}s") }
                        )
                    }
                }

                // Auto-kick toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-kick", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = override.autoKickEnabled,
                        onCheckedChange = { onUpdate(override.copy(autoKickEnabled = it)) }
                    )
                }

                if (override.autoKickEnabled) {
                    Text(
                        "After ${override.autoKickAfter} scrolls",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = override.autoKickAfter.toFloat(),
                        onValueChange = { onUpdate(override.copy(autoKickAfter = it.toInt())) },
                        valueRange = 5f..100f,
                        steps = 18,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Cooldown: ${override.autoKickCooldownSeconds}s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = override.autoKickCooldownSeconds.toFloat(),
                        onValueChange = { onUpdate(override.copy(autoKickCooldownSeconds = it.toInt())) },
                        valueRange = 0f..300f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Simple time selector using hour/minute FilterChips.
 * Cycles hour by +1 and minute in 15-minute increments on click.
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
        FilterChip(
            selected = true,
            onClick = { onTimeSelected((hour + 1) % 24, minute) },
            label = { Text(String.format("%02d", hour)) }
        )
        Text(":", style = MaterialTheme.typography.bodyLarge)
        FilterChip(
            selected = true,
            onClick = { onTimeSelected(hour, (minute + 15) % 60) },
            label = { Text(String.format("%02d", minute)) }
        )
    }
}
