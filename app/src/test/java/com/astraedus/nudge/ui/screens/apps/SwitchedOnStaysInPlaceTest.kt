package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Device QA (PR 3): switching on an app with no rule moved its row up into "Apps with rules" while
 * the finger was still there, the next app slid under it, and a quick second tap switched THAT app
 * on (Calendar got a rule when Calculator was double-tapped). The row switched on here must stay
 * the row under the finger for the rest of the visit, through the real ViewModel and a real
 * rules Flow that emits the new rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchedOnStaysInPlaceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val apps = listOf(
        AppInfo("com.android.calculator", "Calculator", icon = null),
        AppInfo("com.android.calendar", "Calendar", icon = null),
        AppInfo("com.android.camera", "Camera", icon = null)
    )

    private val rules = MutableStateFlow<List<BlockRule>>(emptyList())

    private fun viewModel(): AppListViewModel {
        val repository = mockk<BlockRuleRepository>(relaxed = true)
        every { repository.getAllRules() } returns rules
        every { repository.getAllGroups() } returns flowOf(emptyList())
        every { repository.getAllGroupMembers() } returns flowOf(emptyList())
        coEvery { repository.addRule(any()) } coAnswers {
            val rule = firstArg<BlockRule>().copy(id = rules.value.size + 1L)
            rules.value = rules.value + rule
            rule.id
        }
        val prefs = mockk<NudgePreferences>()
        every { prefs.isStrictModeEnabled } returns flowOf(false)
        every { prefs.strictModeChallengeLength } returns flowOf(StrictModeChallenge.DEFAULT_LENGTH)
        val installed = mockk<InstalledAppsRepository>()
        coEvery { installed.getInstalledApps() } returns apps
        return AppListViewModel(installed, repository, prefs, FakeEssentialPackages())
    }

    @Test
    fun `an app switched on keeps its row, now showing its rule`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }

        val before = vm.uiState.value.sections
        assertEquals(listOf("Calculator", "Calendar", "Camera"), before.otherApps.map { it.appInfo.appName })

        vm.toggleApp(before.otherApps[0])

        val after = vm.uiState.value.sections
        assertEquals(1, rules.value.size)
        assertTrue(after.appsWithRules.isEmpty())
        assertEquals(listOf("Calculator", "Calendar", "Camera"), after.otherApps.map { it.appInfo.appName })
        assertEquals("Delay 15s", after.otherApps[0].summary)
        assertTrue(after.otherApps[0].isOn)
        assertEquals("Apps", after.otherAppsTitle)
    }

    @Test
    fun `a rule made elsewhere still moves up at once`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }

        rules.value = listOf(BlockRule(id = 1, packageName = "com.android.camera", mode = "DELAY", delaySeconds = 15))

        val s = vm.uiState.value.sections
        assertEquals(listOf("Camera"), s.appsWithRules.map { it.appInfo.appName })
        assertEquals(listOf("Calculator", "Calendar"), s.otherApps.map { it.appInfo.appName })
    }
}
