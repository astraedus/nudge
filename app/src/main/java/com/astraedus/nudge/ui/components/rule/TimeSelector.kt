package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/**
 * Simple time selector using hour/minute FilterChips.
 * Cycles hour by +1 and minute in 15-minute increments on click.
 */
@Composable
fun TimeSelector(
    hour: Int,
    minute: Int,
    onTimeSelected: (Int, Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = true,
            onClick = { onTimeSelected((hour + 1) % 24, minute) },
            label = { Text(String.format("%02d", hour)) }
        )
        Text(":", style = MaterialTheme.typography.bodyLarge)
        FilterChip(
            selected = true,
            onClick = { onTimeSelected(hour, (minute + 15) % 60) },
            label = { Text(String.format("%02d", minute)) }
        )
    }
}
