package com.astraedus.nudge.ui.screens.home

import android.content.Context
import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.screens.nuke.NukeViewModel
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val stored = MutableStateFlow(
        NukeState(
            active = true,
            packages = setOf(instagram, messages, clock),
            keyHash = NukeKey.hashOrNull(NukeKey.generateToken())
        )
    )

    private val preferences: NudgePreferences = mockk(relaxed = true) {
        every { isGlobalEnabled } returns MutableStateFlow(true)
        every { isStrictModeEnabled } returns MutableStateFlow(false)
        every { nukeState } returns stored
        every { nukeIntroSeen } returns MutableStateFlow(true)
    }

    private fun essentials(vararg packages: String): EssentialApps = mockk {
        every { packages(any()) } returns packages.toSet()
    }

    private fun home(essentials: EssentialApps): HomeViewModel {
        val timeTracker = TimeTracker()
        return HomeViewModel(
            context = mockk<Context>(relaxed = true),
            nudgePreferences = preferences,
            usageRepository = mockk(relaxed = true),
            blockRuleRepository = mockk(relaxed = true),
            screenTimeProvider = mockk(relaxed = true),
            timeTracker = timeTracker,
            homeChartsBuilder = HomeChartsBuilder(StatsCalculator(timeTracker)),
            installedAppsRepository = mockk<InstalledAppsRepository>(relaxed = true)
                .also { every { it.changes } returns MutableStateFlow(0L) },
            insightsCalculator = InsightsCalculator(),
            essentialApps = essentials,
            ioDispatcher = dispatcher
        )
    }

    private fun nukeScreen(essentials: EssentialApps): NukeViewModel {
        val apps: InstalledAppsRepository = mockk()
        coEvery { apps.getInstalledApps() } returns emptyList()
        return NukeViewModel(preferences, apps, essentials, dispatcher)
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
        stored.value = stored.value.copy(packages = stored.value.packages + "com.google.android.dialer")
        val (card, screen) = counts(essentials())
        assertEquals(3, screen)
        assertEquals(screen, card)
    }
}
