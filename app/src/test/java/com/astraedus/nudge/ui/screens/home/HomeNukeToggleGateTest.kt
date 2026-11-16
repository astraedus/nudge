package com.astraedus.nudge.ui.screens.home

import android.content.Context
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.domain.nuke.NukeTimer
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeNukeClock
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.ui.nuke.NukeTimerCopy
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    private val clock = FakeNukeClock()

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
            scheduleEvaluator = com.astraedus.nudge.domain.engine.ScheduleEvaluator(com.astraedus.nudge.domain.engine.FirstUnlockState.NONE),
            nukeClock = clock,
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

    /** #184: the master switch is a key-gated weakening, so the timer holds it too. */
    @Test
    fun `while the Nuke timer runs the key does not turn the switch off, the emergency code does`() = runTest(dispatcher) {
        preferences.setNukeKey(NukeKey.hashOrNull(token)!!, NukeKeyKind.GENERATED_QR)
        preferences.addNukePackages(setOf("a.b"))
        preferences.armNuke(NukeTimer.start(30, clock.now()))
        val vm = viewModel()

        vm.toggleGlobalEnabled()
        vm.onNukeScanned(token)
        assertTrue(preferences.isGlobalEnabled.first())
        assertEquals(NukeTimerCopy.refusal(30 * 60_000L), vm.nukeUnlock.value?.error)

        clock.advance(30 * 60_000L)
        vm.onNukeScanned(token)
        assertFalse("the key works once the timer is done", preferences.isGlobalEnabled.first())

        preferences.setGlobalEnabled(true)
        preferences.setNukeActive(false)
        preferences.armNuke(NukeTimer.start(30, clock.now()))
        vm.toggleGlobalEnabled()
        vm.useNukeEmergencyCode()
        vm.verifyNukeEmergency(vm.nukeUnlock.value?.emergencyTarget!!)
        assertFalse("the emergency code ignores the timer", preferences.isGlobalEnabled.first())
    }
}
