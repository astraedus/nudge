package com.astraedus.nudge.ui.screens.allapps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.AppPicker
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.components.blockModeDescription
import com.astraedus.nudge.ui.components.rule.BlockModeSegmentedRow
import com.astraedus.nudge.ui.components.rule.DaysAndTimesFields
import com.astraedus.nudge.ui.components.rule.EditorAddRow
import com.astraedus.nudge.ui.components.rule.EditorSectionTitle
import com.astraedus.nudge.ui.components.rule.EditorSubsectionTitle
import com.astraedus.nudge.ui.components.rule.EditorWhenOption
import com.astraedus.nudge.ui.components.rule.WaitTimeChips

/**
 * The All apps editor (spec 3.4), top to bottom: is it on and how big it is, what it leaves alone
 * (allowed groups, allowed apps, the essentials), how it blocks, and when. Only the mode and the
 * window: no first unlock, no limits, no counters, so the rule stays one sentence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllAppsEditorScreen(
    viewModel: AllAppsEditorViewModel,
    onNavigateBack: () -> Unit,
    onOpenGroup: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        val pickable = state.device.pickerApps.mapTo(HashSet()) { it.packageName }
        AppPicker(
            apps = state.device.pickerApps,
            groups = state.pickerGroups,
            initiallySelected = state.allowedPackages.filterTo(HashSet()) { it in pickable },
            initiallySelectedGroups = state.draft.allowedGroupIds,
            onDone = viewModel::applyPicked,
            onDismiss = viewModel::dismissPicker
        )
    }

    if (state.showAlwaysAllowed) {
        AlwaysAllowedSheet(apps = state.device.alwaysAllowed, onClose = viewModel::closeAlwaysAllowed)
    }

    // Back and the top bar's arrow both go through requestLeave: it asks only when something would be
    // lost. The picker, the sheet and the dialogs are their own windows, so Back reaches them first.
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

    if (state.showSaveConfirmation) {
        AllTheTimeConfirmDialog(
            blockedCount = state.blockedCount,
            onConfirm = viewModel::confirmSave,
            onDismiss = viewModel::dismissSaveConfirmation
        )
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete this rule?") },
            text = { Text("Apps will no longer be blocked by this rule.") },
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
                title = { Text("All apps except a few") },
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
        val rows = remember(state.allowedPackages, state.allowedGroupIds, state.tables, state.device, state.ruleId) {
            exceptRows(state)
        }
        val alwaysAllowed = remember(state.device) {
            alwaysAllowedText(state.device.alwaysAllowed.map { it.appName })
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            EnabledRow(
                enabled = state.form.enabled,
                blocksText = blocksText(state.blockedCount, state.form.enabled),
                onEnabledChange = viewModel::setEnabled
            )

            Spacer(Modifier.height(16.dp))
            EditorSectionTitle("Except these")
            rows.groups.forEach { row ->
                AllowedGroupItem(
                    row = row,
                    onOpen = { onOpenGroup(row.groupId) },
                    onRemove = { viewModel.removeGroup(row.groupId) }
                )
            }
            rows.apps.forEach { row ->
                AllowedAppItem(row = row, onRemove = { viewModel.removeApp(row.packageName) })
            }
            EditorAddRow(text = "Add apps or groups", onClick = viewModel::openPicker)
            AlwaysAllowedItem(text = alwaysAllowed, onClick = viewModel::openAlwaysAllowed)

            HowAndWhen(form = state.form, onFormChange = viewModel::updateForm)

            if (state.ruleId != null) {
                Spacer(Modifier.height(32.dp))
                OutlinedButton(
                    onClick = viewModel::requestDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { Text("Delete rule") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** How (the mode, and its wait when it has one) and When (all the time, or days and times). */
@Composable
private fun HowAndWhen(
    form: AllAppsForm,
    onFormChange: ((AllAppsForm) -> AllAppsForm) -> Unit
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
        if (form.showsWaitTime) {
            WaitTimeChips(
                seconds = form.waitSeconds,
                onSelect = { seconds -> onFormChange { it.copy(waitSeconds = seconds) } },
                customDialogTitle = "Custom wait"
            )
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

