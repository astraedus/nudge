package com.astraedus.nudge.ui.screens.groups

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.lock.GroupUsage
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupRule
import com.astraedus.nudge.ui.components.rule.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group editor's pure half: what the form shows for a saved rule, what it writes back, which
 * saves need the Strict Mode challenge (spec 5.6), and what the picker may and may not drop.
 */
class GroupFormTest {

    private val blocking = GroupUsage(blocks = true, allowedBy = false)

    private val groupRule = aGroupRule(groupId = 3L).copy(id = 7L)

    // ── Form <-> rule ─────────────────────────────────────────────────────

    @Test
    fun `a new group starts blocking with Delay 15s, all the time`() {
        val form = GroupRuleForm.from(null)
        assertTrue(form.blockEnabled)
        assertEquals(BlockMode.DELAY, form.mode)
        assertEquals(15, form.waitSeconds)
        assertFalse(form.scheduled)
        assertFalse(form.dailyLimitEnabled)
    }

    @Test
    fun `every field the editor shows survives rule to form to rule`() {
        val saved = groupRule.copy(
            mode = "HOLD", delaySeconds = 30, enabled = false, dailyLimitMinutes = 45,
            scheduleDays = "1,3,5", scheduleStartMinute = 480, scheduleEndMinute = 1080)

        val back = GroupRuleForm.from(saved).toRule(saved)

        assertEquals(saved, back)
    }

    @Test
    fun `fields the editor does not show survive a save`() {
        val saved = groupRule.copy(
            autoKickAfter = 40,
            autoKickAfterMinutes = 12,
            showCounter = true,
            remindAfterMinutes = 20,
            tabVanish = false,
            createdAt = 1234L
        )

        val back = GroupRuleForm.from(saved).copy(waitSeconds = 60).toRule(saved)

        assertEquals(saved.copy(delaySeconds = 60), back)
    }

    @Test
    fun `switching blocking off keeps How and When for next time`() {
        val saved = groupRule.copy(mode = "HARD_BLOCK", dailyLimitMinutes = 30)

        val back = GroupRuleForm.from(saved).copy(blockEnabled = false).toRule(saved)

        assertFalse(back.enabled)
        assertEquals("HARD_BLOCK", back.mode)
        assertEquals(30, back.dailyLimitMinutes)
    }

    @Test
    fun `all the time writes no schedule, and a group rule never targets a package`() {
        val rule = GroupRuleForm(scheduled = false).toRule(null)
        assertNull(rule.scheduleDays)
        assertNull(rule.scheduleStartMinute)
        assertNull(rule.scheduleEndMinute)
        assertNull(rule.packageName)
        assertEquals(0L, rule.id)
    }

    @Test
    fun `days and times writes sorted days and both times`() {
        val rule = GroupRuleForm(
            scheduled = true,
            scheduleDays = setOf(5, 1, 3),
            startMinute = 540,
            endMinute = 1020
        ).toRule(null)
        assertEquals("1,3,5", rule.scheduleDays)
        assertEquals(540, rule.scheduleStartMinute)
        assertEquals(1020, rule.scheduleEndMinute)
    }

    @Test
    fun `after first unlock writes firstUnlockMinutes and clears the time window`() {
        val rule = GroupRuleForm(
            firstUnlock = true,
            firstUnlockMinutes = 30,
            scheduleDays = setOf(1, 2, 3, 4, 5),
            startMinute = 540,
            endMinute = 1020
        ).toRule(null)
        assertEquals(30, rule.firstUnlockMinutes)
        assertNull(rule.scheduleStartMinute)
        assertNull(rule.scheduleEndMinute)
    }

    @Test
    fun `days and times writes the window and clears firstUnlockMinutes`() {
        val rule = GroupRuleForm(
            scheduled = true,
            scheduleDays = setOf(5, 1),
            startMinute = 480,
            endMinute = 960,
            firstUnlockMinutes = 45
        ).toRule(null)
        assertEquals("1,5", rule.scheduleDays)
        assertEquals(480, rule.scheduleStartMinute)
        assertEquals(960, rule.scheduleEndMinute)
        assertNull(rule.firstUnlockMinutes)
    }

    @Test
    fun `a new rule switched to first unlock selects every day, an edited one keeps its days`() {
        // Morning Lock is daily, so an untouched new rule defaults to all seven (2026-11-05).
        assertEquals(
            GroupRuleForm.ALL_DAYS,
            GroupRuleForm().withKind(ScheduleKind.FIRST_UNLOCK).scheduleDays
        )
        // The days-and-times default is unchanged.
        assertEquals(
            GroupRuleForm.WEEKDAYS,
            GroupRuleForm().withKind(ScheduleKind.DAYS_TIMES).scheduleDays
        )
        // A user who chose days keeps them when switching kind.
        assertEquals(
            setOf(1, 2),
            GroupRuleForm(daysTouched = true, scheduleDays = setOf(1, 2))
                .withKind(ScheduleKind.FIRST_UNLOCK)
                .scheduleDays
        )
    }

