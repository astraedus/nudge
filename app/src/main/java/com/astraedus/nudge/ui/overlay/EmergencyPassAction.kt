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
 * The "1-minute daily pass" escape-hatch action shown on every block overlay, below the primary
 * "Go Back" / "I changed my mind" button and above the "Rule: X" footer.
 *
 * Deliberately understated (a muted [TextButton], onSurfaceVariant text) so it reads as a last
 * resort, not a primary CTA. When the feature is off or Strict Mode is on, the caller passes
 * [canUse] = [locked] = false and nothing renders. When the pass is spent, a muted "next in Xh"
 * hint shows instead of the button.
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
                    text = "Use for 1 minute · once a day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        locked -> {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Daily pass used · next in ${formatDuration(nextPassMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}
