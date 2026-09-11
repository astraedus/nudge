package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.interaction.CountMode
import com.astraedus.nudge.domain.interaction.InteractionCounter
import com.astraedus.nudge.domain.logging.NudgeLog

/**
 * Adapter between accessibility events and the interaction counter.
 *
 * It owns the Android-side concerns — the counter overlay, the auto-kick, the feature label read off
 * the node tree — and delegates the actual question *"did the user do something, and how much?"* to
 * the pure [InteractionCounter]. That split is the fix for issue #28's second bug: the decision is
 * now unit-testable and replayable against captured device event streams, instead of being three
 * timestamps and three debounce constants smeared across this file.
 *
 * ## What changed, and why each thing was wrong
 *
 * - **Scrolls were counted by event RATE.** One count per `TYPE_VIEW_SCROLLED` surviving a 500 ms
 *   debounce, so a slow three-second drag between two posts counted 5-6 and a fling counted several
 *   more. A debounce cannot fix that, because a debounce is a rate limit and rate was never the
 *   measurement. Item TRANSITIONS are.
 * - **Content changes stood in for taps** for every package outside
 *   `InAppDetector.SUPPORTED_PACKAGES`. Content changes are not input: a device capture of an
 *   untouched phone on the Instagram feed
 *   (`app/src/test/resources/a11y-captures/instagram-idle-no-input.jsonl`) shows the stream ticking
 *   with zero gestures. That path fed auto-kick, so it could eject a user for doing nothing. It is
 *   **removed**, not tightened. A package we cannot measure now reads zero, which is honest; the old
 *   behaviour was a fabricated number wearing the word "taps".
 * - **Taps inside supported apps were never counted at all** (`handleViewClicked` returned early for
 *   them), so a tap in Instagram counted nothing while a re-render in Discord counted one. Clicks
 *   now count everywhere.
 * - **`SUPPORTED_PACKAGES` no longer gates counting.** It went back to meaning only what its name
 *   says: the apps whose in-app FEATURES (Reels / Shorts / the TikTok feed) we can recognise. That
 *   recognition supplies the counter's LABEL and nothing else, so an app we cannot recognise still
 *   gets a real count instead of silence.
 */
