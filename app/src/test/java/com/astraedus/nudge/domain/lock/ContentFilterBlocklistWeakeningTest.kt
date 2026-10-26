package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.export.ExportedSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContentFilterBlocklistWeakening]: every blocked entry takes protection up, so the list weakens by
 * shrinking. Strict Mode gates removals and leaves additions free, the mirror of
 * [ContentFilterAllowlistWeakening] and the same asymmetry [SettingsWeakeningTest] pins for the
 * escape-hatch toggle.
 */
class ContentFilterBlocklistWeakeningTest {

    @Test
    fun `removing a block under strict mode requires the unlock`() {
        assertTrue(
            ContentFilterBlocklistWeakening.requiresUnlock(
                current = setOf("crypto", "stake.com"),
                next = setOf("crypto"),
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `adding a block under strict mode is free`() {
        assertFalse(
            ContentFilterBlocklistWeakening.requiresUnlock(
                current = setOf("crypto"),
                next = setOf("crypto", "stake.com"),
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `nothing is gated with strict mode off`() {
        assertFalse(
            ContentFilterBlocklistWeakening.requiresUnlock(
                current = setOf("crypto"),
                next = emptySet(),
                strictModeEnabled = false
            )
        )
    }

    @Test
    fun `a canonical-equivalent rewrite is not a removal`() {
        assertFalse(
            ContentFilterBlocklistWeakening.requiresUnlock(
                current = setOf("crypto"),
                next = setOf("CRYPTO"),
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `an import that drops a block is weakening`() {
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                current = ExportedSettings(contentFilterBlocklist = listOf("crypto", "stake.com")),
                incoming = ExportedSettings(contentFilterBlocklist = listOf("crypto"))
            )
        )
    }

    @Test
    fun `an import that adds a block is not weakening`() {
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                current = ExportedSettings(contentFilterBlocklist = listOf("crypto")),
                incoming = ExportedSettings(contentFilterBlocklist = listOf("crypto", "stake.com"))
            )
        )
    }
}
