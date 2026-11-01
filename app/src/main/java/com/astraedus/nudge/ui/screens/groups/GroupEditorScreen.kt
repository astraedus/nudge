package com.astraedus.nudge.ui.screens.groups

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.AppListItem
import com.astraedus.nudge.ui.components.AppPicker
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.components.TonalIcon
import com.astraedus.nudge.ui.components.blockModeDescription
import com.astraedus.nudge.ui.components.rule.AllAppsIcon
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import com.astraedus.nudge.ui.components.rule.BlockModeSegmentedRow
import com.astraedus.nudge.ui.components.rule.DailyLimitChips
import com.astraedus.nudge.ui.components.rule.DaysAndTimesFields
import com.astraedus.nudge.ui.components.rule.EditorAddRow
import com.astraedus.nudge.ui.components.rule.EditorSectionTitle
import com.astraedus.nudge.ui.components.rule.EditorSubsectionTitle
import com.astraedus.nudge.ui.components.rule.EditorWhenOption
import com.astraedus.nudge.ui.components.rule.WaitTimeChips
import com.astraedus.nudge.ui.components.rule.showsWaitTime

/**
 * The group editor (spec 3.3), laid out in the user's question order: which apps, then what happens
 * to them. "Block these apps" heads How and When; with it off they fold away and the group is just a
 * named set. "Allowed during" lists the All apps rules that let the group through. Category chips
 * (PR 6) are not here yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditorScreen(
    viewModel: GroupEditorViewModel,
    onNavigateBack: () -> Unit,
    /** Opens an All apps rule that lists this group as allowed, from "Allowed during". */
    onOpenAllAppsRule: (Long) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allowedDuring by viewModel.allowedDuring.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()

    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onNavigateBack()
    }

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    if (state.showPicker) {
        val pickable = state.pickerApps.mapTo(HashSet()) { it.packageName }
        AppPicker(
            apps = state.pickerApps,
            initiallySelected = state.memberPackages.filterTo(HashSet()) { it in pickable },
            onDone = viewModel::applyPicked,
            onDismiss = viewModel::dismissPicker
        )
    }

    // Back and the top bar's arrow both go through requestLeave: it asks only when something would be
    // lost. The picker and the dialogs are their own windows, so Back reaches them first.
    BackHandler(enabled = state.isDirty, onBack = viewModel::requestLeave)

    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::keepEditing,
            title = { Text("Discard changes?") },
            confirmButton = {
                TextButton(onClick = viewModel::discardChanges) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::keepEditing) { Text("Keep editing") }
            }
        )
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete this group?") },
            text = { Text("Its apps will no longer be blocked by this group.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.savedName.ifBlank { "New group" }) },
                navigationIcon = {
                    IconButton(onClick = viewModel::requestLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(16.dp))
            EditorSectionTitle(
                if (state.members.isEmpty()) "Apps" else "Apps · ${state.members.size}"
            )
            state.members.forEach { app ->
                AppListItem(
                    appName = app.appName,
                    icon = app.icon,
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeMember(app.packageName) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${app.appName}")
                        }
                    }
                )
            }
            EditorAddRow("Add apps", onClick = viewModel::openPicker)

            Spacer(Modifier.height(16.dp))
            BlockSwitchRow(
                checked = state.form.blockEnabled,
                onCheckedChange = { on -> viewModel.updateForm { it.copy(blockEnabled = on) } }
            )

            AnimatedVisibility(visible = state.form.blockEnabled) {
                BlockingFields(
                    form = state.form,
                    onFormChange = viewModel::updateForm
                )
            }

            if (allowedDuring.isNotEmpty()) {
                AllowedDuringSection(rules = allowedDuring, onOpen = onOpenAllAppsRule)
            }

            if (state.groupId != null) {
                Spacer(Modifier.height(32.dp))
                OutlinedButton(
                    onClick = viewModel::requestDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { Text("Delete group") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** How and When, shown only while "Block these apps" is on. */
@Composable
private fun BlockingFields(
    form: GroupRuleForm,
    onFormChange: ((GroupRuleForm) -> GroupRuleForm) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(0.dp))
        EditorSubsectionTitle("How")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockModeSegmentedRow(
                selected = form.mode,
                onSelect = { mode -> onFormChange { it.copy(mode = mode) } },
                compact = true
            )
            Text(
                blockModeDescription(form.mode, manyApps = true),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showsWaitTime(form.mode)) {
            WaitTimeChips(
                seconds = form.waitSeconds,
                onSelect = { seconds -> onFormChange { it.copy(waitSeconds = seconds) } },
                customDialogTitle = "Custom wait"
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelledSwitch(
                label = "Daily limit for each app",
                checked = form.dailyLimitEnabled,
                onCheckedChange = { on -> onFormChange { it.copy(dailyLimitEnabled = on) } }
            )
            if (form.dailyLimitEnabled) {
                DailyLimitChips(
                    minutes = form.dailyLimitMinutes,
                    onSelect = { minutes -> onFormChange { it.copy(dailyLimitMinutes = minutes) } },
                    customDialogTitle = "Custom daily limit"
                )
            }
        }

        Spacer(Modifier.height(0.dp))
        EditorSubsectionTitle("When")
        Column(Modifier.selectableGroup()) {
            EditorWhenOption(
                label = "All the time",
                selected = !form.scheduled,
                onSelect = { onFormChange { it.copy(scheduled = false) } }
            )
            EditorWhenOption(
                label = "Days and times",
                selected = form.scheduled,
                onSelect = { onFormChange { it.copy(scheduled = true) } }
            )
        }
        if (form.scheduled) {
            DaysAndTimesFields(
                days = form.scheduleDays,
                onDaysChange = { days -> onFormChange { it.copy(scheduleDays = days) } },
                startHour = form.startMinute / 60,
                startMinute = form.startMinute % 60,
                onStartTimeChange = { h, m -> onFormChange { it.copy(startMinute = h * 60 + m) } },
                endHour = form.endMinute / 60,
                endMinute = form.endMinute % 60,
                onEndTimeChange = { h, m -> onFormChange { it.copy(endMinute = h * 60 + m) } }
            )
        }
    }
}

/**
 * "Allowed during" (spec 3.3): the All apps rules that let this group through. Each row opens that
 * rule, so the one place a group's two uses meet shows that adding an app here lets it through.
 */
@Composable
private fun AllowedDuringSection(rules: List<AllAppsRuleItem>, onOpen: (Long) -> Unit) {
    Spacer(Modifier.height(16.dp))
    EditorSectionTitle("Allowed during")
    rules.forEach { rule ->
        ListItem(
            headlineContent = { Text(rule.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { TonalIcon(AllAppsIcon) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.clickable(role = Role.Button) { onOpen(rule.ruleId) }
        )
    }
}

/** The group's own block switch, styled as the heading of How and When. */
@Composable
private fun BlockSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Block these apps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabelledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

