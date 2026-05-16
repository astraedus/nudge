package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HourlyHeatmap(
    hourlyMs: List<Long>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (hourlyMs.size != 24) return

    val maxMs = hourlyMs.max().coerceAtLeast(1L)
    val hasData = hourlyMs.any { it > 0L }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!hasData) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No usage recorded today",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val cellCount = 24
                val totalSpacing = (cellCount - 1) * 2.dp.toPx()
                val cellSize = (size.width - totalSpacing) / cellCount
                val cellHeight = size.height

                hourlyMs.forEachIndexed { index, ms ->
                    val x = index * (cellSize + 2.dp.toPx())
                    val intensity = (ms.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)

                    val color = if (ms == 0L) {
                        surfaceVariantColor
                    } else {
                        lerp(surfaceVariantColor, primaryColor, intensity)
                    }

                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size(cellSize, cellHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }

            // Hour labels (just a few markers)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("12am", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
                Text("6am", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
                Text("12pm", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
                Text("6pm", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
                Text("12am", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
            }
        }
    }
}
