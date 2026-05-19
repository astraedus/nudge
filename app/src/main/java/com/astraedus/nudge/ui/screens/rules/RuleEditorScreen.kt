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
import androidx.compose.foundation.clickable
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
import com.astraedus.nudge.ui.components.CustomTimeDialog
import com.astraedus.nudge.ui.components.formatMinutesDisplay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    viewModel: RuleEditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRuleEditor: (String, Long) -> Unit,
    onCreateNewRule: (String) -> Unit
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
                title = { Text(if (state.existingRuleId == null) "New Rule" else "Edit Rule") },
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Current Rules",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "If multiple active rules match, Nudge uses the strongest action:\n\n" +
                            "Hard Block > Delay > Breathing.\n\n" +
                            "Whole-app rules apply when opening the app. Feature rules apply only when Nudge detects that feature, like Reels, Explore, or Shorts.\n\n" +
                            "Example: Instagram can delay when opened, hard block Reels, and delay Explore."
                        )
                    }

                    state.allRulesForPackage.forEach { rule ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToRuleEditor(state.packageName, rule.id) },
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

                    if (state.existingRuleId != null) {
                        OutlinedButton(
                            onClick = { onCreateNewRule(state.packageName) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add Rule")
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
                        "Choose what this rule does when it matches.\n\n" +
                        "Hard Block -- Completely prevents opening the app. You can only go back to the home screen.\n\n" +
                        "Delay -- Shows a countdown timer (5-60 seconds) before letting you in. Gives your brain time to reconsider.\n\n" +
                        "Breathing -- Guides you through a calming breathing exercise before the app opens.\n\n" +
                        "If multiple matching rules are active, the strongest action wins: Hard Block > Delay > Breathing."
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

                    val delayPresets = remember { listOf(5, 15, 30, 60) }
                    var showDelayDialog by remember { mutableStateOf(false) }
                    val isCustomDelay = state.delaySeconds !in delayPresets

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        delayPresets.forEach { seconds ->
                            FilterChip(
                                selected = state.delaySeconds == seconds,
                                onClick = { viewModel.setDelaySeconds(seconds) },
                                label = { Text("${seconds}s") }
                            )
                        }
                        FilterChip(
                            selected = isCustomDelay,
                            onClick = { showDelayDialog = true },
                            label = {
                                Text(if (isCustomDelay) "${state.delaySeconds}s" else "Custom")
                            }
                        )
                    }

                    if (showDelayDialog) {
                        CustomTimeDialog(
                            title = "Custom Delay Duration",
                            unit = "seconds",
                            currentValue = state.delaySeconds,
                            min = 1,
                            max = 300,
                            onConfirm = { seconds ->
                                viewModel.setDelaySeconds(seconds)
                                showDelayDialog = false
                            },
                            onDismiss = { showDelayDialog = false }
                        )
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
                    val dailyPresets = remember { listOf(15, 30, 60, 120) }
                    val dailyPresetLabels = remember { mapOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h") }
                    var showDailyLimitDialog by remember { mutableStateOf(false) }
                    val isCustomDaily = state.dailyLimitMinutes !in dailyPresets

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        dailyPresets.forEach { minutes ->
                            FilterChip(
                                selected = state.dailyLimitMinutes == minutes,
                                onClick = { viewModel.setDailyLimitMinutes(minutes) },
                                label = { Text(dailyPresetLabels[minutes] ?: "${minutes}m") }
                            )
                        }
                        FilterChip(
                            selected = isCustomDaily,
                            onClick = { showDailyLimitDialog = true },
                            label = {
                                Text(
                                    if (isCustomDaily) formatMinutesDisplay(state.dailyLimitMinutes)
                                    else "Custom"
                                )
                            }
                        )
                    }

                    if (showDailyLimitDialog) {
                        CustomTimeDialog(
                            title = "Custom Daily Limit",
                            unit = "minutes",
                            currentValue = state.dailyLimitMinutes,
                            min = 1,
                            max = 480,
                            onConfirm = { minutes ->
                                viewModel.setDailyLimitMinutes(minutes)
                                showDailyLimitDialog = false
                            },
                            onDismiss = { showDailyLimitDialog = false }
                        )
                    }

                    // Show time remaining overlay toggle
                    Spacer(Modifier.height(4.dp))
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
                                    "Show time remaining",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                InfoButton(
                                    "Displays remaining daily time as a floating overlay while you use this app.\n\n" +
                                    "The counter changes color as time runs out:\n" +
                                    "Green = more than 50% left\n" +
                                    "Orange = 25-50% left\n" +
                                    "Red = less than 25% left\n\n" +
                                    "Requires a daily time limit to be set."
                                )
                            }
                            Text(
                                "Floating overlay showing remaining daily time",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.showTimeRemaining,
                            onCheckedChange = { viewModel.setShowTimeRemaining(it) }
                        )
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
                        val dayLabels = remember { listOf(
                            1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                            5 to "Fri", 6 to "Sat", 7 to "Sun"
                        ) }
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

            // --- Apply Rule To (only for supported apps) ---
            if (state.supportsInAppBlocking) {
                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Apply Rule To",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Choose where this rule applies.\n\n" +
                            "If no features are selected, this rule applies to the whole app when you open it.\n\n" +
                            "If you select Reels, Explore, Shorts, or TikTok Feed, this rule only applies when Nudge detects that feature.\n\n" +
                            "The action still comes from Block Mode above. For example, select Explore + Delay to add a 15 second delay only when opening Explore."
                        )
                    }

                    Text(
                        "Select features to scope this rule. Leave all off for the whole app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val pkg = state.packageName

                    // Instagram features
                    if (pkg == "com.instagram.android") {
                        InAppCheckbox(
                            label = "Reels",
                            checked = state.inAppReels,
                            onCheckedChange = { viewModel.setInAppReels(it) }
                        )
                        InAppCheckbox(
                            label = "Explore",
                            checked = state.inAppExplore,
                            onCheckedChange = { viewModel.setInAppExplore(it) }
                        )
                    }

                    // YouTube features
                    if (pkg == "com.google.android.youtube") {
                        InAppCheckbox(
                            label = "Shorts",
                            checked = state.inAppShorts,
                            onCheckedChange = { viewModel.setInAppShorts(it) }
                        )
                    }

                    // TikTok features
                    if (pkg == "com.zhiliaoapp.musically" || pkg == "com.ss.android.ugc.trill") {
                        InAppCheckbox(
                            label = "TikTok Feed",
                            checked = state.inAppTikTokFeed,
                            onCheckedChange = { viewModel.setInAppTikTokFeed(it) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // --- Interaction Counter ---
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
                                "Interaction Counter",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            InfoButton(
                                "Shows a floating counter on screen while you use this app.\n\n" +
                                "For YouTube/Instagram/TikTok: counts how many Reels or Shorts you've scrolled through.\n\n" +
                                "For other apps: counts how many times you've tapped the screen.\n\n" +
                                "Seeing the raw number makes mindless usage feel concrete. The counter turns orange at 10, deep orange at 20, and red at 30."
                            )
                        }
                        Text(
                            "Show floating counter while using this app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.showCounter,
                        onCheckedChange = { viewModel.setShowCounter(it) }
                    )
                }

                // --- Auto-kick (only when counter is enabled) ---
                if (state.showCounter) {
                    Spacer(Modifier.height(8.dp))

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
                                    "Auto-close app",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                InfoButton(
                                    "Automatically sends you to the home screen after a set number of scrolls or taps in one session.\n\n" +
                                    "The counter resets when you re-open the app, so you start fresh each time.\n\n" +
                                    "This is the nuclear option for stopping infinite scroll."
                                )
                            }
                            Text(
                                "Send to home screen after N scrolls or taps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.autoKickEnabled,
                            onCheckedChange = { viewModel.setAutoKickEnabled(it) }
                        )
                    }

                    if (state.autoKickEnabled) {
                        Column(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "After ${state.autoKickAfter} scrolls/taps",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Slider(
                                value = state.autoKickAfter.toFloat(),
                                onValueChange = { viewModel.setAutoKickAfter(it.toInt()) },
                                valueRange = 5f..100f,
                                steps = 18, // (100-5)/5 - 1 = 18 steps for increments of 5
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "5",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "100",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                "Cooldown: ${state.autoKickCooldownSeconds}s",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Wait time before you can re-open the app after auto-close",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = state.autoKickCooldownSeconds.toFloat(),
                                onValueChange = { viewModel.setAutoKickCooldownSeconds(it.toInt()) },
                                valueRange = 0f..300f,
                                steps = 5, // 0, 60, 120, 180, 240, 300
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Off",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "5m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                Text(if (state.existingRuleId == null) "Create Rule" else "Save Rule")
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
