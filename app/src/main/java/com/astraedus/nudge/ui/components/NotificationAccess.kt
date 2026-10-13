package com.astraedus.nudge.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The two notification-permission moves every opt-in Nudge notification makes from the UI: ask for
 * `POST_NOTIFICATIONS` at the moment the user switches the thing on, and send them to Nudge's own
 * notification settings when it is on but cannot arrive. Shared by the "Bro. wtf." Settings row and
 * the usage-reminder section of both rule editors, so the two cannot drift in how they ask.
 */
internal object NotificationAccess {

    /** True on Android 13+ when the runtime notification grant is missing (below 13 it is implicit). */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** Nudge's own notification settings page (API 26+, which is our minSdk). */
    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
