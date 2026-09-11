package com.astraedus.nudge.ui.screens.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard that "which apps pull hardest" is computed in exactly ONE place.
 *
 * [TopBlockedAppsTest] proves the shared function is correct and that `interventions()`
 * currently agrees with it — but a value test cannot see the shape that lets them drift.
 * The failure mode this pins is the cheap one: a future edit re-inlines a per-app loop into
 * `interventions()` "because it already has the events in hand", both surfaces stay green,
 * and the home card and the leaderboard start disagreeing the next time a counting rule
 * changes on one side only. That is the recurring defect class in this package
 * (`docs/architecture/stats-and-charts.md`: two computations of one number).
 *
 * Asserts the invariant (one ranking of [AppInterventionStat] exists in the file), not the
 * spelling, so a faithful refactor re-pins cleanly.
 */
class InterventionsDelegationContractTest {

    /**
     * The file's CODE, with comments stripped — the KDoc on `topBlockedApps` deliberately
     * explains the duplication it replaced, and scanning raw text would make documenting the
     * defect fail the test that prevents it.
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

    private val calculator =
        source("main/java/com/astraedus/nudge/ui/screens/stats/InsightsCalculator.kt")

    @Test
    fun `there is exactly one per-app intervention ranking in the calculator`() {
        val rankings = Regex("""compareByDescending<AppInterventionStat>""")
            .findAll(calculator).count()

        assertEquals(
            "AppInterventionStat must be ranked in exactly one place (topBlockedApps); " +
                "found $rankings rankings. Delegate instead of re-deriving.",
            1,
            rankings
        )
    }

    @Test
    fun `there is exactly one per-app accumulation of block modes`() {
        // The loop body that builds `package -> mode -> count`. Two of these means two
        // answers to one question, which is the whole reason this function was extracted.
        // (Willpower's per-app tally is a DIFFERENT question, resistance, not block modes, 
        // so it is deliberately not matched here; the mode normalisation is the signature.)
        val accumulations = Regex("""normalizeMode\(event\.blockMode\)""").findAll(calculator).count()

        assertEquals(
            "Per-app mode accumulation belongs only in topBlockedApps; found $accumulations.",
            1,
            accumulations
        )
    }

    @Test
    fun `interventions delegates to the shared function`() {
        assertTrue(
            "interventions() must call topBlockedApps() rather than keeping its own copy.",
            calculator.contains("topBlockedApps(events, sinceMs = rangeStart")
        )
    }

    /** The home card is the second caller; if it stops calling in, the extraction was pointless. */
    @Test
    fun `the home dashboard reads the same shared function`() {
        val homeViewModel =
            source("main/java/com/astraedus/nudge/ui/screens/home/HomeViewModel.kt")

        assertTrue(
            "HomeViewModel must source its top-blocked list from InsightsCalculator.topBlockedApps.",
            homeViewModel.contains("topBlockedApps(")
        )
    }

    /**
     * The home card and the week of rows it aggregates must be one emission, not two.
     *
     * `weekEventsFlow` and `screenTimeFlow` are independent `flatMapLatest` chains off a single
     * `dayStartFlow`, so at a midnight rollover they restart independently and for one frame a
     * consumer can hold a new day's boundary beside the previous day's rows. Re-deriving "a week
     * ago" at the card would therefore describe a window its own data was not selected with.
     *
     * So the boundary is computed ONCE, in the query, and travels with the rows. Pinned two ways:
     * exactly one `WEEK_DAYS - 1` expression exists in the file, and the card reads the window
     * off the snapshot rather than off any other flow's day start.
     */
    @Test
    fun `the week boundary is computed once and carried with the rows it selected`() {
        val homeViewModel =
            source("main/java/com/astraedus/nudge/ui/screens/home/HomeViewModel.kt")
        val boundaries = Regex("""startOfDayDaysBefore\([^)]*WEEK_DAYS - 1\s*\)""")
            .findAll(homeViewModel).count()

        assertEquals(
            "The trailing-week boundary must exist in exactly one place (the query that uses " +
                "it); found $boundaries. A second one is a second answer to 'which week'.",
            1,
            boundaries
        )
        assertTrue(
            "The top-blocked window must come from the snapshot the events arrived in.",
            homeViewModel.contains("sinceMs = weekEvents.weekStartMs")
        )
    }
}
