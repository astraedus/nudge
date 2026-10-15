package com.astraedus.nudge.domain.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContentFilterAllowlistWeakening]: every allowlist entry is an exception, so the list weakens by
 * growing. Strict Mode gates additions and leaves removals free, the same asymmetry
 * [SettingsWeakeningTest] pins for the escape-hatch toggle.
 */
class ContentFilterAllowlistWeakeningTest {

    @Test
    fun `adding an entry under strict mode requires the unlock`() {
        assertTrue(
            ContentFilterAllowlistWeakening.requiresUnlock(
                current = setOf("cnc"), next = setOf("cnc", "bbc news"), strictModeEnabled = true
            )
        )
    }

    @Test
    fun `removing an entry under strict mode is free`() {
        assertFalse(
            ContentFilterAllowlistWeakening.requiresUnlock(
                current = setOf("cnc", "bbc news"), next = setOf("cnc"), strictModeEnabled = true
            )
        )
        assertFalse(
            ContentFilterAllowlistWeakening.requiresUnlock(
                current = setOf("cnc"), next = emptySet(), strictModeEnabled = true
            )
        )
    }

    @Test
    fun `nothing is gated with strict mode off`() {
        assertFalse(
            ContentFilterAllowlistWeakening.requiresUnlock(
                current = emptySet(), next = setOf("cnc"), strictModeEnabled = false
            )
        )
    }

    @Test
    fun `swapping one entry for another is an addition`() {
        assertTrue(ContentFilterAllowlistWeakening.isWeakening(setOf("cnc"), setOf("bbc")))
    }

    @Test
    fun `entries are compared in canonical form`() {
        assertFalse(ContentFilterAllowlistWeakening.isWeakening(setOf("bbc news"), setOf(" BBC  News")))
        assertFalse(
            ContentFilterAllowlistWeakening.isWeakening(setOf("cnc-shop.com"), setOf("https://www.cnc-shop.com/"))
        )
    }

    @Test
    fun `an invalid entry is never counted as an addition`() {
        assertFalse(ContentFilterAllowlistWeakening.isWeakening(emptySet(), setOf("   ", "!!!")))
    }
}
