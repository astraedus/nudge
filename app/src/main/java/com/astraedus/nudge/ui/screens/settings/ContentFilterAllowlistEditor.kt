package com.astraedus.nudge.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.lock.ContentFilterAllowlistWeakening

internal const val ALLOWLIST_TITLE = "Allowed words & sites"
internal const val ALLOWLIST_SUBTITLE =
    "Searches with these words, or on these sites, skip strict keyword matching."
internal const val ALLOWLIST_STRICT_SUFFIX = " Adding one requires the unlock challenge."
internal const val ALLOWLIST_UNLOCK_PROMPT = "Add an allowed word or site"
internal const val ALLOWLIST_INVALID =
    "Enter a word or a site, up to ${ContentFilterAllowlist.MAX_ENTRY_LENGTH} characters."
internal const val ALLOWLIST_DUPLICATE = "Already on the list."
internal const val ALLOWLIST_FULL =
    "The list is full (${ContentFilterAllowlist.MAX_ENTRIES}). Remove one first."

/** What submitting the editor's text field does. */
internal sealed interface AllowlistSubmit {
    /** Store [entry] (canonical form); first pass the Strict Mode challenge when [needsUnlock]. */
    data class Add(val entry: String, val needsUnlock: Boolean) : AllowlistSubmit

    /** Nothing is stored; show [message] under the field. */
    data class Rejected(val message: String) : AllowlistSubmit
}

/**
 * The editor's one decision, pure so it is testable without Compose: is [input] storable, and does
 * storing it need the unlock challenge. Adding an exception weakens the filter, so under Strict Mode
 * it does ([ContentFilterAllowlistWeakening]); removals never reach this, they are always free.
 */
internal fun allowlistSubmit(
    input: String,
    current: Set<String>,
    strictModeEnabled: Boolean
): AllowlistSubmit {
    val entry = ContentFilterAllowlist.normalize(input)
        ?: return AllowlistSubmit.Rejected(ALLOWLIST_INVALID)
    if (entry in current) return AllowlistSubmit.Rejected(ALLOWLIST_DUPLICATE)
    if (current.size >= ContentFilterAllowlist.MAX_ENTRIES) {
        return AllowlistSubmit.Rejected(ALLOWLIST_FULL)
    }
    return AllowlistSubmit.Add(
        entry = entry,
        needsUnlock = ContentFilterAllowlistWeakening.requiresUnlock(
            current, current + entry, strictModeEnabled
        )
    )
}

/**
 * "Allowed words & sites" (issue #78): the user's exceptions to strict keyword matching. A text field
 * to add one, and a chip per entry that removes it on tap.
 *
 * [onAdd] receives a canonical entry and whether it must pass the challenge first; the host screen
 * owns the challenge dialog, exactly as it does for its gated toggles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContentFilterAllowlistEditor(
    entries: Set<String>,
    strictModeEnabled: Boolean,
    onAdd: (entry: String, needsUnlock: Boolean) -> Unit,
    onRemove: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val sorted = remember(entries) { entries.sorted() }

    // Clear the field once what it holds has actually been stored. Not on submit: under Strict Mode
    // the add waits on the challenge, and a cancelled challenge should not eat what the user typed.
    LaunchedEffect(entries) {
        if (ContentFilterAllowlist.normalize(input) in entries) input = ""
    }

    fun submit() {
        when (val result = allowlistSubmit(input, entries, strictModeEnabled)) {
            is AllowlistSubmit.Add -> onAdd(result.entry, result.needsUnlock)
            is AllowlistSubmit.Rejected -> error = result.message
        }
    }

    Column {
        ListItem(
            headlineContent = { Text(ALLOWLIST_TITLE) },
            supportingContent = {
                Text(ALLOWLIST_SUBTITLE + if (strictModeEnabled) ALLOWLIST_STRICT_SUFFIX else "")
            },
            leadingContent = { Icon(Icons.Outlined.TaskAlt, contentDescription = null) }
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                label = { Text("Word or site") },
                placeholder = { Text("e.g. cnc") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { submit() }, enabled = input.isNotBlank()) { Text("Add") }
        }
        if (sorted.isEmpty()) {
            Text(
                "No exceptions yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sorted.forEach { entry ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(entry) },
                        label = { Text(entry) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove $entry",
                                modifier = Modifier.size(InputChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
        }
    }
}
