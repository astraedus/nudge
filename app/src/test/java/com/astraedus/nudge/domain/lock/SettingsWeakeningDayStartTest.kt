package com.astraedus.nudge.domain.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logical day start as a Strict Mode dimension (spec 5.6). Any change challenges, because the
 * value anchors every first-unlock window, so neither direction is unambiguously stronger. It only
 * bites while this phone has an enabled first-unlock rule for the change to affect, and while
 * Strict Mode is on.
 */
class SettingsWeakeningDayStartTest {

    @Test
    fun `an unchanged day start is free`() {
        assertFalse(
            SettingsWeakening.requiresDayStartUnlock(
                currentMinute = 240,
                newMinute = 240,
                hasEnabledFirstUnlockRule = true,
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `a changed day start with an enabled first-unlock rule under strict mode requires the unlock`() {
        assertTrue(
            SettingsWeakening.requiresDayStartUnlock(
                currentMinute = 240,
                newMinute = 300,
                hasEnabledFirstUnlockRule = true,
                strictModeEnabled = true
            )
        )
        // Earlier is just as much a change as later.
        assertTrue(
            SettingsWeakening.requiresDayStartUnlock(
                currentMinute = 300,
                newMinute = 240,
                hasEnabledFirstUnlockRule = true,
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `a changed day start with no first-unlock rule is free`() {
        assertFalse(
            SettingsWeakening.requiresDayStartUnlock(
                currentMinute = 240,
                newMinute = 300,
                hasEnabledFirstUnlockRule = false,
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `a changed day start with strict mode off is free`() {
        assertFalse(
            SettingsWeakening.requiresDayStartUnlock(
                currentMinute = 240,
                newMinute = 300,
                hasEnabledFirstUnlockRule = true,
                strictModeEnabled = false
            )
        )
    }
}
