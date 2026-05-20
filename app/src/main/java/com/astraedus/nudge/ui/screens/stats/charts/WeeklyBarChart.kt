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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DayData(
    val label: String,
    val totalMs: Long
)

@Composable
fun WeeklyBarChart(
    days: List<DayData>,
    modifier: Modifier = Modifier,
    formatDuration: (Long) -> String = { ms ->
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        when {
            h > 0L -> "${h}h ${m}m"
            m > 0L -> "${m}m"
            else -> "< 1m"
        }
    }
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (days.isEmpty() || days.all { it.totalMs == 0L }) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No data this week",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )
        }
        return
    }

    val maxMs = days.maxOf { it.totalMs }.coerceAtLeast(1L)
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (selectedIndex != null) {
            val day = days.getOrNull(selectedIndex!!)
            if (day != null) {
                Text(
                    "${day.label}: ${formatDuration(day.totalMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        val barCount = days.size
                        val spacing = 8.dp.toPx()
                        val totalSpacing = (barCount - 1) * spacing
                        val barWidth = (size.width - totalSpacing) / barCount
                        val step = barWidth + spacing
                        val index = (offset.x / step).toInt().coerceIn(0, days.lastIndex)
                        selectedIndex = if (selectedIndex == index) null else index
                    }
                }
        ) {
            val barCount = days.size
            val totalSpacing = (barCount - 1) * 8.dp.toPx()
            val barWidth = (size.width - totalSpacing) / barCount
            val chartHeight = size.height

            days.forEachIndexed { index, day ->
                val x = index * (barWidth + 8.dp.toPx())
                val fraction = (day.totalMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
                val barHeight = (chartHeight * fraction).coerceAtLeast(if (day.totalMs > 0) 4.dp.toPx() else 0f)

                // Background bar
                drawRoundRect(
                    color = surfaceVariantColor,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, chartHeight),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )

                // Filled bar from bottom
                if (barHeight > 0f) {
                    val barColor = if (selectedIndex != null && selectedIndex != index) {
                        primaryColor.copy(alpha = 0.4f)
                    } else {
                        primaryColor
                    }
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, chartHeight - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                }
            }
        }

        // Day labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            days.forEach { day ->
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}
