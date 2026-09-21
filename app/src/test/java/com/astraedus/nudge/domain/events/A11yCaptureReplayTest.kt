package com.astraedus.nudge.domain.events

import com.astraedus.nudge.domain.interaction.InteractionCounter
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import com.astraedus.nudge.service.AwarenessOverlayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays REAL device event streams through the real pipeline.
 *
 * Unit tests over a pure function prove the function does what its author thought. These prove the
 * pipeline does the right thing to what a Pixel 3 actually emitted — which is the gap every bug in
 * `docs/architecture/foreground-detection.md` fell into. Each capture in
 * `app/src/test/resources/a11y-captures/` carries its own provenance and its expected count in `#`
 * header lines; the assertions below are those oracles.
 *
 * This is the workflow for the NEXT report: reproduce with `scripts/a11y-capture.sh`, commit the
 * `.jsonl`, add a failing assertion here, fix. See
 * `docs/architecture/accessibility-event-pipeline.md`.
 */
class A11yCaptureReplayTest {

    private val ownPackage = "dev.astraedus.nudge"
    private val pixelLauncher = "com.google.android.apps.nexuslauncher"

    private val classifier = EventClassifier(
        ownPackageName = ownPackage,
        systemPackages = setOf(
            "com.android.systemui",
            "com.android.launcher3",
            pixelLauncher,
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller"
        ),
        imePackages = setOf("com.google.android.inputmethod.latin"),
        frameworkPackage = "android",
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES
    )

    private fun classify(record: AccessibilityEventRecord) = classifier.classify(
        record = record,
        currentImePackage = "com.google.android.inputmethod.latin",
        launcherPackages = setOf(pixelLauncher),
        pipOnlyPackages = emptySet()
    )

    /** Total interactions the counter would credit across a whole capture. */
    private fun replayCounter(
        capture: String,
        counter: InteractionCounter = InteractionCounter()
    ): Int = A11yCapture.load(capture).sumOf { record ->
        counter.onEvent(record, record.eventTimeMs).count
    }

    // --- bug 2: the counter --------------------------------------------------------------------

    /**
     * The zero-input oracle. Instagram's home feed emits scroll events on an untouched phone; the
     * old counter's event-rate rule ticked on them and fed auto-kick, so a user could be ejected
     * from an app for doing nothing at all. Any non-zero number here is a fabricated interaction.
     */
    @Test
    fun `an untouched Instagram feed counts nothing`() {
        assertEquals(0, replayCounter("instagram-idle-no-input"))
    }

    /**
     * The slow-scroll oracle, and the heart of issue #28 bug 2: *"scrolling slowly between two
     * posts counts as 5-6"*. ONE deliberate 2.5-second drag produced TEN scroll events; the old
     * 500ms debounce scored it 2-3 (more for a longer gesture). The indices moved exactly once.
     */
    @Test
    fun `one slow drag over a real list counts exactly one`() {
        assertEquals(1, replayCounter("aosp-list-slow-scroll"))
    }

    /**
     * Guards the test above against passing for the wrong reason. If the capture had no scroll
     * events, or the counter silently ignored the whole stream, the assertion would still read 1 by
     * luck — a vacuous fixture test is worse than none.
     */
    @Test
    fun `the slow-drag capture really does contain the ten events that made it hard`() {
        val scrolls = A11yCapture.load("aosp-list-slow-scroll")
            .filter { it.type == A11yEventType.VIEW_SCROLLED }
        assertEquals("the capture must still hold all ten scroll events", 10, scrolls.size)
        assertEquals(
            "exactly one forward index transition is the whole point",
            1,
            scrolls.map { it.fromIndex }.zipWithNext().count { (a, b) -> b > a }
        )
        assertTrue(
            "every event must carry the source view id the counter keys on",
            scrolls.all { it.sourceViewId != null }
        )
    }

