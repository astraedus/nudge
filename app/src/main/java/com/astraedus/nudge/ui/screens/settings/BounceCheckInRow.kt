package com.astraedus.nudge.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.service.BounceCheckInNotifier
import kotlinx.coroutines.launch

/**
 * What the "Bro. wtf." row says, and what a tap on its body does.
 *
 * Pure so the one state that matters is testable: the switch ON while notifications are blocked.
 * A switch that reads on and never delivers anything is the "it doesn't work sometimes" review this
 * repo has already had about the accessibility tick, so the row says so and the tap goes to the fix.
 */
internal data class BounceCheckInRowCopy(val subtitle: String, val tapFixesNotifications: Boolean)

internal fun bounceCheckInRowCopy(enabled: Boolean, canPostNotifications: Boolean): BounceCheckInRowCopy =
    if (enabled && !canPostNotifications) {
        BounceCheckInRowCopy(
            subtitle = BOUNCE_ROW_BLOCKED_SUBTITLE,
            tapFixesNotifications = true
        )
    } else {
        BounceCheckInRowCopy(subtitle = BOUNCE_ROW_SUBTITLE, tapFixesNotifications = false)
    }

internal const val BOUNCE_ROW_TITLE = "Bro. wtf."
internal const val BOUNCE_ROW_SUBTITLE =
    "Get a nudge when you bounce between apps after hitting a wall."
internal const val BOUNCE_ROW_BLOCKED_SUBTITLE =
    "Notifications are off for Nudge, so these can't reach you. Tap to turn them on."

/**
 * The Settings row for the bounce check-in (docs/architecture/bounce-check-in.md).
 *
 * Turning it ON on Android 13+ without the notification grant asks for it right there: the switch
 * is the moment the user said they want a notification, so that is the moment to ask. The setting is
 * saved either way, and the row then explains, until the grant arrives, why nothing will show up.
 */
@Composable
internal fun BounceCheckInRow(preferences: NudgePreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by preferences.bounceCheckInEnabled.collectAsStateWithLifecycle(initialValue = false)
    var canPost by remember { mutableStateOf(BounceCheckInNotifier.canPost(context)) }

    // Re-read on resume: the grant is changed in system settings, and the user comes back here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canPost = BounceCheckInNotifier.canPost(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { canPost = BounceCheckInNotifier.canPost(context) }

    val onToggle: (Boolean) -> Unit = { enable ->
        scope.launch { preferences.setBounceCheckInEnabled(enable) }
        if (enable && needsNotificationPermission(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val copy = bounceCheckInRowCopy(enabled, canPost)
    ListItem(
        headlineContent = { Text(BOUNCE_ROW_TITLE) },
        supportingContent = {
            Text(
                copy.subtitle,
                color = if (copy.tapFixesNotifications) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        leadingContent = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onToggle) },
        modifier = Modifier.clickable {
            if (copy.tapFixesNotifications) openNotificationSettings(context) else onToggle(!enabled)
        }
    )
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/** Nudge's own notification settings page (API 26+, which is our minSdk). */
private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
