package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.model.BlockMode

private val DAY_LABELS = listOf(
    1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
    5 to "Fri", 6 to "Sat", 7 to "Sun"
)

/**
 * The body of a "days and times" schedule: active days, start and end time, the block mode that
 * applies in the window and, for anything but a hard block, its wait time. Stateless; the caller
 * owns the "enable schedule" switch and decides when this is shown.
 *
 * Days are ISO day numbers (1 = Monday ... 7 = Sunday). [trailing] is laid out in the same column,
 * after the wait time, for anything the owner screen adds below (App config puts its per-feature
 * overrides there).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSection(
    days: Set<Int>,
    onDaysChange: (Set<Int>) -> Unit,
    startHour: Int,
    startMinute: Int,
    onStartTimeChange: (Int, Int) -> Unit,
    endHour: Int,
    endMinute: Int,
    onEndTimeChange: (Int, Int) -> Unit,
    mode: BlockMode,
    onModeChange: (BlockMode) -> Unit,
    waitSeconds: Int,
    onWaitSecondsChange: (Int) -> Unit,
    trailing: @Composable ColumnScope.() -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Active days",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DAY_LABELS.forEach { (day, label) ->
                FilterChip(
                    selected = day in days,
                    onClick = { onDaysChange(if (day in days) days - day else days + day) },
                    label = { Text(label) }
                )
            }
        }

        TimeRow("Start time", startHour, startMinute, onStartTimeChange)
        TimeRow("End time", endHour, endMinute, onEndTimeChange)

        BlockModeSegmentedRow(selected = mode, onSelect = onModeChange)

        if (mode != BlockMode.HARD_BLOCK) {
            WaitTimeChips(seconds = waitSeconds, onSelect = onWaitSecondsChange)
        }

        trailing()
    }
}

@Composable
private fun TimeRow(label: String, hour: Int, minute: Int, onTimeSelected: (Int, Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TimeSelector(hour = hour, minute = minute, onTimeSelected = onTimeSelected)
    }
}
