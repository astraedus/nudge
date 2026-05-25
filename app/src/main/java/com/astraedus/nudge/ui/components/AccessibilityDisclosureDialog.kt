package com.astraedus.nudge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Prominent disclosure dialog shown BEFORE requesting the Accessibility Service permission.
 *
 * Required by Google Play policy. The dialog explains:
 * - WHY the service is needed (detect foreground apps)
 * - WHAT data is accessed (package names only)
 * - HOW data is used (locally, never sent anywhere)
 *
 * Back press and tapping outside dismiss the dialog WITHOUT granting consent.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("How Nudge Works")
        },
        text = {
            Column {
                Text(
                    "Nudge uses Android's Accessibility Service to detect which app " +
                        "is currently open on your screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This lets Nudge show breathing exercises, delays, or blocks " +
                        "when you open apps you've chosen to limit.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nudge only reads the name of the app in the foreground. " +
                        "It does not read your messages, keystrokes, passwords, " +
                        "or screen content.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "All data stays on your device. Nudge has no internet " +
                        "permission and cannot send data anywhere.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("I Understand")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}
