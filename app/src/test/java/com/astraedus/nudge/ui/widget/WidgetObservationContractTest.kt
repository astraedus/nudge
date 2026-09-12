package com.astraedus.nudge.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The widgets OBSERVE everything they display, and consume the deep link that opened them.
 *
 * An earlier design had every preference writer call `requestRefresh()` by hand. Three of the four
 * writers never did, so the Protection widget - push-only, with no tick to fall back on - could not
 * learn that protection had died, which is the single state it exists to announce. The test written
 * to police that rule was a regex parser standing in for something the compiler should guarantee.
 *
 * Both are gone. `NudgeWidgetUpdater` now collects the sources of truth directly, so any write
 * through any instance propagates. What is left to pin is much smaller, and it is the one thing
 * still capable of silently drifting: **the set of things observed must cover the set of things
 * read.** A fourth preference consumed by `WidgetReads` with no collector here would be invisible
 * again - so it is discovered from the reader, never hand-listed.
 */
class WidgetObservationContractTest {

    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        // Comments stripped: these files document in prose the very mechanism they must use.
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private val widgetReads = source("main/java/com/astraedus/nudge/ui/widget/WidgetReads.kt")
    private val updater = source("main/java/com/astraedus/nudge/ui/widget/NudgeWidgetUpdater.kt")
    private val mainActivity = source("main/java/com/astraedus/nudge/MainActivity.kt")

    /** The preference flows the widget layer actually renders, discovered rather than listed. */
    private fun observedByWidgets(): Set<String> =
        Regex("""\bprefs\.(\w+)\.first\(\)""").findAll(widgetReads)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `the discovery finds the widget-visible preferences at all`() {
        // Without this, every assertion below would pass vacuously the moment WidgetReads is
        // refactored into a different call shape.
        assertTrue(
            "Found no `prefs.<flow>.first()` reads in WidgetReads.kt; the discovery regex no " +
                "longer matches the code it is supposed to be reading.",
            observedByWidgets().size >= 3
        )
    }

    @Test
    fun `every preference a widget renders is collected by the updater`() {
        val missing = observedByWidgets().filterNot { flow ->
            Regex("""preferences\.$flow\b""").containsMatchIn(updater)
        }

        assertTrue(
            "NudgeWidgetUpdater must collect every preference the widgets render, or a change to " +
                "it can never reach a push-only widget. Not collected: $missing",
            missing.isEmpty()
        )
    }

    /** Today and Top-blocked render `usage_events`, so a write there has to wake the updater too. */
    @Test
    fun `the updater also observes the event table the widgets read`() {
        assertTrue(
            "The updater must observe usage_events, or a block decision never reaches the " +
                "Today or Top-blocked widgets.",
            updater.contains("observeLatestEventId()")
        )
    }

    /**
     * The whole point of the rewrite: nobody is told to refresh, so nobody can forget.
     *
     * If a `requestRefresh`-style call reappears in the data layer, the enumerate-every-writer
     * design is back and so is the bug it produced.
     */
    @Test
    fun `no data-layer writer pushes widgets by hand any more`() {
        listOf(
            "main/java/com/astraedus/nudge/data/preferences/NudgePreferences.kt",
            "main/java/com/astraedus/nudge/data/repository/UsageRepository.kt"
        ).forEach { path ->
            val text = source(path)
            assertFalse(
                "$path must not know about widgets. Freshness comes from observing the data, " +
                    "not from every writer remembering to announce itself.",
                text.contains("WidgetRefreshSignal") || text.contains("requestRefresh")
            )
        }
    }

    /** One refresh path, and it is isolated per widget so one failure cannot starve the others. */
    @Test
    fun `each widget refreshes in its own child coroutine with its own failure handling`() {
        assertEquals(
            "updateAll must be called from exactly one place, inside a per-widget child.",
            1,
            Regex("""updateAll\(context\)""").findAll(updater).count()
        )
        assertTrue(
            "Each widget update needs its own launch + runCatching: three sequential calls inside " +
                "one runCatching means a throwing first widget starves the other two.",
            Regex("""launch \{[\s\S]{0,200}?runCatching \{[\s\S]{0,120}?updateAll\(context\)""")
                .containsMatchIn(updater)
        )
    }

