package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StreakCounter(
    streakDays: Int,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = "Streak",
            tint = if (streakDays > 0) primaryColor else onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text = "$streakDays",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (streakDays > 0) primaryColor else onSurfaceVariant
        )
        Text(
            text = " day${if (streakDays != 1) "s" else ""} streak",
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
