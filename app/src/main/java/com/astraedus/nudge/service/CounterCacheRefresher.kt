package com.astraedus.nudge.service

import com.astraedus.nudge.domain.web.WebSessionKey

/**
 * Per-package snapshot of everything the accessibility hot path needs to know about a rule WITHOUT
 * touching the database. One entry exists for every package that needs *any* foreground awareness:
 * the interaction counter, the time-remaining overlay, a time-based auto-kick, or a daily time
 * limit (which needs the clock so the budget is enforced mid-session, not only on re-entry).
 *
 * [showCounter] is what decides whether the floating interaction counter is drawn — it is NOT the
 * same question as "does this package have an entry". A rule can want a time-based auto-kick (or a
 * time-remaining overlay) with the counter switched off, and must not get a counter overlay it
 * never asked for.
 */
data class CounterCacheEntry(
    val showCounter: Boolean = false,
    val autoKickAfter: Int? = null,
    val showTimeRemaining: Boolean = false,
    val dailyLimitMinutes: Int? = null,
    val autoKickCooldownSeconds: Int = 60,
    /** Time-based auto-kick threshold in minutes of session foreground time. Null = disabled. */
    val autoKickAfterMinutes: Int? = null
) {
    /**
     * True when this package needs the periodic foreground-time tick — i.e. something here is driven
     * by a clock rather than by accessibility events. Without a tick, a passively-watched app
     * (zero taps, zero scrolls) produces no events and neither the time-remaining overlay, nor the
     * time-based auto-kick, nor a daily budget running out would ever be noticed.
     *
     * **A daily limit is the third arm, and it was missing until v1.18.4.** The first two arms are
     * the features that DISPLAY a running number, so the predicate read as "who needs the number
     * refreshed" — and a plain daily limit (no time-remaining overlay, no time kick) satisfied
     * neither. Measured on the bench: 150 seconds of continuous foreground time on a 1-minute
     * budget produced exactly ONE evaluation, at t=0, so the limit was only enforced the next time
     * the user re-opened the app. "Daily limits should definitely be enforced even mid session"
     * (product call, 2026-09-29), so a budget is now a clock consumer in its own right; the
     * enforcement itself already lived on the tick path in [TimeRemainingHandler].
     *
     * A package with neither still spins no timer — that is what keeps the clock off the battery
     * for rules that need nothing from it ([#63](https://github.com/astraedus/nudge/issues/63)).
     * Note the daily-limit arm SUBSUMES the old time-remaining arm rather than sitting beside it:
     * the readout is only ever drawn for a rule that has a limit ([TimeRemainingHandler]), so
     * `showTimeRemaining && dailyLimitMinutes != null` cannot be true without the second arm
     * already being true, and spelling it out again would be a condition that can never decide
     * anything.
     */
    val needsForegroundTimeTick: Boolean
        get() = autoKickAfterMinutes != null || dailyLimitMinutes != null

    /**
     * True when some rule actually configures an auto-kick for this package — the only thing that
     * can justify an armed auto-kick cooldown.
     *
     * **Not the same question as cache membership**, and conflating them is a bug the way
     * `hasEntry`/[showCounter] was. Since a daily limit alone puts a package in the cache (see
     * [needsForegroundTimeTick]), membership no longer implies that anything here can kick — so a
     * user who turns auto-kick OFF while keeping a daily limit would have kept an armed cooldown
     * enforcing against a rule that no longer asks for it, which is precisely what
     * [com.astraedus.nudge.domain.block.CooldownGate] exists to prevent.
     */
    val configuresAutoKick: Boolean
        get() = autoKickAfter != null || autoKickAfterMinutes != null
}

