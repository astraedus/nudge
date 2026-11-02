package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.ui.components.CustomTimeDialog

/** The wait-time presets, in seconds. */
val WAIT_TIME_PRESETS: List<Int> = listOf(5, 15, 30, 60)

/**
 * Wait-time chips (5s / 15s / 30s / 60s), for Delay, Hold and Breathing.
 *
 * With [customDialogTitle] null this is the plain preset row. With a title it also shows a
 * "Custom" chip (labelled with the value when [seconds] is not a preset) that opens a seconds
 * dialog from 1 to 300.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaitTimeChips(
    seconds: Int,
    onSelect: (Int) -> Unit,
    customDialogTitle: String? = null
) {
    if (customDialogTitle == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WAIT_TIME_PRESETS.forEach { preset ->
                FilterChip(
                    selected = seconds == preset,
                    onClick = { onSelect(preset) },
                    label = { Text("${preset}s") }
                )
            }
        }
        return
    }

    var showDialog by remember { mutableStateOf(false) }
    val isCustom = seconds !in WAIT_TIME_PRESETS

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        WAIT_TIME_PRESETS.forEach { preset ->
            FilterChip(
                selected = seconds == preset,
                onClick = { onSelect(preset) },
                label = { Text("${preset}s") }
            )
        }
        FilterChip(
            selected = isCustom,
            onClick = { showDialog = true },
            label = { Text(if (isCustom) "${seconds}s" else "Custom") }
        )
    }

    if (showDialog) {
        CustomTimeDialog(
            title = customDialogTitle,
            unit = "seconds",
            currentValue = seconds,
            min = 1,
            max = 300,
            onConfirm = {
                onSelect(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}
