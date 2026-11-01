package com.astraedus.nudge.ui.screens.allapps

import androidx.lifecycle.SavedStateHandle
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.components.PickerSelection
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.CHAT
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.DIALER
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.GAME
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.GONE
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.LAUNCHER
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.SOCIAL
import com.astraedus.nudge.ui.screens.allapps.AllAppsFixtures.WORK
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Layer L1 (view model over a mocked repository): the editor's flows end to end. Load, the picker's
 * Done, the "Block N apps all the time?" question, the one write, and the Strict Mode gate on a save
 * that allows more or a delete of a rule that is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AllAppsEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = mockk<BlockRuleRepository>(relaxed = true)

    private fun viewModel(saved: BlockRule? = null, strictOn: Boolean = false): AllAppsEditorViewModel {
        every { repository.getAllRules() } returns flowOf(listOfNotNull(saved))
        every { repository.getAllGroups() } returns flowOf(AllAppsFixtures.groups)
        every { repository.getAllGroupMembers() } returns flowOf(AllAppsFixtures.members)
        if (saved != null) coEvery { repository.getRule(saved.id) } returns saved
        val installed = mockk<InstalledAppsRepository>()
        coEvery { installed.getInstalledApps() } returns AllAppsFixtures.installed
        coEvery { installed.resolveAppName(any()) } answers { firstArg<String>().substringAfterLast('.') }
        coEvery { installed.resolveIcon(any()) } returns null
        val essentials = mockk<EssentialApps>()
        every { essentials.packages(any()) } returns setOf(DIALER, LAUNCHER)
        val facts = FakePackageFacts(launchable = AllAppsFixtures.installed.mapTo(HashSet()) { it.packageName })
        val prefs = mockk<NudgePreferences>()
        every { prefs.isStrictModeEnabled } returns flowOf(strictOn)
        every { prefs.strictModeChallengeLength } returns flowOf(StrictModeChallenge.DEFAULT_LENGTH)
        val handle = SavedStateHandle(
            if (saved == null) emptyMap() else mapOf(AllAppsEditorViewModel.ARG_RULE_ID to saved.id)
        )
        return AllAppsEditorViewModel(
            handle, repository, installed, essentials, facts, prefs, UnconfinedTestDispatcher()
        )
    }

    private val AllAppsEditorViewModel.s get() = state.value

    private fun saved(
        allowedPackages: Collection<String> = emptyList(),
        allowedGroupIds: Collection<Long> = emptyList(),
        enabled: Boolean = true
    ) = AllAppsFixtures.allAppsRule(
        id = 20,
        allowedPackages = allowedPackages,
        allowedGroupIds = allowedGroupIds,
        enabled = enabled
    )

    private fun writtenRule(): BlockRule {
        val written = slot<BlockRule>()
        coVerify(exactly = 1) { repository.saveAllAppsRule(capture(written)) }
        return written.captured
    }

    @Test
    fun `a new rule opens on the defaults, loaded, with only launchable essentials in the sheet`() {
        val vm = viewModel()
        assertFalse(vm.s.isLoading)
        assertNull(vm.s.ruleId)
        assertEquals(AllAppsForm(), vm.s.form)
        assertEquals(5, vm.s.blockedCount)
        assertEquals(listOf(DIALER), vm.s.device.alwaysAllowed.map { it.packageName })
        assertFalse(vm.s.isDirty)
    }

    @Test
    fun `saving the defaults writes one All apps rule without asking`() {
        val vm = viewModel()
        vm.save()
        assertFalse(vm.s.showSaveConfirmation)
        val rule = writtenRule()
        assertTrue(rule.allApps)
        assertNull(rule.packageName)
        assertNull(rule.groupId)
        assertFalse(rule.keepPassOnShortTrips)
        assertTrue(vm.s.isFinished)
    }

    @Test
    fun `Hard block all the time asks first, and Cancel writes nothing`() {
        val vm = viewModel()
        vm.updateForm { it.copy(mode = BlockMode.HARD_BLOCK) }
        vm.save()
        assertTrue(vm.s.showSaveConfirmation)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
        vm.dismissSaveConfirmation()
        assertFalse(vm.s.showSaveConfirmation)
        assertFalse(vm.s.isFinished)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
        vm.save()
        vm.confirmSave()
        assertEquals(BlockMode.HARD_BLOCK.name, writtenRule().mode)
    }

    @Test
    fun `the picker's Done keeps allowed apps it could not show and replaces the groups`() {
        val vm = viewModel(saved(allowedPackages = listOf(GONE, CHAT), allowedGroupIds = listOf(WORK)))
        vm.openPicker()
        vm.applyPicked(PickerSelection(packages = setOf(GAME), groupIds = setOf(SOCIAL)))
        assertFalse(vm.s.showPicker)
        assertEquals(setOf(GONE, GAME), vm.s.allowedPackages)
        assertEquals(setOf(SOCIAL), vm.s.allowedGroupIds)
    }

    @Test
    fun `strict on - allowing another app waits for the challenge`() {
        val vm = viewModel(saved(allowedPackages = listOf(CHAT)), strictOn = true)
        vm.applyPicked(PickerSelection(packages = setOf(CHAT, GAME), groupIds = emptySet()))
        vm.save()
        assertNotNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
        vm.verifyChallenge(vm.challenge.value!!.target)
        assertEquals(setOf(CHAT, GAME), ExceptCsv.packages(writtenRule().exceptPackages))
        assertTrue(vm.s.isFinished)
    }

    @Test
    fun `strict on - removing an allowed app saves straight away`() {
        val vm = viewModel(saved(allowedPackages = listOf(CHAT)), strictOn = true)
        vm.removeApp(CHAT)
        vm.save()
        assertNull(vm.challenge.value)
        assertEquals(emptySet<String>(), ExceptCsv.packages(writtenRule().exceptPackages))
    }

    @Test
    fun `strict on - deleting a rule that is on waits for the challenge`() {
        val vm = viewModel(saved(), strictOn = true)
        vm.requestDelete()
        vm.confirmDelete()
        assertNotNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.deleteRule(any()) }
        vm.cancelChallenge()
        coVerify(exactly = 0) { repository.deleteRule(any()) }
        assertFalse(vm.s.isFinished)
    }

    @Test
    fun `deleting a rule that is off never asks`() {
        val vm = viewModel(saved(enabled = false), strictOn = true)
        vm.confirmDelete()
        assertNull(vm.challenge.value)
        coVerify(exactly = 1) { repository.deleteRule(20) }
        assertTrue(vm.s.isFinished)
    }

    @Test
    fun `an id that is not an All apps rule closes the editor`() {
        val other = BlockRule(id = 20, packageName = CHAT, mode = BlockMode.DELAY.name)
        val vm = viewModel(other)
        assertTrue(vm.s.isFinished)
    }

    @Test
    fun `back with an unsaved edit asks, without one leaves`() {
        val vm = viewModel(saved())
        vm.removeGroup(WORK)
        vm.requestLeave()
        assertFalse(vm.s.showDiscardConfirmation)
        assertTrue(vm.s.isFinished)

        val edited = viewModel(saved())
        edited.setEnabled(false)
        edited.requestLeave()
        assertTrue(edited.s.showDiscardConfirmation)
        edited.keepEditing()
        assertFalse(edited.s.isFinished)
        edited.requestLeave()
        edited.discardChanges()
        assertTrue(edited.s.isFinished)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
    }
}
