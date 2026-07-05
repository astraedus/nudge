package com.astraedus.nudge.domain.emergency

/**
 * Pure logic for the "1-minute daily pass" emergency escape hatch — a per-app, once-per-rolling-24h
 * free window to open a blocked app. No Android dependencies, so this is fully JVM-unit-testable.
 *
 * The "usage ledger" maps a package name to the epoch-millis of its LAST pass use. Lockout is a
 * rolling window: an app can be passed again once [LOCKOUT_MS] has elapsed since its last use.
 *
 * Serialized form is `pkg1=epochMillis;pkg2=epochMillis`. Package names never contain `=` or `;`,
 * so this stays unambiguous. [parse] is deliberately lenient — any malformed entry is skipped and a
 * blank/garbage input yields an empty map, never an exception (persisted prefs must never crash the
 * overlay hot path).
 */
object EmergencyPass {

    /** Free window granted per use. */
    const val PASS_DURATION_MS: Long = 60_000L

    /** Rolling lockout after a use before the same app can be passed again (24h). */
    const val LOCKOUT_MS: Long = 86_400_000L

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

    /** True once [cooldownMs] has elapsed since this app's last pass (or it was never used). */
    fun canUse(usage: Map<String, Long>, pkg: String, now: Long, cooldownMs: Long): Boolean =
        now - (usage[pkg] ?: 0L) >= cooldownMs

    /** Remaining lockout in ms (0 if available now). For "Next free pass in Xh" UI. */
    fun nextAvailableMs(usage: Map<String, Long>, pkg: String, now: Long, cooldownMs: Long): Long {
        val last = usage[pkg] ?: return 0L
        return (last + cooldownMs - now).coerceAtLeast(0L)
    }

    /** Return a new ledger with [pkg]'s last-use timestamp set to [now]. */
    fun record(usage: Map<String, Long>, pkg: String, now: Long): Map<String, Long> =
        usage + (pkg to now)
}