    /**
     * The old mechanism, run against the same capture, so the regression is pinned by the number it
     * used to produce rather than by a description of it. If someone reintroduces rate-based
     * counting, this is the test that says what it costs.
     */
    @Test
    fun `the old event-rate rule really would have over-counted this capture`() {
        val debounceMs = 500L
        var last = 0L
        val rateCount = A11yCapture.load("aosp-list-slow-scroll")
            .filter { it.type == A11yEventType.VIEW_SCROLLED }
            .count { record ->
                if (record.eventTimeMs - last < debounceMs) false
                else { last = record.eventTimeMs; true }
            }
        assertTrue(
            "the pre-fix rule must score this single gesture as more than one interaction " +
                "(it scored $rateCount)",
            rateCount > 1
        )
        assertEquals(1, replayCounter("aosp-list-slow-scroll"))
    }

    /**
     * The oracle behind *"scrolling the comments counts ~10 taps continuously"*.
     *
     * Three human gestures inside a YouTube list produced TWENTY-ONE scroll events over 4.6
     * seconds, which is how the old rate rule turned one gesture into several counts. The number
     * has to be the eight items the user moved past, never the events the gesture emitted.
     */
    @Test
    fun `three gestures over a real list count items consumed and not events emitted`() {
        val records = A11yCapture.load("yt-list-scroll-3-gestures")
        val scrolls = records.count { it.type == A11yEventType.VIEW_SCROLLED }
        assertEquals("the capture must still hold all twenty-one scroll events", 21, scrolls)
        assertEquals(
            "three gestures, eight items moved past, twenty-one events — the number must be the " +
                "items, never the events",
            8,
            replayCounter("yt-list-scroll-3-gestures")
        )
    }

    /**
     * A sheet opened over a live feed must not be counted as the feed being consumed — the overlay
     * calling it "reels" is what the report objects to.
     *
     * Built from a real stream plus a synthetic second source rather than from one capture, and the
     * reason is worth recording. On YouTube the comments list and the home feed carry the *same*
     * `viewIdResourceName` (`com.google.android.youtube:id/results`) and the same class — verified
     * on device — so they are one reused list surface and there is nothing there to separate. The
     * screen shape this rule exists for is the one `instagram-idle-no-input.jsonl` shows: two
     * genuinely distinct sources, `android:id/list` and `…:id/swipeable_tab_view_pager`, alive in
     * ONE window. The feed below models that, and the sheet events are the real ones.
     */
    @Test
    fun `a second scroll source over a live primary counts nothing`() {
        val counter = InteractionCounter()
        val sheet = A11yCapture.load("yt-list-scroll-3-gestures")
        // Same clock as the capture: the user scrolled the feed a moment before opening the sheet.
        val firstSheetScroll = sheet.first { it.type == A11yEventType.VIEW_SCROLLED }.eventTimeMs
        val justBefore = firstSheetScroll - 1_000
        val feed = AccessibilityEventRecord(
            type = A11yEventType.VIEW_SCROLLED,
            packageName = "com.google.android.youtube",
            className = "android.support.v7.widget.RecyclerView",
            // Same windowId as the sheet — which is exactly the case the view id in the source key
            // exists to separate. Without it these two share a key and their indices interleave.
            windowId = 9386,
            itemCount = 40,
            sourceViewId = "com.google.android.youtube:id/feed"
        )
        counter.onEvent(feed.copy(fromIndex = 0), justBefore - 100)
        assertEquals(1, counter.onEvent(feed.copy(fromIndex = 1), justBefore).count)

        val sheetCount = sheet.sumOf { counter.onEvent(it, it.eventTimeMs).count }
        assertEquals("a sheet over a live feed is not the feed being consumed", 0, sheetCount)
    }

