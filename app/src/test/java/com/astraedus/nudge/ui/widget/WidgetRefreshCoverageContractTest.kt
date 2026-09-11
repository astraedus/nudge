package com.astraedus.nudge.ui.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every preference a widget RENDERS must push a refresh when it is WRITTEN.
 *
 * The Protection widget declares `updatePeriodMillis="0"` — it is push-only, on purpose, because
 * polling a toggle every half hour is worse than useless. That makes the push the entire update
 * mechanism, and a writer that forgets it does not degrade the widget, it freezes it.
 *
 * Device QA found two instances of that at once, and they are the reason this test discovers its
 * own inputs rather than listing them:
 *
 *  - `recordProtectionCheck` wrote `protectionDegraded` and pushed nothing, so **"blocking has
 *    stopped" — the single state this widget exists to surface — was the one state it could never
 *    receive.** A widget placed while healthy stayed healthy-looking indefinitely; only a freshly
 *    placed instance ever showed the truth. An alarm nobody can observe is not an alarm.
 *  - `setStrictModeEnabled` pushed nothing either, so after turning Strict Mode on the widget went
 *    on drawing the unlocked TOGGLE affordance. The tap was then correctly refused by the guard in
 *    `ToggleProtectionAction`, which read to the tester as "the first tap does nothing".
 *
 * Writing that list by hand would have pinned yesterday's bug. Instead the test reads
 * `WidgetReads.kt` to find which preference flows the widgets actually consume, resolves each to
 * its `Keys.*` constant, and then requires every function that writes that key to push. A fourth
 * widget-visible preference, or a new write path to an existing one (the settings-import path was
 * exactly that, and was also missing its push), fails here until it is wired.
 */
class WidgetRefreshCoverageContractTest {

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        // Comments stripped: the KDoc above each fix quotes the very call it documents.
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private val widgetReads = source("main/java/com/astraedus/nudge/ui/widget/WidgetReads.kt")
    private val preferences =
        source("main/java/com/astraedus/nudge/data/preferences/NudgePreferences.kt")

    /** The preference flows the widget layer reads, discovered rather than listed. */
    private fun widgetVisibleFlows(): Set<String> =
        Regex("""\bprefs\.(\w+)\.first\(\)""").findAll(widgetReads)
            .map { it.groupValues[1] }
            .toSet()

    /** `isGlobalEnabled` -> `GLOBAL_ENABLED`, by reading the flow's own declaration. */
    private fun keyFor(flow: String): String =
        Regex("""val $flow:[\s\S]{0,400}?Keys\.(\w+)""").find(preferences)
            ?.groupValues?.get(1)
            ?: error("Could not resolve the Keys.* constant backing NudgePreferences.$flow")

    /**
     * Every function in NudgePreferences that writes [key], as (name, body).
     *
     * Brace-matched from each write site backwards to its enclosing declaration, rather than
     * split on the `suspend fun` keyword. The split version misattributed two writes to the
     * function declared above the real one and reported them as failures — a contract test that
     * cries wolf gets deleted, so the parsing has to be exact.
     */
    private fun writersOf(key: String): List<Pair<String, String>> {
        // The " =" is load-bearing: `prefs[Keys.X]` on its own also matches the READ inside each
        // Flow declaration, whose nearest preceding `fun` is some unrelated function further up.
        // That misattribution reported two healthy functions as failures on the first run.
        val marker = "prefs[Keys.$key] ="
        val results = mutableListOf<Pair<String, String>>()
        var from = 0
        while (true) {
            val write = preferences.indexOf(marker, from)
            if (write < 0) break
            from = write + marker.length

            // Nearest enclosing declaration, searching backwards.
            val declaration = Regex("""(?:suspend )?fun (\w+)\(""")
                .findAll(preferences.substring(0, write))
                .lastOrNull() ?: continue
            val name = declaration.groupValues[1]

            // Brace-match the body from the first '{' after the signature.
            val open = preferences.indexOf('{', declaration.range.last)
            if (open < 0) continue
            var depth = 0
            var i = open
            while (i < preferences.length) {
                if (preferences[i] == '{') depth++
                if (preferences[i] == '}') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            results += name to preferences.substring(open, minOf(i + 1, preferences.length))
        }
        return results.distinctBy { it.first }
    }

    @Test
    fun `the widget layer reads preferences at all`() {
        // Guards the whole test against silently passing on an empty discovery — if the read
        // helper is refactored to a different call shape, this fails rather than going vacuous.
        assertTrue(
            "Discovered no `prefs.<flow>.first()` reads in WidgetReads.kt; the discovery regex " +
                "no longer matches the code, so every assertion below would pass vacuously.",
            widgetVisibleFlows().size >= 3
        )
    }

    @Test
    fun `every preference a widget renders pushes a refresh on every write path`() {
        val failures = mutableListOf<String>()

        widgetVisibleFlows().forEach { flow ->
            val key = keyFor(flow)
            val writers = writersOf(key)
            if (writers.isEmpty()) {
                failures += "$flow (Keys.$key): no writer found — the resolver is wrong, not the code"
                return@forEach
            }
            writers.forEach { (name, body) ->
                if (!body.contains("widgetRefreshSignal.requestRefresh()")) {
                    failures += "$name() writes Keys.$key, which a widget renders, " +
                        "but never calls widgetRefreshSignal.requestRefresh()"
                }
            }
        }

        assertTrue(
            "A push-only widget freezes on the write it is not told about:\n" +
                failures.joinToString("\n") { "  - $it" },
            failures.isEmpty()
        )
    }

    /**
     * The push must come AFTER the write, or the refresh reads the value it is replacing and the
     * widget renders one state behind — which looks exactly like no push at all.
     */
    @Test
    fun `the refresh is pushed after the write completes, not before`() {
        val failures = mutableListOf<String>()

        widgetVisibleFlows().forEach { flow ->
            writersOf(keyFor(flow)).forEach { (name, body) ->
                val write = body.indexOf("dataStore.edit")
                val push = body.indexOf("widgetRefreshSignal.requestRefresh()")
                if (write >= 0 && push >= 0 && push < write) {
                    failures += "$name() pushes the widget refresh before its own write lands"
                }
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
