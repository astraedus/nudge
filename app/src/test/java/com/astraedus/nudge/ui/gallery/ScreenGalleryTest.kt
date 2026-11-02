package com.astraedus.nudge.ui.gallery

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.astraedus.nudge.ui.overlay.HardBlockContent
import com.astraedus.nudge.ui.redirect.RedirectAppBubble
import com.astraedus.nudge.ui.screens.apps.AppListContent
import com.astraedus.nudge.ui.screens.home.HomeContent
import com.astraedus.nudge.ui.theme.NudgeTheme
import org.junit.Rule
import org.junit.Test

/**
 * Golden images of curated screen states (Paparazzi, JVM-only via layoutlib; no device, no
 * Robolectric). How to add a screen, record and review a diff: `docs/screenshot-tests.md`.
 *
 * Every screen renders from [GalleryFixtures], never from real user data, through the same
 * stateless `XContent(state, callbacks)` entry point its production screen calls.
 *
 * Pixel 3 geometry (393dp wide) because that is the bench device every manual QA run uses, and a
 * narrow screen is where clipped switches and overflowing labels show first.
 *
 * `dynamicColor = false`: the device's wallpaper palette would replace the brand colours on
 * Android 12+, and a golden must not depend on whatever wallpaper layoutlib assumes.
 */
class ScreenGalleryTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_3)

    @Test
    fun home_light() = snapshot(dark = false) { home() }

    @Test
    fun home_dark() = snapshot(dark = true) { home() }

    @Test
    fun manageApps_light() = snapshot(dark = false) {
        AppListContent(
            state = GalleryFixtures.manageApps(),
            onSearchQueryChange = {},
            onNavigateBack = {},
            onNavigateToAppConfig = {},
            onOpenGroup = {},
            onOpenAllAppsRule = {},
            onToggleAllApps = {},
            onToggleGroup = {},
            onToggleApp = {},
            onBlockSeveral = {}
        )
    }

    @Test
    fun blockOverlay_hardBlock_light() = snapshot(dark = false) {
        HardBlockContent(
            packageName = GalleryFixtures.BLOCKED_PACKAGE,
            appLabel = GalleryFixtures.BLOCKED_LABEL,
            onGoBack = {},
            ruleName = "Evenings",
            untilText = "Until 9:00 PM",
            // One entry, so the screen's random pick is deterministic. The text is a real
            // shipped message (NudgeMessages.hardBlockMessages), not one written for the test.
            messagePool = listOf(GalleryFixtures.hardBlockMessage()),
            canUseEmergencyPass = true,
            redirect = { RedirectAppBubble(target = null, onLaunch = {}, onEdit = {}) }
        )
    }

    @Composable
    private fun home() {
        HomeContent(
            state = GalleryFixtures.home(),
            nukeSummary = GalleryFixtures.nukeSummary(),
            lockCard = null,
            onToggleGlobalEnabled = {},
            onNavigateToApps = {},
            onNavigateToStats = {},
            onNavigateToSettings = {},
            onNavigateToWillpower = {},
            onNavigateToInterventions = {},
            onNavigateToAppDetail = {},
            onNavigateToNuke = {},
            onOpenAllAppsRule = {}
        )
    }

    private fun snapshot(dark: Boolean, content: @Composable () -> Unit) {
        if (dark) {
            paparazzi.unsafeUpdateConfig(
                deviceConfig = DeviceConfig.PIXEL_3.copy(nightMode = NightMode.NIGHT)
            )
        }
        paparazzi.snapshot {
            NudgeTheme(darkTheme = dark, dynamicColor = false) { content() }
        }
    }
}