class CounterCacheRefresher(
    private val refreshIntervalMs: Long = 10_000L
) {
    // Atomic reference swap -- readers never see a half-populated map
    @Volatile
    private var enabledPackages: Map<String, CounterCacheEntry> = emptyMap()
    @Volatile
    private var lastRefreshTime: Long = 0L

    /**
     * True when this package is TRACKED at all (counter, time-remaining overlay, time-kick, or a
     * daily limit). Use this for "should I keep foreground state for this app"; use
     * [isCounterEnabled] for "should I draw / feed the interaction counter", and
     * [CounterCacheEntry.configuresAutoKick] for "may an armed cooldown still enforce".
     *
     * Membership answers less than it used to, and deliberately: since a daily limit alone puts a
     * package here, "tracked" now means only "something needs the foreground clock". Every other
     * question has its own predicate above, because this one standing in for them is how a
     * time-kick-only rule nearly grew a counter (v1.10.0) and how a limit-only rule would have kept
     * a dead auto-kick cooldown alive (v1.18.4).
     */
    fun hasEntry(packageName: String): Boolean = packageName in enabledPackages

    /** True only when the user actually asked for the floating interaction counter. */
    fun isCounterEnabled(packageName: String): Boolean =
        enabledPackages[packageName]?.showCounter == true

    fun getAutoKickAfter(packageName: String): Int? = enabledPackages[packageName]?.autoKickAfter

    fun getEntry(packageName: String): CounterCacheEntry? = enabledPackages[packageName]

    fun snapshot(): Set<String> = enabledPackages.keys

    suspend fun refreshIfNeeded(
        now: Long,
        loadEnabledPackages: suspend () -> Map<String, CounterCacheEntry>
    ): Boolean {
        if ((now - lastRefreshTime) < refreshIntervalMs) return false
        lastRefreshTime = now
        enabledPackages = loadEnabledPackages()
        return true
    }

    suspend fun forceRefresh(
        loadEnabledPackages: suspend () -> Map<String, CounterCacheEntry>
    ) {
        lastRefreshTime = System.currentTimeMillis()
        enabledPackages = loadEnabledPackages()
    }

    companion object {
        /**
         * The cache entries a rule's WEBSITES contribute, one per configured domain, keyed by
         * [WebSessionKey]. Empty unless the rule actually enforces on the web AND wants something
         * clock-driven there.
         *
         * Why per domain and not per browser package: a cooldown or a kick armed on
         * `com.android.chrome` would lock the entire browser. Why not per app package: time on
         * instagram.com would then spend the Instagram app's session.
         *
         * The counter and the time-remaining overlay are deliberately NOT carried across. The
         * counter is fed by tap/scroll events, which arrive carrying the browser's package rather
         * than a domain; the time-remaining overlay needs a DAILY web total, which needs persisted
         * per-domain records (`docs/BACKLOG.md`). Promising either here would put a control in the
         * UI that silently does nothing, which is the defect class this whole area exists to fix.
         *
         * @param webEnforces whether the rule's resolved web mode actually blocks
         *   ([com.astraedus.nudge.domain.model.WebBlockMode.resolve] != NONE). A rule that blocks
         *   nothing on the web must not eject the user from a site it is not blocking.
         */
        fun webEntriesFor(
            webDomains: String?,
            webEnforces: Boolean,
            autoKickAfterMinutes: Int?,
            autoKickCooldownSeconds: Int
        ): List<Pair<String, CounterCacheEntry>> {
            if (!webEnforces || autoKickAfterMinutes == null || webDomains.isNullOrBlank()) {
                return emptyList()
            }
            return webDomains.split(',')
                .mapNotNull { WebSessionKey.forDomain(it) }
                .distinct()
                .map { key ->
                    key to CounterCacheEntry(
                        showCounter = false,
                        autoKickAfter = null,
                        showTimeRemaining = false,
                        dailyLimitMinutes = null,
                        autoKickCooldownSeconds = autoKickCooldownSeconds,
                        autoKickAfterMinutes = autoKickAfterMinutes
                    )
                }
        }

        /**
         * Collapses every rule targeting the same package into one entry. Where rules disagree the
         * merge always takes the STRICTEST interpretation: the lowest kick threshold, the lowest
         * daily limit, the longest cooldown, and any rule asking for an overlay wins.
         */
        fun mergeEntries(
            entries: Iterable<Pair<String, CounterCacheEntry>>
        ): Map<String, CounterCacheEntry> {
            return entries
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, packageEntries) ->
                    CounterCacheEntry(
                        showCounter = packageEntries.any { it.showCounter },
                        autoKickAfter = packageEntries
                            .mapNotNull { it.autoKickAfter }
                            .minOrNull(),
                        showTimeRemaining = packageEntries.any { it.showTimeRemaining },
                        dailyLimitMinutes = packageEntries
                            .mapNotNull { it.dailyLimitMinutes }
                            .minOrNull(),
                        autoKickCooldownSeconds = packageEntries
                            .maxOf { it.autoKickCooldownSeconds },
                        autoKickAfterMinutes = packageEntries
                            .mapNotNull { it.autoKickAfterMinutes }
                            .minOrNull()
                    )
                }
        }
    }
}
