package com.astraedus.nudge.ui.components.rule

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.screens.groups.GroupRuleForm
import com.astraedus.nudge.ui.screens.groups.toRule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one summary format (spec 3.2), moved here from Active Rules with the helper.
 *
 * Contract change in groups PR 3: a schedule used to read as the bare word "Scheduled"; it now reads
 * as WHEN ("Mon–Fri 9:00–17:00"), and a rule that is active all the time adds nothing. The
 * schedule cases below were updated to that, not dropped.
 */
class BuildSummaryTextTest {

    private fun rule(
        mode: String = "DELAY",
        delaySeconds: Int = 15,
        inAppFeatures: String? = null,
        dailyLimitMinutes: Int? = null,
        scheduleDays: String? = null,
        scheduleStartMinute: Int? = null,
        scheduleEndMinute: Int? = null
    ) = BlockRule(
        id = 0,
        packageName = "com.test",
        mode = mode,
        delaySeconds = delaySeconds,
        inAppFeatures = inAppFeatures,
        dailyLimitMinutes = dailyLimitMinutes,
        scheduleDays = scheduleDays,
        scheduleStartMinute = scheduleStartMinute,
        scheduleEndMinute = scheduleEndMinute
    )

    @Test
    fun `simple hard block`() {
        val result = RuleSummary.buildSummaryText(listOf(rule(mode = "HARD_BLOCK")))
        assertEquals("Hard Block", result)
    }

    @Test
    fun `delay with daily limit`() {
        val result = RuleSummary.buildSummaryText(
            listOf(rule(mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 30))
        )
        assertEquals("Delay 15s · 30min limit", result)
    }

    @Test
    fun `breathing with schedule`() {
        val result = RuleSummary.buildSummaryText(
            listOf(rule(mode = "BREATHING", delaySeconds = 30, scheduleDays = "1,2,3,4,5"))
        )
        assertEquals("Breathing 30s · Mon–Fri", result)
    }

    @Test
    fun `default rule with feature override`() {
        val rules = listOf(
            rule(mode = "DELAY", delaySeconds = 15),
            rule(mode = "HARD_BLOCK", inAppFeatures = "REELS")
        )
        val result = RuleSummary.buildSummaryText(rules)
        assertEquals("Delay 15s · Reels: Hard Block", result)
    }

    @Test
    fun `multiple feature overrides`() {
        val rules = listOf(
            rule(mode = "DELAY", delaySeconds = 10),
            rule(mode = "HARD_BLOCK", inAppFeatures = "REELS"),
            rule(mode = "HARD_BLOCK", inAppFeatures = "SHORTS")
        )
        val result = RuleSummary.buildSummaryText(rules)
        assertEquals("Delay 10s · Reels: Hard Block · Shorts: Hard Block", result)
    }

    @Test
    fun `feature-only rule with no default`() {
        val result = RuleSummary.buildSummaryText(
            listOf(rule(mode = "HARD_BLOCK", inAppFeatures = "REELS"))
        )
        assertEquals("Reels: Hard Block", result)
    }

    @Test
    fun `daily limit and schedule together`() {
        val result = RuleSummary.buildSummaryText(
            listOf(rule(mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 60, scheduleDays = "1,2,3"))
        )
        assertEquals("Delay 15s · 60min limit · Mon–Wed", result)
    }

    @Test
    fun `comma-separated features in single rule`() {
        val result = RuleSummary.buildSummaryText(
            listOf(rule(mode = "HARD_BLOCK", inAppFeatures = "REELS,EXPLORE"))
        )
        assertEquals("Reels/Explore: Hard Block", result)
    }

    @Test
    fun `a start minute with no end is no window, as in the engine, so nothing is added`() {
        // ScheduleEvaluator only applies a time window when BOTH ends are set; this rule is active
        // all the time, and "All the time" is left out of a summary.
        val result = RuleSummary.buildSummaryText(
            listOf(rule(mode = "DELAY", delaySeconds = 15, scheduleStartMinute = 540))
        )
        assertEquals("Delay 15s", result)
    }

    @Test
    fun `days and a time window read as both`() {
        val result = RuleSummary.buildSummaryText(
            listOf(
                rule(
                    mode = "HARD_BLOCK",
                    scheduleDays = "1,2,3,4,5",
                    scheduleStartMinute = 540,
                    scheduleEndMinute = 1020
                )
            )
        )
        assertEquals("Hard Block · Mon–Fri 9:00–17:00", result)
    }

    @Test
    fun `an app's always-on rule comes before its scheduled rule, then its features`() {
        val rules = listOf(
            rule(mode = "HARD_BLOCK", inAppFeatures = "REELS"),
            rule(mode = "HARD_BLOCK", scheduleDays = "6,7"),
            rule(mode = "DELAY", delaySeconds = 15, dailyLimitMinutes = 30)
        )
        assertEquals(
            "Delay 15s · 30min limit · Hard Block · Sat, Sun · Reels: Hard Block",
            RuleSummary.buildSummaryText(rules)
        )
    }

    @Test
    fun `a rule that only caps the day reads as its limit, not as Not blocked`() {
        assertEquals(
            "30min limit",
            RuleSummary.buildSummaryText(listOf(rule(mode = "NONE", dailyLimitMinutes = 30)))
        )
        // The app opens freely and only Reels is blocked: both halves are true, so both are said.
        assertEquals(
            "Not blocked · Reels: Hard Block",
            RuleSummary.buildSummaryText(
                listOf(rule(mode = "NONE"), rule(mode = "HARD_BLOCK", inAppFeatures = "REELS"))
            )
        )
        assertEquals("Not blocked", RuleSummary.buildSummaryText(listOf(rule(mode = "NONE"))))
    }

    @Test
    fun `every mode has words`() {
        assertEquals("Hold 10s", RuleSummary.buildSummaryText(listOf(rule(mode = "HOLD", delaySeconds = 10))))
        assertEquals("Breathing 30s", RuleSummary.buildSummaryText(listOf(rule(mode = "BREATHING", delaySeconds = 30))))
    }

    // --- Group rows on Manage Apps (spec PR 2) ---

    @Test
    fun `group title counts its apps, singular for one`() {
        assertEquals("Social · 9 apps", RuleSummary.groupTitle("Social", 9))
        assertEquals("Work · 1 app", RuleSummary.groupTitle("Work", 1))
        assertEquals("Empty · 0 apps", RuleSummary.groupTitle("Empty", 0))
    }

    @Test
    fun `a group with no rule of its own reads Not blocked`() {
        assertEquals("Not blocked", RuleSummary.groupSummaryText(null))
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

        assertEquals(
            "Delay 15s · 30min limit · Mon–Fri 9:00–17:00",
            RuleSummary.groupSummaryText(saved)
        )
    }

    @Test
    fun `a hard-blocking group summarises as Hard Block`() {
        val saved = BlockRule(groupId = 4L, mode = "HARD_BLOCK")
        assertEquals("Hard Block", RuleSummary.groupSummaryText(saved))
    }
}
