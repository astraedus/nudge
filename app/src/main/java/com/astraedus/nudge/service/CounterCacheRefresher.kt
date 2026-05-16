package com.astraedus.nudge.service

internal class CounterCacheRefresher(
    private val refreshIntervalMs: Long = 10_000L
) {
    private val enabledPackages = mutableSetOf<String>()
    private var lastRefreshTime: Long = 0L

    fun isEnabled(packageName: String): Boolean = packageName in enabledPackages

    fun snapshot(): Set<String> = enabledPackages.toSet()

    suspend fun refreshIfNeeded(
        now: Long,
        loadEnabledPackages: suspend () -> Set<String>
    ): Boolean {
        if ((now - lastRefreshTime) < refreshIntervalMs) return false
        lastRefreshTime = now

        enabledPackages.clear()
        enabledPackages.addAll(loadEnabledPackages())
        return true
    }
}