    /**
     * The limit of the rule above, asserted rather than left to be discovered. The handover is
     * time-based, so a list the user stays in past the window does take over — at which point they
     * really are consuming it, and pretending otherwise would be its own lie. The capture spans
     * ~4.6 seconds, well inside the default window, which is why the test above reads zero.
     */
    @Test
    fun `a second source does take over once the primary has been silent past the handover window`() {
        val counter = InteractionCounter(handoverMs = 1_000)
        val sheet = A11yCapture.load("yt-list-scroll-3-gestures")
        val feed = AccessibilityEventRecord(
            type = A11yEventType.VIEW_SCROLLED,
            packageName = "com.google.android.youtube",
            className = "android.support.v7.widget.RecyclerView",
            windowId = 9386,
            sourceViewId = "com.google.android.youtube:id/feed"
        )
        val longBefore = sheet.first { it.type == A11yEventType.VIEW_SCROLLED }.eventTimeMs - 60_000
        counter.onEvent(feed.copy(fromIndex = 0), longBefore - 100)
        counter.onEvent(feed.copy(fromIndex = 1), longBefore)
        assertTrue(sheet.sumOf { counter.onEvent(it, it.eventTimeMs).count } > 0)
    }

    /**
     * Content changes are not input. The proxy that counted one "tap" per second of them for
     * unsupported packages is removed; this asserts the stream it fed on is still full of them, so
     * the removal is load-bearing rather than cosmetic.
     */
    @Test
    fun `content changes are plentiful and count nothing`() {
        val records = A11yCapture.load("instagram-idle-no-input")
        val contentChanges = records.count { it.type == A11yEventType.WINDOW_CONTENT_CHANGED }
        assertTrue("the idle capture must still be full of content changes", contentChanges > 10)
        val counter = InteractionCounter()
        assertEquals(
            0,
            records.filter { it.type == A11yEventType.WINDOW_CONTENT_CHANGED }
                .sumOf { counter.onEvent(it, it.eventTimeMs).count }
        )
    }

    /**
     * The device sequence behind issue #28 FAIL 2 (QA 2026-09-12): YouTube opens on Shorts, the user
     * taps the in-app Home tab, and every item counted on the feed was still captioned "shorts".
     *
     * Replayed here as the counting oracle. The caption logic itself lives in `InteractionHandler`
     * (it needs detection, which needs a node tree), so this pins what the STREAM says: the only
     * scroll source in 150 seconds of Shorts swiping plus a feed visit is the feed's own list.
     */
    @Test
    fun `the shorts-then-home-tab capture counts only the feed, because Shorts emits no scrolls`() {
        val records = A11yCapture.load("yt-shorts-then-home-tab")
        val scrolls = records.filter { it.type == A11yEventType.VIEW_SCROLLED }

        assertEquals(
            "150 seconds of swiping on the Shorts pager produced no scroll events at all; both of " +
                "these are the home feed's list, which is why the reported run showed no counter " +
                "activity on Shorts",
            listOf("com.google.android.youtube:id/results"),
            scrolls.mapNotNull { it.sourceViewId }.distinct()
        )
        assertEquals("one scroll event, from the feed, across the whole run", 1, scrolls.size)
        assertEquals(
            "and one click -- the tab tap itself, which is what counted FIRST on the feed and is " +
                "why a scroll-only test would have missed this bug",
            1,
            records.count { it.type == A11yEventType.VIEW_CLICKED }
        )
    }

    /**
     * The counterfactual for FAIL 1, asserted so nobody re-investigates it from scratch: nothing in
     * this capture ends a sitting. The reported second delay overlay did NOT come from the tab tap,
     * from the two-minute return window, or from picture-in-picture -- all three are ruled out by
     * this stream. It came from a service rebind, which `SittingTracker` no longer treats as the
     * user leaving.
     */
    @Test
    fun `nothing in the shorts-then-home-tab capture ends the sitting`() {
        val tracker = SittingTracker()
        val ends = mutableListOf<SittingEvent.Ended>()
        A11yCapture.load("yt-shorts-then-home-tab").forEach { record ->
            when (val event = tracker.onSignal(classify(record), record.eventTimeMs)) {
                is SittingEvent.Ended -> ends += event
                is SittingEvent.Started -> event.ended?.let { ends += it }
                is SittingEvent.Unchanged -> Unit
            }
        }
        assertEquals("the user never left YouTube; ended: $ends", emptyList<SittingEvent.Ended>(), ends)
        assertEquals("com.google.android.youtube", tracker.currentApp)
    }

