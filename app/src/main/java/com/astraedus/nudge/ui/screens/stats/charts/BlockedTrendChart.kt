package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TrendDay(
    val label: String,
    val blockedCount: Int,
    val walkedAwayCount: Int
)

@Composable
fun BlockedTrendChart(
    days: List<TrendDay>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (days.isEmpty() || days.all { it.blockedCount == 0 && it.walkedAwayCount == 0 }) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(100.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No nudges yet",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )
        }
        return
    }

    val maxCount = days.maxOf { maxOf(it.blockedCount, it.walkedAwayCount) }.coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            val barCount = days.size
            val totalSpacing = (barCount - 1) * 8.dp.toPx()
            val barWidth = (size.width - totalSpacing) / barCount
            val chartHeight = size.height - 8.dp.toPx() // Leave room at top

            // Draw blocked bars (behind, with alpha)
            days.forEachIndexed { index, day ->
                val x = index * (barWidth + 8.dp.toPx())
                val fraction = (day.blockedCount.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                val barHeight = (chartHeight * fraction).coerceAtLeast(if (day.blockedCount > 0) 3.dp.toPx() else 0f)

                if (barHeight > 0f) {
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.3f),
                        topLeft = Offset(x, chartHeight - barHeight + 8.dp.toPx()),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }

            // Draw walked-away line with dots
            if (days.any { it.walkedAwayCount > 0 }) {
                val path = Path()
                val points = mutableListOf<Offset>()

                days.forEachIndexed { index, day ->
                    val x = index * (barWidth + 8.dp.toPx()) + barWidth / 2f
                    val fraction = (day.walkedAwayCount.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                    val y = chartHeight - (chartHeight * fraction) + 8.dp.toPx()
                    points.add(Offset(x, y))

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                // Draw line
                drawPath(
                    path = path,
                    color = secondaryColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draw dots
                points.forEach { point ->
                    drawCircle(
                        color = secondaryColor,
                        radius = 3.dp.toPx(),
                        center = point
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

        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = primaryColor.copy(alpha = 0.3f)
            ) {}
            Text(
                " Blocked",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                "   ",
                style = MaterialTheme.typography.labelSmall
            )
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = secondaryColor
            ) {}
            Text(
                " Walked Away",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}
