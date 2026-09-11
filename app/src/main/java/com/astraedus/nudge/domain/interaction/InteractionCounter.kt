package com.astraedus.nudge.domain.interaction

import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord

/** What a counted interaction represents, so the number and its label can never disagree. */
enum class CountMode {
    /** Items of content consumed: reels swiped, posts scrolled past, shorts watched. */
    ITEMS,

    /** Discrete taps. */
    TAPS
}

/** The outcome of feeding one event to [InteractionCounter]. */
data class CountResult(
    /** How many interactions to add. Zero is the common answer and is not a failure. */
    val count: Int,
    val mode: CountMode,
    /**
     * Why, in one token, for the log. "Event rate" bugs are invisible without this: a counter that
     * says 6 and a counter that says 1 look identical in logcat unless it says WHY.
     */
    val reason: String
) {
    val counted: Boolean get() = count > 0

    companion object {
        fun none(reason: String) = CountResult(0, CountMode.ITEMS, reason)
    }
}

/**
 * Counts what the USER did, from what the event stream can actually prove.
 *
 * ## The bug this replaces
 *
 * Issue [#28](https://github.com/astraedus/nudge/issues/28): *"the tap/scroll counter is extremely
 * faulty… scrolling slowly between two posts counts as 5-6"*, *"scrolling the comments counts ~10
 * taps continuously"*, *"holding the screen in certain places makes the counter tick continuously"*.
 *
 * The old `InteractionHandler` counted **event rate**:
 *  - one count per `TYPE_VIEW_SCROLLED` that survived a 500 ms global debounce. A scroll gesture
 *    fires these continuously for its whole duration plus the fling, so a slow three-second drag
 *    between two posts was six counts — the user did one thing.
 *  - for any package outside `InAppDetector.SUPPORTED_PACKAGES`, one count per second of
 *    `TYPE_WINDOW_CONTENT_CHANGED`, as a stand-in for taps. Content changes have nothing to do with
 *    input: a device capture of an untouched phone sitting on the Instagram feed produced scroll and
 *    content-change events with **zero** user input, so the counter ticked — and fed auto-kick,
 *    ejecting a user for doing nothing.
 *
 * A debounce cannot fix that, because a debounce is a rate limit and the defect is that rate was
 * being used as a measure at all. Debounces remain here only as guards against duplicate delivery.
 *
 * ## What is counted instead
 *
 * `TYPE_VIEW_SCROLLED` carries ground truth that was never read: `fromIndex` / `toIndex` /
 * `currentItemIndex` / `itemCount` from the adapter, and `scrollDeltaX/Y` on API 28+. Captured from
 * a real Pixel 3 (`app/src/test/resources/a11y-captures/`), an untouched Instagram feed emits
 * repeated scroll events whose indices never move — `fi:0 ti:2 n:20` over and over — while a real
 * swipe moves them. So:
 *
 *  - **an item transition is one interaction.** Forward movement only: scrolling back up is not
 *    consuming new content, and a "jump to top" must not count forty items.
 *  - when a source reports no indices at all, accumulated vertical distance against a screen height
 *    is the fallback.
 *  - horizontal-only movement is never an item (that is a carousel or a tab strip).
 *  - identical indices with zero delta — the untouched-phone signature — count nothing, by
 *    construction rather than by a timer.
 *
 * `TYPE_VIEW_CLICKED` is the tap signal, for every package. The old code took clicks only from
 * packages OUTSIDE the supported set, so a tap inside Instagram or YouTube was never counted at all
 * while a content change in Discord was counted as one.
 *
 * ## Multiple scroll sources in one screen
 *
 * A screen usually has more than one scrollable thing: a feed, a tab pager, and a comments sheet
 * that opens over the top. The capture shows them arriving as separate sources in the same window
 * (`androidx.recyclerview.widget.RecyclerView` at `android:id/list` and
 * `androidx.viewpager.widget.ViewPager` at `…/swipeable_tab_view_pager`).
 *
 * Only the PRIMARY source counts, and the primary is simply *the source that has most recently been
 * producing item transitions*. A sheet opened over a feed cannot take that role while the feed is
 * still live, which is what stops "scrolling the comments" from registering as content consumed; if
 * the user genuinely moves to a different list and stays there past [handoverMs], it takes over.
 * Deliberately no per-app view-id list: that is the same hardcoded-set trap this whole change
 * exists to remove — the view id is used only as an IDENTITY, never matched against known names.
 *
 * ## Asymmetry worth not "fixing"
 *
 * The index path ignores a source's FIRST event (there is no previous index to compare against, and
 * counting the establishing event is how opening a sheet used to register as a tap). The distance
 * fallback has no equivalent first-seen guard, deliberately: a delta is already a measurement of
 * movement that has happened, not a position, so there is nothing to establish. Adding a guard there
 * would silently drop the first screenful of every scroll on sources that report no indices.
 */
