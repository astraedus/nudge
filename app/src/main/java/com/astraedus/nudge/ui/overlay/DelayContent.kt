package com.astraedus.nudge.ui.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun DelayContent(
    delaySeconds: Int,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    ruleName: String? = null,
    appLabel: String? = null,
    dailyTimeRemainingMs: Long? = null,
    dailyLimitMinutes: Int? = null,
    titlePool: List<String> = NudgeMessages.delayTitles,
    subtitlePool: List<String> = NudgeMessages.delaySubtitles,
    canUseEmergencyPass: Boolean = false,
    emergencyLocked: Boolean = false,
    nextPassMs: Long = 0L,
    onUseEmergencyPass: () -> Unit = {}
) {
    val title = remember { titlePool.random() }
    val subtitle = remember { subtitlePool.random() }
    var remainingSeconds by remember { mutableIntStateOf(delaySeconds) }

    val progress by animateFloatAsState(
        targetValue = if (delaySeconds > 0) remainingSeconds.toFloat() / delaySeconds.toFloat() else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "countdown_progress"
    )

    // The countdown ticks ONLY while the overlay is actually on screen (issue #8). A plain
    // LaunchedEffect(Unit) is not frame-gated: `delay()` kept counting after the user tabbed out,
    // so the timer reached zero invisibly and granted passthrough while they were on the launcher —
    // returning to the app then opened it with no delay at all. `remainingSeconds` is remembered
    // OUTSIDE this block, so a pause resumes where it left off instead of restarting.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
            onComplete()
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
            // App name + daily time remaining
            if (appLabel != null) {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (dailyTimeRemainingMs != null && dailyLimitMinutes != null && dailyLimitMinutes > 0) {
                    Text(
                        text = "${formatDuration(dailyTimeRemainingMs)} left today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = timeRemainingColor(dailyTimeRemainingMs, dailyLimitMinutes)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(180.dp)
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(180.dp),
                    strokeWidth = 8.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Text(
                    text = "$remainingSeconds",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedButton(onClick = onCancel) {
                Text("I changed my mind")
            }

            EmergencyPassAction(
                canUse = canUseEmergencyPass,
                locked = emergencyLocked,
                nextPassMs = nextPassMs,
                onUse = onUseEmergencyPass
            )

            if (ruleName != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Rule: $ruleName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

@Composable
internal fun timeRemainingColor(remainingMs: Long, limitMinutes: Int): androidx.compose.ui.graphics.Color {
    val limitMs = limitMinutes.toLong() * 60_000L
    val pct = if (limitMs > 0) remainingMs.toFloat() / limitMs else 1f
    return when {
        pct > 0.50f -> MaterialTheme.colorScheme.primary
        pct > 0.25f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
}
