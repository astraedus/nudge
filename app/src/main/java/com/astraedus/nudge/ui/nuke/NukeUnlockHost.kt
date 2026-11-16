package com.astraedus.nudge.ui.nuke

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.nuke.NukeEmergencyCode
import com.astraedus.nudge.ui.components.ChallengeDialog
import com.astraedus.nudge.ui.qr.ScanQrContract

/** Whether this device has any camera at all. A phone without one gets null from every scan. */
fun hasAnyCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * The dialog every Nuke-weakening action goes through: scan the paired key, or type a fresh
 * 64-character emergency code. Renders nothing while [state] is null.
 *
 * Owns its own camera launcher, so any screen whose ViewModel holds a [NukeGate] gets the whole
 * flow from one call (the Home screen's master toggle, the Nuke screen). The scan result goes to
 * [onScanned]; the gate decides whether it was the key.
 *
 * The emergency half REUSES [ChallengeDialog] (paste suppressed, dash-insensitive, case-sensitive,
 * live x/y counter) with a Nuke title and a fresh [NukeEmergencyCode] per attempt.
 */
@Composable
fun NukeUnlockHost(
    state: NukeUnlockState?,
    onScanned: (String?) -> Unit,
    onUseEmergencyCode: () -> Unit,
    onVerifyEmergency: (String) -> Unit,
    onBackToChoice: () -> Unit,
    onCancel: () -> Unit
) {
    val scanner = rememberLauncherForActivityResult(ScanQrContract()) { payload -> onScanned(payload) }
    val context = LocalContext.current
    val hasCamera = remember { hasAnyCamera(context) }
    val active = state ?: return

    val target = active.emergencyTarget
    if (target != null) {
        ChallengeDialog(
            target = target,
            title = "Emergency: end Nuke",
            prompt = "${active.prompt}. No code on you? Type all ${NukeEmergencyCode.LENGTH} " +
                "characters below, exactly. Every attempt gets a new one.",
            onUnlock = onVerifyEmergency,
            onCancel = onBackToChoice,
            confirmLabel = "End it"
        )
        return
    }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
        title = { Text("Nuke is on") },
        text = {
            Column {
                Text(
                    if (hasCamera) {
                        "${active.prompt}? That needs your Nuke code. Go and get it, and scan it here."
                    } else {
                        "${active.prompt}? That needs your Nuke code, and this phone has no camera " +
                            "to scan it with. The emergency code is the way out."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                active.error?.let {
                    Spacer(Modifier.height(12.dp))
                    // A locked code is not a mistake: plain body text, so it never reads like the
                    // link below it. A wrong code keeps the error colour.
                    Text(
                        it,
                        color = if (active.errorIsCodeLock) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onUseEmergencyCode) {
                    Text("No code on you? Type the emergency code")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hasCamera,
                onClick = {
                    scanner.launch(ScanQrContract.Request(title = "Scan your Nuke code", subtitle = active.prompt))
                }
            ) {
                Text("Scan code")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Never mind") }
        }
    )
}
