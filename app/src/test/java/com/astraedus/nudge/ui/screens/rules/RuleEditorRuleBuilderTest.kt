package com.astraedus.nudge.ui.screens.rules

import com.astraedus.nudge.ui.components.DurationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the rule editor's SAVE contract via the pure [RuleEditorViewModel.buildRule] mapping.
 *
 * Everything here is a field that only exists correctly if the mapping is right, and every case is
 * one a user can reach: which auto-kick trigger survives a save, whether an untouched duration is
 * re-persisted verbatim, and whether settings this screen has no UI for are preserved.
 */
class RuleEditorRuleBuilderTest {

    private fun state(
        autoKickEnabled: Boolean = false,
        autoKickByInteractions: Boolean = true,
        autoKickAfter: Int = 30,
        autoKickAfterMinutesText: String = "",
        autoKickCooldownMinutesText: String = "1",
        originalAutoKickCooldownSeconds: Int = 60,
        originalAutoKickAfterMinutes: Int? = null,
        showCounter: Boolean = true,
        webDomains: String? = null,
        webBlockMode: String? = null,
        tabVanish: Boolean = true,
        followingSteer: Boolean = false
    ) = RuleEditorUiState(
        packageName = "com.example.app",
        existingRuleId = 7L,
        showCounter = showCounter,
        autoKickEnabled = autoKickEnabled,
        autoKickByInteractions = autoKickByInteractions,
        autoKickAfter = autoKickAfter,
        autoKickAfterMinutesText = autoKickAfterMinutesText,
        autoKickCooldownMinutesText = autoKickCooldownMinutesText,
        originalAutoKickCooldownSeconds = originalAutoKickCooldownSeconds,
        originalAutoKickAfterMinutes = originalAutoKickAfterMinutes,
        webDomains = webDomains,
        webBlockMode = webBlockMode,
        tabVanish = tabVanish,
        followingSteer = followingSteer
    )

    // ── tab vanish / following steer (regression) ──

    /**
     * Same regression as `webDomains` below, one feature later. This editor has no UI for either
     * flag and rebuilds the whole rule, so before they were threaded through, opening an Instagram
     * rule here and saving ANY unrelated change silently turned a switched-off tab cover back ON.
     */
    @Test
    fun `saving preserves a tab cover the user switched off`() {
        assertFalse(RuleEditorViewModel.buildRule(state(tabVanish = false)).tabVanish)
    }

    /**
     * The other direction, and the one that loses a feature the user deliberately enabled: the
     * Following steer defaults to OFF, so dropping it here would switch it off on every save.
     */
    @Test
    fun `saving preserves a following steer the user switched on`() {
        assertTrue(RuleEditorViewModel.buildRule(state(followingSteer = true)).followingSteer)
    }

    @Test
    fun `the defaults round-trip unchanged`() {
        val rule = RuleEditorViewModel.buildRule(state())

        assertTrue("tab vanish defaults on", rule.tabVanish)
        assertFalse("the steer defaults off", rule.followingSteer)
    }

    // ── web domains (regression) ──

    @Test
    fun `saving preserves web domains this screen cannot edit`() {
        // Regression: the editor rebuilds the whole rule, so a field it has no UI for used to be
        // dropped -- one unrelated edit here silently switched off an app's web blocking.
        val rule = RuleEditorViewModel.buildRule(
            state(webDomains = "instagram.com,www.instagram.com")
        )

        assertEquals("instagram.com,www.instagram.com", rule.webDomains)
    }

    @Test
    fun `a rule with no web domains still saves null`() {
        assertNull(RuleEditorViewModel.buildRule(state()).webDomains)
    }

    @Test
    fun `saving preserves the website block mode this screen cannot edit`() {
        // Same regression class as webDomains: webBlockMode is what those domains block with, so
        // dropping it downgrades a website-only block (app open, site blocked) back to inheriting
        // an app-level mode that may be NONE -- i.e. enforcing nothing at all.
        val rule = RuleEditorViewModel.buildRule(
            state(webDomains = "instagram.com", webBlockMode = "HARD_BLOCK")
        )

        assertEquals("HARD_BLOCK", rule.webBlockMode)
    }

