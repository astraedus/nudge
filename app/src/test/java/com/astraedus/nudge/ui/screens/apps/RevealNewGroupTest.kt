package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Device QA (PR 2): the first group saved from Manage Apps "vanished". The list keeps its first
 * visible row in place by key; with no groups that row is "Block several apps", so the new group's
 * rows were inserted above the viewport. The list's top is now one stable row ([ManageAppsRowsTest]),
 * and a newly added group also scrolls the list back to it; these pin WHICH group and WHEN (never on
 * an ordinary open, which would yank the user's scroll).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RevealNewGroupTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val social = AppGroup(id = 1, name = "Social")
    private val browsers = AppGroup(id = 2, name = "Browsers")
    private val games = AppGroup(id = 3, name = "games")

    // ── L1: the pure decision ──────────────────────────────────────────────

    @Test
    fun `the first load reveals nothing, even with groups`() {
        assertNull(AppListViewModel.newlyAddedGroupId(known = null, groups = listOf(social)))
    }

    @Test
    fun `a group that was not there before is revealed`() {
        assertEquals(1L, AppListViewModel.newlyAddedGroupId(emptySet(), listOf(social)))
        assertEquals(2L, AppListViewModel.newlyAddedGroupId(setOf(1L), listOf(social, browsers)))
    }

    @Test
    fun `no new id - a rename, a delete, a toggle - reveals nothing`() {
        assertNull(AppListViewModel.newlyAddedGroupId(setOf(1L, 2L), listOf(social.copy(name = "Feeds"), browsers)))
        assertNull(AppListViewModel.newlyAddedGroupId(setOf(1L, 2L), listOf(social)))
    }

    @Test
    fun `two new groups at once reveal the first by name, as the list sorts them`() {
        assertEquals(3L, AppListViewModel.newlyAddedGroupId(setOf(1L), listOf(social, games, browsers.copy(name = "x"))))
    }

    // ── The ViewModel: survives the trip to the editor ─────────────────────

    private val groups = MutableStateFlow<List<AppGroup>>(emptyList())

    private fun viewModel(): AppListViewModel {
        val repository = mockk<BlockRuleRepository>(relaxed = true)
        every { repository.getAllGroups() } returns groups
        every { repository.getAllGroupMembers() } returns flowOf(emptyList<AppGroupMember>())
        every { repository.getAllRules() } returns flowOf(emptyList<BlockRule>())
        val prefs = mockk<NudgePreferences>()
        every { prefs.isStrictModeEnabled } returns flowOf(false)
        every { prefs.strictModeChallengeLength } returns flowOf(StrictModeChallenge.DEFAULT_LENGTH)
        val installed = mockk<InstalledAppsRepository>()
        coEvery { installed.getInstalledApps() } returns emptyList()
        return AppListViewModel(installed, repository, prefs, FakeEssentialPackages())
    }

    @Test
    fun `the first group saved is revealed, once`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertNull(vm.uiState.value.revealGroupId)

        groups.value = listOf(social) // saved in the editor

        assertEquals(1L, vm.uiState.value.revealGroupId)
        vm.onGroupRevealed()
        assertNull(vm.uiState.value.revealGroupId)
    }

    @Test
    fun `opening Manage Apps with groups already there reveals nothing`() = runTest {
        groups.value = listOf(social, browsers)
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(2, vm.uiState.value.sections.groups.size)
        assertNull(vm.uiState.value.revealGroupId)
    }

    @Test
    fun `a group added while the screen was unsubscribed is still revealed on return`() = runTest {
        val vm = viewModel()
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        first.cancel() // the editor is on top; collection stops
        testScheduler.advanceTimeBy(10_000) // past WhileSubscribed(5000): upstream stops too

        groups.value = listOf(social)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(1L, vm.uiState.value.revealGroupId)
    }
}
