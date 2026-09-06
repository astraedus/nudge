package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.astraedus.nudge.domain.health.EnabledAccessibilityServices

/**
 * The one place that answers "is Nudge actually blocking right now?".
 *
 * There used to be no such place: the only reader in the app was a private helper on the Settings
 * screen, called once at first composition, and it read the WRONG SIGNAL.
 *
 * ## Why two reads, not one
 *
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is **intent**, not liveness. Verified against
 * AOSP master (`AccessibilityServiceConnection.binderDied` →
 * `AccessibilityUserState.serviceDisconnectedLocked` → `mCrashedServices`, and
 * `AccessibilityManagerService.updateServicesLocked`'s explicit
 * `if (…getCrashedServicesLocked().contains(componentName)) continue;`):
 *
 * - when an accessibility service's process is killed — an LMK reap overnight, an OEM kill — the
 *   component lands in the crashed set, **AOSP never rebinds it**, and it is **left in the
 *   settings string**. AOSP's own comment says the set exists so "users may toggle the on/off
 *   switch to retry". Only an explicit force-stop removes the component from the string.
 * - so the settings string, the system's own Accessibility toggle, and Nudge's Settings screen all
 *   keep reading "enabled" over a permanently dead service. That IS the bug in our 3-star review
 *   and in issue #23, and a watchdog polling that string would have sat there seeing "all good"
 *   forever.
 *
 * `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` is the liveness
 * signal: server-side it iterates `AccessibilityUserState.mBoundServices`, i.e. services that are
 * actually bound. **The gap between the two lists is exactly the "enabled but dead" state.**
 *
 * Why the process dies in the first place, device-independently: the accessibility binding uses
 * `Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE` (`AccessibilityServiceConnection.bindLocked`), so
 * its foreground-grade `oom_adj` protection lapses while the screen is off. Overnight the process
 * is an ordinary low-memory-killer candidate — which is why our own foreground service running
 * continuously is real protection and not decoration.
 */
object ProtectionStatus {

    /**
     * The setting to watch for live updates. A `ContentObserver` on this URI fires the moment the
     * system enables or disables ANY accessibility service, so it catches the user (or a
     * force-stop) turning ours off. It will NOT fire for a crash — see the class comment — which is
     * why liveness has to be re-read alongside it rather than inferred from it.
     */
    val ACCESSIBILITY_SERVICES_URI: Uri
        get() = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    /**
     * INTENT: is our service listed in the system's enabled-services setting.
     *
     * True does not mean it is running. False means the user turned it off, or the app was
     * force-stopped (the one event AOSP actively strips the component for).
     */
    fun isAccessibilityServiceGranted(context: Context): Boolean =
        EnabledAccessibilityServices.contains(
            raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ),
            packageName = context.packageName,
            className = SERVICE_CLASS_NAME
        )

    /**
     * REALITY: is our service bound and alive right now, as the system sees it.
     *
     * Asks the framework rather than trusting a flag of our own on purpose. An in-process flag
     * cannot distinguish "our process was killed and the service is gone for good" from "our
     * process has only just started", and after a crash-kill it is the FORMER — this is the one
     * question a static boolean is structurally unable to answer.
     */
    fun isAccessibilityServiceConnected(context: Context): Boolean {
        // Both failure paths answer "connected", deliberately. Not being ABLE to read the list is
        // not evidence that we are dead, and this runs every 15 minutes on every device: a missed
        // alert costs one cycle, a false "your phone broke Nudge" costs the user's trust in the one
        // channel we have. The granted check above still catches a genuinely disabled service.
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return true

        val bound: List<AccessibilityServiceInfo> = try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?: emptyList()
        } catch (_: RuntimeException) {
            return true
        }

        // `getComponentName()` is not public API, so match on the two things that are: the id
        // (a ComponentName.flattenToShortString) and the ResolveInfo behind it.
        if (EnabledAccessibilityServices.containsAny(
                entries = bound.map { it.id },
                packageName = context.packageName,
                className = SERVICE_CLASS_NAME
            )
        ) {
            return true
        }

        return bound.any { info ->
            val service = info.resolveInfo?.serviceInfo ?: return@any false
            service.packageName == context.packageName && service.name == SERVICE_CLASS_NAME
        }
    }

    /**
     * What every tick, gate and alert in the app should mean by "accessibility is on": the user
     * wants it AND the system has it bound. A green tick over either half alone is the false
     * success state this whole subsystem was built to delete.
     */
    fun isAccessibilityServiceWorking(context: Context): Boolean =
        isAccessibilityServiceGranted(context) && isAccessibilityServiceConnected(context)

    private val SERVICE_CLASS_NAME: String = NudgeAccessibilityService::class.java.name
}
