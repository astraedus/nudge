package com.astraedus.nudge.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * The two independent facts that decide whether Nudge is actually enforcing anything.
 *
 * Split behind an interface so [NudgeMonitorService]'s health poll is testable without a device,
 * and so the distinction stays legible: [isPermissionGranted] is what the USER controls (the
 * Settings toggle), [isServiceConnected] is what the SYSTEM controls (the bind). They disagree for
 * minutes after a process kill, and that disagreement is exactly the failure this reports.
 */
interface AccessibilityStatusProvider {
    /** Our service is listed in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. */
    fun isPermissionGranted(): Boolean

    /** Our `AccessibilityService` is bound and live in this process right now. */
    fun isServiceConnected(): Boolean
}

class AndroidAccessibilityStatusProvider(
    private val context: Context
) : AccessibilityStatusProvider {

    /**
     * Read the raw setting rather than `AccessibilityManager.getEnabledAccessibilityServiceList`:
     * that list reflects *bound* services on some builds, which would collapse the two questions
     * into one and hide the exact state we are trying to detect.
     *
     * Fails CLOSED on an unreadable setting — reporting "permission missing" nags a working install
     * at worst, while reporting "granted" would let a genuinely un-granted install look healthy.
     */
    override fun isPermissionGranted(): Boolean {
        val expected = ComponentName(context, NudgeAccessibilityService::class.java)
        val raw = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        } ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(raw) }
        return splitter.any { entry ->
            ComponentName.unflattenFromString(entry)?.equals(expected) == true
        }
    }

    override fun isServiceConnected(): Boolean = NudgeAccessibilityService.isConnected()
}
