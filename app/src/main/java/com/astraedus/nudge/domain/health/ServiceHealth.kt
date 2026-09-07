package com.astraedus.nudge.domain.health

/**
 * What Nudge should be *saying about itself* right now.
 *
 * ## Why this exists
 * On 2026-09-07 the Pixel's nightly `full_backup_package dev.astraedus.nudge` killed the process
 * (`am_proc_died` reason 100) and Android's own Settings screen said, correctly:
 *
 * > "Accessibility Service: Enabled, but your phone stopped it, turn it off and back on to restart
 * > blocking"
 *
 * Nudge said nothing. Worse, its permanent foreground notification carried the hardcoded text
 * "Nudge is active / Monitoring app usage" throughout — a blocker that had stopped blocking was
 * still telling the user it was blocking. Every enforcement path in this app hangs off the
 * accessibility service being *bound*, and "enabled in Settings" is not the same question.
 *
 * Three states, three different sentences, one function that maps between them. It is pure so the
 * whole truth table is a unit test rather than a device session — and so no caller can invent a
 * fourth state in a `when` branch's `else`.
 */
enum class ServiceHealth {
    /** Master toggle off. A disabled Nudge must behave as if uninstalled — including saying nothing. */
    DISABLED,

    /** The user never granted (or has revoked) the accessibility permission. Nothing is enforced. */
    PERMISSION_MISSING,

    /**
     * Granted in Settings, but our service is not connected: the OS killed the process (backup,
     * memory pressure, "force stop") and the rebind has not landed. **Blocking is down and the user
     * cannot tell from inside the app.** This is the state the whole file exists for.
     */
    STOPPED_BY_SYSTEM,

    /** Granted, bound, enforcing. */
    ACTIVE;

    /** True when Nudge is not currently enforcing anything despite the user having asked it to. */
    val isDegraded: Boolean
        get() = this == PERMISSION_MISSING || this == STOPPED_BY_SYSTEM

    companion object {
        /**
         * @param globalEnabled the master toggle (`NudgePreferences.isGlobalEnabled`).
         * @param permissionGranted whether our service is listed in
         *   `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. This is what the *user* controls.
         * @param serviceConnected whether our `AccessibilityService` instance is live in this
         *   process right now (`onServiceConnected` ran, `onDestroy` has not). This is what the
         *   *system* controls, and the two disagree for minutes at a time after a process kill.
         */
        fun evaluate(
            globalEnabled: Boolean,
            permissionGranted: Boolean,
            serviceConnected: Boolean
        ): ServiceHealth = when {
            !globalEnabled -> DISABLED
            !permissionGranted -> PERMISSION_MISSING
            !serviceConnected -> STOPPED_BY_SYSTEM
            else -> ACTIVE
        }
    }
}
