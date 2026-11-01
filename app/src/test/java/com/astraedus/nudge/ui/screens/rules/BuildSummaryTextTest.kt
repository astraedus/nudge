package com.astraedus.nudge.ui.screens.rules

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.screens.groups.GroupRuleForm
import com.astraedus.nudge.ui.screens.groups.toRule
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

    // --- Group rows on Manage Apps (spec PR 2) ---

    @Test
    fun `group title counts its apps, singular for one`() {
        assertEquals("Social · 9 apps", ActiveRulesViewModel.groupTitle("Social", 9))
        assertEquals("Work · 1 app", ActiveRulesViewModel.groupTitle("Work", 1))
        assertEquals("Empty · 0 apps", ActiveRulesViewModel.groupTitle("Empty", 0))
    }

    @Test
    fun `a group with no rule of its own reads Not blocked`() {
        assertEquals("Not blocked", ActiveRulesViewModel.groupSummaryText(null))
    }

    @Test
    fun `a group rule saved by the editor reads in the same words as an app rule`() {
        val saved = GroupRuleForm(
            mode = BlockMode.DELAY,
            waitSeconds = 15,
            dailyLimitEnabled = true,
            dailyLimitMinutes = 30,
            scheduled = true
        ).toRule(null).copy(groupId = 4L)

        assertEquals("Delay 15s · 30min limit · Scheduled", ActiveRulesViewModel.groupSummaryText(saved))
    }

    @Test
    fun `a hard-blocking group summarises as Hard Block`() {
        val saved = BlockRule(groupId = 4L, mode = "HARD_BLOCK")
        assertEquals("Hard Block", ActiveRulesViewModel.groupSummaryText(saved))
    }
}
