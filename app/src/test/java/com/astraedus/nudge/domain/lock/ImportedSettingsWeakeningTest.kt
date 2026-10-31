package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.export.ExportedSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ImportedSettingsWeakening.isWeakening] — the policy that decides whether restoring a
 * backup file's settings over this device's would reduce protection.
 *
 * An export file is plain, hand-editable JSON. Without this policy, a user under Strict Mode could
 * type `"strictModeEnabled": false` into a text editor and import their way straight out of the
 * commitment lock — the exact bypass Strict Mode exists to prevent, on the one path that writes
 * settings without going through the Settings screen. Every axis is checked both directions, exactly
 * as [RuleWeakeningTest] and [SettingsWeakeningTest] do for their own surfaces.
 */
class ImportedSettingsWeakeningTest {

    private fun device(
        contentFilterEnabled: Boolean? = false,
        contentFilterMode: String? = "HARD_BLOCK",
        contentFilterStrictKeywords: Boolean? = false,
        contentFilterAllowlist: List<String>? = emptyList(),
        strictModeEnabled: Boolean? = false,
        strictModeChallengeLength: Int? = 24,
        emergencyPassEnabled: Boolean? = true,
        bounceCheckInEnabled: Boolean? = true,
        customDelayTitles: String? = "",
        customDelaySubtitles: String? = "",
        customHardBlockMessages: String? = ""
    ) = ExportedSettings(
        contentFilterEnabled = contentFilterEnabled,
        contentFilterMode = contentFilterMode,
        contentFilterStrictKeywords = contentFilterStrictKeywords,
        contentFilterAllowlist = contentFilterAllowlist,
        strictModeEnabled = strictModeEnabled,
        strictModeChallengeLength = strictModeChallengeLength,
        emergencyPassEnabled = emergencyPassEnabled,
        bounceCheckInEnabled = bounceCheckInEnabled,
        customDelayTitles = customDelayTitles,
        customDelaySubtitles = customDelaySubtitles,
        customHardBlockMessages = customHardBlockMessages
    )

    // ── strictModeEnabled ──

