package com.astraedus.nudge.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The two things about [ProportionalBar] that can be wrong without anyone looking at a screen:
 * the clamp, and whether it is still the only bar of its kind.
 *
 * No Android deps in either, so this runs on the plain JVM.
 */
class ProportionalBarTest {

    // ── barFillFraction() ──

    @Test
    fun `a fraction inside the range is passed through untouched`() {
        assertEquals(0f, barFillFraction(0f), 0f)
        assertEquals(0.25f, barFillFraction(0.25f), 0f)
        assertEquals(1f, barFillFraction(1f), 0f)
    }

    @Test
    fun `an over-full fraction is clamped rather than overflowing the track`() {
        // Reachable from float division: a count divided by a leader that rounds a hair low.
        assertEquals(1f, barFillFraction(1.0000001f), 0f)
        assertEquals(1f, barFillFraction(7f), 0f)
        assertEquals(1f, barFillFraction(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `a negative fraction is clamped to empty`() {
        assertEquals(0f, barFillFraction(-0.5f), 0f)
        assertEquals(0f, barFillFraction(Float.NEGATIVE_INFINITY), 0f)
    }

    /**
     * The case a bare `coerceIn` does NOT catch, and the reason this function exists at all.
     *
     * Every comparison against NaN is false, so `coerceIn` returns it unchanged and it reaches
     * `Modifier.fillMaxWidth`, where it becomes a NaN width in layout. Both call sites divide
     * by a value read from data (`count / topCount`, `usage / total`), and `0 / 0` is one empty
     * week or one all-zero day away.
     */
    @Test
    fun `NaN falls back to empty instead of reaching layout`() {
        assertEquals(0f, barFillFraction(Float.NaN), 0f)
        assertEquals(0f, barFillFraction(0f / 0f), 0f)
    }

    // ── the one-bar invariant ──

    /**
     * Source-level guard that there is still exactly ONE proportional bar in the app.
     *
     * The stats screen's usage bar and the dashboard's top-blocked bar were the same track-plus-
     * fill structure with different, unchosen geometry (8dp/4dp against 6dp/3dp). Nobody decided
     * that; it drifted, the way two computations of one number drift in this package
     * (`docs/architecture/stats-and-charts.md`). A value test cannot see a third one appearing.
     *
     * `Modifier.fillMaxWidth(<fraction>)` — with an argument, as opposed to the bare
     * `fillMaxWidth()` that is everywhere — is the signature of "draw this much of that", so it
     * belongs only to the component that owns the geometry.
     */
    @Test
    fun `the proportional fill idiom exists only in the shared component`() {
        val fractionalFill = Regex("""fillMaxWidth\(\s*[^)\s]""")
        val offenders = mainSources()
            .filter { it.name != "ProportionalBar.kt" }
            .filter { fractionalFill.containsMatchIn(code(it)) }
            .map { it.name }
            .sorted()

        assertEquals(
            "A bar that fills a fraction of its track belongs in ProportionalBar, which owns " +
                "the height, the radius and the clamp; found a hand-rolled one in $offenders.",
            emptyList<String>(),
            offenders
        )
    }

    /** The file's CODE, with comments stripped: this KDoc names the idiom it forbids. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("main sources not found from working dir ${File("").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
