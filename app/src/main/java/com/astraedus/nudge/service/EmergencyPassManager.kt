package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.emergency.EmergencyPass
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime owner of the "2-minute daily pass" emergency escape hatch.
 *
 * Modeled on [PassthroughManager] (a small, thread-safe, process-lifetime singleton) plus the
 * "never fail to go home" kick used by the Strict Mode guard.
 *
 * When granted, the app is usable for [EmergencyPass.PASS_DURATION_MS]; the accessibility service
 * short-circuits normal evaluation while [isPassActive] is true. The active free window stays scoped
 * to the app it was granted on (per-package [activeUntil]); the LOCKOUT it records is GLOBAL — once
 * spent, the pass is unavailable for every app for [EmergencyPass.LOCKOUT_MS]. At expiry a scheduled
 * kick sends the user home and clears the window, and the next foreground event re-blocks normally as
 * a backstop. The lockout is persisted so it survives a process restart; the active window is
 * in-memory only (a restart simply ends the window — fail-safe toward re-blocking).
 */
@Singleton
class EmergencyPassManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NudgePreferences
) {
    /** package → epoch-millis when the current free window ends. */
    private val activeUntil = ConcurrentHashMap<String, Long>()

    /** package → scheduled kick job, so a fresh grant can replace the prior timer. */
    private val kickJobs = ConcurrentHashMap<String, Job>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fast, non-blocking check for the accessibility hot path: is a free window open for [pkg]? */
    fun isPassActive(pkg: String): Boolean =
        (activeUntil[pkg] ?: 0L) > System.currentTimeMillis()

    /**
     * Grant a free window for [pkg]: opens the in-memory (per-app) window, persists the GLOBAL
     * lockout, and schedules the kick-home at expiry (replacing any prior kick for the same app).
     * Caller is responsible for gating this (Strict Mode off, feature enabled, outside lockout) —
     * see the overlay Activity.
     */
    fun usePass(pkg: String) {
        if (pkg.isBlank()) return
        val now = System.currentTimeMillis()
        activeUntil[pkg] = now + EmergencyPass.PASS_DURATION_MS

        scope.launch { prefs.recordEmergencyPassUsed(now) }

        kickJobs.remove(pkg)?.cancel()
        kickJobs[pkg] = scope.launch {
            delay(EmergencyPass.PASS_DURATION_MS)
            activeUntil.remove(pkg)
            kickJobs.remove(pkg)
            // Don't yank the user home if Nudge was globally disabled while the window was open — a
            // disabled Nudge must behave as if uninstalled (Bug 3 invariant).
            if (prefs.isGlobalEnabled.first()) kickHome()
        }
    }

    /**
     * Cancel every active free window and its pending kick. Called when Nudge is globally disabled so
     * a scheduled expiry-kick can never fire while enforcement is off.
     */
    fun cancelAll() {
        kickJobs.values.forEach { it.cancel() }
        kickJobs.clear()
        activeUntil.clear()
    }

    /**
     * Send the user home at window expiry. Prefer the accessibility service's GLOBAL_ACTION_HOME;
     * if the service is unavailable, fall back to a HOME intent so we never fail to leave the app.
     */
    private fun kickHome() {
        if (NudgeAccessibilityService.requestGoHome()) return
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)
        } catch (_: Exception) {
            // Best-effort: nothing more we can safely do from a background singleton.
        }
    }

    fun resetForTests() = cancelAll()
}
