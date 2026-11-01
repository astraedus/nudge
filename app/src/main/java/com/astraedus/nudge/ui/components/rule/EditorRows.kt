package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.TonalIcon

/*
 * The rows the group editor and the All apps editor share, in one place so the two editors read as
 * one family: same type, padding and touch target.
 */

/**
 * Whether the editor shows the wait time chips for [mode]: every mode that has a length. Delay and
 * Hold count down, and Breathing runs for the same seconds (`BreathingContent` reads
 * `delaySeconds`); Hard block has no wait. One answer for both editors, so they cannot drift.
 */
fun showsWaitTime(mode: BlockMode): Boolean =
    mode == BlockMode.DELAY || mode == BlockMode.HOLD || mode == BlockMode.BREATHING

/** A section heading ("Apps", "How", "Except these"). */
@Composable
fun EditorSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** A heading inside a section ("When"), in the muted colour. */
@Composable
fun EditorSubsectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** "+ Add apps": a list row whose 40dp icon slot lines up with the app icons above it. */
@Composable
fun EditorAddRow(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, color = MaterialTheme.colorScheme.primary) },
        leadingContent = { TonalIcon(Icons.Default.Add, primary = true) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** One choice under "When" ("All the time", "Days and times"), a 48dp radio row. */
@Composable
fun EditorWhenOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
