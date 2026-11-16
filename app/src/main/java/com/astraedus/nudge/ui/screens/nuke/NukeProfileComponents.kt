package com.astraedus.nudge.ui.screens.nuke

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.nuke.NukeProfiles
import com.astraedus.nudge.domain.nuke.NukeTimer
import com.astraedus.nudge.ui.nuke.NukeTimerCopy
import kotlinx.coroutines.launch

/*
 * Nuke profiles (#183) and the code-lock picker (#184). Kept small on purpose: someone who never saves
 * a profile sees one extra text link beside "Apps to nuke", and someone who never locks the code sees
 * one "Lock code for  None" row in the off card. No description lines; every label says what tapping
 * does.
 */

/**
 * "Apps to nuke", with "Save as profile" beside it once there is something new to save.
 *
 * The link is a clickable Text, not a TextButton: a button's 48dp minimum would make this header
 * taller than it is without profiles (round 1). Compose still gives it a 48dp touch target, because
 * hit testing expands a small pointer target to the platform minimum without taking layout space.
 * Its text ends on the list's content edge, the same edge as the search field's.
 */
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
            Text(
                "Save as profile",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button, onClick = onSaveAsProfile)
            )
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
                    // Selection shows by fill alone, no check mark, so a chip never changes width.
                    label = { Text(chip.name) },
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
    // The existing name starts selected (Rename), so typing replaces it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
    }
    val name = field.text
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val save: () -> Unit = {
        scope.launch {
            val refusal = submit(name)
            if (refusal == null) onDismiss() else error = refusal
        }
    }
    // Open ready to type: the Name field focused and the keyboard up.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { typed ->
                    field = if (typed.text.length <= NukeProfiles.MAX_NAME_LENGTH) {
                        typed
                    } else {
                        val capped = typed.text.take(NukeProfiles.MAX_NAME_LENGTH)
                        TextFieldValue(capped, selection = TextRange(capped.length))
                    }
                    error = null
                },
                label = { Text("Name") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
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
 * "Lock code for   None ▾": how long the next start locks the code. A setting row in the app's own
 * pattern (label left, value and caret on the content edge, the whole row the tap target), in the
 * card's colours rather than a pink button. The choice is remembered, so a routine is one tap. While
 * Nuke is on it is not shown (a running lock cannot be changed: shortening it would be a way around it).
 */
@Composable
fun NukeTimerPicker(choiceMinutes: Int, contentColor: Color, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = NukeTimerCopy.choiceLabel(choiceMinutes)
    val valueColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.DropdownList) { open = true }
                .semantics { contentDescription = "${NukeTimerCopy.PICKER_LABEL}: $label" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                NukeTimerCopy.PICKER_LABEL,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            Text(label, style = MaterialTheme.typography.bodyLarge, color = valueColor)
            // The arrow glyph sits inside 7dp of empty icon space on each side; shift it so its tip
            // lands on the content edge, the right edge of the buttons below.
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = valueColor,
                modifier = Modifier.offset(x = CARET_INSET)
            )
        }
        // A zero-width anchor ON the content edge: the menu cannot open rightwards from it (that
        // would cross the edge), so it opens leftwards with its right edge on the content edge,
        // instead of running from the value to the screen edge (round 1).
        Box(modifier = Modifier.align(Alignment.BottomEnd).size(0.dp)) {
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

/** The empty space right of the ArrowDropDown glyph inside its 24dp icon box. */
private val CARET_INSET = 7.dp
