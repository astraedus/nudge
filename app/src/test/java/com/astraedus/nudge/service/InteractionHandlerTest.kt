package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.logging.NudgeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InteractionHandlerTest {

    private lateinit var tracker: InteractionTracker
    private lateinit var overlayManager: FakeCounterOverlayManager
    private lateinit var inAppDetector: FakeInAppDetector
    private lateinit var timeRemainingHandler: FakeTimeRemainingHandler
    private lateinit var counterCache: CounterCacheRefresher
    private lateinit var handler: InteractionHandler

    /**
     * How many times the kick asked to send the user home. The Android "how" (global action, HOME
     * intent) is injected by the service, so the policy under test stays JVM-pure.
     */
    private var goHomeCount = 0

    /**
     * The injected clock. Several claims here are about debounce windows, and reading the real
     * clock made them depend on how fast the JVM happened to run — the classic flaky-by-design
     * test. It starts well past the click debounce so the very first click of a test is not
     * silently swallowed by `now - 0L < clickDebounceMs`.
     */
    private var now = 10_000L

    @Before
    fun setUp() {
        tracker = InteractionTracker()
        overlayManager = FakeCounterOverlayManager()
        inAppDetector = FakeInAppDetector()
        timeRemainingHandler = FakeTimeRemainingHandler()
        counterCache = CounterCacheRefresher()
        goHomeCount = 0
        now = 10_000L

        handler = InteractionHandler(
            interactionTracker = tracker,
            counterOverlayManager = overlayManager,
            inAppDetector = inAppDetector,
            timeRemainingHandler = timeRemainingHandler,
            counterCache = counterCache,
            logger = NudgeLog.NoOp,
            autoKickExecutor = AutoKickExecutor(
                interactionTracker = tracker,
                counterOverlayManager = overlayManager,
                counterCache = counterCache,
                logger = NudgeLog.NoOp,
                goHome = { goHomeCount++ }
            ),
            clock = { now }
        )
    }

    private fun enablePackage(
        packageName: String,
        autoKickAfter: Int? = null,
        showTimeRemaining: Boolean = false,
        dailyLimitMinutes: Int? = null,
        autoKickCooldownSeconds: Int = 60,
        showCounter: Boolean = true
    ) {
        val entry = CounterCacheEntry(
            showCounter = showCounter,
            autoKickAfter = autoKickAfter,
            showTimeRemaining = showTimeRemaining,
            dailyLimitMinutes = dailyLimitMinutes,
            autoKickCooldownSeconds = autoKickCooldownSeconds
        )
        enablePackages(mapOf(packageName to entry))
    }

    private fun enablePackages(packages: Map<String, CounterCacheEntry>) {
        kotlinx.coroutines.runBlocking {
            counterCache.forceRefresh { packages }
        }
    }

    // --- Event builders ---------------------------------------------------------------------
    //
    // The handler now takes the whole AccessibilityEventRecord rather than a package name, because
    // the counter's answer lives in fields the old signature threw away (windowId / className /
    // fromIndex). These helpers keep that plumbing out of the individual tests.

    private fun click(packageName: String) =
        AccessibilityEventRecord(type = A11yEventType.VIEW_CLICKED, packageName = packageName)

    private fun scroll(
        packageName: String,
        index: Int,
        windowId: Int = 1,
        className: String = "androidx.recyclerview.widget.RecyclerView"
    ) = AccessibilityEventRecord(
        type = A11yEventType.VIEW_SCROLLED,
        packageName = packageName,
        className = className,
        windowId = windowId,
        fromIndex = index
    )

    /**
     * Feed the two events that make up ONE counted swipe.
     *
     * A scroll counts on a forward item-index TRANSITION, so the first event from any source is
     * `source_first_seen` and counts zero by design — that is what stops opening a comments sheet
     * from registering as content consumed. A test that wants a scroll to count therefore always
     * feeds two records from the same (windowId, className).
     */
    private fun scrollOnce(
        packageName: String,
        windowId: Int = 1,
        className: String = "androidx.recyclerview.widget.RecyclerView"
    ) {
        handler.handleInteraction(scroll(packageName, index = 0, windowId = windowId, className = className))
        handler.handleInteraction(scroll(packageName, index = 1, windowId = windowId, className = className))
    }

    /**
     * One counted tap. The clock is frozen in these tests, and the counter debounces clicks as a
     * guard against the same tap being delivered twice, so a test that fires several taps has to
     * move time along or it is really only firing one.
     */
    private fun clickOnce(packageName: String) {
        handler.handleInteraction(click(packageName))
        now += 1_000L
    }

    // --- click tests ---

    @Test
    fun `handleInteraction increments session count for non-supported packages`() {
        enablePackage("com.example.notes")

        handler.handleInteraction(click("com.example.notes"))

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals(1, overlayManager.lastSessionCount)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `a click inside a supported package now counts - Instagram`() {
        // INVERTED (issue #28 bug 2). This used to assert that a tap in a SUPPORTED_PACKAGES app
        // counted NOTHING: `handleViewClicked` returned early for them. The result was backwards —
        // a real tap in Instagram counted zero while a React Native re-render in Discord counted
        // one. SUPPORTED_PACKAGES now means only "we can name this app's in-app feature", which
        // supplies the counter's LABEL and never a veto on counting.
        enablePackage("com.instagram.android")

        handler.handleInteraction(click("com.instagram.android"))

        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `a click inside a supported package now counts - YouTube`() {
        // INVERTED with the Instagram case above; same old early return, same fix.
        enablePackage("com.google.android.youtube")

        handler.handleInteraction(click("com.google.android.youtube"))

        assertEquals(1, tracker.getSessionCount("com.google.android.youtube"))
    }

    @Test
    fun `a click inside a supported package now counts - TikTok`() {
        // INVERTED with the Instagram case above; same old early return, same fix.
        enablePackage("com.zhiliaoapp.musically")

        handler.handleInteraction(click("com.zhiliaoapp.musically"))

        assertEquals(1, tracker.getSessionCount("com.zhiliaoapp.musically"))
    }

    @Test
    fun `handleInteraction respects click debounce - rapid clicks ignored`() {
        // The debounce survives, but only as a guard against the SAME tap being delivered twice —
        // it is no longer the mechanism that decides what a tap is. Driven off the injected clock
        // so "within the window" is a fact rather than a race.
        enablePackage("com.example.notes")

        // First click goes through
        handler.handleInteraction(click("com.example.notes"))
        // Second click immediately after (within the 300ms debounce) -- ignored
        handler.handleInteraction(click("com.example.notes"))

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals(1, overlayManager.lastSessionCount)
    }

    @Test
    fun `handleInteraction does nothing when package not in cache`() {
        handler.handleInteraction(click("com.example.notes"))

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleInteraction shows overlay on first interaction`() {
        enablePackage("com.example.notes")

        handler.handleInteraction(click("com.example.notes"))

        assertTrue(overlayManager.visible)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleInteraction calls timeRemainingHandler maybeUpdate`() {
        enablePackage("com.example.notes")

        handler.handleInteraction(click("com.example.notes"))

        assertEquals("com.example.notes", timeRemainingHandler.lastMaybeUpdatePackage)
    }

    // --- content-change tests ---

    @Test
    fun `a content change is not an interaction and counts nothing`() {
        // REPLACES the removed `handleContentChanged`, which counted one "tap" per second of
        // TYPE_WINDOW_CONTENT_CHANGED for every package outside SUPPORTED_PACKAGES as a stand-in
        // for taps in apps that do not fire TYPE_VIEW_CLICKED (React Native ones like Discord).
        //
        // It was removed rather than tightened because content changes are not input at all: the
        // capture at `app/src/test/resources/a11y-captures/instagram-idle-no-input.jsonl` is an
        // UNTOUCHED phone sitting on the Instagram feed, and the content changes keep arriving with
        // zero user gestures. That path fed auto-kick, so the counter could eject a user for doing
        // nothing. A package we cannot measure now reads zero, which is honest; the old number was
        // fabricated and wearing the word "taps".
        enablePackage("com.example.notes")

        handler.handleInteraction(
            AccessibilityEventRecord(
                type = A11yEventType.WINDOW_CONTENT_CHANGED,
                packageName = "com.example.notes"
            )
        )

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertFalse(overlayManager.visible)
        assertNull(overlayManager.lastShowLabel)
    }

    // --- scroll tests ---

    @Test
    fun `handleInteraction increments count for supported packages with detected feature`() {
        enablePackage("com.instagram.android")
        // The label lives on the SESSION now, set the way production sets it. It used to be a
        // handler field cleared on a different schedule from the count it captions, which is how a
        // stale "shorts" ended up over a fresh taps count and vice versa.
        tracker.setSessionLabel("com.instagram.android", "reels")

        scrollOnce("com.instagram.android")

        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals("reels", overlayManager.lastShowLabel)
    }

    @Test
    fun `a scroll in an unsupported package now counts`() {
        // INVERTED (issue #28 bug 2). This used to assert that a scroll outside SUPPORTED_PACKAGES
        // counted NOTHING. That early return is exactly what made docs/BACKLOG.md's "counter
        // doesn't increment on YouTube swipes" possible: any feed we could not name was silent.
        // Counting is now gated on the item index moving, not on the package being on a list.
        enablePackage("com.example.notes")

        scrollOnce("com.example.notes")

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals("scrolls", overlayManager.lastShowLabel)
    }

    @Test
    fun `a scroll with no detected feature now counts under a generic label`() {
        // INVERTED (issue #28 bug 2). This used to assert that a scroll counted NOTHING unless
        // InAppDetector recognised an in-app feature — detection held a veto. It now supplies only
        // a NAME: an unresolved label is a cosmetic loss, a refused count is the feature not
        // working. The "no feature detected means no label" half of the old claim survives below.
        enablePackage("com.instagram.android")
        inAppDetector.featureToReturn = null

        handler.noteDetectedFeature("com.instagram.android", inAppDetector.featureToReturn)
        scrollOnce("com.instagram.android")

        assertNull(tracker.snapshot("com.instagram.android").label)
        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals("scrolls", overlayManager.lastShowLabel)
    }

    @Test
    fun `EXPLORE yields no label but no longer suppresses the count`() {
        // INVERTED (issue #28 bug 2). This used to assert that an Instagram Explore scroll counted
        // NOTHING, because the label lookup `return`ed out of the counting path for EXPLORE. The
        // surviving half of that claim — EXPLORE is not a feature we put a name to — is asserted
        // directly on noteDetectedFeature, where it now lives.
        enablePackage("com.instagram.android")
        inAppDetector.featureToReturn = InAppDetector.Feature.EXPLORE

        handler.noteDetectedFeature("com.instagram.android", InAppDetector.Feature.EXPLORE)
        scrollOnce("com.instagram.android")

        assertNull(tracker.snapshot("com.instagram.android").label)
        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals("scrolls", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleInteraction detects YouTube Shorts via cached label`() {
        enablePackage("com.google.android.youtube")
        tracker.setSessionLabel("com.google.android.youtube", "shorts")

        scrollOnce("com.google.android.youtube")

        assertEquals(1, tracker.getSessionCount("com.google.android.youtube"))
        assertEquals("shorts", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleInteraction detects TikTok feed via cached label`() {
        enablePackage("com.zhiliaoapp.musically")
        tracker.setSessionLabel("com.zhiliaoapp.musically", "videos")

        scrollOnce("com.zhiliaoapp.musically")

        assertEquals(1, tracker.getSessionCount("com.zhiliaoapp.musically"))
        assertEquals("videos", overlayManager.lastShowLabel)
    }

    @Test
    fun `the session label captions the count and the counting path never runs detection`() {
        // Stronger than it was: detection used to happen inline in the scroll path and was skipped
        // only because a label was already cached. The node-tree read now happens only on the
        // service's already-debounced content-change path, so the counter's correctness can never
        // depend on a binder round trip fired from a burst of scroll events.
        enablePackage("com.instagram.android")
        tracker.setSessionLabel("com.instagram.android", "reels")

        scrollOnce("com.instagram.android")

        assertEquals("reels", tracker.snapshot("com.instagram.android").label)
        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals(0, inAppDetector.detectCallCount)
    }

    @Test
    fun `a scroll does nothing when package not in cache`() {
        scrollOnce("com.instagram.android")

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    @Test
    fun `the first event from a scroll source establishes position and counts nothing`() {
        // The "source_first_seen" rule, pinned directly because every scroll test above depends on
        // it. Counting the first sighting is how opening a comments sheet over a feed used to
        // register as content consumed.
        enablePackage("com.example.notes")

        handler.handleInteraction(scroll("com.example.notes", index = 0))

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertFalse(overlayManager.visible)
    }

    // --- onAppChanged tests ---

    @Test
    fun `onAppChanged resets interaction tracker state`() {
        enablePackage("com.example.notes")
        handler.handleInteraction(click("com.example.notes"))
        assertEquals(1, tracker.getSessionCount("com.example.notes"))

        handler.onAppChanged("com.example.other")

        assertEquals(0, tracker.getSessionCount("com.example.other"))
    }

    // --- counter not shown when session is zero ---

    @Test
    fun `counterNotShownOnAppEntryWhenSessionCountIsZero`() {
        enablePackage("com.example.notes")

        // Enter the app for the first time -- session count is 0
        handler.onAppChanged("com.example.notes")

        // Counter overlay should NOT be shown (session count is 0)
        assertFalse(overlayManager.visible)
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `counter shown on app entry when session count is positive`() {
        enablePackage("com.example.notes")

        // Record some interactions first
        handler.onAppChanged("com.example.notes")
        handler.handleInteraction(click("com.example.notes"))
        now += 1_000L
        handler.handleInteraction(click("com.example.notes"))
        assertTrue(overlayManager.visible)

        // Switch away (hide overlay), then come back
        overlayManager.visible = false
        overlayManager.lastShowLabel = null
        handler.onAppChanged("com.example.other")

        // Return to notes -- session count persists (within expiry), overlay shows
        handler.onAppChanged("com.example.notes")

        assertTrue(overlayManager.visible)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    // --- counter is gated on showCounter, not on cache membership ---

    @Test
    fun `a package tracked only for a time-based kick gets no counter overlay`() {
        // Regression guard for the split between "this package is tracked" and "the user wants the
        // counter". A time-based auto-kick puts a package in the cache with showCounter = false;
        // if the interaction paths keyed off cache membership, enabling "kick after 30 minutes"
        // would silently switch on a floating tap counter nobody asked for.
        //
        // Now fed through the single handleInteraction entry point: a click AND a scroll, since a
        // scroll in an unsupported package is exactly the thing that started counting in this
        // change and must still respect this gate.
        enablePackage("com.example.notes", showCounter = false, autoKickAfter = null)

        handler.handleInteraction(click("com.example.notes"))
        scrollOnce("com.example.notes")

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertFalse(overlayManager.visible)
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `session bookkeeping still runs for a package without a counter`() {
        // ...but the session boundary itself must still be tracked, because the time-based trigger
        // depends on it.
        enablePackage("com.example.notes", showCounter = false)

        handler.onAppChanged("com.example.notes")
        tracker.setSessionUsageBaseline("com.example.notes", 5_000L)
        handler.onAppChanged("com.example.other")

        assertFalse(overlayManager.visible)
        assertEquals(5_000L, tracker.getSessionUsageBaseline("com.example.notes"))
    }

    // --- auto-kick goes through the shared executor ---

    @Test
    fun `interaction auto-kick sends home, arms the cooldown and resets the session`() {
        enablePackage(
            "com.example.notes",
            autoKickAfter = 1,
            autoKickCooldownSeconds = 90
        )

        handler.handleInteraction(click("com.example.notes"))

        assertEquals(1, goHomeCount)
        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertTrue(tracker.isInCooldown("com.example.notes"))
        assertFalse(overlayManager.visible)
    }

    @Test
    fun `interaction auto-kick with no cooldown configured arms none`() {
        enablePackage(
            "com.example.notes",
            autoKickAfter = 1,
            autoKickCooldownSeconds = 0
        )

        handler.handleInteraction(click("com.example.notes"))

        assertEquals(1, goHomeCount)
        assertFalse(tracker.isInCooldown("com.example.notes"))
    }

    @Test
    fun `no auto-kick below the interaction threshold`() {
        enablePackage("com.example.notes", autoKickAfter = 5)

        handler.handleInteraction(click("com.example.notes"))

        assertEquals(0, goHomeCount)
        assertEquals(1, tracker.getSessionCount("com.example.notes"))
    }

    // --- hideCounter tests ---

    @Test
    fun `hideCounter hides overlay when visible`() {
        enablePackage("com.example.notes")
        handler.handleInteraction(click("com.example.notes"))
        assertTrue(overlayManager.visible)

        handler.hideCounter()

        assertFalse(overlayManager.visible)
    }

    @Test
    fun `hideCounter does nothing when overlay not visible`() {
        handler.hideCounter()
        assertFalse(overlayManager.visible)
    }

    // --- Test doubles ---

    // --- the caption and the number it describes must never disagree (round-2 review) ---

    /**
     * The user-visible bug: the caption was written once, in `show`, so a promotion that reset the
     * number left the old word above it. Five taps, then the first reel, and the overlay read
     * "1 taps" -- a stale unit over a fresh count, in the one place the user actually looks.
     */
    @Test
    fun `promoting a visible counter from taps to items refreshes the caption`() {
        enablePackage("com.example.notes")
        repeat(5) { clickOnce("com.example.notes") }
        assertEquals("taps", overlayManager.visibleLabel)
        assertEquals(5, overlayManager.lastSessionCount)

        scrollOnce("com.example.notes")

        assertEquals(1, overlayManager.lastSessionCount)
        assertEquals(
            "the caption must follow the unit the number is now in",
            "scrolls",
            overlayManager.visibleLabel
        )
    }

    /**
     * Detection usually lands AFTER the user has already scrolled a few times, so correcting the
     * caption only at the next interaction leaves "3 scrolls" sitting over a reel feed.
     */
    @Test
    fun `detecting a feature while the counter is up corrects the caption immediately`() {
        enablePackage("com.instagram.android")
        scrollOnce("com.instagram.android")
        assertEquals("scrolls", overlayManager.visibleLabel)

        handler.noteDetectedFeature("com.instagram.android", InAppDetector.Feature.REELS)

        assertEquals("reels", overlayManager.visibleLabel)
    }

    /** A caption that has not changed must not be rewritten on every single interaction. */
    @Test
    fun `an unchanged caption is not rewritten`() {
        enablePackage("com.example.notes")
        repeat(4) { clickOnce("com.example.notes") }

        assertEquals(emptyList<String>(), overlayManager.labelUpdates)
    }

    /**
     * Item 11: re-entering an app used to caption it "taps" unconditionally, because `onAppChanged`
     * carried its own copy of the overlay-writing block and built its own label. A session already
     * counting reels was captioned "taps" the moment the user came back to it.
     */
    @Test
    fun `re-entering a session captions it with the unit that session is counting`() {
        enablePackage("com.instagram.android")
        tracker.setSessionLabel("com.instagram.android", "reels")
        scrollOnce("com.instagram.android")
        handler.hideCounter()

        handler.onAppChanged("com.instagram.android")

        assertEquals("reels", overlayManager.visibleLabel)
        assertEquals(1, overlayManager.lastSessionCount)
    }

    /** Re-entering is not an interaction: it must not trip a threshold the session already met. */
    @Test
    fun `re-entering an app runs no interaction side effects`() {
        enablePackage("com.example.notes", autoKickAfter = 2)
        repeat(2) { clickOnce("com.example.notes") }
        val kicksAfterInteractions = goHomeCount
        handler.hideCounter()

        handler.onAppChanged("com.example.notes")

        assertEquals(
            "re-entry must not re-trip a threshold the session already crossed",
            kicksAfterInteractions,
            goHomeCount
        )
    }

    private class FakeCounterOverlayManager : CounterOverlayManagerApi {
        var visible = false
        var lastShowLabel: String? = null
        var lastSessionCount: Int = 0
        var lastDailyTotal: Int = 0
        var hideCount = 0

        /** Every caption change applied to an already-visible overlay, in order. */
        val labelUpdates = mutableListOf<String>()

        /** What the user would actually be reading right now. */
        val visibleLabel: String? get() = labelUpdates.lastOrNull() ?: lastShowLabel

        override fun isVisible(): Boolean = visible
        override fun show(label: String) {
            visible = true
            lastShowLabel = label
        }
        override fun updateLabel(label: String) {
            labelUpdates += label
        }
        override fun updateCount(sessionCount: Int, dailyTotal: Int) {
            lastSessionCount = sessionCount
            lastDailyTotal = dailyTotal
        }
        override fun hide() {
            visible = false
            hideCount++
        }
    }

    private class FakeInAppDetector : InAppDetectorApi {
        var featureToReturn: InAppDetector.Feature? = null
        var detectCallCount: Int = 0

        override fun detectFeature(
            packageName: String,
            rootNode: AccessibilityNodeInfo?
        ): InAppDetector.Feature? {
            detectCallCount++
            return featureToReturn
        }
    }

    private class FakeTimeRemainingHandler : TimeRemainingHandlerApi {
        var lastMaybeUpdatePackage: String? = null
        var resetDebounceCalled = false
        var hideCalled = false

        override fun maybeUpdate(packageName: String) {
            lastMaybeUpdatePackage = packageName
        }
        override fun resetDebounce() { resetDebounceCalled = true }
        override fun hide() { hideCalled = true }
    }
}