    @Test
    fun `a saved rule keeps its days when it switches to first unlock`() {
        val saved = groupRule.copy(scheduleDays = "1,2,3,4,5", scheduleStartMinute = 540, scheduleEndMinute = 600)

        val form = GroupRuleForm.from(saved).withKind(ScheduleKind.FIRST_UNLOCK)

        assertTrue(form.firstUnlock)
        assertEquals(setOf(1, 2, 3, 4, 5), form.scheduleDays)
    }

    @Test
    fun `a first-unlock rule with no days opens showing every day`() {
        // A hand-edited import can carry a window and no days; the engine reads that as every day,
        // so the editor must not show Mon-Fri and narrow it on save (review nit).
        val saved = groupRule.copy(
            scheduleDays = null,
            scheduleStartMinute = null,
            scheduleEndMinute = null,
            firstUnlockMinutes = 30
        )

        val form = GroupRuleForm.from(saved)

        assertTrue(form.firstUnlock)
        assertEquals(GroupRuleForm.ALL_DAYS, form.scheduleDays)
        assertFalse(form.daysTouched)
    }

    @Test
    fun `from round-trips both days and times and first unlock`() {
        val daysTimes = groupRule.copy(
            mode = "HARD_BLOCK",
            scheduleDays = "1,3,5",
            scheduleStartMinute = 540,
            scheduleEndMinute = 1020
        )
        val firstUnlock = groupRule.copy(
            mode = "HARD_BLOCK",
            firstUnlockMinutes = 60,
            scheduleDays = "2,4"
        )

        assertFalse(GroupRuleForm.from(daysTimes).firstUnlock)
        assertEquals(daysTimes, GroupRuleForm.from(daysTimes).toRule(daysTimes))

        assertTrue(GroupRuleForm.from(firstUnlock).firstUnlock)
        assertEquals(firstUnlock, GroupRuleForm.from(firstUnlock).toRule(firstUnlock))
    }

    @Test
    fun `a NONE rule shows as Delay, because a group always blocks with something`() {
        assertEquals(BlockMode.DELAY, GroupRuleForm.from(groupRule.copy(mode = "NONE")).mode)
    }

    @Test
    fun `days and times with no day picked cannot be saved`() {
        assertFalse(GroupRuleForm(scheduled = true, scheduleDays = emptySet()).isComplete)
        assertTrue(GroupRuleForm(scheduled = false, scheduleDays = emptySet()).isComplete)
    }

    // ── Strict Mode on save (spec 5.6) ────────────────────────────────────

    /** Hand-picked groups: every member is an include, whether or not it is installed. */
    private fun weakens(
        oldMembers: Set<String>?,
        newMembers: Set<String>,
        oldRule: BlockRule?,
        newRule: BlockRule,
        usage: GroupUsage = blocking
    ) = groupSaveWeakens(
        oldMembers?.let { GroupDefinition(3L, includes = it) },
        GroupDefinition(3L, includes = newMembers),
        usage, oldRule, newRule,
        installed = emptyMap(), essentials = emptySet(), browsers = emptySet()
    )

    @Test
    fun `creating a group is never weakening`() =
        assertFalse(weakens(null, setOf("a"), null, groupRule.copy(mode = "BREATHING")))

    @Test
    fun `removing an app from a blocking group is weakening`() =
        assertTrue(weakens(setOf("a", "b"), setOf("a"), groupRule, groupRule))

    @Test
    fun `adding an app to a blocking group is not`() =
        assertFalse(weakens(setOf("a"), setOf("a", "b"), groupRule, groupRule))

    @Test
    fun `removing an app from a group that blocks nothing is free`() =
        assertFalse(
            weakens(
                setOf("a", "b"), setOf("a"),
                groupRule.copy(enabled = false), groupRule.copy(enabled = false),
                usage = GroupUsage.UNUSED
            )
        )

    @Test
    fun `turning the group's blocking off is weakening`() =
        assertTrue(weakens(setOf("a"), setOf("a"), groupRule, groupRule.copy(enabled = false)))

    @Test
    fun `a shorter wait or a softer mode is weakening`() {
        assertTrue(weakens(setOf("a"), setOf("a"), groupRule.copy(delaySeconds = 30), groupRule.copy(delaySeconds = 15)))
        assertTrue(weakens(setOf("a"), setOf("a"), groupRule.copy(mode = "HARD_BLOCK"), groupRule.copy(mode = "DELAY")))
    }

    @Test
    fun `a stricter edit is free`() =
        assertFalse(weakens(setOf("a"), setOf("a"), groupRule.copy(mode = "DELAY"), groupRule.copy(mode = "HARD_BLOCK")))

