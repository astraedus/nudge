package com.astraedus.nudge.ui.screens.home

import android.content.Context
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeKeyKind
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.ui.screens.nuke.NukeViewModel
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * "Nuke is on · N apps" is shown twice, on the dashboard card and on the Nuke screen, and the two
 * must never disagree. Found on the bench in spec PR 1: an upgrade made Messages and Clock essential
 * while they were still stored on the list, and the card said 3 apps while the screen said 1.
 *
 * Both counts are the apps Nuke actually blocks ([com.astraedus.nudge.domain.nuke.NukePolicy.nukedCount]
 * over the same [EssentialApps] set). The stored list keeps the essentials, so they count again if
 * this phone stops treating them as essential. Driven through BOTH real ViewModels over one stored
 * state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NukeCountParityTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val instagram = "com.instagram.android"
    private val messages = "com.google.android.apps.messaging"
    private val clock = "com.google.android.deskclock"

    private val preferences = FakeNudgePreferences {
        setNukeKey(NukeKey.hashOrNull(NukeKey.generateToken())!!, NukeKeyKind.GENERATED_QR)
        addNukePackages(setOf(instagram, messages, clock))
        setNukeActive(true)
        setNukeIntroSeen()
    }

    private fun essentials(vararg packages: String): EssentialApps = mockk {
        every { packages(any()) } returns packages.toSet()
        coEvery { browsers() } returns emptySet()
    }

    private fun home(essentials: EssentialApps): HomeViewModel {
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
            essentialApps = essentials,
            scheduleEvaluator = com.astraedus.nudge.domain.engine.ScheduleEvaluator(),
            ioDispatcher = dispatcher
        )
    }

    private fun nukeScreen(essentials: EssentialApps): NukeViewModel {
        return NukeViewModel(preferences, FakeInstalledAppsRepository(), essentials, dispatcher)
    }

    /** (dashboard card count, Nuke screen header count), both read live. */
    private fun TestScope.counts(essentials: EssentialApps): Pair<Int, Int> {
        val home = home(essentials)
        val nuke = nukeScreen(essentials)
        backgroundScope.launch { home.nukeSummary.collect {} }
        backgroundScope.launch { nuke.uiState.collect {} }
        return home.nukeSummary.value.appCount to nuke.uiState.value.nukedCount
    }

    @Test
    fun `stored essentials are left out of both counts, so the card and the screen agree`() = runTest(dispatcher) {
        val (card, screen) = counts(essentials(messages, clock))
        assertEquals("the Nuke screen counts only what Nuke blocks", 1, screen)
        assertEquals("the dashboard card must say what the Nuke screen says", screen, card)
    }

    @Test
    fun `an app that stops being essential counts again on both, because the stored list kept it`() = runTest(dispatcher) {
        val (card, screen) = counts(essentials(messages))
        assertEquals(2, screen)
        assertEquals(screen, card)
    }

    @Test
    fun `the static floor is left out of both counts too`() = runTest(dispatcher) {
        preferences.addNukePackages(setOf("com.google.android.dialer"))
        val (card, screen) = counts(essentials())
        assertEquals(3, screen)
        assertEquals(screen, card)
    }
}
