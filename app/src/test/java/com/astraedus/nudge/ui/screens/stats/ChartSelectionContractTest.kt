package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.testing.SourceScan
import org.junit.Test

/**
 * The SHAPE residue of the day-selection contract, after the behaviour moved to
 * [ChartSelectionTest] (through `StatsDaySelection`).
 *
 * The bug: `WeeklyBarChart` and `BlockedTrendChart` each held
 * `var selectedIndex by remember { mutableStateOf<Int?>(null) }`. Tapping a bar mutated that
 * private state and no number on the screen moved. Compose UI is not JVM-testable here, and
 * `selectedIndex` / `onSelectDay` default to null, so omitting the wiring still compiles; these
 * scans pin the things the compiler and the holder test cannot see.
 */
class ChartSelectionContractTest {

    private val statsDir = "com/astraedus/nudge/ui/screens/stats"
    private val charts = listOf("charts/WeeklyBarChart.kt", "charts/BlockedTrendChart.kt")
    private val screens = listOf("StatsScreen.kt", "AppDetailScreen.kt")

    @Test
    fun `a day-scoped chart owns no selection state of its own`() {
        charts.forEach {
            SourceScan.assertNoCall(
                SourceScan.kotlinFile("$statsDir/$it"),
                "mutableStateOf",
                "A tapped bar highlights itself and no number on the screen moves. Take " +
                    "selectedIndex from the caller (StatsDaySelection) instead."
            )
        }
    }

    @Test
    fun `every day-scoped screen wires its charts and its way back to the shared selection`() {
        screens.forEach {
            val file = SourceScan.kotlinFile("$statsDir/$it")
            val why = "Chart parameters default to null, so a dropped argument compiles and the " +
                "chart silently stops following the screen's day."
            SourceScan.assertCall(file, "selectedIndex = state.selectedDayIndex", why)
            SourceScan.assertCall(file, "onSelectDay = viewModel::selectDay", why)
            SourceScan.assertCall(file, "onJumpToToday = viewModel::jumpToToday", why)
        }
    }

    /** Home embeds the same charts read-only; a callback there starts an interaction it cannot show. */
    @Test
    fun `the home dashboard embeds the charts read-only`() {
        val home = SourceScan.kotlinFile("com/astraedus/nudge/ui/screens/home/HomeScreen.kt")
        SourceScan.assertNoCall(
            home,
            "onSelectDay =",
            "Home charts must stay read-only so taps open the stats screen."
        )
        SourceScan.assertCall(
            home,
            "onClick = onNavigateToStats",
            "The home charts card must navigate to the full stats screen."
        )
    }
}
