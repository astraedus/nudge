package com.astraedus.nudge.domain.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure emergency-pass ledger logic. The pass gates a protection-WEAKENING escape hatch
 * with GLOBAL (one-per-24h-across-all-apps) semantics, so both the eligibility boundary
 * (exact-cooldown, never-used, cross-app lockout) and robustness of the persisted-string parser
 * (malformed input must never throw; a legacy per-app ledger must migrate) are load-bearing.
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
    fun `global record round-trips through serialize`() {
        val recorded = EmergencyPass.recordGlobal(t0)
        assertEquals(mapOf(EmergencyPass.GLOBAL_KEY to t0), recorded)
        assertEquals(recorded, EmergencyPass.parse(EmergencyPass.serialize(recorded)))
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

    // ── globalLastUsed (migration-safe MAX) ──

    @Test
    fun `globalLastUsed is null when never used`() {
        assertNull(EmergencyPass.globalLastUsed(emptyMap()))
    }

    @Test
    fun `globalLastUsed is the single global entry`() {
        assertEquals(t0, EmergencyPass.globalLastUsed(mapOf(EmergencyPass.GLOBAL_KEY to t0)))
    }

    @Test
    fun `globalLastUsed takes the MAX across a legacy per-app ledger (migration)`() {
        // Old per-app ledger: the most recent app's timestamp becomes the global last-used.
        val legacy = mapOf(
            "com.instagram.android" to t0,
            "com.zhiliaoapp.musically" to t0 + 5_000,
            "com.google.android.youtube" to t0 - 10_000
        )
        assertEquals(t0 + 5_000, EmergencyPass.globalLastUsed(legacy))
    }

    // ── canUseGlobal ──

    @Test
    fun `canUseGlobal true when never used before`() {
        assertTrue(EmergencyPass.canUseGlobal(emptyMap(), t0, cooldown))
    }

    @Test
    fun `canUseGlobal false immediately after use`() {
        val usage = EmergencyPass.recordGlobal(t0)
        assertFalse(EmergencyPass.canUseGlobal(usage, t0, cooldown))
    }

    @Test
    fun `canUseGlobal false just before cooldown elapses`() {
        val usage = EmergencyPass.recordGlobal(t0)
        assertFalse(EmergencyPass.canUseGlobal(usage, t0 + cooldown - 1, cooldown))
    }

    @Test
    fun `canUseGlobal true exactly at cooldown boundary`() {
        val usage = EmergencyPass.recordGlobal(t0)
        assertTrue(EmergencyPass.canUseGlobal(usage, t0 + cooldown, cooldown))
    }

    @Test
    fun `canUseGlobal is GLOBAL - using on any app locks out all apps`() {
        // A per-app ledger with a single recent entry (e.g. pass used on Instagram) still blocks the
        // pass for every other app because the lockout is global, not per-package.
        val usedOnInstagram = mapOf("com.instagram.android" to t0)
        // No matter which app's block screen we are on, the pass is locked until cooldown elapses.
        assertFalse(EmergencyPass.canUseGlobal(usedOnInstagram, t0 + 1, cooldown))
        assertTrue(EmergencyPass.canUseGlobal(usedOnInstagram, t0 + cooldown, cooldown))
    }

    // ── nextAvailableGlobalMs ──

    @Test
    fun `nextAvailableGlobalMs is zero when never used`() {
        assertEquals(0L, EmergencyPass.nextAvailableGlobalMs(emptyMap(), t0, cooldown))
    }

    @Test
    fun `nextAvailableGlobalMs is full cooldown immediately after use`() {
        val usage = EmergencyPass.recordGlobal(t0)
        assertEquals(cooldown, EmergencyPass.nextAvailableGlobalMs(usage, t0, cooldown))
    }

    @Test
    fun `nextAvailableGlobalMs decreases as time passes`() {
        val usage = EmergencyPass.recordGlobal(t0)
        val elapsed = 3_600_000L // 1h
        assertEquals(cooldown - elapsed, EmergencyPass.nextAvailableGlobalMs(usage, t0 + elapsed, cooldown))
    }

    @Test
    fun `nextAvailableGlobalMs is zero once cooldown has passed`() {
        val usage = EmergencyPass.recordGlobal(t0)
        assertEquals(0L, EmergencyPass.nextAvailableGlobalMs(usage, t0 + cooldown, cooldown))
        assertEquals(0L, EmergencyPass.nextAvailableGlobalMs(usage, t0 + cooldown + 5_000, cooldown))
    }

    @Test
    fun `nextAvailableGlobalMs uses the MAX timestamp of a legacy ledger`() {
        val legacy = mapOf("com.a" to t0, "com.b" to t0 + 10_000)
        // Locked out relative to the most recent (com.b) entry.
        assertEquals(cooldown - 10_000, EmergencyPass.nextAvailableGlobalMs(legacy, t0 + 20_000, cooldown))
    }

    // ── recordGlobal ──

    @Test
    fun `recordGlobal collapses to a single global entry`() {
        val result = EmergencyPass.recordGlobal(t0)
        assertEquals(mapOf(EmergencyPass.GLOBAL_KEY to t0), result)
    }

    @Test
    fun `recordGlobal output round-trips through serialize and canUseGlobal`() {
        val recorded = EmergencyPass.recordGlobal(t0)
        val reparsed = EmergencyPass.parse(EmergencyPass.serialize(recorded))
        assertFalse(EmergencyPass.canUseGlobal(reparsed, t0, cooldown))
        assertTrue(EmergencyPass.canUseGlobal(reparsed, t0 + cooldown, cooldown))
    }

    // ── constants sanity ──

    @Test
    fun `constants match the spec`() {
        assertEquals(120_000L, EmergencyPass.PASS_DURATION_MS)
        assertEquals(86_400_000L, EmergencyPass.LOCKOUT_MS)
        assertEquals("*", EmergencyPass.GLOBAL_KEY)
    }
}
