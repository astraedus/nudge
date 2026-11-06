package com.astraedus.nudge.ui.screens.groups

import androidx.lifecycle.SavedStateHandle
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.aGroupRule
import com.astraedus.nudge.testing.anApp
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Device QA (groups PR 2): leaving the group editor dropped unsaved edits without a word. Back and
 * the top bar's arrow now ask "Discard changes?" when, and only when, something would be lost.
 * "Something" is measured against what the editor opened with, so these pin the baseline for both
 * a new group and a saved one, and that a change undone by hand is not an edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditorDiscardTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun viewModel(groupId: Long?): GroupEditorViewModel {
        val db = FakeRuleDatabase()
        db.seedGroup(anAppGroup("Social", id = 1), "com.example.chat", "com.example.feed")
        db.seedRule(aGroupRule(groupId = 1).copy(id = 7))
        val installed = FakeInstalledAppsRepository(
            anApp("com.example.chat"), anApp("com.example.feed"), anApp("com.example.game")
        )
        val essentials = mockk<EssentialApps>()
        every { essentials.packages(any()) } returns emptySet()
        coEvery { essentials.browsers() } returns emptySet()
        val handle = SavedStateHandle(
            if (groupId == null) emptyMap() else mapOf(GroupEditorViewModel.ARG_GROUP_ID to groupId)
        )
        return GroupEditorViewModel(
            handle, db.repository, installed, essentials, FakeNudgePreferences(),
            mockk<com.astraedus.nudge.domain.usecase.SetDayStartMinuteUseCase>(relaxed = true),
            UnconfinedTestDispatcher()
        )
    }

    private val GroupEditorViewModel.s get() = state.value

    @Test
    fun `a new group left untouched leaves without asking`() {
        val vm = viewModel(groupId = null)
        assertFalse(vm.s.isDirty)
        vm.requestLeave()
        assertFalse(vm.s.showDiscardConfirmation)
        assertTrue(vm.s.isFinished)
    }

    @Test
    fun `anything entered in a new group asks first`() {
        val vm = viewModel(groupId = null)
        vm.setName("Work")
        assertTrue(vm.s.isDirty)
        vm.requestLeave()
        assertTrue(vm.s.showDiscardConfirmation)
        assertFalse(vm.s.isFinished)
    }

    @Test
    fun `a saved group opened and left as it was leaves without asking`() {
        val vm = viewModel(groupId = 1)
        assertFalse(vm.s.isLoading)
        assertFalse(vm.s.isDirty)
        vm.requestLeave()
        assertTrue(vm.s.isFinished)
    }

    @Test
    fun `each kind of edit to a saved group counts - apps, rule, name`() {
        val vm = viewModel(groupId = 1)
        vm.removeMember("com.example.feed")
        assertTrue("removed an app", vm.s.isDirty)

        val other = viewModel(groupId = 1)
        other.updateForm { it.copy(mode = BlockMode.HARD_BLOCK) }
        assertTrue("changed the mode", other.s.isDirty)

        val renamed = viewModel(groupId = 1)
        renamed.setName("Feeds")
        assertTrue("renamed", renamed.s.isDirty)
    }

    @Test
    fun `a change undone by hand is not an edit, and trailing spaces in the name are not either`() {
        val vm = viewModel(groupId = 1)
        vm.updateForm { it.copy(mode = BlockMode.HARD_BLOCK) }
        vm.updateForm { it.copy(mode = BlockMode.DELAY) }
        vm.setName("Social ")
        assertFalse(vm.s.isDirty)
    }

    @Test
    fun `Keep editing stays, Discard leaves`() {
        val vm = viewModel(groupId = 1)
        vm.setName("Feeds")
        vm.requestLeave()
        vm.keepEditing()
        assertFalse(vm.s.showDiscardConfirmation)
        assertFalse(vm.s.isFinished)
        assertTrue("the edit survives", vm.s.isDirty)

        vm.requestLeave()
        vm.discardChanges()
        assertTrue(vm.s.isFinished)
        assertFalse(vm.s.showDiscardConfirmation)
    }
}
