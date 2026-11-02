package com.astraedus.nudge.ui.screens.allapps

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.testing.aGroupRule
import com.astraedus.nudge.ui.components.PickerGroup
import com.astraedus.nudge.ui.components.rule.RuleSummary
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.CHAT
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.DELETED
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.DIALER
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.FEED
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.GAME
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.GONE
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.MAIL
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.NOTES
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.SOCIAL
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.WORK
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.appRule
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.device
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.tables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer L1 (pure unit): the editor's state. What counts as an unsaved edit (so Back asks "Discard
 * changes?" only when something would be lost), what Save writes, when Save is enabled, and the rows
 * "Except these" shows.
 */
class AllAppsEditorStateTest {

    private fun state(
        allowedPackages: Set<String> = emptySet(),
        allowedGroupIds: Set<Long> = emptySet(),
        form: AllAppsForm = AllAppsForm(),
        baseline: AllAppsDraft? = AllAppsDraft(form, allowedPackages, allowedGroupIds),
        ruleId: Long? = 20
    ) = AllAppsEditorState(
        isLoading = false,
        ruleId = ruleId,
        form = form,
        allowedPackages = allowedPackages,
        allowedGroupIds = allowedGroupIds,
        device = device,
        tables = tables(),
        baseline = baseline
    )

    // --- isDirty ---

    @Test
    fun `nothing changed is not an edit`() {
        assertFalse(state(allowedPackages = setOf(CHAT), allowedGroupIds = setOf(WORK)).isDirty)
    }

    @Test
    fun `a changed form, an allowed app or an allowed group is an edit`() {
        val s = state(allowedPackages = setOf(CHAT))
        assertTrue(s.copy(form = s.form.copy(mode = BlockMode.HOLD)).isDirty)
        assertTrue(s.copy(allowedPackages = setOf(CHAT, GAME)).isDirty)
        assertTrue(s.copy(allowedPackages = emptySet()).isDirty)
        assertTrue(s.copy(allowedGroupIds = setOf(SOCIAL)).isDirty)
    }

    @Test
    fun `a change undone by hand is not an edit`() {
        val s = state()
        val changed = s.copy(form = s.form.copy(enabled = false))
        assertFalse(changed.copy(form = changed.form.copy(enabled = true)).isDirty)
    }

    @Test
    fun `a deleted group dropping off the list is not an edit`() {
        assertFalse(state(allowedGroupIds = setOf(WORK, DELETED)).copy(allowedGroupIds = setOf(WORK)).isDirty)
        assertFalse(state(allowedGroupIds = setOf(DELETED)).isDirty)
    }

    @Test
    fun `nothing is dirty while loading or once finished`() {
        assertFalse(state(baseline = null).copy(allowedPackages = setOf(CHAT)).isDirty)
        assertFalse(state().copy(allowedPackages = setOf(CHAT), isFinished = true).isDirty)
    }

    // --- draft ---

    @Test
    fun `a save drops stale group ids and keeps uninstalled allowed apps`() {
        val draft = state(allowedPackages = setOf(GONE, CHAT), allowedGroupIds = setOf(DELETED, SOCIAL)).draft
        assertEquals(setOf(GONE, CHAT), draft.allowedPackages)
        assertEquals(setOf(SOCIAL), draft.allowedGroupIds)
    }

    // --- canSave ---

    @Test
    fun `save needs a loaded, complete rule that blocks something`() {
        assertTrue(state().canSave)
        assertFalse(state().copy(isLoading = true).canSave)
        assertFalse(state(form = AllAppsForm(scheduled = true, scheduleDays = emptySet())).canSave)
        assertFalse(state(allowedPackages = setOf(CHAT, FEED, GAME, MAIL, NOTES)).canSave)
        assertEquals(0, state(allowedPackages = setOf(CHAT, FEED, GAME, MAIL, NOTES)).blockedCount)
    }

    @Test
    fun `the count follows the draft live`() {
        assertEquals(5, state().blockedCount)
        assertEquals(3, state(allowedGroupIds = setOf(WORK)).blockedCount)
        assertEquals(5, state(allowedGroupIds = setOf(DELETED)).blockedCount)
    }

    // --- Except these ---

    @Test
    fun `groups come first, then apps, each in the order added, hiding what is gone`() {
        val s = state(
            allowedPackages = linkedSetOf(GAME, GONE, CHAT, DIALER),
            allowedGroupIds = linkedSetOf(SOCIAL, DELETED, WORK)
        ).copy(tables = tables(listOf(aGroupRule(SOCIAL).copy(id = 11), appRule(id = 10, pkg = GAME))))
        val rows = exceptRows(s)
        assertEquals(listOf(SOCIAL, WORK), rows.groups.map { it.groupId })
        assertEquals(listOf(GAME, CHAT, DIALER), rows.apps.map { it.packageName })
        assertEquals(RuleSummary.groupTitle("Work", 3), rows.groups[1].title)
        assertEquals("1 still blocked by another rule", rows.groups[0].stillBlocked)
        assertNull(rows.groups[1].stillBlocked)
        assertEquals("Also blocked by its own rule", rows.apps[0].alsoBlocked)
        assertNull(rows.apps[1].alsoBlocked)
    }

    @Test
    fun `the rule being edited never names itself`() {
        val s = state(allowedPackages = setOf(GAME)).copy(
            tables = tables(listOf(AllAppsFixtures.allAppsRule(id = 20)))
        )
        assertNull(exceptRows(s).apps.single().alsoBlocked)
    }

    @Test
    fun `the picker offers every group by name with its member count`() {
        assertEquals(
            listOf(PickerGroup(SOCIAL, "Social", 1), PickerGroup(WORK, "Work", 3)),
            state().pickerGroups
        )
    }
}
