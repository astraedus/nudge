package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.SourceScan
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupRule
import com.astraedus.nudge.testing.anApp
import com.astraedus.nudge.testing.anAppGroup
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private companion object {
        const val APP = "com.example"
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val db = FakeRuleDatabase()

    /** The one rule on [APP] the app-row tests act on, switched [on] or off. */
    private fun seedAppRule(on: Boolean) = db.seedRule(aBlockRule(packageName = APP, enabled = on))

    /** An app row as the sections build it: no rule, or a rule that is on / off. */
    private fun appRow(hasRule: Boolean, on: Boolean = false) = AppRow(
        appInfo = anApp(APP, "Example"),
        summary = if (hasRule) "Delay 15s" else null,
        isOn = on
    )

    private fun viewModel(
        strictOn: Boolean,
        installedApps: List<AppInfo> = emptyList(),
        essentials: Set<String> = emptySet()
    ): AppListViewModel {
        return AppListViewModel(
            FakeInstalledAppsRepository(*installedApps.toTypedArray()),
            db.repository,
            FakeNudgePreferences { setStrictModeEnabled(strictOn) },
            FakeEssentialPackages(essentials)
        )
    }

    @Test
    fun `strict on - switching a block off waits for the challenge and writes nothing`() = runTest {
        val vm = viewModel(strictOn = true)

        val rule = seedAppRule(on = true)

        vm.toggleApp(appRow(hasRule = true, on = true))

        assertNotNull(vm.challenge.value)
        assertEquals(listOf(rule), db.rules)
    }

    @Test
    fun `strict on - the right challenge answer disables the block`() = runTest {
        val vm = viewModel(strictOn = true)
        val rule = seedAppRule(on = true)
        vm.toggleApp(appRow(hasRule = true, on = true))

        vm.verifyChallenge(vm.challenge.value!!.target)

        assertEquals(listOf(rule.copy(enabled = false)), db.rules)
        assertNull(vm.challenge.value)
    }

    @Test
    fun `strict on - a wrong answer or cancel never disables the block`() = runTest {
        val vm = viewModel(strictOn = true)
        val rule = seedAppRule(on = true)
        vm.toggleApp(appRow(hasRule = true, on = true))

        vm.verifyChallenge("not-the-code")
        assertEquals(listOf(rule), db.rules)

        vm.cancelChallenge()
        assertNull(vm.challenge.value)
        assertEquals(listOf(rule), db.rules)
    }

    @Test
    fun `strict off - switching a block off writes immediately`() = runTest {
        val vm = viewModel(strictOn = false)
        val rule = seedAppRule(on = true)

        vm.toggleApp(appRow(hasRule = true, on = true))

        assertNull(vm.challenge.value)
        assertEquals(listOf(rule.copy(enabled = false)), db.rules)
    }

    @Test
    fun `switching a block on is never gated, even with strict on`() = runTest {
        val vm = viewModel(strictOn = true)
        val rule = seedAppRule(on = false)

        vm.toggleApp(appRow(hasRule = true, on = false))

        assertNull(vm.challenge.value)
        assertEquals(listOf(rule.copy(enabled = true)), db.rules)
    }

    @Test
    fun `creating a new rule is never gated, even with strict on`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleApp(appRow(hasRule = false))

        assertNull(vm.challenge.value)
        val created = db.rules.single()
        assertEquals(APP, created.packageName)
        assertNull(created.groupId)
        assertEquals("DELAY", created.mode)
        assertEquals(15, created.delaySeconds)
        assertTrue(created.enabled)
    }

    @Test
    fun `strict on - an app row that is off but has a rule never creates a second rule`() = runTest {
        val vm = viewModel(strictOn = true)
        seedAppRule(on = false)

        vm.toggleApp(appRow(hasRule = true, on = false))

        assertEquals(1, db.rules.size)
    }

    // ── Group rows (spec PR 2) ────────────────────────────────────────────

    private fun groupRow(rule: BlockRule?) = GroupRow(
        groupId = 3L,
        title = "Social · 2 apps",
        summary = "Delay 15s",
        blocking = rule?.enabled == true,
        rule = rule
    )

    private val groupRule = aGroupRule(groupId = 3L).copy(id = 9L)

    private fun seedGroupRule(rule: BlockRule = groupRule): BlockRule {
        db.seedGroup(anAppGroup("Social", id = 3L))
        return db.seedRule(rule)
    }

    @Test
    fun `strict on - switching a group off waits for the challenge, then turns its rule off`() = runTest {
        val vm = viewModel(strictOn = true)
        seedGroupRule()

        vm.toggleGroup(groupRow(groupRule))

        assertNotNull(vm.challenge.value)
        assertEquals(groupRule, db.ruleWithId(9L))

        vm.verifyChallenge(vm.challenge.value!!.target)
        assertEquals(groupRule.copy(enabled = false), db.ruleWithId(9L))
    }

    @Test
    fun `switching a group back on is never gated`() = runTest {
        val vm = viewModel(strictOn = true)
        seedGroupRule(groupRule.copy(enabled = false))

        vm.toggleGroup(groupRow(groupRule.copy(enabled = false)))

        assertNull(vm.challenge.value)
        assertEquals(groupRule.copy(enabled = true), db.ruleWithId(9L))
    }

    @Test
    fun `switching on a group with no rule gives it Delay 15s, ungated`() = runTest {
        val vm = viewModel(strictOn = true)

        vm.toggleGroup(groupRow(null))

        assertNull(vm.challenge.value)
        val created = db.rules.single()
        assertEquals(3L, created.groupId)
        assertNull(created.packageName)
        assertEquals("DELAY", created.mode)
        assertEquals(15, created.delaySeconds)
        assertTrue(created.enabled)
    }

    // -- All apps rows (spec PR 4): off is a weakening, like a group row ---------------------

    private val allAppsRule = aBlockRule(id = 8, allApps = true, mode = "HARD_BLOCK", enabled = true)

    private fun allAppsRow(rule: BlockRule) =
        AllAppsRuleItem(rule.id, "All apps", "Hard Block", rule.enabled, rule)

    @Test
    fun `strict on - switching an All apps rule off waits for the challenge`() = runTest {
        val vm = viewModel(strictOn = true)
        db.seedRule(allAppsRule)

        vm.toggleAllApps(allAppsRow(allAppsRule))

        assertNotNull(vm.challenge.value)
        assertEquals(listOf(allAppsRule), db.rules)
        vm.verifyChallenge(vm.challenge.value!!.target)
        assertEquals(listOf(allAppsRule.copy(enabled = false)), db.rules)
    }

    @Test
    fun `strict off - switching an All apps rule off writes immediately`() = runTest {
        val vm = viewModel(strictOn = false)
        db.seedRule(allAppsRule)

        vm.toggleAllApps(allAppsRow(allAppsRule))

        assertNull(vm.challenge.value)
        assertEquals(listOf(allAppsRule.copy(enabled = false)), db.rules)
    }

    @Test
    fun `switching an All apps rule back on is never gated`() = runTest {
        val vm = viewModel(strictOn = true)
        val delayRule = allAppsRule.copy(mode = "DELAY", delaySeconds = 15)
        db.seedRule(delayRule.copy(enabled = false))

        vm.toggleAllApps(allAppsRow(delayRule.copy(enabled = false)))

        assertNull(vm.challenge.value)
        assertNull(vm.allAppsSwitchOn.value)
        assertEquals(listOf(delayRule), db.rules)
    }

    // -- The switch asks what the editor's Save asks (orchestrator ruling, spec 3.4) -----------

    private val launcherApps = listOf("com.example.a", "com.example.b", "com.example.keyboard")
        .map { anApp(it) }

    @Test
    fun `switching on a Hard block all-the-time rule asks first and writes nothing`() = runTest {
        val vm = viewModel(strictOn = false, installedApps = launcherApps, essentials = setOf("com.example.keyboard"))
        db.seedRule(allAppsRule.copy(enabled = false))

        vm.toggleAllApps(allAppsRow(allAppsRule.copy(enabled = false)))

        // Same count the editor shows: launcher apps minus essentials.
        assertEquals(AllAppsSwitchOn(allAppsRule.copy(enabled = false), blockedCount = 2), vm.allAppsSwitchOn.value)
        assertNull(vm.challenge.value)
        assertEquals(listOf(allAppsRule.copy(enabled = false)), db.rules)
    }

    @Test
    fun `Block them on the switch's confirm turns the rule on`() = runTest {
        val vm = viewModel(strictOn = true, installedApps = launcherApps)
        db.seedRule(allAppsRule.copy(enabled = false))
        vm.toggleAllApps(allAppsRow(allAppsRule.copy(enabled = false)))

        vm.confirmAllAppsSwitchOn()

        assertNull(vm.allAppsSwitchOn.value)
        assertEquals(listOf(allAppsRule), db.rules)
    }

    @Test
    fun `Cancel on the switch's confirm writes nothing`() = runTest {
        val vm = viewModel(strictOn = false, installedApps = launcherApps)
        db.seedRule(allAppsRule.copy(enabled = false))
        vm.toggleAllApps(allAppsRow(allAppsRule.copy(enabled = false)))

        vm.dismissAllAppsSwitchOn()
        vm.confirmAllAppsSwitchOn() // a late second tap after Cancel does nothing either

        assertNull(vm.allAppsSwitchOn.value)
        assertEquals(listOf(allAppsRule.copy(enabled = false)), db.rules)
    }

    @Test
    fun `a scheduled Hard block rule switches on without asking`() = runTest {
        val vm = viewModel(strictOn = false, installedApps = launcherApps)
        val scheduled = allAppsRule.copy(enabled = false, scheduleDays = "1,2,3,4,5", scheduleStartMinute = 540, scheduleEndMinute = 1020)
        db.seedRule(scheduled)

        vm.toggleAllApps(allAppsRow(scheduled))

        assertNull(vm.allAppsSwitchOn.value)
        assertEquals(listOf(scheduled.copy(enabled = true)), db.rules)
    }

    @Test
    fun `the switch and the editor ask through the one predicate`() {
        // One function, two callers: the switch cannot drift from the editor's Save.
        val vmSrc = SourceScan.kotlinFile("com/astraedus/nudge/ui/screens/apps/AppListViewModel.kt").readText()
        val formSrc = SourceScan.kotlinFile("com/astraedus/nudge/ui/screens/allapps/AllAppsForm.kt").readText()
        assertTrue(vmSrc.contains("confirmsSwitchOn(rule)"))
        assertTrue(formSrc.contains("AllAppsForm.from(rule).copy(enabled = true).confirmsOnSave"))
    }
}
