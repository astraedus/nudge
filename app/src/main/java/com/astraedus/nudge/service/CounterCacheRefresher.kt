package com.astraedus.nudge.service

internal data class CounterCacheEntry(
    val autoKickAfter: Int? = null
)

internal class CounterCacheRefresher(
    private val refreshIntervalMs: Long = 10_000L
) {
    private val enabledPackages = mutableMapOf<String, CounterCacheEntry>()
    private var lastRefreshTime: Long = 0L

    fun isEnabled(packageName: String): Boolean = packageName in enabledPackages

    fun getAutoKickAfter(packageName: String): Int? = enabledPackages[packageName]?.autoKickAfter

    fun snapshot(): Set<String> = enabledPackages.keys.toSet()

    suspend fun refreshIfNeeded(
        now: Long,
        loadEnabledPackages: suspend () -> Map<String, CounterCacheEntry>
    ): Boolean {
        if ((now - lastRefreshTime) < refreshIntervalMs) return false
        lastRefreshTime = now

        enabledPackages.clear()
        enabledPackages.putAll(loadEnabledPackages())
        return true
    }
}
