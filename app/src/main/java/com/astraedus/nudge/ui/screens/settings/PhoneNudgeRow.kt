package com.astraedus.nudge.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
internal fun phoneNudgeSubtitle(thresholdMinutes: Int): String =
    "After $thresholdMinutes minutes on your phone in one go, show a pause screen. Counts every app."
internal const val PHONE_NUDGE_THRESHOLD_LABEL = "Pause after"
internal const val PHONE_NUDGE_PAUSE_LABEL = "First pause length"

/** The thresholds the picker offers, in minutes. Bounds live in [PhoneNudge]. */
private val THRESHOLD_CHOICES = listOf(10, 15, 20, 30, 40, 45, 60, 90)

/** First-pause lengths the picker offers, in seconds. Later pauses double to a 30 s cap. */
private val PAUSE_CHOICES = listOf(5, 10, 20, 30)

/**
 * Settings for the whole-phone pause (issue #123). Not a protection setting: it neither blocks nor
 * unblocks anything, so Strict Mode does not gate it in either direction.
 *
 * Both pickers are INLINE dropdowns rather than popup menus. A popup near the bottom of Settings
 * opens upward, and six chips overflow a phone-width row; expanding in place always grows downward
 * and pushes the list instead.
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
        supportingContent = { Text(phoneNudgeSubtitle(threshold)) },
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

    InlineDropdownRow(
        label = PHONE_NUDGE_THRESHOLD_LABEL,
        value = "$threshold min",
        choices = THRESHOLD_CHOICES,
        suffix = " min",
        onSelect = { scope.launch { preferences.setPhoneNudgeThresholdMinutes(it) } }
    )
    InlineDropdownRow(
        label = PHONE_NUDGE_PAUSE_LABEL,
        value = "$pause s",
        choices = PAUSE_CHOICES,
        suffix = " s",
        onSelect = { scope.launch { preferences.setPhoneNudgePauseSeconds(it) } },
        footnote = escalationPreview(pause)
    )
}

@Composable
private fun InlineDropdownRow(
    label: String,
    value: String,
    choices: List<Int>,
    suffix: String,
    onSelect: (Int) -> Unit,
    footnote: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                choices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = false
                                onSelect(choice)
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp)
                    ) {
                        Text("$choice$suffix", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        footnote?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** "Later pauses: 10 s, 20 s, then 30 s" for a 5 s first pause. Pure, so the copy is tested. */
internal fun escalationPreview(firstSeconds: Int): String {
    val later = (2..4).map { PhoneNudge.pauseSeconds(it, firstSeconds) }
    val distinct = later.distinct()
    if (distinct.size == 1) return "Later pauses: ${distinct.single()} s"
    return "Later pauses: " +
        later.dropLast(1).joinToString(", ") { "$it s" } +
        ", then " + later.last() + " s"
}
