package com.astraedus.nudge.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.astraedus.nudge.ui.components.rule.ScheduleKind
import com.astraedus.nudge.ui.components.rule.WhenSection
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

    /**
     * `maxPercentDifference = 0.0`: any changed pixel fails. Paparazzi's default lets 0.1% through,
     * and #154's lost 12dp switch inset changes only 0.22% of the Home frame, so a 4dp slip would
     * pass unseen. layoutlib renders in software with its own bundled fonts, and the CI goldens
     * were byte-identical to a laptop render, so zero tolerance does not make the gate flaky.
     */
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_3, maxPercentDifference = 0.0)

    /** A 360dp-wide phone at 1.3x font scale, where the reported folding first showed. */
    private val phone360 = DeviceConfig.PIXEL_3.copy(
        screenWidth = 1080,
        screenHeight = 2400,
        xdpi = 480,
        ydpi = 480,
        orientation = ScreenOrientation.PORTRAIT,
        density = Density.XXHIGH,
        fontScale = 1.3f
    )

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

    @Test
    fun whenSection_firstUnlock_360dp() = snapshot(
        dark = false,
        deviceConfig = phone360
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            WhenSection(
                kind = ScheduleKind.FIRST_UNLOCK,
                onKindChange = {},
                days = (1..7).toSet(),
                onDaysChange = {},
                startHour = 9,
                startMinute = 0,
                onStartTimeChange = { _, _ -> },
                endHour = 17,
                endMinute = 0,
                onEndTimeChange = { _, _ -> },
                firstUnlockMinutes = 30,
                onFirstUnlockMinutesChange = {},
                dayStartMinute = 4 * 60,
                onDayStartClick = {},
                startsAtNextUnlock = true
            )
        }
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

    private fun snapshot(
        dark: Boolean,
        deviceConfig: DeviceConfig = DeviceConfig.PIXEL_3,
        content: @Composable () -> Unit
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = if (dark) {
                deviceConfig.copy(nightMode = NightMode.NIGHT)
            } else {
                deviceConfig
            }
        )
        paparazzi.snapshot {
            NudgeTheme(darkTheme = dark, dynamicColor = false) { content() }
        }
    }
}
