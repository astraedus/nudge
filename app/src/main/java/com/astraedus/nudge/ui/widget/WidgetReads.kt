package com.astraedus.nudge.ui.widget

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The suspending one-shot reads behind each widget.
 *
 * ## One shot, never a subscription
 *
 * A Glance widget composes inside a session the platform tears down as soon as the RemoteViews are
 * handed to the launcher. There is nothing for a `collectAsState` over a Room `Flow` to outlive, so
 * a subscription here would produce exactly one value and then be cancelled — the same value a
 * `.first()` gives, at the cost of looking like live data to the next reader. Every read below is
 * therefore explicitly a snapshot, and freshness comes from [NudgeWidgetUpdater]'s pushes.
 *
 * `.first()` on the repository's existing `Flow` rather than a new suspend DAO method, deliberately:
 * a Room flow emits its query result immediately and this cancels straight after, so it IS the
 * one-shot read — and it is the SAME query the dashboard observes, which is what stops a widget and
 * the screen it links to computing one number two ways.
 *
 * ## Everything runs on IO
 *
 * `getWeeklyUsage` is a binder call plus a walk over a week of usage events; `resolveIcon` hits
 * PackageManager. `provideGlance` is called on Glance's own session context, and blocking it would
 * stall the widget update rather than merely being slow.
 */
object WidgetReads {

    /** Icons are decoded at a fixed pixel size: a RemoteViews payload is IPC, and it has a ceiling. */
    private const val ICON_PX = 64

    suspend fun today(deps: NudgeWidgetEntryPoint): WidgetSnapshot.Today = withContext(Dispatchers.IO) {
        val timeTracker = deps.timeTracker()
        val dayStart = timeTracker.startOfToday()
        // Calendar arithmetic for the exclusive end of the day, matching HomeViewModel: on a DST
        // day `+ 24h` is an hour off true local midnight, and the widget and the tile it mirrors
        // must not count different hours under the same word "Today".
        val dayEnd = timeTracker.startOfDayDaysBefore(dayStart, -1)
        val screenTime = deps.screenTimeProvider()
        val usage = deps.usageRepository()

        val weekly = screenTime.getWeeklyUsage(dayStart)
        WidgetSnapshotMapper.today(
            // `formatDayTotal`, not `formatDuration`: it is the one function that decides how a
            // day's screen time is worded, including the "< 1m" case. A second formatting rule is
            // how the same quantity came to read "< 1m" on one screen and "0s" on another.
            screenTimeFormatted = StatsViewModel.formatDayTotal(weekly.totalOn(dayStart), timeTracker),
            blockedCount = usage.getBlockedCountForDay(dayStart, dayEnd).first(),
            walkAwayCount = usage.getChangedMindCountForDay(dayStart, dayEnd).first(),
            hasUsagePermission = screenTime.hasPermission()
        )
    }

    /**
     * The week's leaderboard, plus the app icons for the rows we are about to draw.
     *
     * The window is the SAME trailing-week boundary the dashboard's "Last 7 days" card subscribes
     * with (`startOfDayDaysBefore(today, WEEK_DAYS - 1)`), so the widget and that card cannot
     * disagree about which week they mean.
     */
    suspend fun topBlocked(
        deps: NudgeWidgetEntryPoint,
        limit: Int
    ): TopBlockedRead = withContext(Dispatchers.IO) {
        val timeTracker = deps.timeTracker()
        val insights = deps.insightsCalculator()
        val apps = deps.installedAppsRepository()

        val dayStart = timeTracker.startOfToday()
        val sinceMs = timeTracker.startOfDayDaysBefore(dayStart, ScreenTimeProvider.WEEK_DAYS - 1)
        val events = deps.usageRepository().getEventsSince(sinceMs).first()

        // The ONE per-app aggregation in the app. Never a second loop here: "which apps pull
        // hardest" already has an answer, and a widget computing its own would be a second one.
        val stats = insights.topBlockedApps(
            events = events,
            sinceMs = sinceMs,
            nowMs = System.currentTimeMillis(),
            limit = limit
        )

        val labels = stats.associate { stat ->
            stat.packageName to insights.appDisplayLabel(
                packageName = stat.packageName,
                resolvedName = apps.resolveAppName(stat.packageName)
            )
        }
        val icons = stats.mapNotNull { stat ->
            // An icon is decoration; a missing or undecodable one must cost the row nothing. The
            // composable falls back to a text rank rather than dropping a real block from the list.
            val bitmap = runCatching {
                apps.resolveIcon(stat.packageName)?.toBitmap(width = ICON_PX, height = ICON_PX)
            }.getOrNull()
            bitmap?.let { stat.packageName to it }
        }.toMap()

        TopBlockedRead(
            snapshot = WidgetSnapshotMapper.topBlocked(stats, labels, limit),
            icons = icons
        )
    }

    suspend fun protection(deps: NudgeWidgetEntryPoint): WidgetSnapshot.Protection =
        withContext(Dispatchers.IO) {
            val prefs = deps.nudgePreferences()
            WidgetSnapshotMapper.protection(
                enabled = prefs.isGlobalEnabled.first(),
                degraded = prefs.protectionDegraded.first(),
                strictModeEnabled = prefs.isStrictModeEnabled.first()
            )
        }

    /**
     * The Top-blocked read: a pure snapshot plus the bitmaps for it.
     *
     * The bitmaps are kept OUT of [WidgetSnapshot] so that type stays free of `android.*` and
     * therefore fully JVM-testable. A row whose package is absent from [icons] renders its rank
     * instead, which is also what an uninstalled app looks like.
     */
    data class TopBlockedRead(
        val snapshot: WidgetSnapshot.TopBlocked,
        val icons: Map<String, Bitmap>
    ) {
        companion object {
            val EMPTY = TopBlockedRead(WidgetSnapshot.TopBlocked.EMPTY, emptyMap())
        }
    }
}
