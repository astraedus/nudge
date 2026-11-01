package com.astraedus.nudge.ui.screens.groups

import androidx.lifecycle.SavedStateHandle
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.service.EssentialApps
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

    private fun app(pkg: String) = InstalledAppsRepository.AppInfo(pkg, pkg, null)

    private fun viewModel(groupId: Long?): GroupEditorViewModel {
        val repository = mockk<BlockRuleRepository>(relaxed = true)
        every { repository.getAllGroups() } returns flowOf(listOf(AppGroup(id = 1, name = "Social")))
        every { repository.getGroupMembers(1) } returns flowOf(
            listOf(AppGroupMember(1, "com.example.chat"), AppGroupMember(1, "com.example.feed"))
        )
        every { repository.getAllRules() } returns flowOf(
            listOf(BlockRule(id = 7, groupId = 1, mode = BlockMode.DELAY.name, delaySeconds = 15))
        )
        val installed = mockk<InstalledAppsRepository>()
        coEvery { installed.getInstalledApps() } returns
            listOf(app("com.example.chat"), app("com.example.feed"), app("com.example.game"))
        val essentials = mockk<EssentialApps>()
        every { essentials.packages(any()) } returns emptySet()
        val prefs = mockk<NudgePreferences>()
        every { prefs.isStrictModeEnabled } returns flowOf(false)
        every { prefs.strictModeChallengeLength } returns flowOf(StrictModeChallenge.DEFAULT_LENGTH)
        val handle = SavedStateHandle(
            if (groupId == null) emptyMap() else mapOf(GroupEditorViewModel.ARG_GROUP_ID to groupId)
        )
        return GroupEditorViewModel(handle, repository, installed, essentials, prefs, UnconfinedTestDispatcher())
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
