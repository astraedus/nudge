package com.astraedus.nudge.ui.screens.home

import android.content.Context
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The master switch is the obvious one-tap way around Nuke, so while Nuke is on, turning it OFF
 * costs the key or the emergency code (docs/architecture/nuke-mode.md). Driven through the REAL
 * HomeViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeNukeToggleGateTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val token = NukeKey.generateToken()
    private val nuke = MutableStateFlow(NukeState())
    private val preferences: NudgePreferences = mockk(relaxed = true)

    private fun viewModel(): HomeViewModel {
        every { preferences.isGlobalEnabled } returns MutableStateFlow(true)
        every { preferences.isStrictModeEnabled } returns MutableStateFlow(false)
        every { preferences.nukeState } returns nuke
        val timeTracker = TimeTracker()
        return HomeViewModel(
            context = mockk<Context>(relaxed = true),
            nudgePreferences = preferences,
            usageRepository = mockk(relaxed = true),
            blockRuleRepository = mockk(relaxed = true),
            screenTimeProvider = mockk(relaxed = true),
            timeTracker = timeTracker,
            homeChartsBuilder = HomeChartsBuilder(StatsCalculator(timeTracker)),
            installedAppsRepository = mockk(relaxed = true),
            insightsCalculator = InsightsCalculator(),
            essentialApps = mockk(relaxed = true),
            ioDispatcher = dispatcher
        )
    }

    @Test
    fun `with Nuke off the switch turns off at once`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleGlobalEnabled()
        assertNull(vm.nukeUnlock.value)
        coVerify(exactly = 1) { preferences.setGlobalEnabled(false) }
    }

    @Test
    fun `with Nuke on the switch waits for the key`() = runTest(dispatcher) {
        nuke.value = NukeState(active = true, packages = setOf("a.b"), keyHash = NukeKey.hashOrNull(token))
        val vm = viewModel()
        vm.toggleGlobalEnabled()
        assertNotNull(vm.nukeUnlock.value)
        coVerify(exactly = 0) { preferences.setGlobalEnabled(any()) }

        vm.onNukeScanned("not the key")
        coVerify(exactly = 0) { preferences.setGlobalEnabled(any()) }

        vm.onNukeScanned(token)
        coVerify(exactly = 1) { preferences.setGlobalEnabled(false) }
    }

    @Test
    fun `with Nuke on the emergency code also works, and cancelling never turns it off`() = runTest(dispatcher) {
        nuke.value = NukeState(active = true, packages = setOf("a.b"), keyHash = NukeKey.hashOrNull(token))
        val vm = viewModel()
        vm.toggleGlobalEnabled()
        vm.cancelNukeUnlock()
        coVerify(exactly = 0) { preferences.setGlobalEnabled(any()) }

        vm.toggleGlobalEnabled()
        vm.useNukeEmergencyCode()
        val code = vm.nukeUnlock.value?.emergencyTarget!!
        vm.verifyNukeEmergency(code)
        coVerify(exactly = 1) { preferences.setGlobalEnabled(false) }
    }
}
