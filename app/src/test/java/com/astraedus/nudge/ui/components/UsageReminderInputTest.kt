package com.astraedus.nudge.ui.components

import com.astraedus.nudge.domain.autokick.UseReminderEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure helpers behind the usage-reminder editor section
 * ([#77](https://github.com/astraedus/nudge/issues/77)). No Android deps, so this runs on the
 * plain JVM.
 */
class UsageReminderInputTest {

    // ── textAfterToggle() ──

    @Test
    fun `enabling with blank text fills the default so the switch is never a no-op`() {
        assertEquals(UsageReminderInput.DEFAULT_MINUTES_TEXT, UsageReminderInput.textAfterToggle(true, ""))
        assertEquals(UsageReminderInput.DEFAULT_MINUTES_TEXT, UsageReminderInput.textAfterToggle(true, "   "))
    }

    @Test
    fun `enabling with zero fills the default because zero is not a threshold`() {
        assertEquals(UsageReminderInput.DEFAULT_MINUTES_TEXT, UsageReminderInput.textAfterToggle(true, "0"))
    }

    @Test
    fun `enabling with a usable value keeps it`() {
        assertEquals("45", UsageReminderInput.textAfterToggle(true, "45"))
    }

    @Test
    fun `disabling keeps whatever text was there`() {
        assertEquals("45", UsageReminderInput.textAfterToggle(false, "45"))
        assertEquals("", UsageReminderInput.textAfterToggle(false, ""))
        assertEquals("0", UsageReminderInput.textAfterToggle(false, "0"))
    }

    // ── resolve() ──

    @Test
    fun `disabled resolves to null even with text present`() {
        assertNull(UsageReminderInput.resolve(enabled = false, text = "20", originalMinutes = null))
        assertNull(UsageReminderInput.resolve(enabled = false, text = "20", originalMinutes = 20))
    }

    @Test
    fun `enabled with a typed value resolves to that value`() {
        assertEquals(20, UsageReminderInput.resolve(enabled = true, text = "20", originalMinutes = null))
    }

    @Test
    fun `enabled with a blank field resolves to null`() {
        assertNull(UsageReminderInput.resolve(enabled = true, text = "", originalMinutes = null))
    }

    @Test
    fun `an edited field wins over the original`() {
        assertEquals(15, UsageReminderInput.resolve(enabled = true, text = "15", originalMinutes = 20))
    }

    @Test
    fun `an untouched field re-saves the original verbatim even beyond the field maximum`() {
        // Mirrors DurationInputTest's resolveMinutes case: 2000 exceeds MAX_MINUTES, so a re-parse
        // would clamp it to 1440. An untouched field must not rewrite the stored value.
        val original = 2000
        assertTrue(original > DurationInput.MAX_MINUTES)
        val untouchedText = DurationInput.minutesToText(original)

        assertEquals(original, UsageReminderInput.resolve(true, untouchedText, original))
    }

    @Test
    fun `an edited over-large value is clamped like every other minutes field`() {
        assertEquals(
            DurationInput.MAX_MINUTES,
            UsageReminderInput.resolve(enabled = true, text = "2000", originalMinutes = 30)
        )
    }

    // ── supersededHint() ──

    @Test
    fun `no hint when there is no reminder`() {
        assertNull(UsageReminderInput.supersededHint(null, 20))
        assertNull(UsageReminderInput.supersededHint(0, 20))
        assertNull(UsageReminderInput.supersededHint(-5, 20))
    }

    @Test
    fun `no hint when there is no kick`() {
        assertNull(UsageReminderInput.supersededHint(20, null))
    }

    @Test
    fun `no hint when the reminder comes before the kick`() {
        assertNull(UsageReminderInput.supersededHint(10, 20))
    }

    @Test
    fun `hint when the reminder equals the kick and it names the kick minutes`() {
        val hint = UsageReminderInput.supersededHint(20, 20)

        assertNotNull(hint)
        assertTrue(hint!!, hint.contains("20"))
    }

    @Test
    fun `hint when the reminder is after the kick and it names the kick minutes`() {
        val hint = UsageReminderInput.supersededHint(30, 20)

        assertNotNull(hint)
        assertTrue(hint!!, hint.contains("20"))
        assertFalse("must name the kick, not the reminder threshold", hint.contains("30"))
    }

    @Test
    fun `a disabled kick never suppresses the reminder`() {
        assertNull(UsageReminderInput.supersededHint(20, 0))
        assertNull(UsageReminderInput.supersededHint(20, -1))
    }

    @Test
    fun `the editor hint and the engine agree on every pair`() {
        // ONE predicate: the editor must never promise a reminder the engine will not send, or hide
        // one it will.
        for (remind in 1..40) {
            for (kick in listOf<Int?>(null) + (1..40).toList()) {
                val hinted = UsageReminderInput.supersededHint(remind, kick) != null
                val neverFires = UseReminderEvaluator.effectiveMinutes(remind, kick) == null
                assertEquals("remind=$remind kick=$kick", neverFires, hinted)
            }
        }
    }

    // ── info() ──

    @Test
    fun `info mentions apps only exactly when websites are configured`() {
        assertTrue(UsageReminderInput.info(websitesConfigured = true).contains("Apps only"))
        assertFalse(UsageReminderInput.info(websitesConfigured = false).contains("Apps only"))
    }

    // ── copy tone ──

    @Test
    fun `no user-facing copy shames or lectures`() {
        val banned = listOf("wasted", "too long", "again", "addict", "should")
        val copy = listOf(
            "TITLE" to UsageReminderInput.TITLE,
            "FIELD_LABEL" to UsageReminderInput.FIELD_LABEL,
            "FIELD_SUPPORTING" to UsageReminderInput.FIELD_SUPPORTING,
            "BLOCKED_WARNING" to UsageReminderInput.BLOCKED_WARNING,
            "info(true)" to UsageReminderInput.info(true),
            "info(false)" to UsageReminderInput.info(false)
        )

        for ((name, text) in copy) {
            for (word in banned) {
                assertFalse("$name contains \"$word\": $text", text.contains(word, ignoreCase = true))
            }
        }
    }
}
