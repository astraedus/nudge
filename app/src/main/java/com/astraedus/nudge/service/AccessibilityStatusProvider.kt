package com.astraedus.nudge.service

import android.content.Context

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

    /** Our `AccessibilityService` is bound and enforcing right now. */
    fun isServiceConnected(): Boolean
}

/**
 * Delegates to [ProtectionStatus], which is the app's single answer to both questions.
 *
 * This class used to parse the settings string itself and read a static
 * `NudgeAccessibilityService.isConnected()`. Two lanes had independently built a health model from
 * the same base commit, so the app briefly carried two: a health poll here and a watchdog in
 * `ProtectionCheck`, each with its own reader. Two readers of the same fact is a bug waiting for
 * the day they disagree, and the Settings screen's permission tick reads a third path still, so
 * they all now come through one place.
 *
 * The connected read changed in the merge, deliberately. The old one was
 * `instance != null` - an in-process static, true only if `onServiceConnected` has run in THIS
 * process. That cannot tell "the OS killed us and will never rebind" from "we have only just
 * started", which is the exact distinction the alert copy turns on: one needs an off-and-on-again,
 * the other needs nothing at all. [ProtectionStatus.isAccessibilityServiceConnected] asks the
 * framework for its bound-services list instead, so the answer survives our own process dying and
 * is the same answer the Settings screen shows the user.
 */
class AndroidAccessibilityStatusProvider(
    private val context: Context
) : AccessibilityStatusProvider {

    override fun isPermissionGranted(): Boolean =
        ProtectionStatus.isAccessibilityServiceGranted(context)

    override fun isServiceConnected(): Boolean =
        ProtectionStatus.isAccessibilityServiceConnected(context)
}
