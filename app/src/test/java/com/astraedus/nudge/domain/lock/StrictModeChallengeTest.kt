package com.astraedus.nudge.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure Strict Mode challenge logic. This is a commitment device — correctness of
 * generation (right length, no confusable chars) and verification (case-sensitive, dash-tolerant)
 * is load-bearing, so it gets thorough coverage.
 */
class StrictModeChallengeTest {

    // ── generate() ──

    @Test
    fun `generate returns requested raw length`() {
        assertEquals(12, StrictModeChallenge.generate(12).length)
        assertEquals(24, StrictModeChallenge.generate(24).length)
        assertEquals(48, StrictModeChallenge.generate(48).length)
    }

    @Test
    fun `generate uses only the unambiguous charset`() {
        // Generate a large sample so any forbidden char would almost certainly appear if allowed.
        val sample = buildString {
            repeat(50) { append(StrictModeChallenge.generate(48)) }
        }
        // Confusable glyphs must NEVER appear.
        for (forbidden in listOf('0', 'O', '1', 'l', 'I')) {
            assertFalse(
                "Challenge must not contain confusable char '$forbidden'",
                sample.contains(forbidden)
            )
        }
        // Every produced char must be in the declared charset.
        assertTrue(sample.all { it in StrictModeChallenge.CHARSET })
    }

    @Test
    fun `charset itself excludes confusable characters`() {
        // Test the CLASS: the charset constant must never include any confusable glyph.
        for (forbidden in listOf('0', 'O', '1', 'l', 'I')) {
            assertFalse(StrictModeChallenge.CHARSET.contains(forbidden))
        }
    }

    @Test
    fun `generate produces different strings across calls`() {
        val a = StrictModeChallenge.generate(24)
        val b = StrictModeChallenge.generate(24)
        val c = StrictModeChallenge.generate(24)
        // Collision of two 24-char strings from a 55-char alphabet is astronomically unlikely.
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
    }

    @Test
    fun `generate coerces non-positive length to at least one char`() {
        assertEquals(1, StrictModeChallenge.generate(0).length)
        assertEquals(1, StrictModeChallenge.generate(-5).length)
    }

    // ── forDisplay() ──

    @Test
    fun `forDisplay groups into dash-separated chunks of five`() {
        assertEquals("abcde-fghij-klmno", StrictModeChallenge.forDisplay("abcdefghijklmno"))
    }

    @Test
    fun `forDisplay handles a partial final group`() {
        assertEquals("abcde-fg", StrictModeChallenge.forDisplay("abcdefg"))
    }

    // ── verify() ──

    @Test
    fun `verify true on exact match`() {
        assertTrue(StrictModeChallenge.verify("aB3kP", "aB3kP"))
    }

    @Test
    fun `verify false on wrong character`() {
        assertFalse(StrictModeChallenge.verify("aB3kQ", "aB3kP"))
    }

    @Test
    fun `verify is case sensitive`() {
        assertFalse(StrictModeChallenge.verify("ab3kp", "aB3kP"))
        assertFalse(StrictModeChallenge.verify("AB3KP", "aB3kP"))
    }

    @Test
    fun `verify false on truncated input`() {
        assertFalse(StrictModeChallenge.verify("aB3k", "aB3kP"))
    }

    @Test
    fun `verify ignores display dashes on either side`() {
        // Target shown grouped; user types raw.
        assertTrue(StrictModeChallenge.verify("abcdefghij", "abcde-fghij"))
        // User types with the dashes too.
        assertTrue(StrictModeChallenge.verify("abcde-fghij", "abcde-fghij"))
        // Both raw.
        assertTrue(StrictModeChallenge.verify("abcdefghij", "abcdefghij"))
    }

    @Test
    fun `verify is dash-insensitive in BOTH directions against a grouped target`() {
        // The bug this guards: the target is displayed dash-grouped and the copy no longer
        // promises "exactly", so typing WITH or WITHOUT dashes must both pass.
        val raw = "k7Qm2vX9pLtR"
        val grouped = StrictModeChallenge.forDisplay(raw) // "k7Qm2-vX9pL-tR"
        // Typed without dashes.
        assertTrue(StrictModeChallenge.verify(raw, grouped))
        // Typed with the same dashes shown.
        assertTrue(StrictModeChallenge.verify(grouped, grouped))
        // Typed with dashes in different (user-chosen) positions — still passes.
        assertTrue(StrictModeChallenge.verify("k7-Qm2vX9-pLtR", grouped))
        // Wrong content with the right dashes must still fail (dashes don't mask a typo).
        assertFalse(StrictModeChallenge.verify("k7Qm2-vX9pL-tX", grouped))
    }

    // ── normalize() / rawLength() — the unit the live counter and verify share ──

    @Test
    fun `normalize strips dashes and surrounding whitespace but keeps case and inner content`() {
        assertEquals("k7Qm2vX9pL", StrictModeChallenge.normalize("  k7Qm2-vX9pL  "))
        assertEquals("k7Qm2vX9pL", StrictModeChallenge.normalize("k7Qm2vX9pL"))
    }

    @Test
    fun `rawLength counts the same characters with or without dashes`() {
        val raw = "k7Qm2vX9pLtR" // 12 raw chars
        assertEquals(12, StrictModeChallenge.rawLength(raw))
        assertEquals(12, StrictModeChallenge.rawLength(StrictModeChallenge.forDisplay(raw)))
        assertEquals(12, StrictModeChallenge.rawLength("  $raw  "))
        // Partial input progresses in raw units regardless of dashes the user typed.
        assertEquals(7, StrictModeChallenge.rawLength("k7Qm2-vX"))
    }

    @Test
    fun `verify trims surrounding whitespace`() {
        assertTrue(StrictModeChallenge.verify("  aB3kP  ", "aB3kP"))
        assertTrue(StrictModeChallenge.verify("\taB3kP\n", "aB3kP"))
    }

    @Test
    fun `verify false when internal whitespace breaks the match`() {
        // Internal space is NOT stripped — an accidental mid-string space must fail.
        assertFalse(StrictModeChallenge.verify("aB 3kP", "aB3kP"))
    }

    @Test
    fun `verify false on empty target`() {
        assertFalse(StrictModeChallenge.verify("", ""))
        assertFalse(StrictModeChallenge.verify("anything", ""))
    }
}
