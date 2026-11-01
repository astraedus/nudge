package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Every switch on Manage Apps obeys one Strict Mode contract (the one Active Rules had, before it
 * merged in here): turning a block OFF is a weakening and takes the typed challenge, from an app row
 * and from a group row alike; turning one ON, or creating a rule, never does. (Regression: the
 * switch used to write straight through, defeating the commitment lock.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelStrictModeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private lateinit var repository: BlockRuleRepository

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        every { repository.getAllRules() } returns flowOf(emptyList())
        every { repository.getAllGroups() } returns flowOf(emptyList())
        every { repository.getAllGroupMembers() } returns flowOf(emptyList())
    }

    /** An app row as the sections build it: no rule, or a rule that is on / off. */
    private fun appRow(hasRule: Boolean, on: Boolean = false) = AppRow(
        appInfo = InstalledAppsRepository.AppInfo("com.example", "Example", icon = null),
        summary = if (hasRule) "Delay 15s" else null,
        isOn = on
    )

    private fun viewModel(
        strictOn: Boolean,
        installedApps: List<InstalledAppsRepository.AppInfo> = emptyList(),
        essentials: Set<String> = emptySet()
    ): AppListViewModel {
        val prefs = mockk<NudgePreferences>()
        every { prefs.isStrictModeEnabled } returns flowOf(strictOn)
        every { prefs.strictModeChallengeLength } returns flowOf(StrictModeChallenge.DEFAULT_LENGTH)
        val installed = mockk<InstalledAppsRepository>()
        coEvery { installed.getInstalledApps() } returns installedApps
        return AppListViewModel(installed, repository, prefs, FakeEssentialPackages(essentials))
    }

    @Test
    fun `strict on - switching a block off waits for the challenge and writes nothing`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleApp(appRow(hasRule = true, on = true))

        assertNotNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.setEnabledForPackage(any(), any()) }
    }

    @Test
    fun `strict on - the right challenge answer disables the block`() = runTest {
        val vm = viewModel(strictOn = true)
        vm.toggleApp(appRow(hasRule = true, on = true))

        vm.verifyChallenge(vm.challenge.value!!.target)

        coVerify(exactly = 1) { repository.setEnabledForPackage("com.example", false) }
        assertNull(vm.challenge.value)
    }

    @Test
    fun `strict on - a wrong answer or cancel never disables the block`() = runTest {
        val vm = viewModel(strictOn = true)
        vm.toggleApp(appRow(hasRule = true, on = true))

        vm.verifyChallenge("not-the-code")
        coVerify(exactly = 0) { repository.setEnabledForPackage(any(), any()) }

        vm.cancelChallenge()
        assertNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.setEnabledForPackage(any(), any()) }
    }

    @Test
    fun `strict off - switching a block off writes immediately`() = runTest {
        val vm = viewModel(strictOn = false)

        vm.toggleApp(appRow(hasRule = true, on = true))

        assertNull(vm.challenge.value)
        coVerify(exactly = 1) { repository.setEnabledForPackage("com.example", false) }
    }

    @Test
    fun `switching a block on is never gated, even with strict on`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleApp(appRow(hasRule = true, on = false))

        assertNull(vm.challenge.value)
        coVerify(exactly = 1) { repository.setEnabledForPackage("com.example", true) }
    }

    @Test
    fun `creating a new rule is never gated, even with strict on`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleApp(appRow(hasRule = false))

        assertNull(vm.challenge.value)
        coVerify(exactly = 1) {
            repository.addRule(match<BlockRule> {
                it.packageName == "com.example" && it.groupId == null && it.mode == "DELAY" &&
                    it.delaySeconds == 15 && it.enabled
            })
        }
    }

    @Test
    fun `strict on - an app row that is off but has a rule never creates a second rule`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleApp(appRow(hasRule = true, on = false))

        coVerify(exactly = 0) { repository.addRule(any<BlockRule>()) }
    }

    // ── Group rows (spec PR 2) ────────────────────────────────────────────

    private fun groupRow(rule: BlockRule?) = GroupRow(
        groupId = 3L,
        title = "Social · 2 apps",
        summary = "Delay 15s",
        blocking = rule?.enabled == true,
        rule = rule
    )

    private val groupRule = BlockRule(id = 9L, groupId = 3L, mode = "DELAY", enabled = true)

    @Test
    fun `strict on - switching a group off waits for the challenge, then turns its rule off`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleGroup(groupRow(groupRule))

        assertNotNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.updateRule(any()) }

        vm.verifyChallenge(vm.challenge.value!!.target)
        coVerify(exactly = 1) { repository.updateRule(groupRule.copy(enabled = false)) }
    }

    @Test
    fun `switching a group back on is never gated`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleGroup(groupRow(groupRule.copy(enabled = false)))

        assertNull(vm.challenge.value)
        coVerify(exactly = 1) { repository.updateRule(groupRule.copy(enabled = true)) }
    }

    @Test
    fun `switching on a group with no rule gives it Delay 15s, ungated`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleGroup(groupRow(null))

        assertNull(vm.challenge.value)
        coVerify(exactly = 1) {
            repository.addRule(match<BlockRule> {
                it.groupId == 3L && it.packageName == null && it.mode == "DELAY" &&
                    it.delaySeconds == 15 && it.enabled
            })
        }
    }

    // -- All apps rows (spec PR 4): off is a weakening, like a group row ---------------------

    private val allAppsRule = BlockRule(id = 8, allApps = true, mode = "HARD_BLOCK", enabled = true)

    private fun allAppsRow(rule: BlockRule) =
        AllAppsRuleItem(rule.id, "All apps", "Hard Block", rule.enabled, rule)

    @Test
    fun `strict on - switching an All apps rule off waits for the challenge`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleAllApps(allAppsRow(allAppsRule))

        assertNotNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
        vm.verifyChallenge(vm.challenge.value!!.target)
        coVerify(exactly = 1) { repository.saveAllAppsRule(allAppsRule.copy(enabled = false)) }
    }

    @Test
    fun `strict off - switching an All apps rule off writes immediately`() = runTest {
        val vm = viewModel(strictOn = false)

        vm.toggleAllApps(allAppsRow(allAppsRule))

        assertNull(vm.challenge.value)
        coVerify(exactly = 1) { repository.saveAllAppsRule(allAppsRule.copy(enabled = false)) }
    }

    @Test
    fun `switching an All apps rule back on is never gated`() = runTest {
        val vm = viewModel(strictOn = true)
        val delayRule = allAppsRule.copy(mode = "DELAY", delaySeconds = 15)

        vm.toggleAllApps(allAppsRow(delayRule.copy(enabled = false)))

        assertNull(vm.challenge.value)
        assertNull(vm.allAppsSwitchOn.value)
        coVerify(exactly = 1) { repository.saveAllAppsRule(delayRule) }
    }

    // -- The switch asks what the editor's Save asks (orchestrator ruling, spec 3.4) -----------

    private val launcherApps = listOf("com.example.a", "com.example.b", "com.example.keyboard")
        .map { InstalledAppsRepository.AppInfo(it, it, icon = null) }

    @Test
    fun `switching on a Hard block all-the-time rule asks first and writes nothing`() = runTest {
        val vm = viewModel(strictOn = false, installedApps = launcherApps, essentials = setOf("com.example.keyboard"))

        vm.toggleAllApps(allAppsRow(allAppsRule.copy(enabled = false)))

        // Same count the editor shows: launcher apps minus essentials.
        assertEquals(AllAppsSwitchOn(allAppsRule.copy(enabled = false), blockedCount = 2), vm.allAppsSwitchOn.value)
        assertNull(vm.challenge.value)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
    }

    @Test
    fun `Block them on the switch's confirm turns the rule on`() = runTest {
        val vm = viewModel(strictOn = true, installedApps = launcherApps)
        vm.toggleAllApps(allAppsRow(allAppsRule.copy(enabled = false)))

        vm.confirmAllAppsSwitchOn()

        assertNull(vm.allAppsSwitchOn.value)
        coVerify(exactly = 1) { repository.saveAllAppsRule(allAppsRule) }
    }

    @Test
    fun `Cancel on the switch's confirm writes nothing`() = runTest {
        val vm = viewModel(strictOn = false, installedApps = launcherApps)
        vm.toggleAllApps(allAppsRow(allAppsRule.copy(enabled = false)))

        vm.dismissAllAppsSwitchOn()
        vm.confirmAllAppsSwitchOn() // a late second tap after Cancel does nothing either

        assertNull(vm.allAppsSwitchOn.value)
        coVerify(exactly = 0) { repository.saveAllAppsRule(any()) }
    }

    @Test
    fun `a scheduled Hard block rule switches on without asking`() = runTest {
        val vm = viewModel(strictOn = false, installedApps = launcherApps)
        val scheduled = allAppsRule.copy(enabled = false, scheduleDays = "1,2,3,4,5", scheduleStartMinute = 540, scheduleEndMinute = 1020)

        vm.toggleAllApps(allAppsRow(scheduled))

        assertNull(vm.allAppsSwitchOn.value)
        coVerify(exactly = 1) { repository.saveAllAppsRule(scheduled.copy(enabled = true)) }
    }

    @Test
    fun `the switch and the editor ask through the one predicate`() {
        // One function, two callers: the switch cannot drift from the editor's Save.
        val vmSrc = java.io.File("src/main/java/com/astraedus/nudge/ui/screens/apps/AppListViewModel.kt").readText()
        val formSrc = java.io.File("src/main/java/com/astraedus/nudge/ui/screens/allapps/AllAppsForm.kt").readText()
        assertTrue(vmSrc.contains("confirmsSwitchOn(rule)"))
        assertTrue(formSrc.contains("AllAppsForm.from(rule).copy(enabled = true).confirmsOnSave"))
    }
}
