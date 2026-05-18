package com.astraedus.nudge.service

import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-app interaction counts (taps and scrolls) in memory.
 *
 * Session counts reset when the foreground app changes AND the user has been
 * away from the app longer than [SESSION_EXPIRY_MS]. Returning within the
 * expiry window preserves the session count so users cannot game auto-kick
 * by closing and reopening an app.
 *
 * Daily totals persist until [resetDaily] is called (service restart or midnight).
 * All fields are in-memory only -- no DB writes on every interaction.
 */
@Singleton
class InteractionTracker @Inject constructor() {

    companion object {
        /** Session counter persists if the user returns within this window. */
        const val SESSION_EXPIRY_MS = 5L * 60L * 1000L
    }

    data class SessionCount(
        val packageName: String,
        val sessionCount: Int = 0,
        val dailyTotal: Int = 0,
        val countType: CountType = CountType.TAP
    )

    enum class CountType { TAP, SCROLL }

    /** Override in tests to control time. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private val sessionCounts = mutableMapOf<String, Int>()
    private val dailyTotals = mutableMapOf<String, Int>()
    private var currentPackage: String? = null

    /** Package -> epoch ms when the user last left the app. */
    private val lastLeftAt = mutableMapOf<String, Long>()

    /** Package -> epoch ms when cooldown expires. Set after auto-kick. */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** Called when the foreground app changes. Resets the session count only if
     *  the user has been away longer than [SESSION_EXPIRY_MS] (and not in cooldown). */
    fun onAppChanged(packageName: String) {
        if (packageName != currentPackage) {
            // Record when we left the current app
            currentPackage?.let { lastLeftAt[it] = clock() }

            // Only reset session counter if away long enough (and not in cooldown)
            if (!isInCooldown(packageName)) {
                val leftAt = lastLeftAt[packageName]
                val now = clock()
                if (leftAt == null || (now - leftAt) >= SESSION_EXPIRY_MS) {
                    sessionCounts[packageName] = 0
                }
            }
            lastLeftAt.remove(packageName)
            currentPackage = packageName
        }
    }

    /** Records a single interaction and returns the updated counts. */
    fun recordInteraction(packageName: String): SessionCount {
        val session = (sessionCounts[packageName] ?: 0) + 1
        sessionCounts[packageName] = session
        val daily = (dailyTotals[packageName] ?: 0) + 1
        dailyTotals[packageName] = daily
        return SessionCount(packageName, session, daily)
    }

    fun getSessionCount(packageName: String): Int = sessionCounts[packageName] ?: 0
    fun getDailyTotal(packageName: String): Int = dailyTotals[packageName] ?: 0

    fun resetSession(packageName: String) {
        sessionCounts[packageName] = 0
        lastLeftAt.remove(packageName)
        if (currentPackage == packageName) {
            currentPackage = null
        }
    }

    /** Clear daily totals -- called at midnight or service start. */
    fun resetDaily() {
        dailyTotals.clear()
        lastLeftAt.clear()
    }

    // --- Cooldown ---

    /** Set a cooldown that expires [durationMs] from now. */
    fun setCooldown(packageName: String, durationMs: Long) {
        cooldownUntil[packageName] = clock() + durationMs
    }

    /** Returns true if the package is currently in cooldown. */
    fun isInCooldown(packageName: String): Boolean {
        val until = cooldownUntil[packageName] ?: return false
        if (clock() >= until) {
            cooldownUntil.remove(packageName)
            return false
        }
        return true
    }

    /** Returns remaining cooldown time in ms, or 0 if not in cooldown. */
    fun getCooldownRemainingMs(packageName: String): Long {
        val until = cooldownUntil[packageName] ?: return 0L
        val remaining = until - clock()
        if (remaining <= 0L) {
            cooldownUntil.remove(packageName)
            return 0L
        }
        return remaining
    }

    /** Clear the cooldown for a package. */
    fun clearCooldown(packageName: String) {
        cooldownUntil.remove(packageName)
    }
}
