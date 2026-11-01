package com.astraedus.nudge.ui.screens.groups

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.lock.GroupUsage
import com.astraedus.nudge.domain.model.BlockMode
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

    private fun groupRule(
        mode: String = "DELAY",
        delaySeconds: Int = 15,
        enabled: Boolean = true,
        dailyLimitMinutes: Int? = null
    ) = BlockRule(
        id = 7L,
        groupId = 3L,
        mode = mode,
        delaySeconds = delaySeconds,
        enabled = enabled,
        dailyLimitMinutes = dailyLimitMinutes
    )

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
        val saved = groupRule(mode = "HOLD", delaySeconds = 30, enabled = false, dailyLimitMinutes = 45)
            .copy(scheduleDays = "1,3,5", scheduleStartMinute = 480, scheduleEndMinute = 1080)

        val back = GroupRuleForm.from(saved).toRule(saved)

        assertEquals(saved, back)
    }

    @Test
    fun `fields the editor does not show survive a save`() {
        val saved = groupRule().copy(
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
        val saved = groupRule(mode = "HARD_BLOCK", dailyLimitMinutes = 30)

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
    fun `a NONE rule shows as Delay, because a group always blocks with something`() {
        assertEquals(BlockMode.DELAY, GroupRuleForm.from(groupRule(mode = "NONE")).mode)
    }

    @Test
    fun `days and times with no day picked cannot be saved`() {
        assertFalse(GroupRuleForm(scheduled = true, scheduleDays = emptySet()).isComplete)
        assertTrue(GroupRuleForm(scheduled = false, scheduleDays = emptySet()).isComplete)
    }

    // ── Strict Mode on save (spec 5.6) ────────────────────────────────────

    private fun weakens(
        oldMembers: Set<String>?,
        newMembers: Set<String>,
        oldRule: BlockRule?,
        newRule: BlockRule,
        usage: GroupUsage = blocking
    ) = groupSaveWeakens(3L, oldMembers, newMembers, usage, oldRule, newRule)

    @Test
    fun `creating a group is never weakening`() =
        assertFalse(weakens(null, setOf("a"), null, groupRule(mode = "BREATHING")))

    @Test
    fun `removing an app from a blocking group is weakening`() =
        assertTrue(weakens(setOf("a", "b"), setOf("a"), groupRule(), groupRule()))

    @Test
    fun `adding an app to a blocking group is not`() =
        assertFalse(weakens(setOf("a"), setOf("a", "b"), groupRule(), groupRule()))

    @Test
    fun `removing an app from a group that blocks nothing is free`() =
        assertFalse(
            weakens(
                setOf("a", "b"), setOf("a"),
                groupRule(enabled = false), groupRule(enabled = false),
                usage = GroupUsage.UNUSED
            )
        )

    @Test
    fun `turning the group's blocking off is weakening`() =
        assertTrue(weakens(setOf("a"), setOf("a"), groupRule(), groupRule(enabled = false)))

    @Test
    fun `a shorter wait or a softer mode is weakening`() {
        assertTrue(weakens(setOf("a"), setOf("a"), groupRule(delaySeconds = 30), groupRule(delaySeconds = 15)))
        assertTrue(weakens(setOf("a"), setOf("a"), groupRule(mode = "HARD_BLOCK"), groupRule(mode = "DELAY")))
    }

    @Test
    fun `a stricter edit is free`() =
        assertFalse(weakens(setOf("a"), setOf("a"), groupRule(mode = "DELAY"), groupRule(mode = "HARD_BLOCK")))

    @Test
    fun `editing a rule that was off, while switching it on, is free whatever it softens`() =
        assertFalse(
            weakens(
                setOf("a"), setOf("a"),
                groupRule(mode = "HARD_BLOCK", enabled = false), groupRule(mode = "BREATHING"),
                usage = GroupUsage.UNUSED
            )
        )

    // ── Strict Mode on delete ─────────────────────────────────────────────

    @Test
    fun `deleting a blocking group is weakening, an unused one is free`() {
        assertTrue(groupDeleteWeakens(3L, setOf("a"), blocking))
        assertFalse(groupDeleteWeakens(3L, setOf("a"), GroupUsage.UNUSED))
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
        val feature = BlockRule(id = 1, groupId = 3L, mode = "HARD_BLOCK", inAppFeatures = "REELS")
        val other = BlockRule(id = 2, groupId = 4L, mode = "DELAY")
        val own = BlockRule(id = 3, groupId = 3L, mode = "DELAY")
        assertEquals(own, ownGroupRule(3L, listOf(feature, other, own)))
        assertNull(ownGroupRule(5L, listOf(feature, other, own)))
    }
}
