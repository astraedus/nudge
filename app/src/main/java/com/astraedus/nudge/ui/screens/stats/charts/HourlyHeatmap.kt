package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HourlyHeatmap(
    hourlyMs: List<Long>,
    modifier: Modifier = Modifier,
    formatDuration: (Long) -> String = { ms ->
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        when {
            h > 0L -> "${h}h ${m}m"
            m > 0L -> "${m}m"
            ms > 0L -> "< 1m"
            else -> "0m"
        }
    }
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    if (hourlyMs.size != 24) return

    val maxMs = hourlyMs.max().coerceAtLeast(1L)
    val hasData = hourlyMs.any { it > 0L }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

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
            if (selectedIndex != null) {
                val ms = hourlyMs.getOrElse(selectedIndex!!) { 0L }
                val hourLabel = formatHourLabel(selectedIndex!!)
                Text(
                    "$hourLabel: ${formatDuration(ms)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(hourlyMs) {
                        detectTapGestures { offset ->
                            val spacing = 2.dp.toPx()
                            val cellSize = (size.width - 23 * spacing) / 24
                            val step = cellSize + spacing
                            val index = (offset.x / step).toInt().coerceIn(0, 23)
                            selectedIndex = if (selectedIndex == index) null else index
                        }
                    }
            ) {
                val cellCount = 24
                val totalSpacing = (cellCount - 1) * 2.dp.toPx()
                val cellSize = (size.width - totalSpacing) / cellCount
                val cellHeight = size.height

                hourlyMs.forEachIndexed { index, ms ->
                    val x = index * (cellSize + 2.dp.toPx())
                    val intensity = (ms.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)

                    val baseColor = if (ms == 0L) {
                        surfaceVariantColor
                    } else {
                        lerp(surfaceVariantColor, primaryColor, intensity)
                    }

                    val color = if (selectedIndex != null && selectedIndex != index) {
                        baseColor.copy(alpha = 0.5f)
                    } else {
                        baseColor
                    }

                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size(cellSize, cellHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )

                    // Draw border on selected cell
                    if (selectedIndex == index) {
                        drawRoundRect(
                            color = onSurfaceColor,
                            topLeft = Offset(x, 0f),
                            size = Size(cellSize, cellHeight),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
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

private fun formatHourLabel(hour: Int): String = when (hour) {
    0 -> "12am"
    in 1..11 -> "${hour}am"
    12 -> "12pm"
    else -> "${hour - 12}pm"
}
