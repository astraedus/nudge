package com.astraedus.nudge.ui.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DelayContent(
    delaySeconds: Int,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    ruleName: String? = null,
    untilText: String? = null,
    appLabel: String? = null,
    dailyTimeRemainingMs: Long? = null,
    dailyLimitMinutes: Int? = null,
    titlePool: List<String> = NudgeMessages.delayTitles,
    subtitlePool: List<String> = NudgeMessages.delaySubtitles,
    canUseEmergencyPass: Boolean = false,
    emergencyLocked: Boolean = false,
    nextPassMs: Long = 0L,
    onUseEmergencyPass: () -> Unit = {},
    redirect: @Composable () -> Unit = {}
) {
    val countdown = rememberBlockCountdown(delaySeconds, onComplete)
    DelayBody(
        countdown = countdown,
        onCancel = onCancel,
        ruleName = ruleName,
        untilText = untilText,
        appLabel = appLabel,
        dailyTimeRemainingMs = dailyTimeRemainingMs,
        dailyLimitMinutes = dailyLimitMinutes,
        titlePool = titlePool,
        subtitlePool = subtitlePool,
        canUseEmergencyPass = canUseEmergencyPass,
        emergencyLocked = emergencyLocked,
        nextPassMs = nextPassMs,
        onUseEmergencyPass = onUseEmergencyPass,
        redirect = redirect
    )
}

/**
 * The DELAY picture for a [countdown] someone else owns: the ring, the number and the exits.
 * [DelayContent] is this plus its own countdown; MIRROR renders it as its fallback over the SAME
 * countdown, so a camera that fails mid-wait changes the picture and nothing else.
 */
@Composable
internal fun DelayBody(
    countdown: BlockCountdown,
    onCancel: () -> Unit,
    ruleName: String?,
    untilText: String?,
    appLabel: String?,
    dailyTimeRemainingMs: Long?,
    dailyLimitMinutes: Int?,
    titlePool: List<String>,
    subtitlePool: List<String>,
    canUseEmergencyPass: Boolean,
    emergencyLocked: Boolean,
    nextPassMs: Long,
    onUseEmergencyPass: () -> Unit,
    redirect: @Composable () -> Unit
) {
    val title = remember { titlePool.random() }
    val subtitle = remember { subtitlePool.random() }
    val remainingSeconds = countdown.remainingSeconds
    val delaySeconds = countdown.totalSeconds

    val progress by animateFloatAsState(
        targetValue = if (delaySeconds > 0) remainingSeconds.toFloat() / delaySeconds.toFloat() else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "countdown_progress"
    )

    // Centred while it fits, scrollable only when it does not: see overlayContentScroll.
    val scroll = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // targetSdk 36 enforces edge-to-edge with no opt-out, so the window now spans
                // under the status and navigation bars. The Surface above stays full-bleed (the
                // block must cover every pixel of the app behind it); only the CONTENT is inset.
                .safeDrawingPadding()
                .overlayContentScroll(scroll)
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

            // Title and subtitle ABOVE the visual keep the visual and the leave button together
            // in the vertical middle of the (centred) column.
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

            Spacer(modifier = Modifier.height(32.dp))

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

            Spacer(Modifier.height(24.dp))

            // The way out sits right under the countdown, big and centred. The ring itself is never
            // clickable: leaving is this button, not a tap on the clock.
            LeaveButton(onClick = onCancel)

            Spacer(Modifier.height(24.dp))

            // The redirect app: one tap leaves for somewhere better, through the same walk-away.
            redirect()

            EmergencyPassAction(
                canUse = canUseEmergencyPass,
                locked = emergencyLocked,
                nextPassMs = nextPassMs,
                onUse = onUseEmergencyPass
            )

            RuleFooter(ruleName = ruleName, until = untilText)
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