    /**
     * Every extra that can PRODUCE a route must also be CLEARED when the route is consumed.
     *
     * `onCreate` re-reads the Intent on every creation, and a configuration change recreates the
     * Activity with the same one - so an extra left in place threw the user back to the widget's
     * target on every rotation, for the life of the task. Discovered from the reader so a third
     * deep-link extra cannot be added with no matching removal.
     */
    @Test
    fun `every deep-link extra the Activity reads is removed when the link is consumed`() {
        val readerStart = mainActivity.indexOf("private fun routeFrom(")
        assertTrue("routeFrom not found in MainActivity", readerStart >= 0)
        val reader = mainActivity.substring(readerStart, mainActivity.indexOf("\n    }", readerStart))

        val extrasRead = Regex("""get\w*Extra\(\s*([\w.]+)""").findAll(reader)
            .map { it.groupValues[1].substringAfterLast('.') }
            .toSet()
        assertTrue("Found no Intent extras read by routeFrom; the regex has drifted.", extrasRead.isNotEmpty())

        val consumerStart = mainActivity.indexOf("private fun consumeDeepLink(")
        assertTrue("consumeDeepLink not found in MainActivity", consumerStart >= 0)
        val consumer = mainActivity.substring(consumerStart, mainActivity.indexOf("\n    }", consumerStart))

        val extrasCleared = Regex("""removeExtra\(\s*([\w.]+)""").findAll(consumer)
            .map { it.groupValues[1].substringAfterLast('.') }
            .toSet()

        assertEquals(
            "Every extra routeFrom reads must be removed on consumption, or a rotation replays " +
                "the deep link. Read: $extrasRead, cleared: $extrasCleared",
            extrasRead,
            extrasCleared
        )
    }

    /**
     * Protection state refreshes IMMEDIATELY; only the event stream is coalesced.
     *
     * Rate-limiting was applied to both at first, and device QA found what that costs: a deferred
     * refresh runs later, and later is usually after the user has left the app. The master toggle
     * was switched off about five seconds after a block event, landed inside the cooldown that
     * event had opened, and the widget went on claiming "Blocking on".
     *
     * The rule that came out of it generalises past this class: **a deferral only buys something
     * against a source that actually bursts, and it always costs the chance the refresh never
     * happens at all.** `usage_events` bursts and carries counts; preferences do not burst and
     * carry whether protection is on. Only one of those is worth waiting on.
     *
     * Pinned by SHAPE because there is no value to assert: both paths end in the same `pushAll`,
     * and the only difference is which one goes through the coalescer.
     */
    @Test
    fun `protection state bypasses the coalescer and refreshes immediately`() {
        val preferenceCollector = Regex(
            """preferences\.isGlobalEnabled[\s\S]{0,900}?\.collect \{([^}]*)\}"""
        ).find(updater)?.groupValues?.get(1)
            ?: error("Could not find the preference collector in NudgeWidgetUpdater")

        assertTrue(
            "The preference collector must refresh directly. Sending it through the coalescer " +
                "defers the one state this widget exists to show, past the moment the user " +
                "leaves the app.\nFound: $preferenceCollector",
            preferenceCollector.contains("pushAll(")
        )
        assertFalse(
            "Protection state must NOT be coalesced - it does not burst, so a window buys " +
                "nothing and risks the refresh never running.\nFound: $preferenceCollector",
            preferenceCollector.contains("request()")
        )

        val eventCollector = Regex(
            """observeLatestEventId\(\)[\s\S]{0,400}?\.collect \{([^}]*)\}"""
        ).find(updater)?.groupValues?.get(1)
            ?: error("Could not find the usage-events collector in NudgeWidgetUpdater")

        assertTrue(
            "The events stream DOES burst - a user hitting a wall of blocks writes several rows " +
                "a second - so it must go through the coalescer.\nFound: $eventCollector",
            eventCollector.contains("request()")
        )
    }

