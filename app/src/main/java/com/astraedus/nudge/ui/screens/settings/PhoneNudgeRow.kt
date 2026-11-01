package com.astraedus.nudge.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.nudge.PhoneNudge
import kotlinx.coroutines.launch

internal const val PHONE_NUDGE_TITLE = "Phone nudge"
internal const val PHONE_NUDGE_SUBTITLE =
    "A pause when you've been on your phone a while, across every app."
internal const val PHONE_NUDGE_THRESHOLD_LABEL = "Check in after"
internal const val PHONE_NUDGE_PAUSE_LABEL = "First pause"

/** The thresholds the picker offers, in minutes. Bounds live in [PhoneNudge]. */
private val THRESHOLD_CHOICES = listOf(10, 20, 30, 45, 60, 90)

/** First-pause lengths the picker offers, in seconds. Later pauses double, capped at 30s. */
private val PAUSE_CHOICES = listOf(3, 5, 10, 15)

/**
 * Settings for the whole-phone pause (issue #123). Not a protection setting: it neither blocks nor
 * unblocks anything, so Strict Mode does not gate it in either direction.
 *
 * The threshold is a dropdown rather than a chip row: six chips overflow a phone-width row and clip
 * the last one. The first pause stays chips (four fit) with the escalation spelled out underneath,
 * because "5 s" on its own does not tell you what happens at 40 and 60 minutes.
 */
@Composable
internal fun PhoneNudgeRow(preferences: NudgePreferences) {
    val scope = rememberCoroutineScope()
    val enabled by preferences.phoneNudgeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val threshold by preferences.phoneNudgeThresholdMinutes
        .collectAsStateWithLifecycle(initialValue = PhoneNudge.DEFAULT_THRESHOLD_MINUTES)
    val pause by preferences.phoneNudgePauseSeconds
        .collectAsStateWithLifecycle(initialValue = PhoneNudge.DEFAULT_FIRST_PAUSE_SECONDS)

    ListItem(
        headlineContent = { Text(PHONE_NUDGE_TITLE) },
        supportingContent = { Text(PHONE_NUDGE_SUBTITLE) },
        leadingContent = { Icon(Icons.Outlined.Timer, contentDescription = null) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { scope.launch { preferences.setPhoneNudgeEnabled(it) } }
            )
        },
        modifier = Modifier.clickable {
            scope.launch { preferences.setPhoneNudgeEnabled(!enabled) }
        }
    )

    if (!enabled) return

    DropdownRow(
        label = PHONE_NUDGE_THRESHOLD_LABEL,
        choices = THRESHOLD_CHOICES,
        selected = threshold,
        suffix = " min",
        onSelect = { scope.launch { preferences.setPhoneNudgeThresholdMinutes(it) } }
    )
    PauseRow(
        selected = pause,
        onSelect = { scope.launch { preferences.setPhoneNudgePauseSeconds(it) } }
    )
}

@Composable
private fun DropdownRow(
    label: String,
    choices: List<Int>,
    selected: Int,
    suffix: String,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("$selected$suffix") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text("$choice$suffix") },
                        onClick = {
                            expanded = false
                            onSelect(choice)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PauseRow(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = PHONE_NUDGE_PAUSE_LABEL,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PAUSE_CHOICES.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text("$choice s") }
                )
            }
        }
        Text(
            text = escalationPreview(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "Then 10 s, 20 s, 30 s, 30 s" for a 5 s first pause. Pure, so the copy is unit-testable. */
internal fun escalationPreview(firstSeconds: Int): String {
    val later = (2..5).map { PhoneNudge.pauseSeconds(it, firstSeconds) }
    return "Then " + later.joinToString(", ") { "$it s" }
}