class InteractionHandler(
    private val interactionTracker: InteractionTracker,
    private val counterOverlayManager: CounterOverlayManagerApi,
    private val inAppDetector: InAppDetectorApi,
    private val timeRemainingHandler: TimeRemainingHandlerApi,
    private val counterCache: CounterCacheRefresher,
    private val logger: NudgeLog,
    private val autoKickExecutor: AutoKickExecutor,
    private val counter: InteractionCounter = InteractionCounter(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    /**
     * The feature label for the surface currently being scrolled ("reels" / "shorts" / "videos"),
     * resolved once from the node tree and then reused so a tree walk does not happen per scroll.
     */
    var activeReelLabel: String? = null

    /** A `viewIdResourceName` resolved for one scrolling source, and when. See [sourceViewIdFor]. */
    private class ResolvedSourceId(val viewId: String?, val atMs: Long)

    /**
     * Resolved source ids, keyed by the free part of a source's identity.
     *
     * Per key, NOT a single slot. A single slot looks like a throttle and is not one: two scroll
     * sources alternating — a feed and the tab pager above it, which the Instagram capture shows
     * arriving interleaved from one window — would miss the cache on every event and pay a binder
     * IPC each time, which is the exact cost the throttle exists to prevent. Bounded by the number
     * of distinct (windowId, className) pairs on a screen, which is a handful, and cleared with the
     * sitting.
     */
    private val resolvedSourceIds = mutableMapOf<Pair<Int, String?>, ResolvedSourceId>()

    /**
     * Feed one click or scroll event.
     *
     * Takes the pure record rather than a package name because the counter's whole answer lives in
     * fields the old signature threw away: `fromIndex` / `toIndex` / `currentItemIndex` /
     * `itemCount` / `scrollDeltaY` / `windowId`.
     *
     * @param resolveSourceViewId reads the scrolling view's `viewIdResourceName`. A binder round
     *   trip, so it is called at most once per [SOURCE_RESOLVE_THROTTLE_MS] — see [sourceViewIdFor].
     *   Defaults to the record's own field, which is what a replayed capture carries.
     */
    fun handleInteraction(
        record: AccessibilityEventRecord,
        resolveSourceViewId: () -> String? = { record.sourceViewId }
    ) {
        val packageName = record.packageName
        if (!counterCache.isCounterEnabled(packageName)) return

        val now = clock()
        // Only a scroll needs to know WHICH view moved. Resolving it for a click would pay a binder
        // round trip for an answer the click path does not read.
        val sourceViewId = if (record.type == A11yEventType.VIEW_SCROLLED) {
            sourceViewIdFor(record, now, resolveSourceViewId)
        } else {
            null
        }
        val result = counter.onEvent(record, now, sourceViewId)
        if (!result.counted) {
            // Logged at debug because "the counter did not move" and "the counter is broken" were
            // indistinguishable in logcat, which is the ambiguity that cost this repo a release
            // cycle on the picture-in-picture fix and another on the time-based auto-kick.
            logger.d("interaction not counted package=$packageName reason=${result.reason}")
            return
        }

        val count = interactionTracker.recordInteractions(packageName, result.count, result.mode)
        if (count.mode != result.mode) {
            // A tap arriving in a session that is counting items. Dropped, not tallied elsewhere —
            // see InteractionTracker.recordInteractions for why one session counts one unit.
            logger.d(
                "interaction not counted package=$packageName reason=wrong_mode " +
                    "event=${result.mode} session=${count.mode}"
            )
            return
        }
        logger.d(
            "interaction counted package=$packageName n=${result.count} " +
                "mode=${result.mode} reason=${result.reason} session=${count.sessionCount}"
        )
        showOrUpdateCounter(count, labelFor(count.mode))
    }

    /**
     * Resolve the counter's label. For a recognised in-app feature this is "reels" / "shorts" /
     * "videos"; otherwise it describes what was actually counted.
     *
     * Detection is only ever asked for a NAME here. It used to hold a veto — a scroll in a supported
     * app counted nothing unless a feature was recognised, and a scroll anywhere else counted
     * nothing at all — which is why the interaction counter has never worked on the YouTube home
     * feed (`docs/BACKLOG.md`). A label we cannot resolve is a cosmetic loss; a count we refuse to
     * make is the feature not working.
     */
    private fun labelFor(mode: CountMode): String = when (mode) {
        CountMode.TAPS -> activeReelLabel ?: "taps"
        CountMode.ITEMS -> activeReelLabel ?: "scrolls"
    }

    /**
     * Which view is scrolling, resolved at most once per [SOURCE_RESOLVE_THROTTLE_MS].
     *
     * The counter needs this to tell a comments sheet apart from the feed behind it: both are
     * commonly a `RecyclerView` in the same window, so without the view id they share a source key,
     * the sheet inherits the feed's primary status, and their indices interleave into phantom
     * counts. That is the reported "scrolling the comments counts ~10 taps".
     *
     * But `AccessibilityEvent.getSource()` is a binder round trip into the observed app, and a
     * single slow one-second drag produces ten scroll events (measured on a Pixel 3:
     * `app/src/test/resources/a11y-captures/aosp-list-slow-scroll.jsonl`). Paying for each of them
     * would put an IPC on the accessibility event thread ten times a second, which is exactly the
     * cost this file already debounces node reads to avoid. A throttled read is correct in steady
     * state — a source does not change identity mid-scroll — and the only error window is the few
     * hundred milliseconds after the user opens a sheet, where "first seen counts zero" caps the
     * damage at a single count.
     */
    private fun sourceViewIdFor(
        record: AccessibilityEventRecord,
        nowMs: Long,
        resolve: () -> String?
    ): String? {
        val key = record.windowId to record.className
        val cached = resolvedSourceIds[key]
        if (cached != null && nowMs - cached.atMs < SOURCE_RESOLVE_THROTTLE_MS) return cached.viewId
        val viewId = try {
            resolve()
        } catch (e: Exception) {
            logger.w("failed to resolve scroll source view id", e)
            null
        }
        resolvedSourceIds[key] = ResolvedSourceId(viewId, nowMs)
        return viewId
    }

    /**
     * Resolve the in-app feature label for [packageName] from the node tree, at most once per
     * surface. Called from the service's content-change path, which already owns a debounced tree
     * read — the counter itself must never trigger one, because scroll events arrive in bursts.
     */
    fun noteDetectedFeature(feature: InAppDetector.Feature?) {
        val label = when (feature) {
            InAppDetector.Feature.SHORTS -> "shorts"
            InAppDetector.Feature.REELS -> "reels"
            InAppDetector.Feature.TIKTOK_FEED -> "videos"
            InAppDetector.Feature.EXPLORE, null -> return
        }
        activeReelLabel = label
    }

    /**
     * Resolve the label for a scrolled surface if it is cheap to do so.
     *
     * Kept as an explicitly-optional lookup: [rootNodeProvider] is a binder read, and the counter's
     * correctness must never depend on it. If it returns null, the count still happens under a
     * generic label.
     */
    fun resolveLabelIfUnknown(packageName: String, rootNodeProvider: () -> AccessibilityNodeInfo?) {
        if (activeReelLabel != null) return
        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) return
        val rootNode = rootNodeProvider() ?: return
        noteDetectedFeature(inAppDetector.detectFeature(packageName, rootNode))
    }

    private fun showOrUpdateCounter(count: InteractionTracker.SessionCount, label: String) {
        try {
            if (!counterOverlayManager.isVisible()) {
                counterOverlayManager.show(label)
            }
            counterOverlayManager.updateCount(count.sessionCount, count.dailyTotal)
            timeRemainingHandler.maybeUpdate(count.packageName)
            checkAutoKick(count)
        } catch (e: Exception) {
            logger.w("counter overlay update failed package=${count.packageName}", e)
        }
    }

    private fun checkAutoKick(count: InteractionTracker.SessionCount) {
        val autoKickAfter = counterCache.getEntry(count.packageName)?.autoKickAfter ?: return
        if (count.sessionCount < autoKickAfter) return

        // The kick itself (cooldown, home, session reset, overlay teardown) is shared with the
        // time-based trigger -- see AutoKickExecutor.
        autoKickExecutor.kick(
            count.packageName,
            reason = "interactions session=${count.sessionCount} threshold=$autoKickAfter"
        )
    }

    /**
     * Called when the foreground app changes to a counter-enabled package.
     * Shows the counter overlay immediately with current session count so the user
     * sees the counter as soon as they enter the app (not only after first interaction).
     */
    fun onAppChanged(packageName: String) {
        // Always: this is the session boundary for BOTH auto-kick triggers, including packages
        // that only have a time-based rule (and therefore no counter).
        interactionTracker.onAppChanged(packageName)
        // Show counter on app entry only if there is a persisted session count > 0
        // (avoids showing a confusing "0" when the user first opens an app)
        if (counterCache.isCounterEnabled(packageName)) {
            val sessionCount = interactionTracker.getSessionCount(packageName)
            val dailyTotal = interactionTracker.getDailyTotal(packageName)
            if (sessionCount > 0) {
                val label = activeReelLabel ?: "taps"
                try {
                    if (!counterOverlayManager.isVisible()) {
                        counterOverlayManager.show(label)
                    }
                    counterOverlayManager.updateCount(sessionCount, dailyTotal)
                } catch (e: Exception) {
                    logger.w("counter overlay show on app entry failed package=$packageName", e)
                }
            }
        }
    }

    /**
     * The user's sitting changed, so the screen did too.
     *
     * Per-source scroll state — last item index, accumulated distance, and which source holds the
     * primary election — describes ONE screen. Carrying it into the next app would let a stale
     * index produce a phantom transition, and would let the previous app's feed keep the election
     * and silence the new app's.
     */
    fun onSittingChanged() {
        counter.reset()
        activeReelLabel = null
        resolvedSourceIds.clear()
    }

    fun isCounterVisible(): Boolean = counterOverlayManager.isVisible()

    fun hideCounter() {
        if (counterOverlayManager.isVisible()) counterOverlayManager.hide()
    }

    companion object {
        /**
         * Minimum gap between `viewIdResourceName` binder reads. Matches
         * `NudgeAccessibilityService.SWITCH_CHECK_DEBOUNCE_MS`, which throttles the other
         * per-event node read on this thread for the same reason.
         */
        const val SOURCE_RESOLVE_THROTTLE_MS = 500L

        /** The event types this handler accepts; anything else is not an interaction. */
        internal fun handles(type: A11yEventType): Boolean =
            type == A11yEventType.VIEW_CLICKED || type == A11yEventType.VIEW_SCROLLED
    }
}