    /**
     * A widget must never render a value captured OUTSIDE its composition.
     *
     * The decisive one, and it is a fact about `glance-appwidget` 1.2.0 rather than a preference.
     * `AppWidgetSession.processEvent` handles `UpdateGlanceState` by refreshing an internal
     * `MutableState` from the widget's `GlanceStateDefinition` and letting Compose recompose. It
     * never calls `GlanceAppWidget.provideGlance`. So `provideGlance` runs **once per session**,
     * not once per update, and anything computed before `provideContent` is frozen for that
     * session's lifetime - `initialTimeout = 45s`, `idleTimeout = 5s` by default.
     *
     * All three widgets shipped with `val snapshot = WidgetReads...` above `provideContent`. Every
     * refresh landing inside a live session recomposed the stale capture: the refresh ran, logged,
     * threw nothing, and changed nothing on screen. It cost three device rounds precisely because
     * the session timeouts make it intermittent - a refresh far enough after the last one gets a
     * fresh session and looks perfectly correct.
     *
     * Scanned as shape because no value-level test can see it: the code is *correct* at the moment
     * it runs, and wrong only on the second update within the window.
     */
    @Test
    fun `no widget renders a value captured outside its composition`() {
        val widgetFiles = listOf("TodayWidget.kt", "TopBlockedWidget.kt", "ProtectionWidget.kt")

        widgetFiles.forEach { name ->
            val text = source("main/java/com/astraedus/nudge/ui/widget/$name")
            val start = text.indexOf("override suspend fun provideGlance")
            assertTrue("$name has no provideGlance", start >= 0)
            val contentAt = text.indexOf("provideContent {", start)
            assertTrue("$name never calls provideContent", contentAt > start)

            val prelude = text.substring(start, contentAt)
            val body = text.substring(contentAt)

            // A read whose RESULT is bound above provideContent is a capture: the session freezes
            // it. Seeding the store is fine - that is a write, and the composable re-reads it.
            val captured = Regex("""\bval\s+(\w+)\s*=\s*runCatching\s*\{\s*WidgetReads\.""")
                .findAll(prelude).map { it.groupValues[1] }.toList()
            assertTrue(
                "$name binds a WidgetReads result above provideContent ($captured). " +
                    "provideGlance runs once per SESSION, so that value is frozen and every later " +
                    "refresh re-renders it. Publish into WidgetSnapshotStore and read it inside " +
                    "provideContent instead.",
                captured.isEmpty()
            )

            assertTrue(
                "$name must read its snapshot from the store INSIDE provideContent, so a " +
                    "recomposition picks up the newest value.",
                Regex("""provideContent \{[\s\S]{0,600}?\bstore\.""").containsMatchIn(body)
            )
        }
    }

    /** The updater must publish fresh data BEFORE asking Glance to update, or the recomposition finds the old value. */
    @Test
    fun `the updater republishes snapshots before every update`() {
        val publishAt = updater.indexOf("publishSnapshots()")
        val updateAt = updater.indexOf("updateAll(context)")

        assertTrue("NudgeWidgetUpdater must republish snapshots on every refresh", publishAt >= 0)
        assertTrue(
            "publishSnapshots() must run BEFORE updateAll - an update on a live session only " +
                "recomposes, so whatever is published at that moment is what appears.",
            publishAt < updateAt
        )
        listOf("publishToday", "publishTopBlocked", "publishProtection").forEach { call ->
            assertTrue(
                "Every widget's data must be republished, or that widget keeps a stale frame: $call",
                updater.contains(call)
            )
        }
    }
}
