package com.astraedus.nudge.ui

import android.content.Context
import android.provider.Settings

/**
 * Check whether the app has WRITE_SECURE_SETTINGS permission,
 * which is required for grayscale mode. This permission can only
 * be granted via ADB, not through the normal Android UI.
 */
fun hasGrayscalePermission(context: Context): Boolean {
    return try {
        val current = Settings.Secure.getInt(
            context.contentResolver,
            "accessibility_display_daltonizer_enabled",
            0
        )
        Settings.Secure.putInt(
            context.contentResolver,
            "accessibility_display_daltonizer_enabled",
            current
        )
        true
    } catch (_: SecurityException) {
        false
    }
}
