package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astraedus.nudge.ui.components.CustomTimeDialog
import com.astraedus.nudge.ui.components.formatMinutesDisplay
import androidx.compose.ui.unit.dp

/** The daily-limit presets, in minutes, and their chip labels. */
val DAILY_LIMIT_PRESETS: List<Pair<Int, String>> =
    listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h")

/**
 * Daily-limit chips (15m / 30m / 1h / 2h) plus a "Custom" chip, labelled with the value when
 * [minutes] is not a preset, that opens a minutes dialog from 1 to 480. Shared by App config and the
 * group editor so a limit is picked the same way everywhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyLimitChips(
    minutes: Int,
    onSelect: (Int) -> Unit,
    customDialogTitle: String
) {
    var showDialog by remember { mutableStateOf(false) }
    val isCustom = DAILY_LIMIT_PRESETS.none { it.first == minutes }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DAILY_LIMIT_PRESETS.forEach { (preset, label) ->
            FilterChip(
                selected = minutes == preset,
                onClick = { onSelect(preset) },
                label = { Text(label) }
            )
        }
        FilterChip(
            selected = isCustom,
            onClick = { showDialog = true },
            label = { Text(if (isCustom) formatMinutesDisplay(minutes) else "Custom") }
        )
    }

    if (showDialog) {
        CustomTimeDialog(
            title = customDialogTitle,
            unit = "minutes",
            currentValue = minutes,
            min = 1,
            max = 480,
            onConfirm = { value ->
                onSelect(value)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}
