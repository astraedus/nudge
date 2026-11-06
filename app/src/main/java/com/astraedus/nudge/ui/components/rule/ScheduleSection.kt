package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.model.BlockMode

private val DAY_LABELS = listOf(
    1 to "M" to "Monday",
    2 to "T" to "Tuesday",
    3 to "W" to "Wednesday",
    4 to "T" to "Thursday",
    5 to "F" to "Friday",
    6 to "S" to "Saturday",
    7 to "S" to "Sunday"
)

/**
 * The seven day-of-week chips, shared by every schedule kind so a day is picked the same way
 * everywhere. Days are ISO day numbers (1 = Monday ... 7 = Sunday).
 *
 * The visible labels are single letters to keep all seven days on one narrow-phone row. Each chip
 * still announces the full day name ("Monday") and its selected state to accessibility services.
 */
@Composable
fun DayOfWeekChips(
    days: Set<Int>,
    onDaysChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        DAY_LABELS.forEach { (pair, fullName) ->
            val (day, label) = pair
            val selected = day in days
            CompactDayChip(
                label = label,
                fullName = fullName,
                selected = selected,
                onClick = { onDaysChange(if (selected) days - day else days + day) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * A compact toggle chip for a single day of the week. The visual chip is sized so seven of them fit
 * on one row on a 360dp phone with a 1.3x font scale. The height is kept at the 48dp minimum touch
 * target; the width is the largest that fits in the remaining space after the "Days" label and is
 * therefore slightly below 48dp on a 360dp screen.
 */
@Composable
private fun CompactDayChip(
    label: String,
    fullName: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.small
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 40.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(containerColor, shape)
            .toggleable(
                value = selected,
                onValueChange = { onClick() },
                role = Role.Checkbox
            )
            .semantics {
                contentDescription = fullName
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor
        )
    }
}

/**
 * The body of a "days and times" schedule: active days, start and end time, the block mode that
 * applies in the window and, for anything but a hard block, its wait time. Stateless; the caller
 * owns the "enable schedule" switch and decides when this is shown.
 *
 * Days are ISO day numbers (1 = Monday ... 7 = Sunday). [trailing] is laid out in the same column,
 * after the wait time, for anything the owner screen adds below (App config puts its per-feature
 * overrides there).
 */
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
        DaysAndTimesFields(
            days = days,
            onDaysChange = onDaysChange,
            startHour = startHour,
            startMinute = startMinute,
            onStartTimeChange = onStartTimeChange,
            endHour = endHour,
            endMinute = endMinute,
            onEndTimeChange = onEndTimeChange
        )

        BlockModeSegmentedRow(selected = mode, onSelect = onModeChange)

        if (mode != BlockMode.HARD_BLOCK) {
            WaitTimeChips(seconds = waitSeconds, onSelect = onWaitSecondsChange)
        }

        trailing()
    }
}

/**
 * The days and the window of a "days and times" schedule: day chips, then Start and End. Shared by
 * App config's scheduled override (inside [ScheduleSection]) and the group editor's "When", so the
 * two read the same (spec 3.3). Days are ISO day numbers (1 = Monday ... 7 = Sunday).
 */
@Composable
fun DaysAndTimesFields(
    days: Set<Int>,
    onDaysChange: (Set<Int>) -> Unit,
    startHour: Int,
    startMinute: Int,
    onStartTimeChange: (Int, Int) -> Unit,
    endHour: Int,
    endMinute: Int,
    onEndTimeChange: (Int, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Active days",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DayOfWeekChips(days = days, onDaysChange = onDaysChange)

        TimeRow("Start time", startHour, startMinute, onStartTimeChange)
        TimeRow("End time", endHour, endMinute, onEndTimeChange)
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
