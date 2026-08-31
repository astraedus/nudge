package com.astraedus.nudge.ui.screens.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for "one source of truth for a day's screen time", in the same spirit as
 * [ChartSelectionContractTest] and `BlockOverlayWalkAwayContractTest`: the defect was not in any
 * VALUE a unit test could inspect, it was in the SHAPE of the code — two computations of one
 * calendar day, sitting on the same screen.
 *
 * The bug: `getDailyScreenTimesForWeek` built the bars out of
 * `queryUsageStats(INTERVAL_DAILY)` (pre-aggregated buckets the provider's own comment already
 * called stale and misaligned) while the drill-down under those bars summed live `queryEvents`
 * spans. On the Pixel 3 a Wednesday bar rendered tall and dark over a drill-down that read
 * "0s / No usage recorded" — each half internally consistent, and flatly contradicting the other.
 *
 * These assertions describe the CLASS of defect. Nothing here can be caught by exercising the
 * ViewModels on the JVM (they need `UsageStatsManager`), so the shape is what gets pinned: any
 * future edit that reintroduces a day-scoped screen-time query beside the weekly one, or brings
 * back the pre-aggregated API, fails here.
 */
class ScreenTimeSourceContractTest {

    /**
     * The file's CODE, with comments stripped.
     *
     * These assertions are about what the code does, and the files deliberately document the
     * defect they were fixed for — naming `INTERVAL_DAILY` and `DAY_MS` in prose so the next
     * reader knows why they are absent. Scanning raw text would make writing that explanation
     * fail the test that protects it.
     */
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private val screenTimeProvider =
        "main/java/com/astraedus/nudge/data/repository/ScreenTimeProvider.kt"

    /** Every screen that draws a screen-time bar and a number describing one of those bars. */
    private val screenTimeConsumers = listOf(
        "main/java/com/astraedus/nudge/ui/screens/stats/StatsViewModel.kt",
        "main/java/com/astraedus/nudge/ui/screens/stats/AppDetailViewModel.kt",
        "main/java/com/astraedus/nudge/ui/screens/home/HomeViewModel.kt"
    )

    /** The exact API that shipped the bug. */
    @Test
    fun `screen-time series never come from pre-aggregated daily buckets`() {
        assertFalse(
            "$screenTimeProvider must not read queryUsageStats(INTERVAL_DAILY): those buckets " +
                "are stale and midnight-misaligned on Android 12+, which is how a tall bar came " +
                "to sit over a '0s' drill-down. Read events and bucket them (getWeeklyUsage).",
            source(screenTimeProvider).contains("INTERVAL_DAILY")
        )
    }

    @Test
    fun `the provider offers one weekly source and no day-scoped total beside it`() {
        val text = source(screenTimeProvider)

        assertTrue(
            "$screenTimeProvider must expose the one weekly source",
            text.contains("fun getWeeklyUsage(")
        )
        listOf(
            "fun getTotalScreenTime(",
            "fun getTotalScreenTimeToday(",
            "fun getPerAppScreenTime(",
            "fun getPerAppScreenTimeToday(",
            "fun getDailyScreenTimesForWeek(",
            "fun getPerAppDailyScreenTimesForWeek("
        ).forEach { signature ->
            assertFalse(
                "$screenTimeProvider must not offer `$signature` — a second way to total a day " +
                    "is a second answer for it. Read the day out of getWeeklyUsage instead.",
                text.contains(signature)
            )
        }
    }

    /**
     * The bars and the numbers under them have to be the same read. A screen that calls the
     * weekly source twice, or pairs it with its own day query, is back to two answers.
     */
    @Test
    fun `every screen reads its bars and its day numbers from one weekly value`() {
        screenTimeConsumers.forEach { path ->
            val text = source(path)

            assertEquals(
                "$path must read the week exactly once — a second read is a second answer",
                1,
                Regex("getWeeklyUsage\\(").findAll(text).count()
            )
            assertFalse(
                "$path must not compute a day's screen time separately from the weekly series",
                text.contains("getTotalScreenTime(") || text.contains("getPerAppScreenTime(")
            )
        }
    }

    /**
     * The provider reads events; it does not decide who was in the foreground.
     *
     * It used to do both, three times over — the weekly bars, the hourly heatmap and the Willpower
     * session stats each walked the cursor with their own `package -> startTime` map. Nothing in
     * those maps said only ONE app can be foreground at a time, so a missing `ACTIVITY_PAUSED`
     * left an app accruing time while the next app accrued its own, and each open span was billed
     * through to "now" however stale it was. A real phone reported ~17 hours of screen time before
     * lunchtime. `ForegroundSpanTracker` is now the single answer, and one loop feeds it.
     */
    @Test
    fun `the provider does not pair foreground events itself`() {
        val text = source(screenTimeProvider)

        assertFalse(
            "$screenTimeProvider must not keep its own package -> start-time map: several open " +
                "spans at once is how a day came to hold more hours than the day. Feed " +
                "ForegroundSpanTracker, which emits non-overlapping spans.",
            text.contains("foregroundStarts")
        )
        assertTrue(
            "$screenTimeProvider must route every read through ForegroundSpanTracker",
            text.contains("ForegroundSpanTracker")
        )
        assertEquals(
            "$screenTimeProvider must walk the event cursor in exactly one place — a second " +
                "loop is a second interpretation of which app was on screen, and the three it " +
                "used to have all drifted",
            1,
            Regex("""while \(events\.hasNextEvent\(\)\)""").findAll(text).count()
        )
    }

    /**
     * A day is looked up by its start timestamp, not by an index into whatever happens to be
     * loaded. The selection moves the instant an arrow is tapped while the new window is still in
     * flight; an index would quietly resolve to a different date for that frame — one day's
     * numbers under another day's heading, which is the defect all over again.
     */
    @Test
    fun `the day drill-down addresses its day by timestamp`() {
        listOf(screenTimeConsumers[0], screenTimeConsumers[1]).forEach { path ->
            assertTrue(
                "$path must read its day out of the weekly value by day-start timestamp",
                source(path).contains("perAppOn(")
            )
        }
        assertTrue(
            "${screenTimeConsumers[2]} must read today's tile out of the same weekly value",
            source(screenTimeConsumers[2]).contains("totalOn(")
        )
    }

    /**
     * Day boundaries are calendar arithmetic everywhere a day-scoped number is bucketed or
     * labelled. `+ 86_400_000` is an hour off true local midnight across a DST transition, and
     * these series are drawn side by side — one chart's "Wed" must not cover a different 24 hours
     * from its neighbour's.
     */
    @Test
    fun `day boundaries are walked by calendar, not by adding 24 hours`() {
        val dayWalkers = screenTimeConsumers + listOf(
            "main/java/com/astraedus/nudge/ui/screens/stats/StatsCalculator.kt",
            screenTimeProvider
        )
        dayWalkers.forEach { path ->
            assertFalse(
                "$path must not derive a day boundary with raw millis arithmetic — use " +
                    "TimeTracker.startOfDayDaysBefore so a DST day is genuinely 23 or 25 hours",
                source(path).contains("DAY_MS")
            )
        }
    }
}
