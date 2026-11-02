package com.astraedus.nudge.ui.gallery

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.InstalledAppsRepository.AppInfo
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.ui.overlay.NudgeMessages
import com.astraedus.nudge.ui.screens.apps.AppListUiState
import com.astraedus.nudge.ui.screens.apps.buildManageAppsSections
import com.astraedus.nudge.ui.screens.home.HomeCharts
import com.astraedus.nudge.ui.screens.home.HomeUiState
import com.astraedus.nudge.ui.screens.home.NukeSummary
import com.astraedus.nudge.ui.screens.home.TopBlockedApp
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay

/**
 * The fixed states [ScreenGalleryTest] renders. Fictional, never real user data, and free of
 * wall-clock reads (every timestamp is pinned), so a golden only changes when the UI does.
 *
 * Where production derives a value (Manage Apps sections, overlay copy), the fixture goes through
 * the production code rather than restating its output, so the golden shows what the app would.
 */
internal object GalleryFixtures {

    const val BLOCKED_PACKAGE = "com.instagram.android"
    const val BLOCKED_LABEL = "Instagram"

    /** A real shipped overlay line, so the golden shows the copy users see. */
    fun hardBlockMessage(): String = NudgeMessages.hardBlockMessages.first()

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /**
     * A typical mid-week dashboard: protection on, usage permission granted, a week of history
     * with today (Sun, the last entry) part-way through. Hand-written: the chart builder reads the
     * clock and the database, and a golden needs neither.
     */
    fun home(): HomeUiState {
        val screenTime = listOf(
            DayData("Mon", 2 * HOUR_MS + 40 * MINUTE_MS),
            DayData("Tue", 3 * HOUR_MS + 5 * MINUTE_MS),
            DayData("Wed", 1 * HOUR_MS + 50 * MINUTE_MS),
            DayData("Thu", 2 * HOUR_MS + 10 * MINUTE_MS),
            DayData("Fri", 2 * HOUR_MS + 55 * MINUTE_MS),
            DayData("Sat", 1 * HOUR_MS + 38 * MINUTE_MS),
            DayData("Sun", 2 * HOUR_MS + 14 * MINUTE_MS)
        )
        val trend = listOf(
            TrendDay("Mon", blockedCount = 9, walkedAwayCount = 3),
            TrendDay("Tue", blockedCount = 12, walkedAwayCount = 4),
            TrendDay("Wed", blockedCount = 6, walkedAwayCount = 3),
            TrendDay("Thu", blockedCount = 8, walkedAwayCount = 2),
            TrendDay("Fri", blockedCount = 11, walkedAwayCount = 5),
            TrendDay("Sat", blockedCount = 4, walkedAwayCount = 2),
            TrendDay("Sun", blockedCount = 7, walkedAwayCount = 3)
        )
        return HomeUiState(
            isGlobalEnabled = true,
            todayTotalUsageFormatted = "2h 14m",
            activeAppCount = 6,
            blockedCountToday = 7,
            changedMindCountToday = 3,
            allTimeBlockedCount = 412,
            allTimeChangedMindCount = 138,
            hasUsagePermission = true,
            charts = HomeCharts(
                weeklyScreenTime = screenTime,
                weeklyTrend = trend,
                weekTotalMs = screenTime.sumOf { it.totalMs },
                weekBlocked = trend.sumOf { it.blockedCount },
                weekWalkedAway = trend.sumOf { it.walkedAwayCount }
            ),
            weekTotalFormatted = "16h 32m",
            // icon = null: the card's generic glyph. A launcher icon would need a real
            // PackageManager, which is exactly what this JVM render does not have.
            topBlocked = listOf(
                TopBlockedApp(BLOCKED_PACKAGE, BLOCKED_LABEL, icon = null, count = 21),
                TopBlockedApp("com.google.android.youtube", "YouTube", icon = null, count = 18),
                TopBlockedApp("com.reddit.frontpage", "Reddit", icon = null, count = 12)
            )
        )
    }

    /** Nuke set up (a key exists) but idle, guarding four apps. */
    fun nukeSummary(): NukeSummary = NukeSummary(active = false, hasKey = true, appCount = 4)

    /**
     * A typical Manage Apps list, built by the production [buildManageAppsSections]:
     * a "Social" category group on Delay, YouTube on Breathing with a 30-minute limit, Reddit with
     * a switched-off Hard Block, and unruled apps (one without an icon, to show the fallback).
     */
    fun manageApps(): AppListUiState {
        val installed = listOf(
            app(BLOCKED_PACKAGE, BLOCKED_LABEL, AppCategory.SOCIAL, 0xFFC13584),
            app("com.zhiliaoapp.musically", "TikTok", AppCategory.SOCIAL, 0xFF111111),
            app("com.google.android.youtube", "YouTube", AppCategory.VIDEO, 0xFFFF0000),
            app("com.reddit.frontpage", "Reddit", AppCategory.NEWS, 0xFFFF4500),
            app("com.android.chrome", "Chrome", AppCategory.PRODUCTIVITY, 0xFF4285F4),
            app("com.spotify.music", "Spotify", AppCategory.AUDIO, 0xFF1DB954),
            app("com.duolingo", "Duolingo", AppCategory.UNDEFINED, null)
        )
        val social = AppGroup(id = 1, name = "Social", categories = AppCategory.SOCIAL.name)
        val rules = listOf(
            BlockRule(id = 1, groupId = social.id, mode = BlockMode.DELAY.name, createdAt = 0),
            BlockRule(
                id = 2,
                packageName = "com.google.android.youtube",
                mode = BlockMode.BREATHING.name,
                dailyLimitMinutes = 30,
                createdAt = 0
            ),
            BlockRule(
                id = 3,
                packageName = "com.reddit.frontpage",
                mode = BlockMode.HARD_BLOCK.name,
                enabled = false,
                createdAt = 0
            )
        )
        return AppListUiState(
            sections = buildManageAppsSections(
                installed = installed,
                groups = listOf(social),
                members = emptyList<AppGroupMember>(),
                rules = rules,
                essentials = emptySet(),
                browsers = setOf("com.android.chrome"),
                query = ""
            ),
            isLoading = false
        )
    }

    /** A flat brand-coloured tile standing in for a launcher icon; null = no icon at all. */
    private fun app(pkg: String, name: String, category: AppCategory, color: Long?): AppInfo {
        val icon: Drawable? = color?.let {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(it.toInt())
                setSize(96, 96)
            }
        }
        return AppInfo(packageName = pkg, appName = name, icon = icon, category = category)
    }
}
