package com.astraedus.nudge.ui.widget

import com.astraedus.nudge.ui.screens.stats.AppInterventionStat

/**
 * Everything a widget draws, resolved BEFORE composition.
 *
 * A Glance widget composes inside a short-lived process the platform may tear down the moment the
 * frame is produced, so there is nowhere for a long-lived `Flow` subscription to live. Each widget
 * therefore does one suspending read, turns the result into one of these immutable values, and
 * composes from it — the composables themselves never touch a repository.
 *
 * Every field is a finished display value. That is deliberate: it puts the whole of each widget's
 * logic on the JVM, where [WidgetSnapshotMapper] is unit-tested, and leaves the Glance layer as
 * layout only. Nothing here imports `android.*`.
 */
sealed interface WidgetSnapshot {

    /**
     * "Today at a glance": the three numbers the app is about.
     *
     * [blocked] is the same day count the Home dashboard's Blocked tile shows, read through the
     * same query: confrontations the user was SHOWN, each counted once. A widget whose number
     * disagrees with the tile two taps away is a bug report — which is why this used to be
     * deliberately RAW, to match a tile that was itself wrong. Both now read
     * `UsageRepository.getShownCountForDay`, so agreement and correctness are the same thing.
     * [walkedAway] is its complement, not a subset of it.
     */
    data class Today(
        val screenTime: String,
        val blocked: Int,
        val walkedAway: Int,
        val hasUsagePermission: Boolean
    ) : WidgetSnapshot {
        companion object {
            /** Shown when the read failed or permission is missing — never a crash, never a lie. */
            val EMPTY = Today(
                screenTime = NO_VALUE,
                blocked = 0,
                walkedAway = 0,
                hasUsagePermission = false
            )
        }
    }

    /** One row of the "blocked most this week" leaderboard. */
    data class BlockedApp(
        val packageName: String,
        val label: String,
        val count: Int,
        /**
         * Bar length as a whole percentage of the longest bar in this list, 0..100.
         *
         * An integer, not a float, because Glance has no fractional-width modifier: the composable
         * multiplies an available width in `Dp` by this, and an integer keeps the value the test
         * asserts on identical to the value the layout uses.
         */
        val barPercent: Int
    )

    /** "Blocked most this week." Empty list is a legitimate, rendered state, not a hidden widget. */
    data class TopBlocked(val apps: List<BlockedApp>) : WidgetSnapshot {
        companion object {
            val EMPTY = TopBlocked(apps = emptyList())
        }
    }

    /** What the master toggle is doing, and whether the widget is allowed to change it. */
    data class Protection(
        val enabled: Boolean,
        val degraded: Boolean,
        val strictModeEnabled: Boolean
    ) : WidgetSnapshot {

        /** The single visual state, so no composable re-derives it from the three flags. */
        val state: ProtectionState
            get() = when {
                !enabled -> ProtectionState.OFF
                degraded -> ProtectionState.DEGRADED
                else -> ProtectionState.ON
            }

        /**
         * Whether tapping the widget may write the master toggle itself.
         *
         * **This is the Strict Mode contract, as a value.** Turning protection ON is never gated;
         * turning it OFF while the commitment lock is on must go through the app's typed challenge,
         * so the widget refuses to write and opens the app instead. A widget that flipped the pref
         * directly would be a one-tap bypass of the exact lock `docs/architecture/strict-mode.md`
         * exists to close, reachable from the home screen without unlocking anything.
         *
         * Expressed here, and tested here, so the branch is a fact about a value rather than a
         * runtime `if` somebody can quietly invert inside a callback.
         */
        val togglesInWidget: Boolean
            get() = !(enabled && strictModeEnabled)

        companion object {
            /**
             * Fails toward "protection is on and locked": a failed read must never render an
             * inviting one-tap OFF affordance.
             */
            val EMPTY = Protection(enabled = true, degraded = false, strictModeEnabled = true)
        }
    }

    companion object {
        /** The em-dash-free placeholder for a number we could not read. */
        const val NO_VALUE = "--"
    }
}

/** The three states the Protection widget can draw. */
enum class ProtectionState { ON, OFF, DEGRADED }

/**
 * The pure half of every widget: raw repository values in, finished [WidgetSnapshot] out.
 *
 * No `Context`, no Android types, no suspension. Each widget's `provideGlance` does the I/O and
 * hands the results here, which is what makes the widgets' behaviour testable at all — Glance
 * composables are not JVM-testable in this project (see `app/build.gradle.kts` on why
 * `glance-appwidget-testing` is deliberately absent).
 */
object WidgetSnapshotMapper {

    fun today(
        screenTimeFormatted: String,
        blockedCount: Int,
        walkAwayCount: Int,
        hasUsagePermission: Boolean
    ): WidgetSnapshot.Today = WidgetSnapshot.Today(
        // Without Usage Access there is no screen time to show, and "0s" would read as a real
        // measurement of a real zero. A placeholder says "not known" instead of asserting a number
        // we never took. The block counts come from our own database and are unaffected.
        screenTime = if (hasUsagePermission) screenTimeFormatted else WidgetSnapshot.NO_VALUE,
        blocked = blockedCount.coerceAtLeast(0),
        walkedAway = walkAwayCount.coerceAtLeast(0),
        hasUsagePermission = hasUsagePermission
    )

    /**
     * The leaderboard rows for the Top-blocked widget.
     *
     * [stats] comes from `InsightsCalculator.topBlockedApps` - the ONE per-app aggregation in
     * the app - already sorted and already truncated by its caller, and the widget trims again per
     * size variant when it lays the rows out. A third `limit` here was dead: it never once received
     * a list it could shorten.
     *
     * [labels] is what `InstalledAppsRepository` resolved. A package missing from the map is not an
     * error - the app may have been uninstalled since the event was written, and the leaderboard is
     * exactly where that shows up. Falling back to the raw package keeps the row rather than
     * dropping a real block from the count.
     */
    fun topBlocked(
        stats: List<AppInterventionStat>,
        labels: Map<String, String>
    ): WidgetSnapshot.TopBlocked {
        if (stats.isEmpty()) return WidgetSnapshot.TopBlocked.EMPTY
        val visible = stats
        // Relative to the visible list's own top, not the window's: the bars have to fill the
        // widget they are drawn in. The list arrives sorted, but `maxOf` rather than `first()`
        // means a future unsorted caller gets short bars, not bars longer than the track.
        val top = visible.maxOf { it.total }.coerceAtLeast(1)
        return WidgetSnapshot.TopBlocked(
            apps = visible.map { stat ->
                WidgetSnapshot.BlockedApp(
                    packageName = stat.packageName,
                    label = labels[stat.packageName]?.takeIf { it.isNotBlank() } ?: stat.packageName,
                    count = stat.total,
                    barPercent = ((stat.total.toLong() * 100L) / top).toInt().coerceIn(0, 100)
                )
            }
        )
    }

    fun protection(
        enabled: Boolean,
        degraded: Boolean,
        strictModeEnabled: Boolean
    ): WidgetSnapshot.Protection = WidgetSnapshot.Protection(
        enabled = enabled,
        // A switched-OFF Nudge is not a broken Nudge. Reporting "blocking has stopped" over a
        // toggle the user deliberately turned off would train them to ignore the one message that
        // means their phone killed us.
        degraded = degraded && enabled,
        strictModeEnabled = strictModeEnabled
    )
}
