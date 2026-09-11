package com.astraedus.nudge.domain.interaction

import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #28 bug 2: *"the tap/scroll counter is extremely faulty"*.
 *
 * The old counter measured EVENT RATE — one count per scroll event past a 500 ms debounce, plus one
 * count per second of content change for unsupported packages — so a slow three-second drag between
 * two posts counted 5-6, an autoplaying feed counted with no user input at all, and a comments sheet
 * counted continuously. Those numbers fed auto-kick, so the defect ejected people for doing nothing.
 *
 * This class counts ITEM TRANSITIONS instead, and these tests are the oracles for that claim. The
 * two that matter most are named after the report: five reel swipes must be five, and a slow scroll
 * between two posts must be one. A debounce could not deliver either, because a debounce is a rate
 * limit and rate was never the right measure.
 */
class InteractionCounterTest {

    private val unset = AccessibilityEventRecord.UNDEFINED

    private val instagram = "com.instagram.android"
    private val igWindow = 9281
    private val feed = "androidx.recyclerview.widget.RecyclerView"
    private val tabPager = "androidx.viewpager.widget.ViewPager"

    /**
     * A sheet opened OVER the feed. [InteractionCounter] identifies a source by window id plus
     * class name, so the sheet must differ in one of those to be a separate source at all.
     */
    private val commentsSheet = "com.instagram.android.comments.CommentsList"

    private fun scroll(
        className: String? = feed,
        windowId: Int = igWindow,
        fromIndex: Int = unset,
        toIndex: Int = unset,
        currentItemIndex: Int = unset,
        itemCount: Int = unset,
        scrollDeltaX: Int = unset,
        scrollDeltaY: Int = unset,
        sourceViewId: String? = null
    ) = AccessibilityEventRecord(
        type = A11yEventType.VIEW_SCROLLED,
        packageName = instagram,
        className = className,
        windowId = windowId,
        fromIndex = fromIndex,
        toIndex = toIndex,
        currentItemIndex = currentItemIndex,
        itemCount = itemCount,
        scrollDeltaX = scrollDeltaX,
        scrollDeltaY = scrollDeltaY,
        sourceViewId = sourceViewId,
        isScrollable = true
    )

    private fun click() = AccessibilityEventRecord(
        type = A11yEventType.VIEW_CLICKED,
        packageName = instagram,
        windowId = igWindow
    )

    /** Feed a sequence and return the TOTAL counted, which is the number the user would see. */
    private fun InteractionCounter.total(
        events: List<AccessibilityEventRecord>,
        startMs: Long = 100_000L,
        stepMs: Long = 15_000L
    ): Int = events.foldIndexed(0) { i, acc, e -> acc + onEvent(e, startMs + i * stepMs).count }

    // --- the untouched phone: the capture that proved rate was the wrong measure ------------------

    /**
     * Verbatim from a Pixel 3 capture, 2026-09-11: an Instagram home feed NOBODY WAS TOUCHING emits
     * this event repeatedly — identical indices, every delta zero. Five arrived in 75 idle seconds,
     * and under the old rate-based counter all five counted.
     */
    private val idleInstagramFeedEvent = AccessibilityEventRecord(
        type = A11yEventType.VIEW_SCROLLED,
        packageName = instagram,
        className = feed,
        windowId = igWindow,
        fromIndex = 0,
        toIndex = 2,
        currentItemIndex = unset,
        itemCount = 20,
        scrollDeltaX = 0,
        scrollDeltaY = 0,
        scrollX = 0,
        scrollY = 0,
        maxScrollX = 0,
        maxScrollY = 0,
        sourceViewId = "android:id/list",
        isScrollable = true
    )

    /** The same screen's second scroll source, in the SAME window — a tab strip, not content. */
    private val idleInstagramTabPagerEvent = AccessibilityEventRecord(
        type = A11yEventType.VIEW_SCROLLED,
        packageName = instagram,
        className = tabPager,
        windowId = igWindow,
        fromIndex = 0,
        toIndex = 0,
        currentItemIndex = unset,
        itemCount = 5,
        scrollDeltaX = 0,
        scrollDeltaY = 0,
        sourceViewId = "com.instagram.android:id/swipeable_tab_view_pager",
        isScrollable = true
    )

    @Test
    fun `an untouched Instagram feed counts nothing`() {
        val counter = InteractionCounter()
        assertEquals(0, counter.total(List(5) { idleInstagramFeedEvent }))
    }

    @Test
    fun `the idle tab pager in the same window also counts nothing`() {
        val counter = InteractionCounter()
        assertEquals(
            0,
            counter.total(List(5) { idleInstagramFeedEvent } + List(5) { idleInstagramTabPagerEvent })
        )
    }

