package com.astraedus.nudge.ui.screens.apps

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.anApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        anApp("com.android.calculator", "Calculator"),
        anApp("com.android.calendar", "Calendar"),
        anApp("com.android.camera", "Camera")
    )

    private val db = FakeRuleDatabase()

    private fun viewModel(): AppListViewModel = AppListViewModel(
        FakeInstalledAppsRepository(*apps.toTypedArray()), db.repository, FakeNudgePreferences(), FakeEssentialPackages()
    )

    @Test
    fun `an app switched on keeps its row, now showing its rule`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }

        val before = vm.uiState.value.sections
        assertEquals(listOf("Calculator", "Calendar", "Camera"), before.otherApps.map { it.appInfo.appName })

        vm.toggleApp(before.otherApps[0])

        val after = vm.uiState.value.sections
        assertEquals(1, db.rules.size)
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

        db.seedRule(aBlockRule(id = 1, packageName = "com.android.camera"))

        val s = vm.uiState.value.sections
        assertEquals(listOf("Camera"), s.appsWithRules.map { it.appInfo.appName })
        assertEquals(listOf("Calculator", "Calendar"), s.otherApps.map { it.appInfo.appName })
    }
}
