package com.astraedus.nudge.ui.screens.nuke

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.nuke.NukeProfiles
import com.astraedus.nudge.domain.nuke.NukeTimer
import com.astraedus.nudge.ui.nuke.NukeTimerCopy
import kotlinx.coroutines.launch

/*
 * Nuke profiles (#183) and the timer picker (#184). Kept small on purpose: someone who never saves a
 * profile sees one extra text button beside "Apps to nuke", and someone who never picks a timer sees
 * one "Timer: None" row in the off card. No description lines; every label says what tapping does.
 */

/** "Apps to nuke", with "Save as profile" beside it once there is something to save. */
@Composable
fun NukeListHeader(canSave: Boolean, onSaveAsProfile: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Apps to nuke",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (canSave) {
            TextButton(onClick = onSaveAsProfile) { Text("Save as profile") }
        }
    }
}

/**
 * One chip per saved profile; the chip whose apps ARE the list is selected. Tapping an unselected
 * chip while Nuke is off switches the list to it (the snackbar offers Undo). Every other tap opens
 * Rename and Delete, which never touch the list and so work while Nuke is on too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NukeProfileChips(
    chips: List<NukeProfileChip>,
    nukeActive: Boolean,
    onApply: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            var menuOpen by remember(chip.name) { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = chip.selected,
                    onClick = {
                        if (!chip.selected && !nukeActive) onApply(chip.name) else menuOpen = true
                    },
                    label = { Text(chip.name) },
                    leadingIcon = if (chip.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (chip.selected) "Profile ${chip.name}, in use" else "Profile ${chip.name}"
                    }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename(chip.name)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete(chip.name)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The name dialog for both "Save as profile" and "Rename profile". [submit] returns why the name was
 * refused (the dialog stays open and shows it) or null when it was saved (the dialog closes).
 *
 * @param confirmLabel the confirm button for the name typed so far: "Save", or "Update" when saving
 *   over a profile that already has that name.
 */
@Composable
fun NukeProfileNameDialog(
    title: String,
    initialName: String,
    confirmLabel: (String) -> String,
    submit: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val save: () -> Unit = {
        scope.launch {
            val refusal = submit(name)
            if (refusal == null) onDismiss() else error = refusal
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(NukeProfiles.MAX_NAME_LENGTH)
                    error = null
                },
                label = { Text("Name") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = save, enabled = NukeProfiles.nameProblem(name) == null) {
                Text(confirmLabel(name))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun NukeDeleteProfileDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “$name”?") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * "Timer   None ▾": the timer the next start uses. One row in the off card; the choice is
 * remembered, so a routine is one tap. While Nuke is on it is not shown (a running timer cannot be
 * changed: shortening it would be a way around it).
 */
@Composable
fun NukeTimerPicker(choiceMinutes: Int, contentColor: Color, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Timer",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        Box {
            val label = NukeTimerCopy.choiceLabel(choiceMinutes)
            TextButton(
                onClick = { open = true },
                modifier = Modifier.semantics { contentDescription = "Timer: $label" }
            ) {
                Text(label)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                NukeTimer.CHOICES_MINUTES.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(NukeTimerCopy.choiceLabel(minutes)) },
                        onClick = {
                            open = false
                            onPick(minutes)
                        }
                    )
                }
            }
        }
    }
}