class InteractionCounter(
    /** Screen height in px, for the distance fallback when a source reports no indices. */
    private val screenHeightPx: Int = DEFAULT_SCREEN_HEIGHT_PX,
    /** How long the primary source must be silent before another source may take over. */
    private val handoverMs: Long = DEFAULT_HANDOVER_MS,
    /** Guard against duplicate delivery of the same click. Not a counting mechanism. */
    private val clickDebounceMs: Long = DEFAULT_CLICK_DEBOUNCE_MS
) {

    /**
     * Identity of a scrollable view.
     *
     * [windowId] and [className] are free — every event carries them — but they are NOT enough on
     * their own: a comments sheet opened over a feed is very often another
     * `androidx.recyclerview.widget.RecyclerView` in the same window, so the two would share a key,
     * the sheet would inherit the feed's primary status, and its indices would interleave with the
     * feed's to produce phantom transitions. That is precisely the reported symptom ("scrolling the
     * comments counts ~10 taps continuously").
     *
     * [viewId] is `viewIdResourceName`, which costs a binder round trip and so is resolved by the
     * CALLER under its own throttle (see `InteractionHandler.handleInteraction`) rather than being
     * read here per event. A null viewId keys separately from any non-null one: "we could not tell
     * which view this was" is its own source, never a wildcard that matches every other.
     */
    private data class SourceKey(val windowId: Int, val className: String?, val viewId: String?)

    private class SourceState {
        var lastIndex: Int = UNSET_INDEX
        var accumulatedPx: Int = 0

        /** When this source last produced an event. Used only to evict the stalest source. */
        var lastSeenMs: Long = 0L
    }

    private val sources = mutableMapOf<SourceKey, SourceState>()
    private var primary: SourceKey? = null
    private var primaryLastTransitionMs: Long = 0L
    private var lastClickAtMs: Long = 0L

    /** How many scroll sources are currently tracked. Exposed so the bound is testable. */
    fun trackedSourceCount(): Int = sources.size

    /** Forget everything. Called when the sitting changes, so state cannot leak between apps. */
    fun reset() {
        sources.clear()
        primary = null
        primaryLastTransitionMs = 0L
        lastClickAtMs = 0L
    }

    /**
     * Feed one event. Returns how much to count and why.
     *
     * @param nowMs a clock the caller supplies so every branch is testable without sleeping.
     * @param sourceViewId the scrolling view's `viewIdResourceName`, or null when it was not read.
     *   Defaults to whatever the record already carries, which is what a captured fixture replays
     *   with; in the service it is resolved under a throttle because it costs a binder call.
     */
    fun onEvent(
        record: AccessibilityEventRecord,
        nowMs: Long,
        sourceViewId: String? = record.sourceViewId
    ): CountResult = when (record.type) {
        A11yEventType.VIEW_SCROLLED -> onScroll(record, nowMs, sourceViewId)
        A11yEventType.VIEW_CLICKED -> onClick(nowMs)
        else -> CountResult.none("not_an_interaction")
    }

    private fun onClick(nowMs: Long): CountResult {
        // A debounce here is a GUARD against the same tap being delivered twice, not the mechanism
        // that decides what a tap is — that distinction is the whole point of this class.
        if (nowMs - lastClickAtMs < clickDebounceMs) return CountResult.none("click_debounced")
        lastClickAtMs = nowMs
        return CountResult(1, CountMode.TAPS, "click")
    }

    private fun onScroll(
        record: AccessibilityEventRecord,
        nowMs: Long,
        sourceViewId: String?
    ): CountResult {
        // A carousel or a tab strip moving sideways is not content consumed. Checked before
        // anything else so a horizontal pager can never become the primary source.
        if (isHorizontalOnly(record)) return CountResult.none("horizontal")

        val key = SourceKey(record.windowId, record.className, sourceViewId)
        val state = sources.getOrPut(key) { evictStalestIfFull(nowMs); SourceState() }
        state.lastSeenMs = nowMs

        val index = itemIndexOf(record)
        val counted: Int
        val reason: String
        if (index != UNSET_INDEX) {
            val previous = state.lastIndex
            state.lastIndex = index
            if (previous == UNSET_INDEX) {
                // First sight of this source. Establishing where it starts is not an interaction —
                // counting it is how opening a comments sheet used to register as a tap.
                return CountResult.none("source_first_seen")
            }
            // Forward movement only, and capped: a fling legitimately covers several items, a
            // "scroll to top" covers forty and means the user consumed nothing.
            counted = (index - previous).coerceIn(0, MAX_ITEMS_PER_EVENT)
            reason = if (counted > 0) "item_transition" else "no_transition"
        } else {
            // No adapter indices: fall back to distance actually travelled. scrollDeltaY is API 28+
            // and is -1 when unset, so an unset delta must not be read as one pixel.
            val delta = verticalDeltaOf(record)
            if (delta <= 0) return CountResult.none("no_indices_no_delta")
            state.accumulatedPx += delta
            counted = state.accumulatedPx / screenHeightPx
            state.accumulatedPx -= counted * screenHeightPx
            reason = if (counted > 0) "distance" else "accumulating"
        }

        if (counted <= 0) return CountResult(0, CountMode.ITEMS, reason)

        // Only the primary source's transitions are content the user consumed. A comments sheet
        // scrolled while the feed behind it is still live is not a reel watched.
        if (!claimPrimary(key, nowMs)) return CountResult.none("secondary_source")

        primaryLastTransitionMs = nowMs
        return CountResult(counted, CountMode.ITEMS, reason)
    }

    /**
     * Keep [sources] bounded.
     *
     * A sitting is not short and an app is not one screen: every activity gets a fresh `windowId`,
     * so an hour in one app can mint a new source key many times over. The entries are tiny, but
     * this is an accessibility service that runs for days on a 3GB device, and an unbounded map on
     * the hot path is the kind of thing that is only ever noticed as "the phone got slow".
     *
     * Evicting the stalest is safe: a source that has not fired in a while is one the user is no
     * longer scrolling, and the cost of being wrong is one `source_first_seen` event — the same zero
     * a genuinely new source costs.
     */
    private fun evictStalestIfFull(nowMs: Long) {
        if (sources.size < MAX_TRACKED_SOURCES) return
        val stalest = sources.minByOrNull { it.value.lastSeenMs }?.key ?: return
        if (stalest == primary) primary = null
        sources.remove(stalest)
    }

    /**
     * Decide whether [key] may count right now. The first source to move becomes primary; another
     * may take over only once the incumbent has been silent for [handoverMs].
     */
    private fun claimPrimary(key: SourceKey, nowMs: Long): Boolean {
        val current = primary
        if (current == null || current == key) {
            primary = key
            return true
        }
        if (nowMs - primaryLastTransitionMs < handoverMs) return false
        primary = key
        return true
    }

    private fun itemIndexOf(record: AccessibilityEventRecord): Int {
        // currentItemIndex is what a pager sets; fromIndex is what a list sets. Neither is always
        // present, and -1 means "the view never set this", never "position minus one".
        if (record.currentItemIndex >= 0) return record.currentItemIndex
        if (record.fromIndex >= 0) return record.fromIndex
        return UNSET_INDEX
    }

    private fun verticalDeltaOf(record: AccessibilityEventRecord): Int {
        val dy = record.scrollDeltaY
        if (dy > 0) return dy
        return 0
    }

    private fun isHorizontalOnly(record: AccessibilityEventRecord): Boolean {
        val dx = record.scrollDeltaX
        val dy = record.scrollDeltaY
        return dx > 0 && dy <= 0
    }

    companion object {
        private const val UNSET_INDEX = Int.MIN_VALUE

        /**
         * Upper bound on items credited to a single scroll event. A fast fling really does cover
         * several items; an adapter jumping to position 0 covers all of them and means nothing was
         * consumed. A guard, not a mechanism.
         */
        const val MAX_ITEMS_PER_EVENT = 5

        /** Fallback screen height, only used if the real one cannot be read. */
        const val DEFAULT_SCREEN_HEIGHT_PX = 2000

        /** How long the primary scroll source must be idle before another may take over. */
        const val DEFAULT_HANDOVER_MS = 10_000L

        /** Duplicate-delivery guard for clicks. */
        const val DEFAULT_CLICK_DEBOUNCE_MS = 300L

        /**
         * Upper bound on distinct scroll sources tracked within one sitting. Comfortably more than
         * any real screen has; it exists so the map cannot grow with the length of the sitting.
         */
        const val MAX_TRACKED_SOURCES = 32
    }
}
