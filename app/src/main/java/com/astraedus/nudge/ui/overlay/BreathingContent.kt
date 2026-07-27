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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun BreathingContent(
    delaySeconds: Int,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    ruleName: String? = null,
    appLabel: String? = null,
    dailyTimeRemainingMs: Long? = null,
    dailyLimitMinutes: Int? = null,
    subtitlePool: List<String> = NudgeMessages.delaySubtitles,
    canUseEmergencyPass: Boolean = false,
    emergencyLocked: Boolean = false,
    nextPassMs: Long = 0L,
    onUseEmergencyPass: () -> Unit = {}
) {
    val subtitle = remember { subtitlePool.random() }
    val circleScale = remember { Animatable(0.6f) }
    var isInhaling by remember { mutableStateOf(true) }
    var overallProgress by remember { mutableFloatStateOf(0f) }

    val inhaleMs = 4000
    val exhaleMs = 4000
    val totalMs = delaySeconds * 1000L

    // Time the user has actually spent looking at the exercise. Held outside the lifecycle block so
    // it survives a pause, and accumulated per visible segment rather than measured from a single
    // start timestamp — otherwise time spent away from the overlay would still count down (#8).
    var elapsedMs by remember { mutableLongStateOf(0L) }

    // repeatOnLifecycle cancels the block below RESUMED and starts a NEW one from the top on
    // re-entry. Once elapsedMs has covered totalMs, the first tick() of that new block would
    // complete again — so completion is guarded exactly-once. onComplete grants passthrough, and
    // this path must never be able to grant it twice.
    val completed = remember { AtomicBoolean(false) }

    // Breathing cycle animation — runs ONLY while the overlay is on screen (issue #8).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, totalMs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var segmentStart = System.currentTimeMillis()

            // Fold the segment that just ended into the accumulated total, refresh the progress
            // bar, and report whether the exercise is finished.
            fun tick(): Boolean {
                val now = System.currentTimeMillis()
                elapsedMs = advanceBreathingElapsed(elapsedMs, segmentStart, now)
                segmentStart = now
                overallProgress = breathingProgress(elapsedMs, totalMs)
                return isBreathingComplete(elapsedMs, totalMs)
            }

            fun completeOnce() {
                if (completed.compareAndSet(false, true)) onComplete()
            }

            while (true) {
                if (tick()) {
                    completeOnce()
                    break
                }

                // Inhale: scale from 0.6 to 1.0 over 4 seconds
                isInhaling = true
                circleScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = inhaleMs, easing = LinearEasing)
                )

                if (tick()) {
                    completeOnce()
                    break
                }

                // Exhale: scale from 1.0 to 0.6 over 4 seconds
                isInhaling = false
                circleScale.animateTo(
                    targetValue = 0.6f,
                    animationSpec = tween(durationMillis = exhaleMs, easing = LinearEasing)
                )
            }
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
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = if (isInhaling) "Breathe in..." else "Breathe out...",
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

/**
 * Fold the wall-clock duration of the segment that just ended into the accumulated visible time.
 * Clamped at zero so a backwards clock jump (NTP correction, timezone change) can never rewind
 * progress and strand the user on the exercise.
 */
internal fun advanceBreathingElapsed(elapsedMs: Long, segmentStartMs: Long, nowMs: Long): Long =
    elapsedMs + (nowMs - segmentStartMs).coerceAtLeast(0L)

/** Fraction of the exercise completed, clamped to 0f..1f. A zero-length exercise reads as done. */
internal fun breathingProgress(elapsedMs: Long, totalMs: Long): Float =
    if (totalMs <= 0L) 1f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)

/** True once the accumulated visible time has covered the configured exercise length. */
internal fun isBreathingComplete(elapsedMs: Long, totalMs: Long): Boolean = elapsedMs >= totalMs
