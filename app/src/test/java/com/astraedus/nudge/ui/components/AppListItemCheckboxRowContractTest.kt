package com.astraedus.nudge.ui.components

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape contract for every [AppListItem] row that carries a `Checkbox`: the WHOLE row is the one
 * toggle target (`Modifier.toggleable(... role = Role.Checkbox ...)`) and the `Checkbox` itself
 * takes no clicks (`onCheckedChange = null`).
 *
 * Bug it guards (1.31.0, fixed in 1.31.1): the Nuke app list gave its Checkbox the click handler and
 * passed the row's handler to an `onClick` parameter that [AppListItem] declared and never used, so
 * tapping an app's icon or name did nothing; only the small box toggled. The compiler now rejects an
 * `onClick` argument (the parameter is gone), but it cannot see a row that simply forgets the
 * modifier, which is the shape this scan checks. A Compose UI test would be the stronger layer; the
 * repo defers Robolectric/emulator UI tests (docs/testing-strategy.md), so the device half of this
 * is `scripts/device-qa.sh nuke-row-tap`.
 */
class AppListItemCheckboxRowContractTest {

    /** Each `AppListItem(...)` call in production UI code, as its full argument text. */
    private fun appListItemCalls(): List<Pair<String, String>> =
        SourceScan.kotlinSources("com/astraedus/nudge/ui").flatMap { file ->
            val code = SourceScan.code(file)
            Regex("""\bAppListItem\(""").findAll(code)
                // Skip the declaration itself.
                .filterNot { code.substring(maxOf(0, it.range.first - 4), it.range.first).endsWith("fun ") }
                .map { file.name to balancedArgs(code, it.range.last) }
                .toList()
        }

    /** The text between the `(` at [open] and its matching `)`. */
    private fun balancedArgs(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        error("unbalanced AppListItem( call")
    }

    @Test
    fun `the scan finds the checkbox rows it is meant to check`() {
        val files = appListItemCalls().filter { "Checkbox(" in it.second }.map { it.first }.toSet()
        // If these move or are renamed, update the list; an empty match would make the contract vacuous.
        assertTrue("expected the Nuke list and the shared picker among checkbox rows, got $files",
            files.containsAll(setOf("NukeScreen.kt", "AppPicker.kt")))
    }

    @Test
    fun `every checkbox row is one toggleable target and the box takes no clicks`() {
        val bad = appListItemCalls().filter { (_, args) -> "Checkbox(" in args }.filterNot { (_, args) ->
            val compact = args.replace(Regex("""\s+"""), " ")
            "Modifier.toggleable(" in compact &&
                "role = Role.Checkbox" in compact &&
                Regex("""Checkbox\([^)]*onCheckedChange = null""").containsMatchIn(compact)
        }
        assertTrue(
            "An AppListItem row with a Checkbox must put Modifier.toggleable(role = Role.Checkbox) on " +
                "the row and give the Checkbox onCheckedChange = null (1.31.0 Nuke list: icon and name " +
                "taps did nothing). Offending calls in: ${bad.map { it.first }}",
            bad.isEmpty()
        )
    }
}
