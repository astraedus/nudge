package com.astraedus.nudge.domain.emergency

/**
 * Pure logic for the "2-minute daily pass" emergency escape hatch — ONE global free window per
 * rolling 24h across the whole device. No Android dependencies, so this is fully JVM-unit-testable.
 *
 * Global (not per-app) semantics: using the pass on ANY blocked app opens a free window for that app
 * AND locks the pass out for every app for [LOCKOUT_MS]. The active free window itself stays scoped
 * to the app it was granted on (owned by `EmergencyPassManager`); this object only decides
 * eligibility (the shared lockout).
 *
 * Persistence: the lockout is stored in the same serialized ledger the per-app design used
 * (`pkg=epochMillis;…`), so old data migrates for free — [globalLastUsed] takes the MAX timestamp
 * across ALL entries, so a pre-existing per-app ledger is interpreted as "last used = the most
 * recent of any app." A fresh global use collapses the ledger to a single [GLOBAL_KEY] entry.
 *
 * [parse] is deliberately lenient — any malformed entry is skipped and blank/garbage input yields an
 * empty map, never an exception (persisted prefs must never crash the overlay hot path).
 */
object EmergencyPass {

    /** Free window granted per use (2 minutes). */
    const val PASS_DURATION_MS: Long = 120_000L

    /** Rolling global lockout after a use before the pass can be used again (24h). */
    const val LOCKOUT_MS: Long = 86_400_000L

    /**
     * Reserved ledger key holding the single global last-used timestamp after a fresh use. Not a
     * real Android package name (packages never contain `*`), so it can never collide with a
     * migrated per-app entry.
     */
    const val GLOBAL_KEY: String = "*"

    private const val ENTRY_SEPARATOR = ';'
    private const val KEY_VALUE_SEPARATOR = '='

    /** Deserialize the ledger. Malformed entries are ignored; blank input → empty map. Never throws. */
    fun parse(raw: String): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, Long>()
        for (entry in raw.split(ENTRY_SEPARATOR)) {
            if (entry.isBlank()) continue
            val idx = entry.indexOf(KEY_VALUE_SEPARATOR)
            // Require a non-empty key before '=' and a value after it.
            if (idx <= 0 || idx == entry.length - 1) continue
            val pkg = entry.substring(0, idx)
            val millis = entry.substring(idx + 1).toLongOrNull() ?: continue
            if (millis < 0) continue
            result[pkg] = millis
        }
        return result
    }

    /** Inverse of [parse]. Round-trips (blank keys are dropped defensively). */
    fun serialize(usage: Map<String, Long>): String =
        usage.entries
            .filter { it.key.isNotBlank() }
            .joinToString(ENTRY_SEPARATOR.toString()) { "${it.key}$KEY_VALUE_SEPARATOR${it.value}" }

    /**
     * The global last-used timestamp: the MAX across ALL ledger entries. This makes the lockout
     * global (any app's use counts) and migrates a legacy per-app ledger transparently. Null if the
     * pass has never been used.
     */
    fun globalLastUsed(usage: Map<String, Long>): Long? = usage.values.maxOrNull()

    /** True once [cooldownMs] has elapsed since the pass was last used on ANY app (or it never was). */
    fun canUseGlobal(usage: Map<String, Long>, now: Long, cooldownMs: Long): Boolean {
        val last = globalLastUsed(usage) ?: return true
        return now - last >= cooldownMs
    }

    /** Remaining global lockout in ms (0 if available now). For the "Next pass in Xh" UI hint. */
    fun nextAvailableGlobalMs(usage: Map<String, Long>, now: Long, cooldownMs: Long): Long {
        val last = globalLastUsed(usage) ?: return 0L
        return (last + cooldownMs - now).coerceAtLeast(0L)
    }

    /**
     * A fresh ledger recording a global use at [now]. Collapses to the single [GLOBAL_KEY] entry —
     * previous per-app entries are dropped because the global timestamp already dominates them.
     */
    fun recordGlobal(now: Long): Map<String, Long> = mapOf(GLOBAL_KEY to now)
}
