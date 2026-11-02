package com.astraedus.nudge.domain.events

/**
 * The accessibility event types this app reacts to, as a closed set.
 *
 * The framework's `AccessibilityEvent.TYPE_*` values are raw `Int` bit flags and every branch in the
 * service used to switch on them directly. Mapping them to an enum at the service boundary buys two
 * things the raw ints cannot: the pure layer has no `android.*` dependency (so it is JVM-testable
 * and replayable from a captured fixture), and a `when` over this enum is exhaustive, so a new event
 * type cannot silently fall into an `else`.
 */
enum class A11yEventType {
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    WINDOW_CONTENT_CHANGED,
    VIEW_CLICKED,
    VIEW_SCROLLED,

    /** Anything else the service is registered for but does not branch on. */
    OTHER;

    /** True for the two types that mean "a window came forward, or the window list changed". */
    val isWindowChange: Boolean
        get() = this == WINDOW_STATE_CHANGED || this == WINDOWS_CHANGED
}

/**
 * One accessibility event, reduced to pure data.
 *
 * This is the single input type of the whole event pipeline. [com.astraedus.nudge.service.NudgeAccessibilityService]
 * converts the framework object into one of these once, at the top of `onAccessibilityEvent`, and
 * everything downstream — classification, the sitting model, interaction counting — reads only this.
 * That is what makes the pipeline replayable: a captured event stream (`scripts/a11y-capture.sh`) is
 * a list of these, so a bug report becomes a fixture and a fixture becomes a failing test.
 *
 * Numeric fields keep the framework's own sentinel: [UNDEFINED] (-1) means "the source view did not
 * populate this". Never treat -1 as a real measurement.
 *
 * @param sourceViewId `viewIdResourceName` of the event's source node. Reading it costs a binder
 *   round trip, so it is populated only where the pipeline needs it (see
 *   [com.astraedus.nudge.service.AccessibilityEventRecordFactory]); null means "not read", never
 *   "the view has no id".
 */
data class AccessibilityEventRecord(
    val type: A11yEventType,
    val packageName: String,
    val className: String? = null,
    val windowId: Int = UNDEFINED,
    val eventTimeMs: Long = 0L,
    val fromIndex: Int = UNDEFINED,
    val toIndex: Int = UNDEFINED,
    val currentItemIndex: Int = UNDEFINED,
    val itemCount: Int = UNDEFINED,
    val scrollDeltaX: Int = UNDEFINED,
    val scrollDeltaY: Int = UNDEFINED,
    val scrollX: Int = UNDEFINED,
    val scrollY: Int = UNDEFINED,
    val maxScrollX: Int = UNDEFINED,
    val maxScrollY: Int = UNDEFINED,
    val contentChangeTypes: Int = 0,
    val sourceViewId: String? = null,
    val isScrollable: Boolean = false
) {
    /**
     * True for a `TYPE_WINDOW_STATE_CHANGED` that is about an accessibility PANE inside a window
     * (a view with a pane title appearing, disappearing, or retitled) and not about a window
     * coming forward.
     *
     * The platform overloads the type. Its own documentation for `TYPE_WINDOW_STATE_CHANGED` says
     * these events "should only be dispatched from Views that have accessibility pane titles, and
     * replaces TYPE_WINDOW_CONTENT_CHANGED for those sources": a pane event is a CONTENT CHANGE
     * wearing the window type, and the only thing that tells them apart is [contentChangeTypes].
     *
     * It matters because a pane can disappear from an app that is already gone. YouTube tears down
     * its Shorts player pane (`android.view.ViewGroup`, `CONTENT_CHANGE_TYPE_PANE_DISAPPEARED`) a few
     * dozen milliseconds AFTER the launcher has come forward. Read as "YouTube came forward", it
     * started a new sitting over the home screen, the Delay overlay landed on the launcher, and
     * when its countdown finished the completion hand-back reopened YouTube by itself. See
     * `youtube-pane-straggler-after-home.jsonl`.
     */
    val isPaneChange: Boolean
        get() = type == A11yEventType.WINDOW_STATE_CHANGED && (contentChangeTypes and PANE_CHANGE_TYPES) != 0

    companion object {
        /** The framework's own "this field was never set" value (`AccessibilityRecord.UNDEFINED`). */
        const val UNDEFINED = -1

        /**
         * `AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE` (0x08), `..._PANE_APPEARED` (0x10) and
         * `..._PANE_DISAPPEARED` (0x20), API 28. Spelled as numbers because this layer has no
         * `android.*` dependency; `PaneChangeTest` pins them to the framework constants, so a typo
         * here fails a test instead of silently classifying panes as windows again.
         */
        const val PANE_CHANGE_TYPES = 0x08 or 0x10 or 0x20
    }
}