    @Test
    fun `turning strict mode off weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = true),
                device(strictModeEnabled = false)
            )
        )
    }

    @Test
    fun `turning strict mode on does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = false),
                device(strictModeEnabled = true)
            )
        )
    }

    // ── strictModeChallengeLength ──

    @Test
    fun `lowering the challenge length weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeChallengeLength = 24),
                device(strictModeChallengeLength = 12)
            )
        )
    }

    @Test
    fun `raising the challenge length does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeChallengeLength = 24),
                device(strictModeChallengeLength = 48)
            )
        )
    }

    @Test
    fun `an equal challenge length does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeChallengeLength = 24),
                device(strictModeChallengeLength = 24)
            )
        )
    }

    // ── emergencyPassEnabled ──

    @Test
    fun `enabling the emergency pass weakens protection`() {
        assertTrue(
            "the pass re-opens a one-tap bypass on every block overlay",
            ImportedSettingsWeakening.isWeakening(
                device(emergencyPassEnabled = false),
                device(emergencyPassEnabled = true)
            )
        )
    }

    @Test
    fun `disabling the emergency pass does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(emergencyPassEnabled = true),
                device(emergencyPassEnabled = false)
            )
        )
    }

    // ── contentFilterEnabled ──

    @Test
    fun `turning the content filter off weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterEnabled = true),
                device(contentFilterEnabled = false)
            )
        )
    }

    @Test
    fun `turning the content filter on does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterEnabled = false),
                device(contentFilterEnabled = true)
            )
        )
    }

    // ── contentFilterStrictKeywords ──

    @Test
    fun `turning off strict keyword matching weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterStrictKeywords = true),
                device(contentFilterStrictKeywords = false)
            )
        )
    }

    @Test
    fun `turning on strict keyword matching does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterStrictKeywords = false),
                device(contentFilterStrictKeywords = true)
            )
        )
    }

    // ── contentFilterMode (HARD_BLOCK > DELAY > BREATHING > NONE) ──

    @Test
    fun `softening the content filter mode weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterMode = "HARD_BLOCK"),
                device(contentFilterMode = "DELAY")
            )
        )
    }

    @Test
    fun `hardening the content filter mode does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterMode = "DELAY"),
                device(contentFilterMode = "HARD_BLOCK")
            )
        )
    }

    @Test
    fun `an equal content filter mode does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterMode = "DELAY"),
                device(contentFilterMode = "DELAY")
            )
        )
    }

    @Test
    fun `an unrecognised content filter mode ranks with NONE and softening into it weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterMode = "BREATHING"),
                device(contentFilterMode = "totally-unknown-mode")
            )
        )
    }

    // ── custom message pools: not an axis, even under Strict Mode ──

    @Test
    fun `changing the custom delay titles is never weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = true, customDelayTitles = "Old title"),
                device(strictModeEnabled = true, customDelayTitles = "New title")
            )
        )
    }

    @Test
    fun `changing the custom delay subtitles is never weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = true, customDelaySubtitles = "Old subtitle"),
                device(strictModeEnabled = true, customDelaySubtitles = "New subtitle")
            )
        )
    }

    @Test
    fun `changing the custom hard block messages is never weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = true, customHardBlockMessages = "Old message"),
                device(strictModeEnabled = true, customHardBlockMessages = "New message")
            )
        )
    }

    // ── null handling: backward compatibility ──

    @Test
    fun `a null incoming payload is never weakening`() {
        assertFalse(
            "a backup carrying no settings at all must never be treated as weakening",
            ImportedSettingsWeakening.isWeakening(device(strictModeEnabled = true), null)
        )
    }

    @Test
    fun `a null strictModeEnabled field in the incoming file leaves the device value untouched`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = true),
                device(strictModeEnabled = null)
            )
        )
    }

    @Test
    fun `a null strictModeChallengeLength field in the incoming file leaves the device value untouched`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeChallengeLength = 48),
                device(strictModeChallengeLength = null)
            )
        )
    }

    @Test
    fun `a null emergencyPassEnabled field in the incoming file leaves the device value untouched`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(emergencyPassEnabled = false),
                device(emergencyPassEnabled = null)
            )
        )
    }

    @Test
    fun `a null strictModeEnabled field on the device side is never weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeEnabled = null),
                device(strictModeEnabled = false)
            )
        )
    }

    @Test
    fun `a null strictModeChallengeLength field on the device side is never weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(strictModeChallengeLength = null),
                device(strictModeChallengeLength = 1)
            )
        )
    }

    // ── independence + identity ──

    @Test
    fun `strengthening one axis while softening another is still weakening`() {
        // Strict Mode challenge length raised (strengthening) but the pass turned on (weakening).
        val current = device(strictModeChallengeLength = 12, emergencyPassEnabled = false)
        val incoming = device(strictModeChallengeLength = 48, emergencyPassEnabled = true)
        assertTrue(ImportedSettingsWeakening.isWeakening(current, incoming))
    }

    @Test
    fun `an incoming payload identical to the device is not weakening`() {
        assertFalse(ImportedSettingsWeakening.isWeakening(device(), device()))
    }

    // ── contentFilterAllowlist ("Allowed words & sites", issue #78) ──

    @Test
    fun `an import that adds an allowlist entry weakens protection`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterAllowlist = listOf("cnc")),
                device(contentFilterAllowlist = listOf("cnc", "bbc news"))
            )
        )
    }

    @Test
    fun `an import that only removes allowlist entries does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterAllowlist = listOf("cnc", "bbc news")),
                device(contentFilterAllowlist = listOf("cnc"))
            )
        )
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterAllowlist = listOf("cnc")),
                device(contentFilterAllowlist = emptyList())
            )
        )
    }

    @Test
    fun `an import whose allowlist differs only in case or spacing does not weaken protection`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterAllowlist = listOf("bbc news")),
                device(contentFilterAllowlist = listOf("  BBC   News "))
            )
        )
    }

    @Test
    fun `a file that does not carry the allowlist never weakens on that axis`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterAllowlist = emptyList()),
                device(contentFilterAllowlist = null)
            )
        )
    }

    @Test
    fun `an allowlist entry added onto a device that never had the setting still weakens`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                device(contentFilterAllowlist = null),
                device(contentFilterAllowlist = listOf("cnc"))
            )
        )
    }

    // ── bounceCheckInEnabled ──

    /**
     * The check-in is not a protection setting: turning it off changes what the app SAYS, never
     * what it blocks. Carrying it in a backup must therefore not create a Strict Mode gate.
     */
    @Test
    fun `turning the bounce check-in off is not weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                device(bounceCheckInEnabled = true),
                device(bounceCheckInEnabled = false)
            )
        )
    }
}
