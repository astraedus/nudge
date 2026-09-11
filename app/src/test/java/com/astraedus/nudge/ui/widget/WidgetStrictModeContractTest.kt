package com.astraedus.nudge.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decisive test for the Protection widget: **a home-screen widget may never weaken protection
 * behind Strict Mode's back.**
 *
 * `docs/architecture/strict-mode.md` describes a commitment lock that gates every
 * protection-WEAKENING action behind a typed unlock challenge. `HomeViewModel.toggleGlobalEnabled`
 * honours it. A widget that called `setGlobalEnabled(false)` on its own would be a ONE-TAP BYPASS of
 * that lock, sitting on the launcher, reachable without unlocking anything — a hole straight through
 * the product's purpose, and not one any value-level unit test would notice, because each individual
 * line of it looks correct.
 *
 * So this test asserts the SHAPE of the code, the same technique as
 * [com.astraedus.nudge.ui.screens.stats.ScreenTimeSourceContractTest] and
 * `BlockOverlayWalkAwayContractTest`. The value-level half of the same contract lives in
 * `WidgetSnapshotMapperTest`, which exercises `Protection.togglesInWidget` over all eight inputs.
 * Both halves are needed: the value test proves the RULE is right, this one proves the rule is
 * actually what the code consults.
 */
class WidgetStrictModeContractTest {

    /**
     * A file's CODE, with comments stripped.
     *
     * These assertions are about what the code DOES, and the widget sources deliberately explain in
     * prose the very calls they must not make — naming `setGlobalEnabled(false)` and `startActivity`
     * so the next reader knows why they are shaped this way. Scanning raw text would make writing
     * that explanation fail the test that protects it.
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

    private val widgetDir = File("app/src/main/java/com/astraedus/nudge/ui/widget")
        .takeIf { it.exists() }
        ?: File("src/main/java/com/astraedus/nudge/ui/widget")

    private fun widgetSources(): List<File> {
        assertTrue(
            "ui/widget/ must exist — it is the package this contract governs",
            widgetDir.isDirectory
        )
        return widgetDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val protectionWidget =
        "main/java/com/astraedus/nudge/ui/widget/ProtectionWidget.kt"

    /**
     * The whole contract in one assertion: every write that turns protection OFF anywhere in the
     * widget package sits on a line that ALSO carries a negated Strict Mode guard.
     *
     * Same-line, deliberately. A guard on the preceding line can be separated from what it guards by
     * an innocent-looking refactor, and the failure would be silent and unreachable from any JVM
     * test. Keeping them on one line makes the two impossible to part without this test noticing.
     */
    @Test
    fun `no widget turns protection off outside a strict-mode guard`() {
        val offending = mutableListOf<String>()
        var guardedWrites = 0

        widgetSources().forEach { file ->
            source(file.path.substringAfter("src/")).lines().forEach { line ->
                if (!line.contains("setGlobalEnabled(false)")) return@forEach
                if (line.contains("!strictModeEnabled")) {
                    guardedWrites++
                } else {
                    offending += "${file.name}: ${line.trim()}"
                }
            }
        }

        assertEquals(
            "A widget may only write setGlobalEnabled(false) on a line that also carries the " +
                "!strictModeEnabled guard. Turning protection OFF is a WEAKENING action: while " +
                "Strict Mode is on it must go through the app's typed challenge, or the widget is " +
                "a one-tap bypass of the commitment lock. Offending: $offending",
            emptyList<String>(),
            offending
        )
        assertEquals(
            "Exactly one guarded write is expected, in ToggleProtectionAction. More than one means " +
                "the decision has been duplicated; zero means the toggle stopped working and this " +
                "test would otherwise pass vacuously.",
            1,
            guardedWrites
        )
    }

    /**
     * The guard has to be read from the preference, not from whatever the widget happened to be
     * rendering when it was drawn. That snapshot can be minutes old.
     */
    @Test
    fun `the strict-mode guard is read fresh inside the callback`() {
        val text = source(protectionWidget)
        assertTrue(
            "$protectionWidget must read isStrictModeEnabled from NudgePreferences inside the " +
                "action callback — a stale snapshot is not a safe basis for writing a protection " +
                "setting",
            Regex("""strictModeEnabled\s*=\s*preferences\.isStrictModeEnabled\.first\(\)""")
                .containsMatchIn(text)
        )
    }

    /**
     * The locked branch must be an `actionStartActivity` chosen at COMPOSE time, never a runtime
     * `startActivity` inside the callback.
     *
     * This is not a style preference. An `ActionCallback` runs from a broadcast, and starting an
     * Activity from there is subject to the API 31+ background-activity-start restriction: it would
     * silently do nothing on any modern phone, so a locked widget would read to the user as a dead
     * widget rather than as a locked one — and the tap that was supposed to raise the challenge
     * would simply be lost.
     */
    @Test
    fun `the toggle callback never starts an activity`() {
        val text = source(protectionWidget)
        val callback = text.substringAfter("class ToggleProtectionAction")
        assertTrue(
            "ToggleProtectionAction must exist in $protectionWidget",
            text.contains("class ToggleProtectionAction")
        )
        listOf("startActivity", "actionStartActivity", "Intent(").forEach { forbidden ->
            assertFalse(
                "ToggleProtectionAction must not contain `$forbidden`: background activity starts " +
                    "are blocked on API 31+, so the Strict Mode branch has to be a different " +
                    "composable with actionStartActivity, chosen at compose time from the snapshot",
                callback.contains(forbidden)
            )
        }
    }

