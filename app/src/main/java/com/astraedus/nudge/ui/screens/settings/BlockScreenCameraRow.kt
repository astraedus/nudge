package com.astraedus.nudge.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.ui.components.rule.MirrorCameraNote
import com.astraedus.nudge.ui.components.rule.rememberMirrorCameraAccess
import kotlinx.coroutines.launch

internal const val BLOCK_SCREEN_CAMERA_TITLE = "Show the camera on block screens"
internal const val BLOCK_SCREEN_CAMERA_SUBTITLE =
    "You see yourself instead of a plain screen. Nothing is recorded."
internal const val BLOCK_SCREEN_CAMERA_OFF_NOTE = "Camera is off, so block screens stay plain."

/** Turning the switch ON asks for the camera, unless it is already granted. Turning it OFF never asks. */
internal fun shouldRequestCameraOnToggle(enable: Boolean, granted: Boolean): Boolean = enable && !granted

/** The "Allow camera" line shows only while the switch is ON and the camera is not granted. */
internal fun blockScreenCameraNoteVisible(enabled: Boolean, granted: Boolean): Boolean = enabled && !granted

/**
 * The Settings row for the camera behind block screens. The setting is saved even when the camera
 * is denied (the block screens then stay plain, and one line says so with the fix).
 */
@Composable
internal fun BlockScreenCameraRow(preferences: NudgePreferences) {
    val scope = rememberCoroutineScope()
    val enabled by preferences.cameraOnBlockScreens.collectAsStateWithLifecycle(initialValue = false)
    val access = rememberMirrorCameraAccess()

    val onToggle: (Boolean) -> Unit = { enable ->
        scope.launch { preferences.setCameraOnBlockScreens(enable) }
        if (shouldRequestCameraOnToggle(enable, access.granted)) access.requestIfNeeded()
    }

    Column {
        ListItem(
            headlineContent = { Text(BLOCK_SCREEN_CAMERA_TITLE) },
            supportingContent = { Text(BLOCK_SCREEN_CAMERA_SUBTITLE) },
            leadingContent = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = onToggle) },
            modifier = Modifier.clickable { onToggle(!enabled) }
        )
        if (blockScreenCameraNoteVisible(enabled, access.granted)) {
            MirrorCameraNote(
                access,
                modifier = Modifier.padding(horizontal = 16.dp),
                text = BLOCK_SCREEN_CAMERA_OFF_NOTE
            )
        }
    }
}
