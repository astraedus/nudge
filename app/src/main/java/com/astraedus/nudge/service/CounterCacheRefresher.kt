package com.astraedus.nudge.service

internal data class CounterCacheEntry(
    val autoKickAfter: Int? = null,
    val showTimeRemaining: Boolean = false,
    val dailyLimitMinutes: Int? = null,
    val autoKickCooldownSeconds: Int = 60
)

internal class CounterCacheRefresher(
    private val refreshIntervalMs: Long = 10_000L
) {
    private val enabledPackages = mutableMapOf<String, CounterCacheEntry>()
    private var lastRefreshTime: Long = 0L

    fun isEnabled(packageName: String): Boolean = packageName in enabledPackages

    fun getAutoKickAfter(packageName: String): Int? = enabledPackages[packageName]?.autoKickAfter

    fun getEntry(packageName: String): CounterCacheEntry? = enabledPackages[packageName]

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

    companion object {
        fun mergeEntries(
            entries: Iterable<Pair<String, CounterCacheEntry>>
        ): Map<String, CounterCacheEntry> {
            return entries
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, packageEntries) ->
                    CounterCacheEntry(
                        autoKickAfter = packageEntries
                            .mapNotNull { it.autoKickAfter }
                            .minOrNull(),
                        showTimeRemaining = packageEntries.any { it.showTimeRemaining },
                        dailyLimitMinutes = packageEntries
                            .mapNotNull { it.dailyLimitMinutes }
                            .minOrNull(),
                        autoKickCooldownSeconds = packageEntries
                            .maxOf { it.autoKickCooldownSeconds }
                    )
                }
        }
    }
}