    // --- bug 1: the sitting --------------------------------------------------------------------

    /**
     * The reported repro, replayed end to end: a completed delay on Google Keep, an
     * `ACTION_GET_CONTENT` photo picker excursion, and the return. The pre-fix build logged
     * `passthrough cleared on app switch package=com.google.android.providers.media.module` and
     * re-blocked; the sitting must survive.
     */
    @Test
    fun `a picker excursion never ends the sitting`() {
        val tracker = SittingTracker(returnWindowMs = 5L * 60L * 1000L)
        val ends = mutableListOf<SittingEvent.Ended>()
        var sawPicker = false

        A11yCapture.load("picker-subflow-keeps-sitting").forEach { record ->
            if (record.packageName == "com.google.android.providers.media.module") sawPicker = true
            when (val event = tracker.onSignal(classify(record), record.eventTimeMs)) {
                is SittingEvent.Ended -> ends += event
                is SittingEvent.Started -> event.ended?.let { ends += it }
                is SittingEvent.Unchanged -> Unit
            }
        }

        assertTrue("the capture must actually contain the picker excursion", sawPicker)
        assertEquals(
            "nothing in this capture may end a sitting — the user never left Keep; ended: $ends",
            emptyList<SittingEvent.Ended>(),
            ends
        )
        assertEquals("com.google.android.keep", tracker.currentApp)
    }

    /**
     * The counterfactual for the test above. Same capture, but the sitting is driven by the OLD
     * rule ("any foreign app window means the user left"), which must reproduce the bug — otherwise
     * the capture does not contain the defect and the passing test above means nothing.
     */
    @Test
    fun `the old rule really would have revoked the grant on this capture`() {
        var current: String? = null
        var revocations = 0
        A11yCapture.load("picker-subflow-keeps-sitting").forEach { record ->
            val signal = classify(record)
            if (signal !is ForegroundSignal.AppWindow) return@forEach
            if (current != null && current != signal.packageName) revocations++
            current = signal.packageName
        }
        assertTrue(
            "the pre-fix rule must revoke at least once on this capture (it revoked $revocations)",
            revocations > 0
        )
    }

    // --- properties every capture must hold ----------------------------------------------------

    /**
     * A capture that decodes to nothing, or loses its provenance header, silently turns every
     * assertion over it into a tautology. Checked over ALL captures so a new one cannot be added
     * without its oracle.
     */
    @Test
    fun `every committed capture decodes and documents its own oracle`() {
        val names = A11yCapture.names()
        assertTrue("there must be captures committed", names.isNotEmpty())
        names.forEach { name ->
            assertTrue("capture '$name' must decode to events", A11yCapture.load(name).isNotEmpty())
            val header = A11yCapture.header(name)
            assertTrue(
                "capture '$name' must record HOW it was taken",
                header.any { it.contains("HOW:") }
            )
            assertTrue(
                "capture '$name' must state its EXPECTED count — a fixture with no oracle " +
                    "cannot fail, and a test that cannot fail is not a test",
                header.any { it.contains("EXPECTED") }
            )
        }
    }

    /**
     * No signal a capture contains may classify as something the sitting model would choke on.
     * Cheap, but it is the property that would catch a device emitting a shape we have never seen.
     */
    @Test
    fun `every captured event classifies without throwing and drives the sitting safely`() {
        val tracker = SittingTracker(returnWindowMs = 5L * 60L * 1000L)
        A11yCapture.names().forEach { name ->
            A11yCapture.load(name).forEach { record ->
                tracker.onSignal(classify(record), record.eventTimeMs)
            }
        }
    }
}
