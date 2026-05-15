package com.astraedus.nudge.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun BreathingContent(
    delaySeconds: Int,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val circleScale = remember { Animatable(0.6f) }
    var isInhaling by remember { mutableStateOf(true) }
    var overallProgress by remember { mutableFloatStateOf(0f) }

    val inhaleMs = 4000
    val exhaleMs = 4000
    val cycleMs = inhaleMs + exhaleMs

    // Breathing cycle animation
    LaunchedEffect(Unit) {
        val totalMs = delaySeconds * 1000L
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= totalMs) {
                onComplete()
                break
            }

            overallProgress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)

            // Inhale: scale from 0.6 to 1.0 over 4 seconds
            isInhaling = true
            circleScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = inhaleMs, easing = LinearEasing)
            )

            val elapsedAfterInhale = System.currentTimeMillis() - startTime
            if (elapsedAfterInhale >= totalMs) {
                onComplete()
                break
            }

            overallProgress = (elapsedAfterInhale.toFloat() / totalMs).coerceIn(0f, 1f)

            // Exhale: scale from 1.0 to 0.6 over 4 seconds
            isInhaling = false
            circleScale.animateTo(
                targetValue = 0.6f,
                animationSpec = tween(durationMillis = exhaleMs, easing = LinearEasing)
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isInhaling) "Breathe in..." else "Breathe out...",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(circleScale.value)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        )
                )

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(circleScale.value)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Overall progress bar
            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val remainingSeconds = ((1f - overallProgress) * delaySeconds).toInt()
            Text(
                text = "${remainingSeconds}s remaining",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedButton(onClick = onCancel) {
                Text("I changed my mind")
            }
        }
    }
}