    /**
     * And the compose-time branch must actually exist and be driven by the snapshot's own rule.
     * Without this the previous two tests would pass over a widget that simply had no locked path.
     */
    @Test
    fun `the locked branch opens the app instead of writing`() {
        val text = source(protectionWidget)
        assertTrue(
            "$protectionWidget must branch on WidgetSnapshot.Protection.togglesInWidget, the one " +
                "place the Strict Mode decision is made (and the one that is unit-tested)",
            text.contains("togglesInWidget")
        )
        assertTrue(
            "$protectionWidget must offer an actionStartActivity path for the locked case, so the " +
                "user meets the real typed challenge in the app",
            text.contains("openAppAt(")
        )
        assertTrue(
            "$protectionWidget must offer an actionRunCallback path for the unlocked case",
            text.contains("actionRunCallback<ToggleProtectionAction>()")
        )
    }

    /**
     * Nothing else in the widget package may write ANY preference. The widgets are a read surface
     * plus exactly one audited action; a second writer would be a second place to get this wrong.
     */
    @Test
    fun `the widget package writes no preference other than the audited master toggle`() {
        val writes = mutableListOf<String>()
        widgetSources().forEach { file ->
            source(file.path.substringAfter("src/")).lines().forEach { line ->
                val match = Regex("""\.set[A-Z][A-Za-z]*\(""").find(line) ?: return@forEach
                val call = match.value.removePrefix(".").removeSuffix("(")
                if (call != "setGlobalEnabled") writes += "${file.name}: $call"
            }
        }
        assertEquals(
            "The widget package must not write preferences beyond the audited master toggle. " +
                "Found: $writes",
            emptyList<String>(),
            writes
        )
    }

    /**
     * A widget runs inside the launcher's update pipeline. An exception escaping `provideGlance`
     * takes the widget down with an error layout the user cannot fix, so every read is wrapped.
     */
    @Test
    fun `every widget falls back rather than crashing its host`() {
        val widgets = listOf("TodayWidget.kt", "TopBlockedWidget.kt", "ProtectionWidget.kt")
        widgets.forEach { name ->
            val text = source("main/java/com/astraedus/nudge/ui/widget/$name")
            assertTrue(
                "$name must read its data inside runCatching — an exception in provideGlance is " +
                    "the user's home screen breaking, and they have no way to retry it",
                text.contains("runCatching")
            )
            assertTrue(
                "$name must supply a fallback snapshot via getOrElse",
                text.contains("getOrElse")
            )
        }
    }

    /**
     * The data reads are one-shots. A `collect` over a Room Flow inside a widget session looks like
     * live data and is not: the session is torn down as soon as the RemoteViews are handed over, so
     * the subscription yields one value and dies — the same value `.first()` gives, while reading
     * like something that keeps up to date.
     */
    @Test
    fun `widget reads are one-shot, never a long-lived collection`() {
        widgetSources().forEach { file ->
            val text = source(file.path.substringAfter("src/"))
            listOf(".collect {", ".collectLatest", "collectAsState", "stateIn(").forEach { banned ->
                assertFalse(
                    "${file.name} must not use `$banned`: a widget composes in a short-lived " +
                        "session with nothing for a subscription to outlive. Read once with " +
                        "`.first()` before provideContent and push updates via NudgeWidgetUpdater.",
                    text.contains(banned)
                )
            }
        }
    }

    /**
     * `logEvent` is on the accessibility hot path — every block decision and every walk-away goes
     * through it. The widget push must be fire-and-forget: non-suspending, and coalesced, or a user
     * hitting a wall of blocks pays a RemoteViews build plus a binder call to the launcher per
     * event, in the middle of deciding whether to block them.
     */
    @Test
    fun `the hot-path refresh is non-suspending and debounced`() {
        val signal = source("main/java/com/astraedus/nudge/domain/widget/WidgetRefreshSignal.kt")
        assertTrue(
            "WidgetRefreshSignal.requestRefresh must not be suspend: it is called from " +
                "UsageRepository.logEvent, on the accessibility hot path",
            Regex("""fun requestRefresh\(\)""").containsMatchIn(signal)
        )
        assertFalse(
            "WidgetRefreshSignal must declare no suspending member",
            signal.contains("suspend fun")
        )

        val updater = source("main/java/com/astraedus/nudge/ui/widget/NudgeWidgetUpdater.kt")
        assertTrue(
            "NudgeWidgetUpdater must coalesce through WidgetRefreshDebouncer rather than pushing " +
                "on every event",
            updater.contains("WidgetRefreshDebouncer") && updater.contains("tryAcquire(")
        )
        assertTrue(
            "NudgeWidgetUpdater must use a monotonic clock — a wall-clock jump backwards would " +
                "lock refreshes out for however far it jumped, and silently",
            updater.contains("SystemClock.elapsedRealtime()")
        )

        val repository = source("main/java/com/astraedus/nudge/data/repository/UsageRepository.kt")
        assertTrue(
            "UsageRepository.logEvent must be the single chokepoint that pushes the widgets",
            repository.contains("widgetRefreshSignal.requestRefresh()")
        )
        assertFalse(
            "The widget refresh must not be scheduled through WorkManager: the platform tick, the " +
                "existing 15-minute watchdog and these pushes already cover every case",
            repository.contains("WorkManager") || updater.contains("WorkManager")
        )
    }
}
