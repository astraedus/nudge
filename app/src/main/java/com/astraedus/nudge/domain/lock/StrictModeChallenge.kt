package com.astraedus.nudge.domain.lock

import kotlin.random.Random

/**
 * Pure-Kotlin logic for the Strict Mode "commitment lock" challenge.
 *
 * Strict Mode makes weakening protection (turning off the global toggle, disabling/deleting
 * a rule, softening a config, or turning Strict Mode itself off) require typing a random
 * unlock string by hand. The friction is deliberate: it gives the conscious self a moment to
 * reconsider before undoing a block. Strengthening protection is never gated.
 *
 * No Android imports — fully unit-testable on the JVM.
 */
object StrictModeChallenge {

    /**
     * Unambiguous charset: excludes visually-confusable glyphs (0/O, 1/l/I) so a user copying
     * the string by eye never mis-reads it. Both letter cases are included so the challenge is
     * genuinely case-sensitive.
     */
    const val CHARSET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Characters per display group (e.g. "k7Qm2-vX9pL"). */
    private const val GROUP_SIZE = 5

    /** Default / suggested difficulty presets (number of raw characters to type). */
    const val LENGTH_EASY = 12
    const val LENGTH_MEDIUM = 24
    const val LENGTH_HARD = 48
    const val DEFAULT_LENGTH = LENGTH_MEDIUM

    /**
     * Generates a fresh random challenge string of [length] raw characters drawn from [CHARSET].
     * Returns the RAW string (no dashes); use [forDisplay] to render it grouped.
     *
     * @param random injectable for deterministic tests; defaults to [Random.Default].
     */
    fun generate(length: Int = DEFAULT_LENGTH, random: Random = Random.Default): String {
        val safeLength = length.coerceAtLeast(1)
        return buildString(safeLength) {
            repeat(safeLength) {
                append(CHARSET[random.nextInt(CHARSET.length)])
            }
        }
    }

    /**
     * Renders a raw challenge string grouped into dash-separated chunks for readability,
     * e.g. "k7Qm2vX9pLtR4wZ" -> "k7Qm2-vX9pL-tR4wZ". Display-only; never stored or compared.
     */
    fun forDisplay(raw: String): String =
        raw.chunked(GROUP_SIZE).joinToString("-")

    /**
     * Strips display dashes and surrounding whitespace, returning the raw comparable content.
     * Internal whitespace is NOT stripped — it would make a typo (an accidental space mid-string)
     * silently pass, which weakens the commitment device.
     *
     * Public so the UI can derive the live progress counter from the SAME rule [verify] compares
     * against (single source of truth: the dashes the user may type are ignored identically in the
     * counter and the match).
     */
    fun normalize(value: String): String =
        value.trim().replace("-", "")

    /**
     * Count of raw, dash-stripped characters in [value] — the unit the unlock counter shows and
     * the unit [verify] compares. Typing the code WITH or WITHOUT dashes yields the same count.
     */
    fun rawLength(value: String): Int = normalize(value).length

    /**
     * Case-SENSITIVE exact match of [input] against [target].
     *
     * Both sides are normalized first: surrounding whitespace trimmed and display dashes removed,
     * so the user may type the string with or without the dashes shown on screen. Everything else
     * (case, every character) must match exactly.
     */
    fun verify(input: String, target: String): Boolean {
        val normalizedTarget = normalize(target)
        if (normalizedTarget.isEmpty()) return false
        return normalize(input) == normalizedTarget
    }
}