    @Test
    fun `a rule with no independent website mode still saves null`() {
        assertNull(RuleEditorViewModel.buildRule(state()).webBlockMode)
    }

    // ── the two triggers are independent ──

    @Test
    fun `both triggers can be set at once`() {
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickByInteractions = true,
                autoKickAfter = 40,
                autoKickAfterMinutesText = "30"
            )
        )

        assertEquals(40, rule.autoKickAfter)
        assertEquals(30, rule.autoKickAfterMinutes)
    }

    @Test
    fun `the time trigger survives with the interaction trigger switched off`() {
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickByInteractions = false,
                autoKickAfterMinutesText = "30"
            )
        )

        assertNull(rule.autoKickAfter)
        assertEquals(30, rule.autoKickAfterMinutes)
    }

    @Test
    fun `the time trigger does not require the interaction counter`() {
        // The whole point of the time trigger is passive use, where a counter is pointless. The
        // interaction trigger genuinely needs the counter machinery, so it alone stays gated.
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickByInteractions = true,
                autoKickAfterMinutesText = "30",
                showCounter = false
            )
        )

        assertNull(rule.autoKickAfter)
        assertEquals(30, rule.autoKickAfterMinutes)
    }

    @Test
    fun `a blank minutes field means the time trigger is off`() {
        val rule = RuleEditorViewModel.buildRule(
            state(autoKickEnabled = true, autoKickAfterMinutesText = "")
        )

        assertNull(rule.autoKickAfterMinutes)
        assertEquals(30, rule.autoKickAfter)
    }

    @Test
    fun `turning auto-kick off clears both triggers`() {
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = false,
                autoKickAfter = 40,
                autoKickAfterMinutesText = "30",
                originalAutoKickAfterMinutes = 30
            )
        )

        assertNull(rule.autoKickAfter)
        assertNull(rule.autoKickAfterMinutes)
    }

    @Test
    fun `turning auto-kick off keeps the stored cooldown instead of resetting it`() {
        // Snapping back to the 60s default would lose the user's setting AND read as a protection
        // weakening (RuleWeakening treats a lowered cooldown as weaker) on the next comparison.
        val rule = RuleEditorViewModel.buildRule(
            state(autoKickEnabled = false, originalAutoKickCooldownSeconds = 150)
        )

        assertEquals(150, rule.autoKickCooldownSeconds)
    }

    // ── duration round-tripping ──

    @Test
    fun `an untouched cooldown re-saves the exact stored value`() {
        // 150s is a real value the old 0-300s slider produced. It displays as "3 min"; saving an
        // unrelated change must not quietly turn it into 180s.
        val stored = 150
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickCooldownMinutesText = DurationInput.cooldownSecondsToText(stored),
                originalAutoKickCooldownSeconds = stored
            )
        )

        assertEquals(stored, rule.autoKickCooldownSeconds)
    }

    @Test
    fun `editing the cooldown writes the new value`() {
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickCooldownMinutesText = "15",
                originalAutoKickCooldownSeconds = 150
            )
        )

        assertEquals(900, rule.autoKickCooldownSeconds)
    }

    @Test
    fun `clearing the cooldown turns it off`() {
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickCooldownMinutesText = "",
                originalAutoKickCooldownSeconds = 150
            )
        )

        assertEquals(0, rule.autoKickCooldownSeconds)
    }

    @Test
    fun `an untouched minutes threshold re-saves the exact stored value`() {
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                autoKickAfterMinutesText = DurationInput.minutesToText(45),
                originalAutoKickAfterMinutes = 45
            )
        )

        assertEquals(45, rule.autoKickAfterMinutes)
    }

    @Test
    fun `the cooldown does not depend on the interaction counter`() {
        // Both triggers share one cooldown, so it must survive with the counter off.
        val rule = RuleEditorViewModel.buildRule(
            state(
                autoKickEnabled = true,
                showCounter = false,
                autoKickAfterMinutesText = "30",
                autoKickCooldownMinutesText = "15",
                originalAutoKickCooldownSeconds = 60
            )
        )

        assertEquals(900, rule.autoKickCooldownSeconds)
    }
}
