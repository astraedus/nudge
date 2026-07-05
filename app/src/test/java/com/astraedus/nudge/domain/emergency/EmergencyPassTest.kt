package com.astraedus.nudge.domain.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure emergency-pass ledger logic. This gates a protection-WEAKENING escape hatch,
 * so both the boundary of "can I use it" (exact-cooldown, never-used) and robustness of the
 * persisted-string parser (malformed input must never throw) are load-bearing.
 */
class EmergencyPassTest {

    private val cooldown = EmergencyPass.LOCKOUT_MS
    private val t0 = 1_000_000_000_000L // arbitrary fixed "now" base

    // ── parse / serialize round-trip ──

    @Test
    fun `empty string parses to empty map`() {
        assertEquals(emptyMap<String, Long>(), EmergencyPass.parse(""))
        assertEquals(emptyMap<String, Long>(), EmergencyPass.parse("   "))
    }

    @Test
    fun `single entry round-trips`() {
        val usage = mapOf("com.foo.bar" to 123456789L)
        assertEquals(usage, EmergencyPass.parse(EmergencyPass.serialize(usage)))
    }

    @Test
    fun `multiple entries round-trip`() {
        val usage = linkedMapOf(
            "com.instagram.android" to 1_700_000_000_000L,
            "com.zhiliaoapp.musically" to 1_700_000_050_000L,
            "com.google.android.youtube" to 1_700_000_099_999L
        )
        assertEquals(usage, EmergencyPass.parse(EmergencyPass.serialize(usage)))
    }

    @Test
    fun `serialize of empty map is empty string`() {
        assertEquals("", EmergencyPass.serialize(emptyMap()))
    }

    @Test
    fun `malformed entries are ignored and never throw`() {
        // Missing value, missing key, non-numeric value, negative value, stray separators, no '='.
        val raw = "com.a=100;=200;com.b=;com.c=abc;;com.d=-5;garbage;com.e=300"
        val parsed = EmergencyPass.parse(raw)
        assertEquals(mapOf("com.a" to 100L, "com.e" to 300L), parsed)
    }

    @Test
    fun `entirely garbage input yields empty map`() {
        assertEquals(emptyMap<String, Long>(), EmergencyPass.parse(";;;===;no-equals;"))
    }

    // ── canUse ──

    @Test
    fun `canUse true when never used before`() {
        assertTrue(EmergencyPass.canUse(emptyMap(), "com.foo", t0, cooldown))
    }

    @Test
    fun `canUse false immediately after use`() {
        val usage = mapOf("com.foo" to t0)
        assertFalse(EmergencyPass.canUse(usage, "com.foo", t0, cooldown))
    }

    @Test
    fun `canUse false just before cooldown elapses`() {
        val usage = mapOf("com.foo" to t0)
        assertFalse(EmergencyPass.canUse(usage, "com.foo", t0 + cooldown - 1, cooldown))
    }

    @Test
    fun `canUse true exactly at cooldown boundary`() {
        val usage = mapOf("com.foo" to t0)
        assertTrue(EmergencyPass.canUse(usage, "com.foo", t0 + cooldown, cooldown))
    }

    @Test
    fun `canUse is per-app`() {
        val usage = mapOf("com.foo" to t0)
        // Different package has never been used → available even though com.foo is locked out.
        assertFalse(EmergencyPass.canUse(usage, "com.foo", t0 + 1, cooldown))
        assertTrue(EmergencyPass.canUse(usage, "com.bar", t0 + 1, cooldown))
    }

    // ── nextAvailableMs ──

    @Test
    fun `nextAvailableMs is zero when never used`() {
        assertEquals(0L, EmergencyPass.nextAvailableMs(emptyMap(), "com.foo", t0, cooldown))
    }

    @Test
    fun `nextAvailableMs is full cooldown immediately after use`() {
        val usage = mapOf("com.foo" to t0)
        assertEquals(cooldown, EmergencyPass.nextAvailableMs(usage, "com.foo", t0, cooldown))
    }

    @Test
    fun `nextAvailableMs decreases as time passes`() {
        val usage = mapOf("com.foo" to t0)
        val elapsed = 3_600_000L // 1h
        assertEquals(cooldown - elapsed, EmergencyPass.nextAvailableMs(usage, "com.foo", t0 + elapsed, cooldown))
    }

    @Test
    fun `nextAvailableMs is zero once cooldown has passed`() {
        val usage = mapOf("com.foo" to t0)
        assertEquals(0L, EmergencyPass.nextAvailableMs(usage, "com.foo", t0 + cooldown, cooldown))
        assertEquals(0L, EmergencyPass.nextAvailableMs(usage, "com.foo", t0 + cooldown + 5_000, cooldown))
    }

    // ── record ──

    @Test
    fun `record adds a new entry`() {
        val result = EmergencyPass.record(emptyMap(), "com.foo", t0)
        assertEquals(mapOf("com.foo" to t0), result)
    }

    @Test
    fun `record updates an existing entry and preserves others`() {
        val usage = mapOf("com.foo" to t0, "com.bar" to t0)
        val result = EmergencyPass.record(usage, "com.foo", t0 + 10_000)
        assertEquals(mapOf("com.foo" to t0 + 10_000, "com.bar" to t0), result)
    }

    @Test
    fun `record does not mutate the input map`() {
        val usage = mapOf("com.foo" to t0)
        EmergencyPass.record(usage, "com.bar", t0)
        assertEquals(mapOf("com.foo" to t0), usage)
    }

    @Test
    fun `record output round-trips through serialize and canUse`() {
        val recorded = EmergencyPass.record(emptyMap(), "com.foo", t0)
        val reparsed = EmergencyPass.parse(EmergencyPass.serialize(recorded))
        assertFalse(EmergencyPass.canUse(reparsed, "com.foo", t0, cooldown))
        assertTrue(EmergencyPass.canUse(reparsed, "com.foo", t0 + cooldown, cooldown))
    }

    // ── constants sanity ──

    @Test
    fun `constants match the spec`() {
        assertEquals(60_000L, EmergencyPass.PASS_DURATION_MS)
        assertEquals(86_400_000L, EmergencyPass.LOCKOUT_MS)
    }
}
