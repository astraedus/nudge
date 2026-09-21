package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The overlay for a [com.astraedus.nudge.domain.model.BlockMode.HOLD] block: the app opens after the
 * rule's duration, but only if a finger stays on the target for all of it
 * ([issue #35](https://github.com/astraedus/nudge/issues/35)).
 *
 * Structurally this IS [DelayContent] with the countdown swapped for [HoldTarget] — same app label
 * and daily-time line, same headline pool, same "I changed my mind", same escape hatch, same rule
 * footer. The screen should read as the delay the user already knows, with one rule changed, not as
 * a different feature.
 *
 * The countdown is not [DelayContent]'s: there is no ticker here at all. The only thing that moves
 * time forward is a finger, so the whole timing question is [HoldTarget]'s and — under it — the pure
 * `HoldProgress`.
 *
 * @param onComplete the block's existing completion callback. The hold IS the timer; completing it
 *   lands in the same `onTimerComplete()` every other timed overlay lands in, which is where the one
 *   passthrough grant lives.
 */
@Composable
fun HoldContent(
    holdSeconds: Int,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    ruleName: String? = null,
    appLabel: String? = null,
    dailyTimeRemainingMs: Long? = null,
    dailyLimitMinutes: Int? = null,
    titlePool: List<String> = NudgeMessages.delayTitles,
    canUseEmergencyPass: Boolean = false,
    emergencyLocked: Boolean = false,
    nextPassMs: Long = 0L,
    onUseEmergencyPass: () -> Unit = {}
) {
    // Unkeyed by design, exactly as in DelayContent: BlockOverlayActivity composes this subtree
    // under a per-delivery key, so a new block already gets a fresh headline (issue #15).
    val title = remember { titlePool.random() }

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
                Spacer(modifier = Modifier.height(24.dp))
            }

            HoldTarget(
                holdDurationMs = holdSeconds.toLong() * 1000L,
                // The SAME completion callback every other timed overlay hands to its timer. The
                // hold gates the one existing grant; it never becomes a second way in.
                onHoldComplete = onComplete
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
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
