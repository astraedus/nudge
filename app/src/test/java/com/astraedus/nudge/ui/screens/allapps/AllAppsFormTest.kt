package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.target
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.testing.aBlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer L1 (pure unit): what the All apps editor writes, and which saves ask a question first
 * (spec 3.4) or need the Strict Mode challenge (spec 5.6).
 */
class AllAppsFormTest {

    private fun saved(
        allowedPackages: Collection<String> = emptyList(),
        allowedGroupIds: Collection<Long> = emptyList(),
        enabled: Boolean = true,
        mode: BlockMode = BlockMode.DELAY,
        delaySeconds: Int = 15,
        scheduleDays: String? = null,
        start: Int? = null,
        end: Int? = null
    ) = aBlockRule(
        id = 5,
        mode = mode.name,
        delaySeconds = delaySeconds,
        enabled = enabled,
        allApps = true,
        scheduleDays = scheduleDays,
        scheduleStartMinute = start,
        scheduleEndMinute = end,
        exceptPackages = ExceptCsv.ofPackages(allowedPackages),
        exceptGroupIds = ExceptCsv.ofGroupIds(allowedGroupIds)
    )

    private fun BlockRule.edited(
        allowedPackages: Collection<String> = ExceptCsv.packages(exceptPackages),
        allowedGroupIds: Collection<Long> = ExceptCsv.groupIds(exceptGroupIds),
        transform: (AllAppsForm) -> AllAppsForm = { it }
    ) = transform(AllAppsForm.from(this)).toRule(this, allowedPackages, allowedGroupIds)

    // --- Form ---

    @Test
    fun `a new rule starts on, Delay 15s, all the time, and saves without asking`() {
        val form = AllAppsForm.from(null)
        assertEquals(AllAppsForm(), form)
        assertTrue(form.enabled)
        assertEquals(BlockMode.DELAY, form.mode)
        assertFalse(form.scheduled)
        assertFalse(form.confirmsOnSave)
    }

