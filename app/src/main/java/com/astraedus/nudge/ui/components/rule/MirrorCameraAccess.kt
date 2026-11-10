package com.astraedus.nudge.ui.components.rule

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.qr.CameraPermissionFlow
import com.astraedus.nudge.ui.qr.ScanScreenState

/**
 * The CAMERA permission as the rule editors see it, for MIRROR.
 *
 * Mirror works without it (the block shows the plain delay countdown instead), so the permission
 * is asked for when the user PICKS Mirror, and a "no" never un-picks it: the rule stays Mirror and
 * one line under the picker says what will happen. Same decisions as the QR scanner
 * ([CameraPermissionFlow]): a denial the system will not ask about again turns the button into
 * "Open settings".
 */
@Stable
class MirrorCameraAccess internal constructor(granted: Boolean) {
    /** Whether CAMERA is granted right now (re-read every time the screen resumes). */
    var granted by mutableStateOf(granted)
        internal set

    /** The system will no longer show the dialog; only Settings can grant it. */
    var blocked by mutableStateOf(false)
        internal set

    internal var launch: () -> Unit = {}

    /** Ask for the camera, unless it is already granted. */
    fun requestIfNeeded() {
        if (!granted) launch()
    }
}

/**
 * Whether a pick of [mode] should ask for the camera: only Mirror, and only when it is not already
 * granted. Pure so the "picking Mirror asks once, picking anything else never does" rule is tested.
 */
internal fun shouldRequestCameraFor(mode: BlockMode, granted: Boolean): Boolean =
    mode == BlockMode.MIRROR && !granted

@Composable
fun rememberMirrorCameraAccess(): MirrorCameraAccess {
    val context = LocalContext.current
    val access = remember { MirrorCameraAccess(hasCameraPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access.granted = granted
        val rationale = context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: false
        access.blocked = CameraPermissionFlow.afterRequest(granted, rationale) == ScanScreenState.BLOCKED
    }
    access.launch = { launcher.launch(Manifest.permission.CAMERA) }
    // Back from Settings with the permission granted: the note goes away with no further tap.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access.granted = hasCameraPermission(context)
        if (access.granted) access.blocked = false
    }
    return access
}

/**
 * The one line under a picker showing Mirror while the camera is off: what the block will do
 * instead ([text]), and the button that fixes it. Renders nothing once the camera is granted.
 */
@Composable
fun MirrorCameraNote(
    access: MirrorCameraAccess,
    modifier: Modifier = Modifier.fillMaxWidth(),
    text: String = MIRROR_CAMERA_OFF_NOTE
) {
    if (access.granted) return
    val context = LocalContext.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { if (access.blocked) openAppSettings(context) else access.launch() }) {
            Text(if (access.blocked) "Open settings" else "Allow camera")
        }
    }
}

/** The fallback, said plainly. */
internal const val MIRROR_CAMERA_OFF_NOTE = "Camera is off, so Mirror shows a countdown instead."

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Open Settings > Apps > Nudge > Permissions", Toast.LENGTH_LONG).show()
    }
}