    // --- first sight of a source ------------------------------------------------------------------

    /**
     * Establishing where a source starts is not an interaction. Counting it is exactly how opening
     * a comments sheet used to register as a tap: the sheet's first scroll event announced an index
     * and the old counter treated the announcement as input.
     */
    @Test
    fun `the first event from a source counts nothing`() {
        val counter = InteractionCounter()
        val result = counter.onEvent(scroll(fromIndex = 7), 100_000)
        assertEquals(0, result.count)
        assertEquals("source_first_seen", result.reason)
    }

    // --- the two oracles from the bug report --------------------------------------------------------

    /** "Five reel swipes = five." */
    @Test
    fun `five successive forward transitions count five`() {
        val counter = InteractionCounter()
        val swipes = (0..5).map { scroll(fromIndex = it) }
        assertEquals(5, counter.total(swipes))
    }

    /**
     * "A slow scroll between two posts = one." The heart of bug 2: a single drag fires scroll events
     * continuously for the whole gesture plus the fling, and the old counter charged for each one.
     * Here eight events move the adapter index exactly once, so exactly one item was consumed.
     */
    @Test
    fun `a slow scroll that moves the index once counts one`() {
        val counter = InteractionCounter()
        val indices = listOf(0, 0, 0, 0, 1, 1, 1, 1)
        assertEquals(1, counter.total(indices.map { scroll(fromIndex = it) }, stepMs = 400L))
    }

    @Test
    fun `a forward index transition counts one item`() {
        val counter = InteractionCounter()
        counter.onEvent(scroll(fromIndex = 0), 100_000)
        val result = counter.onEvent(scroll(fromIndex = 1), 101_000)
        assertEquals(1, result.count)
        assertEquals(CountMode.ITEMS, result.mode)
    }

    @Test
    fun `repeating the same index after a transition counts nothing`() {
        val counter = InteractionCounter()
        counter.onEvent(scroll(fromIndex = 0), 100_000)
        counter.onEvent(scroll(fromIndex = 1), 101_000)
        assertEquals(0, counter.total(List(4) { scroll(fromIndex = 1) }, startMs = 102_000))
    }

    /** Scrolling back up is re-reading, not consuming. */
    @Test
    fun `backward movement counts nothing`() {
        val counter = InteractionCounter()
        counter.onEvent(scroll(fromIndex = 10), 100_000)
        val result = counter.onEvent(scroll(fromIndex = 5), 101_000)
        assertEquals(0, result.count)
        assertEquals("no_transition", result.reason)
    }

    /**
     * A fast fling genuinely covers several items; an adapter jumping to position 0 covers all of
     * them and means nothing was consumed. The cap is a guard against the second case, so a
     * "scroll to top" cannot credit forty items.
     */
    @Test
    fun `a jump larger than the cap is credited only up to the cap`() {
        val counter = InteractionCounter()
        counter.onEvent(scroll(fromIndex = 0), 100_000)
        val result = counter.onEvent(
            scroll(fromIndex = InteractionCounter.MAX_ITEMS_PER_EVENT + 10),
            101_000
        )
        assertEquals(InteractionCounter.MAX_ITEMS_PER_EVENT, result.count)
    }

    // --- which field is the index ---------------------------------------------------------------

    /**
     * `currentItemIndex` is what a pager sets and `fromIndex` is what a list sets. When a source
     * sets both, the pager's position is the real one — here `fromIndex` never moves, so a count of
     * one is only possible if `currentItemIndex` was preferred.
     */
    @Test
    fun `currentItemIndex wins over fromIndex when both are set`() {
        val counter = InteractionCounter()
        counter.onEvent(scroll(currentItemIndex = 3, fromIndex = 0), 100_000)
        assertEquals(1, counter.onEvent(scroll(currentItemIndex = 4, fromIndex = 0), 101_000).count)
    }

    /** -1 means "the view never set this", never "position minus one". */
    @Test
    fun `fromIndex is used when currentItemIndex is unset`() {
        val counter = InteractionCounter()
        counter.onEvent(scroll(currentItemIndex = unset, fromIndex = 0), 100_000)
        assertEquals(
            1,
            counter.onEvent(scroll(currentItemIndex = unset, fromIndex = 1), 101_000).count
        )
    }

    // --- horizontal movement -----------------------------------------------------------------------

