package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The "2-minute daily pass" escape-hatch action shown on every block overlay, below the primary
 * "Go Back" / "I changed my mind" button and above the "Rule: X" footer.
 *
 * Deliberately understated (a muted [TextButton]) so it reads as a last resort, not a primary CTA.
 * When the feature is off or Strict Mode is on, the caller passes [canUse] = [locked] = false and
 * nothing renders. When the pass is spent (globally, across all apps), a visibly DISABLED (grey)
 * button renders — NOT hidden — with the "Daily pass used · next in Xh" hint, so the user can see the
 * escape exists but is used up for the day.
 */
@Composable
fun EmergencyPassAction(
    canUse: Boolean,
    locked: Boolean,
    nextPassMs: Long,
    onUse: () -> Unit
) {
    when {
        canUse -> {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onUse) {
                Text(
                    text = "Use for 2 minutes · once a day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        locked -> {
            Spacer(modifier = Modifier.height(8.dp))
            // Disabled TextButton greys its content automatically — a visible "used up" control
            // rather than a hidden one, so the user knows the daily pass exists but is spent.
            TextButton(onClick = {}, enabled = false) {
                Text(
                    text = "Daily pass used · next in ${formatDuration(nextPassMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
