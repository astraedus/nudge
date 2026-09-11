package com.astraedus.nudge.service

import android.os.Build

import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord

/**
 * Converts the framework's [AccessibilityEvent] into the pure
 * [AccessibilityEventRecord] the rest of the pipeline runs on.
 *
 * This is the ONLY place in the app that reads fields off a live `AccessibilityEvent`. Everything
 * downstream — the classifier, the sitting model, the interaction counter — takes a record, which is
 * why the whole pipeline can be replayed from a capture file in a JVM test.
 *
 * **Why a class and not a top-level function**: reading `event.source.viewIdResourceName` is a
 * binder round trip to the app being observed, and it is the one field here that is not free. The
 * factory owns that decision (and its cost accounting) in one place instead of leaving each call
 * site to remember.
 *
 * @param readSourceViewId whether to pay for the source-node read. A lambda, not a value, because
 *   the answer is a runtime flag (the debug-logging preference can be toggled inside a release
 *   build without restarting the service). See [SourceReadPolicy].
 */
class AccessibilityEventRecordFactory(
    private val readSourceViewId: () -> SourceReadPolicy = { SourceReadPolicy.NEVER },
    private val onSourceReadCost: (nanos: Long) -> Unit = {}
) {

    /**
     * When to pay the binder round trip for `viewIdResourceName`.
     *
     * [NEVER] is the release default: the counter's decisions are made from the scroll record's own
     * fields (indices, deltas, window id), none of which cost anything. [SCROLL_AND_CLICK] is what a
     * capture run uses, because knowing WHICH view scrolled is what lets a human decide, once,
     * offline, whether a given surface should count — after which that decision is expressed as a
     * rule over the free fields.
     */
    enum class SourceReadPolicy { NEVER, SCROLL_AND_CLICK }

    fun toRecord(event: AccessibilityEvent): AccessibilityEventRecord? {
        val packageName = event.packageName?.toString() ?: return null
        val type = eventType(event.eventType)
        return AccessibilityEventRecord(
            type = type,
            packageName = packageName,
            className = event.className?.toString(),
            windowId = event.windowId,
            eventTimeMs = event.eventTime,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            currentItemIndex = event.currentItemIndex,
            itemCount = event.itemCount,
            // API 28+ only. minSdk is 26, and an unguarded read is a NoSuchMethodError on EVERY
            // event on Android 8.x, which kills the accessibility service outright. The -1 sentinel
            // is what the framework itself reports for "never set", and InteractionCounter treats
            // it as "no delta available" (index path only, no horizontal detection).
            scrollDeltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaX else -1,
            scrollDeltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else -1,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            contentChangeTypes = event.contentChangeTypes,
            sourceViewId = sourceViewIdOrNull(event, type),
            isScrollable = event.isScrollable
        )
    }

    private fun sourceViewIdOrNull(event: AccessibilityEvent, type: A11yEventType): String? {
        if (readSourceViewId() != SourceReadPolicy.SCROLL_AND_CLICK) return null
        if (type != A11yEventType.VIEW_SCROLLED && type != A11yEventType.VIEW_CLICKED) return null
        return readSourceViewId(event)
    }

    /**
     * Read `viewIdResourceName` off the event's source node, unconditionally.
     *
     * Public because the interaction counter genuinely needs it in RELEASE builds — it is how a
     * comments sheet is told apart from the feed behind it — but under the caller's own throttle
     * (`InteractionHandler.sourceViewIdFor`), never per event. Cost is accounted here either way, so
     * a capture run reports what production is paying.
     */
    fun readSourceViewId(event: AccessibilityEvent): String? {
        val started = System.nanoTime()
        return try {
            // getSource() is an IPC into the observed app; recycle so a capture run cannot leak
            // node handles over the thousands of events it collects.
            val source = event.source ?: return null
            try {
                source.viewIdResourceName
            } finally {
                @Suppress("DEPRECATION")
                source.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            onSourceReadCost(System.nanoTime() - started)
        }
    }

    companion object {
        fun eventType(frameworkType: Int): A11yEventType = when (frameworkType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> A11yEventType.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> A11yEventType.WINDOWS_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> A11yEventType.WINDOW_CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> A11yEventType.VIEW_CLICKED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> A11yEventType.VIEW_SCROLLED
            else -> A11yEventType.OTHER
        }
    }
}