    /**
     * A carousel or a tab strip moving sideways is not content consumed, and it is rejected BEFORE
     * any source bookkeeping — so a horizontal pager can never win the primary-source election and
     * lock the real feed out of counting.
     */
    @Test
    fun `horizontal-only movement counts nothing and never becomes the primary source`() {
        val counter = InteractionCounter()
        repeat(3) { i ->
            val result = counter.onEvent(
                scroll(className = tabPager, scrollDeltaX = 400, scrollDeltaY = unset),
                100_000 + i * 200L
            )
            assertEquals(0, result.count)
            assertEquals("horizontal", result.reason)
        }

        counter.onEvent(scroll(fromIndex = 0), 101_000)
        assertEquals(
            "the feed must still be able to claim primary after a pager swiped sideways",
            1,
            counter.onEvent(scroll(fromIndex = 1), 102_000).count
        )
    }

    // --- the distance fallback ---------------------------------------------------------------------

    /**
     * Some sources report no adapter indices at all. Then the only honest measure left is distance
     * actually travelled, one item per screen height. Constructed with an explicit small screen so
     * the arithmetic is readable: 400 + 400 + 400 crosses 1000 exactly once.
     */
    @Test
    fun `with no indices accumulated vertical distance counts one item per screen height`() {
        val counter = InteractionCounter(screenHeightPx = 1_000)
        val drag = scroll(scrollDeltaY = 400, scrollDeltaX = 0)

        assertEquals("accumulating", counter.onEvent(drag, 100_000).reason)
        assertEquals(0, counter.onEvent(drag, 100_200).count)

        val crossed = counter.onEvent(drag, 100_400)
        assertEquals(1, crossed.count)
        assertEquals(CountMode.ITEMS, crossed.mode)
        assertEquals("distance", crossed.reason)
    }

    /**
     * `scrollDeltaY` is API 28+ and is -1 when unset, so an unset delta must not be read as one
     * pixel of travel. With no indices and no delta there is no evidence of anything at all.
     */
    @Test
    fun `no indices and no delta counts nothing`() {
        val counter = InteractionCounter(screenHeightPx = 1_000)
        val result = counter.onEvent(scroll(), 100_000)
        assertEquals(0, result.count)
        assertEquals("no_indices_no_delta", result.reason)
    }

    // --- the primary source: the comments-sheet case -------------------------------------------------

    /**
     * The reported symptom *"scrolling the comments counts ~10 taps continuously"*. A sheet opened
     * over a live feed is a second source in the same window; only the source that has most
     * recently been producing transitions counts, so the sheet's scrolling is not reels watched.
     *
     * Deliberately no per-app view-id allowlist here — that is the hardcoded-set trap this whole
     * change exists to remove.
     */
    @Test
    fun `a second source cannot count while the primary is still active`() {
        val counter = InteractionCounter(handoverMs = 5_000)
        counter.onEvent(scroll(fromIndex = 0), 100_000)
        assertEquals(1, counter.onEvent(scroll(fromIndex = 1), 101_000).count)

        counter.onEvent(scroll(className = commentsSheet, fromIndex = 0), 101_500)
        val sheet = counter.onEvent(scroll(className = commentsSheet, fromIndex = 1), 102_000)
        assertEquals(0, sheet.count)
        assertEquals("secondary_source", sheet.reason)
    }

    /**
     * The election is not permanent: if the user genuinely moves to a different list and the old
     * primary stays silent past the handover, the new one takes over. Without this, opening a sheet
     * once would mute real consumption inside it forever.
     */
    @Test
    fun `a second source takes over once the primary has been silent past the handover`() {
        val handover = 5_000L
        val counter = InteractionCounter(handoverMs = handover)
        counter.onEvent(scroll(fromIndex = 0), 100_000)
        val primaryLastTransition = 101_000L
        counter.onEvent(scroll(fromIndex = 1), primaryLastTransition)

        counter.onEvent(scroll(className = commentsSheet, fromIndex = 0), 101_500)
        counter.onEvent(scroll(className = commentsSheet, fromIndex = 1), 102_000)

        val takeover = counter.onEvent(
            scroll(className = commentsSheet, fromIndex = 2),
            primaryLastTransition + handover
        )
        assertEquals(1, takeover.count)
        assertEquals("item_transition", takeover.reason)
    }

    // --- taps ----------------------------------------------------------------------------------------

    /**
     * Clicks are counted for EVERY package. The old code took them only from packages outside its
     * supported set, so a tap inside Instagram or YouTube was never counted while a content change
     * in Discord was.
     */
    @Test
    fun `a click counts one tap`() {
        val counter = InteractionCounter()
        val result = counter.onEvent(click(), 100_000)
        assertEquals(1, result.count)
        assertEquals(CountMode.TAPS, result.mode)
    }

    /** The debounce is a guard against the same tap being DELIVERED twice, not a rate limit. */
    @Test
    fun `a duplicate click inside the debounce counts nothing and a later one counts again`() {
        val debounce = InteractionCounter.DEFAULT_CLICK_DEBOUNCE_MS
        val counter = InteractionCounter(clickDebounceMs = debounce)
        assertEquals(1, counter.onEvent(click(), 100_000).count)
        assertEquals(0, counter.onEvent(click(), 100_000 + debounce - 1).count)
        assertEquals(1, counter.onEvent(click(), 100_000 + debounce).count)
    }

