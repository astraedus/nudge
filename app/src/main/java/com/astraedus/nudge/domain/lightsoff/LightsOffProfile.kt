package com.astraedus.nudge.domain.lightsoff

/**
 * One "Lights Off" lockdown profile: a schedule window plus the allow-list of app packages that
 * stay reachable while the window is active.
 *
 * Lights Off INVERTS Nudge's normal allow-by-default model for the duration of a window: every app
 * is hard-blocked except (a) the packages in [whitelist] and (b) the system-critical safety floor
 * (dialer / launcher / settings / emergency / keyboard / Nudge itself), which is enforced in the
 * accessibility service BEFORE the engine and is deliberately NOT derived from this list — see
 * `service/SystemCriticalPackageResolver`. A user can therefore never lock themselves out of their
 * phone by mis-editing a whitelist.
 *
 * v1 edits and uses `profiles[0]` only, but the stored shape is a LIST from day one so multiple
 * named profiles (weeknight lockdown + study block) is a clean later addition rather than a
 * migration.
 *
 * Pure Kotlin — no Android imports, fully unit-testable on the JVM.
 *
 * @param name user-facing profile name. v1 never shows it; it exists so the list shape is complete.
 * @param scheduleEnabled when false the schedule never activates and only a manual
 *   "start now" window can turn the lights off.
 * @param days ISO day-of-week numbers (1 = Monday .. 7 = Sunday). EMPTY means "every day",
 *   matching [com.astraedus.nudge.domain.engine.ScheduleEvaluator]'s treatment of an unset day list.
 * @param startMinute window start as minutes from midnight (0..1439).
 * @param endMinute window end as minutes from midnight (0..1440), exclusive. An end BEFORE the
 *   start means an overnight window (22:00 → 07:00), evaluated on the selected day the same way
 *   scheduled block rules are.
 * @param whitelist app package names that stay reachable during the window.
 * @param mode block mode applied to non-whitelisted apps. Stored for forward compatibility; v1
 *   always writes and enforces `HARD_BLOCK` (a softer Lights Off mode would let an app that a
 *   per-app rule already HARD_BLOCKs through, i.e. weaken protection — see `BlockEngine` step 0).
 */
data class LightsOffProfile(
    val name: String = DEFAULT_NAME,
    val scheduleEnabled: Boolean = true,
    val days: List<Int> = emptyList(),
    val startMinute: Int = DEFAULT_START_MINUTE,
    val endMinute: Int = DEFAULT_END_MINUTE,
    val whitelist: List<String> = emptyList(),
    val mode: String = DEFAULT_MODE
) {

    /** True if [packageName] is on this profile's user-managed allow-list. */
    fun allows(packageName: String): Boolean = packageName in whitelist

    companion object {
        const val DEFAULT_NAME = "Lights Off"
        const val DEFAULT_MODE = "HARD_BLOCK"

        /** 22:00 — the bedtime/wind-down case the feature is designed around. */
        const val DEFAULT_START_MINUTE = 22 * 60

        /** 07:00. */
        const val DEFAULT_END_MINUTE = 7 * 60

        const val MINUTES_PER_DAY = 24 * 60

        /** Field separator inside one serialized profile record. */
        private const val FIELD_SEP = '|'

        /** Record separator between profiles. */
        private const val RECORD_SEP = '\n'

        /** Separator inside the day list and the whitelist (CSV, like `BlockRule.webDomains`). */
        private const val LIST_SEP = ','

        private const val FIELD_COUNT = 7

        /**
         * Serialize [profiles] to the flat DataStore string form:
         * `name|scheduleEnabled|days|startMinute|endMinute|whitelist|mode`, one record per line.
         *
         * Separator characters are stripped from free-text fields on write so a round-trip can
         * never be corrupted by a profile name or package containing `|`, `,` or a newline.
         */
        fun serialize(profiles: List<LightsOffProfile>): String =
            profiles.joinToString(RECORD_SEP.toString()) { profile ->
                listOf(
                    sanitize(profile.name),
                    profile.scheduleEnabled.toString(),
                    profile.days.filter { it in 1..7 }.joinToString(LIST_SEP.toString()),
                    profile.startMinute.toString(),
                    profile.endMinute.toString(),
                    profile.whitelist.mapNotNull { sanitize(it).ifBlank { null } }
                        .joinToString(LIST_SEP.toString()),
                    sanitize(profile.mode)
                ).joinToString(FIELD_SEP.toString())
            }

        /**
         * Parse the stored form back into profiles. FAILS SOFT: a malformed record is skipped and
         * unparsable numbers fall back to their defaults, so corrupt preferences can never throw on
         * the evaluation path. Note the failure direction is deliberately "fewer/less-configured
         * profiles" — i.e. toward NOT locking the user down — because an accidental global lockdown
         * is the more harmful failure.
         */
        fun parse(raw: String): List<LightsOffProfile> {
            if (raw.isBlank()) return emptyList()
            return raw.split(RECORD_SEP).mapNotNull { line -> parseRecord(line) }
        }

        private fun parseRecord(line: String): LightsOffProfile? {
            if (line.isBlank()) return null
            val fields = line.split(FIELD_SEP)
            if (fields.size < FIELD_COUNT) return null
            val start = fields[3].trim().toIntOrNull()?.takeIf { it in 0..MINUTES_PER_DAY }
                ?: DEFAULT_START_MINUTE
            val end = fields[4].trim().toIntOrNull()?.takeIf { it in 0..MINUTES_PER_DAY }
                ?: DEFAULT_END_MINUTE
            return LightsOffProfile(
                name = fields[0].trim().ifBlank { DEFAULT_NAME },
                scheduleEnabled = fields[1].trim().toBooleanStrictOrNull() ?: true,
                days = parseDays(fields[2]),
                startMinute = start,
                endMinute = end,
                whitelist = parseCsv(fields[5]),
                mode = fields[6].trim().ifBlank { DEFAULT_MODE }
            )
        }

        private fun parseDays(raw: String): List<Int> = raw
            .split(LIST_SEP)
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .distinct()
            .sorted()

        private fun parseCsv(raw: String): List<String> = raw
            .split(LIST_SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        private fun sanitize(value: String): String =
            value.filterNot { it == FIELD_SEP || it == RECORD_SEP || it == LIST_SEP || it == '\r' }
                .trim()
    }
}
