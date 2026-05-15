package com.astraedus.nudge.service

import android.content.Context
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages system-wide grayscale (color correction) to make blocked apps less visually appealing.
 *
 * Requires WRITE_SECURE_SETTINGS, which can only be granted via ADB:
 * ```
 * adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS
 * ```
 *
 * Uses the `accessibility_display_daltonizer` system setting:
 *   daltonizer_enabled = 1 -> color correction on
 *   daltonizer = 0 -> grayscale (simulated monochromacy)
 */
@Singleton
class GrayscaleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "GrayscaleManager"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val DALTONIZER = "accessibility_display_daltonizer"
        private const val DALTONIZER_GRAYSCALE = 0
    }

    fun enableGrayscale() {
        try {
            Settings.Secure.putInt(context.contentResolver, DALTONIZER_ENABLED, 1)
            Settings.Secure.putInt(context.contentResolver, DALTONIZER, DALTONIZER_GRAYSCALE)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted -- cannot enable grayscale", e)
        }
    }

    fun disableGrayscale() {
        try {
            Settings.Secure.putInt(context.contentResolver, DALTONIZER_ENABLED, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted -- cannot disable grayscale", e)
        }
    }

    fun isGrayscaleActive(): Boolean {
        return try {
            Settings.Secure.getInt(context.contentResolver, DALTONIZER_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check whether the app has WRITE_SECURE_SETTINGS permission by attempting
     * a no-op write (read current value, write it back).
     */
    fun hasPermission(): Boolean {
        return try {
            val current = Settings.Secure.getInt(context.contentResolver, DALTONIZER_ENABLED, 0)
            Settings.Secure.putInt(context.contentResolver, DALTONIZER_ENABLED, current)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