    // --- what was removed -----------------------------------------------------------------------------

    /**
     * The content-change proxy is GONE. It used to add one count per second of
     * `TYPE_WINDOW_CONTENT_CHANGED` for any unsupported package, as a stand-in for taps — which
     * meant an autoplaying video, a ticking timestamp or a phone lying face-up on a feed counted as
     * user input. Content changes have nothing to do with input, so they must count zero forever.
     */
    @Test
    fun `a content change is not an interaction`() {
        val counter = InteractionCounter()
        val contentChange = AccessibilityEventRecord(
            type = A11yEventType.WINDOW_CONTENT_CHANGED,
            packageName = instagram,
            windowId = igWindow
        )
        assertEquals(0, counter.total(List(10) { contentChange }, stepMs = 1_000L))
        assertEquals("not_an_interaction", counter.onEvent(contentChange, 200_000).reason)
    }

    @Test
    fun `window state changes are not interactions either`() {
        val counter = InteractionCounter()
        val record = AccessibilityEventRecord(
            type = A11yEventType.WINDOW_STATE_CHANGED,
            packageName = instagram,
            windowId = igWindow
        )
        assertEquals(0, counter.onEvent(record, 100_000).count)
    }

    // --- reset ---------------------------------------------------------------------------------------

    /**
     * Called when the sitting changes, so one app's scroll positions and primary-source election
     * cannot leak into the next app's count.
     */
    @Test
    fun `reset forgets source state and the primary election`() {
        val counter = InteractionCounter(handoverMs = 5_000)
        counter.onEvent(scroll(fromIndex = 0), 100_000)
        assertEquals(1, counter.onEvent(scroll(fromIndex = 1), 101_000).count)

        counter.reset()

        // Source state is gone: the old index is not remembered, so this is a first sighting again.
        val afterReset = counter.onEvent(scroll(fromIndex = 9), 102_000)
        assertEquals(0, afterReset.count)
        assertEquals("source_first_seen", afterReset.reason)

        // And the election is gone: a source that was previously secondary can now claim primary
        // immediately, without waiting out a handover it should no longer be subject to.
        counter.onEvent(scroll(className = commentsSheet, fromIndex = 0), 102_100)
        val claim = counter.onEvent(scroll(className = commentsSheet, fromIndex = 1), 102_200)
        assertTrue("after reset, the first source to move must be able to claim primary", claim.counted)
        assertEquals(1, claim.count)
    }

    /**
     * A sitting is not short and an app is not one screen: every activity gets a fresh `windowId`,
     * so an hour in one app mints new source keys steadily. This is an accessibility service that
     * runs for days on a 3GB device, and an unbounded map on its hottest path is only ever noticed
     * as "the phone got slow".
     */
    @Test
    fun `the tracked-source map is bounded however many screens a sitting visits`() {
        val counter = InteractionCounter()
        repeat(InteractionCounter.MAX_TRACKED_SOURCES * 10) { i ->
            counter.onEvent(scroll(windowId = i, fromIndex = 0), 1_000L + i)
            counter.onEvent(scroll(windowId = i, fromIndex = 1), 1_001L + i)
        }
        assertTrue(
            "sources must stay bounded by MAX_TRACKED_SOURCES",
            counter.trackedSourceCount() <= InteractionCounter.MAX_TRACKED_SOURCES
        )
    }

    /**
     * Eviction must not silently break counting: the source the user is actually scrolling is the
     * one firing events, so it is never the stalest, and it keeps counting across the eviction of
     * screens they have left.
     */
    @Test
    fun `the source being scrolled keeps counting while stale ones are evicted`() {
        val counter = InteractionCounter()
        var clock = 1_000L
        // Establish the live feed as primary.
        counter.onEvent(scroll(windowId = 1, fromIndex = 0), clock++)
        assertEquals(1, counter.onEvent(scroll(windowId = 1, fromIndex = 1), clock++).count)

        // Churn through far more screens than the cap, keeping the feed active throughout.
        var feedCounts = 0
        repeat(InteractionCounter.MAX_TRACKED_SOURCES * 3) { i ->
            counter.onEvent(scroll(windowId = 1_000 + i, fromIndex = 0), clock++)
            feedCounts += counter.onEvent(scroll(windowId = 1, fromIndex = 2 + i), clock++).count
        }
        assertEquals(
            "every forward move of the live source must still count",
            InteractionCounter.MAX_TRACKED_SOURCES * 3,
            feedCounts
        )
    }
}
