package com.astraedus.nudge.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-app interaction counts (taps and scrolls) in memory.
 *
 * Session counts reset when the foreground app changes.
 * Daily totals persist until [resetDaily] is called (service restart or midnight).
 * All fields are in-memory only -- no DB writes on every interaction.
 */
@Singleton
class InteractionTracker @Inject constructor() {

    data class SessionCount(
        val packageName: String,
        val sessionCount: Int = 0,
        val dailyTotal: Int = 0,
        val countType: CountType = CountType.TAP
    )

    enum class CountType { TAP, SCROLL }

    private val sessionCounts = mutableMapOf<String, Int>()
    private val dailyTotals = mutableMapOf<String, Int>()
    private var currentPackage: String? = null

    /** Called when the foreground app changes. Resets the session count for the new app. */
    fun onAppChanged(packageName: String) {
        if (packageName != currentPackage) {
            sessionCounts[packageName] = 0
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
        if (currentPackage == packageName) {
            currentPackage = null
        }
    }

    /** Clear daily totals -- called at midnight or service start. */
    fun resetDaily() {
        dailyTotals.clear()
    }
}