    @Test
    fun `editing a rule that was off, while switching it on, is free whatever it softens`() =
        assertFalse(
            weakens(
                setOf("a"), setOf("a"),
                groupRule.copy(mode = "HARD_BLOCK", enabled = false), groupRule.copy(mode = "BREATHING"),
                usage = GroupUsage.UNUSED
            )
        )

    // ── Strict Mode on delete ─────────────────────────────────────────────

    @Test
    fun `deleting a blocking group is weakening, an unused one is free`() {
        assertTrue(groupDeleteWeakens(GroupDefinition(3L, includes = setOf("a")), blocking, emptyMap(), emptySet(), browsers = emptySet()))
        assertFalse(groupDeleteWeakens(GroupDefinition(3L, includes = setOf("a")), GroupUsage.UNUSED, emptyMap(), emptySet(), browsers = emptySet()))
    }

    // ── Category groups (spec 5.2): membership is resolved before it is compared ──

    private val fiveSocial = (1..5).associate { "com.social$it" to AppCategory.SOCIAL }
    private val socialGroup = GroupDefinition(3L, categories = setOf(AppCategory.SOCIAL))

    private fun categoryWeakens(
        old: GroupDefinition?,
        new: GroupDefinition,
        installed: Map<String, AppCategory>,
        essentials: Set<String> = emptySet(),
        usage: GroupUsage = blocking
    ) = groupSaveWeakens(old, new, usage, groupRule, groupRule, installed, essentials, browsers = emptySet())

    @Test
    fun `unchecking a category that resolves to no installed app is not weakening`() {
        val installed = mapOf("com.game" to AppCategory.GAME)
        assertFalse(categoryWeakens(socialGroup, GroupDefinition(3L), installed))
    }

    @Test
    fun `unchecking a category that resolves to five apps is weakening`() {
        assertTrue(categoryWeakens(socialGroup, GroupDefinition(3L), fiveSocial))
    }

    @Test
    fun `unchecking a category whose only apps are essential is not weakening`() {
        assertFalse(
            categoryWeakens(socialGroup, GroupDefinition(3L), fiveSocial, essentials = fiveSocial.keys)
        )
    }

    @Test
    fun `moving a category app to removed-by-you is weakening`() {
        val removed = socialGroup.copy(excludes = setOf("com.social1"))
        assertTrue(categoryWeakens(socialGroup, removed, fiveSocial))
    }

    @Test
    fun `restoring a removed category app is not weakening under a blocking group`() {
        val removed = socialGroup.copy(excludes = setOf("com.social1"))
        assertFalse(categoryWeakens(removed, socialGroup, fiveSocial))
    }

    @Test
    fun `checking a category is not weakening under a blocking group`() {
        assertFalse(categoryWeakens(GroupDefinition(3L), socialGroup, fiveSocial))
    }

    @Test
    fun `checking a category under an allowing rule is weakening, since apps join the allowed set`() {
        val allowing = GroupUsage(blocks = false, allowedBy = true)
        assertTrue(categoryWeakens(GroupDefinition(3L), socialGroup, fiveSocial, usage = allowing))
    }

    @Test
    fun `a brand-new category group is never weakening`() {
        assertFalse(categoryWeakens(null, socialGroup, fiveSocial))
    }

    @Test
    fun `deleting a category group is weakening while it blocks, free when unused`() {
        assertTrue(groupDeleteWeakens(socialGroup, blocking, fiveSocial, emptySet(), browsers = emptySet()))
        assertFalse(groupDeleteWeakens(socialGroup, GroupUsage.UNUSED, fiveSocial, emptySet(), browsers = emptySet()))
    }

    @Test
    fun `deleting a category group with no installed member is free`() {
        assertFalse(groupDeleteWeakens(socialGroup, blocking, emptyMap(), emptySet(), browsers = emptySet()))
    }

    // ── The picker ────────────────────────────────────────────────────────

    @Test
    fun `the picker's choice replaces what it could show`() =
        assertEquals(setOf("b", "c"), membersAfterPick(setOf("a", "b"), setOf("b", "c"), setOf("a", "b", "c")))

    @Test
    fun `members the picker could not show are kept, never silently dropped`() {
        // "x" is essential or uninstalled: the picker never listed it, so it cannot have been unticked.
        assertEquals(setOf("b", "x"), membersAfterPick(setOf("a", "x"), setOf("b"), setOf("a", "b")))
    }

    // ── Which rule is the group's own ─────────────────────────────────────

    @Test
    fun `the group's own rule is its whole-group rule, not a feature rule or another group's`() {
        val feature = aBlockRule(id = 1, groupId = 3L, mode = "HARD_BLOCK", inAppFeatures = "REELS")
        val other = aBlockRule(id = 2, groupId = 4L, mode = "DELAY")
        val own = aBlockRule(id = 3, groupId = 3L, mode = "DELAY")
        assertEquals(own, ownGroupRule(3L, listOf(feature, other, own)))
        assertNull(ownGroupRule(5L, listOf(feature, other, own)))
    }
}
