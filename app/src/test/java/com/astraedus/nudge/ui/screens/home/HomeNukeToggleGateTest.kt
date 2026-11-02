package com.astraedus.nudge.ui.screens.home

import android.content.Context
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val preferences = FakeNudgePreferences()

    /** Nuke on: a paired key, one listed app, switched active (a key is required to turn it on). */
    private suspend fun nukeOn() {
        preferences.setNukeKey(NukeKey.hashOrNull(token)!!, NukeKeyKind.GENERATED_QR)
        preferences.addNukePackages(setOf("a.b"))
        preferences.setNukeActive(true)
    }

    private fun viewModel(): HomeViewModel {
        val timeTracker = TimeTracker()
        return HomeViewModel(
            context = mockk<Context>(relaxed = true),
            nudgePreferences = preferences,
            usageRepository = FakeUsageRepository(),
            blockRuleRepository = FakeRuleDatabase().repository,
            screenTimeProvider = mockk(relaxed = true),
            timeTracker = timeTracker,
            homeChartsBuilder = HomeChartsBuilder(StatsCalculator(timeTracker)),
            installedAppsRepository = FakeInstalledAppsRepository(),
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
        assertFalse(preferences.isGlobalEnabled.first())
    }

    @Test
    fun `with Nuke on the switch waits for the key`() = runTest(dispatcher) {
        nukeOn()
        val vm = viewModel()
        vm.toggleGlobalEnabled()
        assertNotNull(vm.nukeUnlock.value)
        assertTrue(preferences.isGlobalEnabled.first())

        vm.onNukeScanned("not the key")
        assertTrue(preferences.isGlobalEnabled.first())

        vm.onNukeScanned(token)
        assertFalse(preferences.isGlobalEnabled.first())
    }

    @Test
    fun `with Nuke on the emergency code also works, and cancelling never turns it off`() = runTest(dispatcher) {
        nukeOn()
        val vm = viewModel()
        vm.toggleGlobalEnabled()
        vm.cancelNukeUnlock()
        assertTrue(preferences.isGlobalEnabled.first())

        vm.toggleGlobalEnabled()
        vm.useNukeEmergencyCode()
        val code = vm.nukeUnlock.value?.emergencyTarget!!
        vm.verifyNukeEmergency(code)
        assertFalse(preferences.isGlobalEnabled.first())
    }
}
