package com.astraedus.nudge.ui.screens.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for "the insight screens are reachable from more than one unlabelled tile",
 * in the same spirit as [ScreenTimeSourceContractTest]: the defect was never in a VALUE any unit
 * test could inspect, it was in the SHAPE of the navigation graph.
 *
 * The bug, reported by the owner of this app in his own words: *"I had no idea I could click on
 * the Blocked and Walked Away tiles on Home and that they lead to two different screens with
 * charts."* Willpower and Interventions had exactly one entry point each — an unlabelled,
 * ripple-less Home tile — so two entire screens of charts were, in practice, unreachable. Usage
 * Stats, the screen someone actually opens when they want to understand their numbers, linked to
 * neither of them.
 *
 * These assertions describe the CLASS of defect rather than any one wording. A faithful refactor
 * (renaming the private card composable, rewording the copy, reordering the parameters) re-pins
 * cleanly; letting a callback rot back into an unused default, dropping a card's supporting line
 * so it reads as an unlabelled icon again, or forgetting the NavGraph wiring, does not.
 */
class StatsInsightEntryContractTest {

    /**
     * The file's CODE, with comments stripped.
     *
     * Same helper as [ScreenTimeSourceContractTest], and for the same reason: these files document
     * the defect they were fixed for in prose, naming the very things the assertions forbid.
     * Scanning raw text would make writing that explanation fail the test protecting it.
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

    private val statsScreen = "main/java/com/astraedus/nudge/ui/screens/stats/StatsScreen.kt"
    private val navGraph = "main/java/com/astraedus/nudge/ui/navigation/NavGraph.kt"

    /** The two destinations Usage Stats owes an entry point to. */
    private val callbacks = listOf("onNavigateToWillpower", "onNavigateToInterventions")

    /**
     * The slice of the screen between the streak counter and the first chart section — where the
     * interpretation of the numbers belongs, above the raw charts it interprets.
     */
    private fun insightEntryRegion(): String {
        val text = source(statsScreen)
        val start = text.indexOf("StreakCounter(")
        val end = text.indexOf("\"Screen time\"")

        assertTrue(
            "$statsScreen must still render the StreakCounter — this test navigates by it",
            start >= 0
        )
        assertTrue(
            "$statsScreen must still render the \"Screen time\" section — this test navigates by it",
            end >= 0
        )
        assertTrue(
            "The insight entry points must sit AFTER the streak counter and BEFORE the first " +
                "chart section: they are the interpretation of those charts, and a reader who " +
                "scrolls past the charts has already given up looking.",
            end > start
        )
        return text.substring(start, end)
    }

    @Test
    fun `StatsScreen takes a callback for each insight screen`() {
        val text = source(statsScreen)

        callbacks.forEach { name ->
            assertTrue(
                "$statsScreen must declare `$name: () -> Unit` so NavGraph can wire Usage Stats " +
                    "to that insight screen. Without it the only route there is an unlabelled " +
                    "Home tile, which is the reported bug.",
                Regex("""$name\s*:\s*\(\s*\)\s*->\s*Unit""").containsMatchIn(text)
            )
        }
    }

    /**
     * Declared once, used once. Twice would be the Home screen's other defect — the same
     * destination behind two tap targets, with nothing saying either is a link.
     */
    @Test
    fun `each insight callback is wired to exactly one entry point`() {
        val text = source(statsScreen)

        callbacks.forEach { name ->
            assertEquals(
                "$statsScreen must mention `$name` exactly twice: the parameter and the one " +
                    "card that calls it. Once means the parameter is a dead default nothing " +
                    "invokes; three or more means two tiles lead to the same place again.",
                2,
                Regex(Regex.escape(name)).findAll(text).count()
            )
        }
    }

    /**
     * Both entry points live in the region, each with its own Material icon and each carrying a
     * supporting line. The density rule is the point: an icon plus one word is exactly the
     * unlabelled affordance nobody recognised as tappable.
     */
    @Test
    fun `both entry cards are present, iconographed and explained`() {
        val region = insightEntryRegion()

        callbacks.forEach { name ->
            assertTrue(
                "The insight entry row must invoke `$name` — a card that navigates nowhere is " +
                    "worse than no card",
                region.contains(name)
            )
        }

        listOf("Icons.Outlined.ThumbUp", "Icons.Outlined.Block").forEach { icon ->
            assertTrue(
                "The insight entry row must use the Material icon `$icon`. Material icons only; " +
                    "an emoji is not iconography.",
                region.contains(icon)
            )
        }

        val supportingLines = Regex("\"[^\"\\\\\\n]{20,}\"").findAll(region).count()
        assertTrue(
            "Each of the two insight entry cards owes a supporting line saying what is behind " +
                "it — found $supportingLines strings long enough to be one, expected at least 2. " +
                "A card of an icon and a single word reads unfinished and teaches nothing.",
            supportingLines >= 2
        )
    }

    /**
     * The tap target has to announce itself, or a TalkBack user meets the same dead end by a
     * different road: "button, double tap to activate" says no more than an unlabelled tile did.
     */
    @Test
    fun `the insight entry cards announce their action to TalkBack`() {
        assertTrue(
            "$statsScreen must give its insight entry cards an `onClickLabel` so TalkBack says " +
                "where the tap goes, not merely that something is clickable",
            source(statsScreen).contains("onClickLabel")
        )
    }

    /**
     * Emoji are not iconography here. This is an invariant over the whole file rather than the
     * one card, so the next person to add a section cannot reintroduce it either.
     */
    @Test
    fun `the stats screen uses no emoji as iconography`() {
        val text = source(statsScreen)
        val emoji = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}]""")
        val found = emoji.findAll(text).map { it.value }.toList()

        assertTrue(
            "$statsScreen must not use emoji as iconography — found $found. Material icons only.",
            found.isEmpty()
        )
    }

    /**
     * The parameters exist only if something passes them. Defaulting them to `{}` keeps the file
     * compiling on its own, which is exactly why this assertion has to be here: an unwired
     * callback is a silently inert card, not a build error.
     *
     * Note for whoever runs this first: this test FAILS until the NavGraph call site is updated.
     * That failure is the test doing its job.
     */
    @Test
    fun `NavGraph wires both insight callbacks at the StatsScreen call site`() {
        val text = source(navGraph)
        val marker = text.indexOf("StatsScreen(")
        assertTrue("$navGraph must still call StatsScreen(", marker >= 0)

        val open = text.indexOf('(', marker)
        var depth = 0
        var end = -1
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        assertTrue("$navGraph has an unbalanced StatsScreen( call site", end > open)
        val callSite = text.substring(open, end + 1)

        callbacks.forEach { name ->
            assertTrue(
                "$navGraph must pass `$name` to StatsScreen, or the card renders and does " +
                    "nothing. The parameter defaults to `{}`, so nothing else will catch this.",
                callSite.contains(name)
            )
        }
    }
}