    @Test
    fun `only all the time with Hard block, while on, asks before saving`() {
        for (mode in BlockMode.entries) {
            for (scheduled in listOf(false, true)) {
                for (firstUnlock in listOf(false, true)) {
                    for (enabled in listOf(false, true)) {
                        val form = AllAppsForm(
                            enabled = enabled,
                            mode = mode,
                            scheduled = scheduled,
                            firstUnlock = firstUnlock
                        )
                        // A first-unlock rule is not "all the time", so that question would be a lie.
                        val expected = enabled && !scheduled && !firstUnlock &&
                            mode == BlockMode.HARD_BLOCK
                        assertEquals(
                            "$mode scheduled=$scheduled firstUnlock=$firstUnlock enabled=$enabled",
                            expected,
                            form.confirmsOnSave
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the wait time shows for every mode with a length, Breathing included`() {
        val shown = BlockMode.entries.filter { AllAppsForm(mode = it).showsWaitTime }.toSet()
        // Breathing runs for delaySeconds (BreathingContent), so it has a wait like Delay and Hold.
        assertEquals(setOf(BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING), shown)
    }

    @Test
    fun `the group editor shows the wait time through the same shared predicate`() {
        // The two editors once disagreed on Breathing; both must ask the one function.
        val src = java.io.File(
            "src/main/java/com/astraedus/nudge/ui/screens/groups/GroupEditorScreen.kt"
        ).readText()
        assertTrue(src.contains("if (showsWaitTime(form.mode))"))
        assertFalse(src.contains("form.mode != BlockMode.HARD_BLOCK"))
    }

    @Test
    fun `days and times needs a day`() {
        assertTrue(AllAppsForm(scheduled = false, scheduleDays = emptySet()).isComplete)
        assertFalse(AllAppsForm(scheduled = true, scheduleDays = emptySet()).isComplete)
        assertTrue(AllAppsForm(scheduled = true, scheduleDays = setOf(6, 7)).isComplete)
    }

    // --- toRule ---

    @Test
    fun `a saved rule reads back and writes back the same`() {
        val rule = saved(
            allowedPackages = listOf("com.example.b", "com.example.a"),
            allowedGroupIds = listOf(3L, 1L),
            enabled = false,
            mode = BlockMode.HOLD,
            delaySeconds = 30,
            scheduleDays = "1,3,5",
            start = 600,
            end = 720
        )
        assertEquals(rule, rule.edited())
    }

    @Test
    fun `the target is always All apps, whatever the row held`() {
        val corrupt = saved().copy(packageName = "com.example.a", groupId = 4)
        val written = corrupt.edited()
        assertNull(written.packageName)
        assertNull(written.groupId)
        assertTrue(written.allApps)
        assertTrue(written.target() is RuleTarget.AllApps)
        assertTrue(AllAppsForm().toRule(null, emptyList(), emptyList()).target() is RuleTarget.AllApps)
    }

    @Test
    fun `keep the pass on short trips is forced off`() {
        assertFalse(saved().copy(keepPassOnShortTrips = true).edited().keepPassOnShortTrips)
        assertFalse(AllAppsForm().toRule(null, emptyList(), emptyList()).keepPassOnShortTrips)
    }

    @Test
    fun `the allowed apps and groups are written whole`() {
        val written = AllAppsForm().toRule(null, listOf("com.example.gone", "com.example.a"), listOf(2L))
        assertEquals(setOf("com.example.gone", "com.example.a"), ExceptCsv.packages(written.exceptPackages))
        assertEquals(setOf(2L), ExceptCsv.groupIds(written.exceptGroupIds))
        val none = AllAppsForm().toRule(null, emptyList(), emptyList())
        assertEquals(emptySet<String>(), ExceptCsv.packages(none.exceptPackages))
        assertEquals(emptySet<Long>(), ExceptCsv.groupIds(none.exceptGroupIds))
    }

    @Test
    fun `fields the editor does not show survive a save`() {
        val rule = saved().copy(dailyLimitMinutes = 30, autoKickAfter = 8, createdAt = 1234L)
        val written = rule.edited { it.copy(mode = BlockMode.HARD_BLOCK) }
        assertEquals(30, written.dailyLimitMinutes)
        assertEquals(8, written.autoKickAfter)
        assertEquals(1234L, written.createdAt)
        assertEquals(rule.id, written.id)
    }

    @Test
    fun `all the time clears the window, days and times writes it`() {
        val window = AllAppsForm(scheduled = true, scheduleDays = setOf(5, 1), startMinute = 480, endMinute = 960)
            .toRule(null, emptyList(), emptyList())
        assertEquals("1,5", window.scheduleDays)
        assertEquals(480, window.scheduleStartMinute)
        assertEquals(960, window.scheduleEndMinute)
        val always = window.edited { it.copy(scheduled = false) }
        assertNull(always.scheduleDays)
        assertNull(always.scheduleStartMinute)
        assertNull(always.scheduleEndMinute)
    }

    @Test
    fun `after first unlock writes firstUnlockMinutes and clears the time window`() {
        val rule = AllAppsForm(
            firstUnlock = true,
            firstUnlockMinutes = 30,
            scheduleDays = setOf(1, 2, 3, 4, 5),
            startMinute = 540,
            endMinute = 1020
        ).toRule(null, emptyList(), emptyList())
        assertEquals(30, rule.firstUnlockMinutes)
        assertNull(rule.scheduleStartMinute)
        assertNull(rule.scheduleEndMinute)
    }

    @Test
    fun `days and times writes the window and clears firstUnlockMinutes`() {
        val rule = AllAppsForm(
            scheduled = true,
            scheduleDays = setOf(5, 1),
            startMinute = 480,
            endMinute = 960,
            firstUnlockMinutes = 45
        ).toRule(null, emptyList(), emptyList())
        assertEquals("1,5", rule.scheduleDays)
        assertEquals(480, rule.scheduleStartMinute)
        assertEquals(960, rule.scheduleEndMinute)
        assertNull(rule.firstUnlockMinutes)
    }

    @Test
    fun `from round-trips both days and times and first unlock`() {
        val daysTimes = saved(
            mode = BlockMode.HARD_BLOCK,
            scheduleDays = "1,3,5",
            start = 540,
            end = 1020
        )
        val firstUnlock = saved(mode = BlockMode.HARD_BLOCK)
            .copy(firstUnlockMinutes = 60, scheduleDays = "2,4")

        assertFalse(AllAppsForm.from(daysTimes).firstUnlock)
        assertEquals(daysTimes, daysTimes.edited())

        assertTrue(AllAppsForm.from(firstUnlock).firstUnlock)
        assertEquals(firstUnlock, firstUnlock.edited())
    }

    // --- Strict Mode ---

    @Test
    fun `a new rule is never gated`() {
        val rule = AllAppsForm(enabled = false).toRule(null, listOf("com.example.a"), listOf(1L))
        assertFalse(allAppsSaveWeakens(null, rule))
    }

    @Test
    fun `allowing more is weakening, allowing less is not`() {
        val old = saved(allowedPackages = listOf("com.example.a"), allowedGroupIds = listOf(1L))
        assertTrue(allAppsSaveWeakens(old, old.edited(allowedPackages = listOf("com.example.a", "com.example.b"))))
        assertTrue(allAppsSaveWeakens(old, old.edited(allowedGroupIds = listOf(1L, 2L))))
        assertFalse(allAppsSaveWeakens(old, old.edited(allowedPackages = emptyList())))
        assertFalse(allAppsSaveWeakens(old, old.edited(allowedGroupIds = emptyList())))
    }

    @Test
    fun `turning it off, softening it or shortening its window is weakening`() {
        val old = saved(mode = BlockMode.HARD_BLOCK, delaySeconds = 30)
        assertTrue(allAppsSaveWeakens(old, old.edited { it.copy(enabled = false) }))
        assertTrue(allAppsSaveWeakens(old, old.edited { it.copy(mode = BlockMode.DELAY) }))
        assertTrue(allAppsSaveWeakens(old, old.edited { it.copy(waitSeconds = 5) }))
        assertTrue(allAppsSaveWeakens(old, old.edited { it.copy(scheduled = true) }))
    }

    @Test
    fun `making it stricter never asks`() {
        val old = saved(mode = BlockMode.DELAY, scheduleDays = "1,2,3,4,5", start = 540, end = 1020)
        assertFalse(allAppsSaveWeakens(old, old.edited { it.copy(mode = BlockMode.HARD_BLOCK) }))
        assertFalse(allAppsSaveWeakens(old, old.edited { it.copy(scheduled = false) }))
        assertFalse(allAppsSaveWeakens(old, old.edited { it.copy(waitSeconds = 60) }))
        assertFalse(allAppsSaveWeakens(old, old.edited()))
    }

    @Test
    fun `a rule that was off protected nothing, so editing it is never gated`() {
        val old = saved(enabled = false, mode = BlockMode.HARD_BLOCK)
        assertFalse(allAppsSaveWeakens(old, old.edited(allowedPackages = listOf("com.example.a"))))
        assertFalse(allAppsSaveWeakens(old, old.edited { it.copy(enabled = true, mode = BlockMode.DELAY) }))
    }

    @Test
    fun `deleting is gated only while the rule is on`() {
        assertTrue(allAppsDeleteWeakens(saved(enabled = true)))
        assertFalse(allAppsDeleteWeakens(saved(enabled = false)))
    }
}
