package com.astraedus.nudge.ui.screens.rules

import com.astraedus.nudge.data.db.entity.BlockRule
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSummaryTextTest {

    private fun rule(
        mode: String = "DELAY",
        delaySeconds: Int = 15,
        inAppFeatures: String? = null,
        dailyLimitMinutes: Int? = null,
        scheduleDays: String? = null,
        scheduleStartMinute: Int? = null
    ) = BlockRule(
        id = 0,
        packageName = "com.test",
        mode = mode,
        delaySeconds = delaySeconds,
        inAppFeatures = inAppFeatures,
        dailyLimitMinutes = dailyLimitMinutes,
        scheduleDays = scheduleDays,
        scheduleStartMinute = scheduleStartMinute
    )

    @Test
    fun `simple hard block`() {
        val result = ActiveRulesViewModel.buildSummaryText(listOf(rule(mode = "HARD_BLOCK")))
        assertEquals("Hard Block", result)
    }

    @Test
    fun `delay with daily limit`() {
        val result = ActiveRulesViewModel.buildSummaryText(
            listOf(rule(mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 30))
        )
        assertEquals("Delay 15s · 30min limit", result)
    }

    @Test
    fun `breathing with schedule`() {
        val result = ActiveRulesViewModel.buildSummaryText(
            listOf(rule(mode = "BREATHING", delaySeconds = 30, scheduleDays = "1,2,3,4,5"))
        )
        assertEquals("Breathing 30s · Scheduled", result)
    }

    @Test
    fun `default rule with feature override`() {
        val rules = listOf(
            rule(mode = "DELAY", delaySeconds = 15),
            rule(mode = "HARD_BLOCK", inAppFeatures = "REELS")
        )
        val result = ActiveRulesViewModel.buildSummaryText(rules)
        assertEquals("Delay 15s · Reels: Hard Block", result)
    }

    @Test
    fun `multiple feature overrides`() {
        val rules = listOf(
            rule(mode = "DELAY", delaySeconds = 10),
            rule(mode = "HARD_BLOCK", inAppFeatures = "REELS"),
            rule(mode = "HARD_BLOCK", inAppFeatures = "SHORTS")
        )
        val result = ActiveRulesViewModel.buildSummaryText(rules)
        assertEquals("Delay 10s · Reels: Hard Block · Shorts: Hard Block", result)
    }

    @Test
    fun `feature-only rule with no default`() {
        val result = ActiveRulesViewModel.buildSummaryText(
            listOf(rule(mode = "HARD_BLOCK", inAppFeatures = "REELS"))
        )
        assertEquals("Reels: Hard Block", result)
    }

    @Test
    fun `daily limit and schedule together`() {
        val result = ActiveRulesViewModel.buildSummaryText(
            listOf(rule(mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 60, scheduleDays = "1,2,3"))
        )
        assertEquals("Delay 15s · 60min limit · Scheduled", result)
    }

    @Test
    fun `comma-separated features in single rule`() {
        val result = ActiveRulesViewModel.buildSummaryText(
            listOf(rule(mode = "HARD_BLOCK", inAppFeatures = "REELS,EXPLORE"))
        )
        assertEquals("Reels/Explore: Hard Block", result)
    }

    @Test
    fun `schedule detected via scheduleStartMinute only`() {
        val result = ActiveRulesViewModel.buildSummaryText(
            listOf(rule(mode = "DELAY", delaySeconds = 15, scheduleStartMinute = 540))
        )
        assertEquals("Delay 15s · Scheduled", result)
    }
}
