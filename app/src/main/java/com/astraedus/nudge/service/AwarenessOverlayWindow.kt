package com.astraedus.nudge.service

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one accessibility IDENTITY Nudge's awareness overlays wear — the interaction counter and the
 * time-remaining pill — so the event classifier can recognise them positively.
 *
 * ## Why an overlay needs an identity at all
 *
 * [#41](https://github.com/astraedus/nudge/issues/41): with a daily limit exceeded on Keep and Keep
 * visibly in front, every 30-second clock tick's block was refused:
 *
 * ```
 * block overlay launch dropped target=com.google.android.keep reason=DROP_FOREGROUND_MOVED
 *   foreground=dev.astraedus.nudge
 * ```
 *
 * These overlays are `TYPE_ACCESSIBILITY_OVERLAY` windows owned by Nudge, so every time one is
 * added, or a `TextView` inside one has its text set, the platform emits an accessibility event
 * carrying OUR package. The classifier read any such event as [com.astraedus.nudge.domain.events.ForegroundSignal.OwnUi]
 * and `BlockLaunchGate.foregroundAfter` moves the foreground to Nudge for `OwnUi` — which is right
 * for the block overlay and the main app and wrong for a pill drawn over the app the user never
 * left. Nothing moved the foreground back while the user sat still, so the drop repeated every tick.
 *
 * ## Why the identity is a class name, and why every view in the overlay wears it
 *
 * The classifier decides from pure data (`package`, `className`), which is what makes the pipeline
 * replayable in a JVM test. `View.getAccessibilityClassName()` is what populates `className`, and
 * its default is the FRAMEWORK widget name (`android.widget.TextView`), which no more identifies our
 * pill than it identifies anyone else's. So the views override it.
 *
 * Every view in the overlay answers [CLASS_NAME], not just the root: a `TYPE_WINDOW_CONTENT_CHANGED`
 * is sourced from the view that changed (or, once `ViewRootImpl` coalesces a burst, from their
 * common ancestor), so identifying only the root would leave the counter's own text updates
 * classified as `OwnUi` and the defect half-fixed. `AwarenessOverlayContractTest` is what keeps the
 * two managers from reaching for a bare `TextView` again.
 *
 * [CLASS_NAME] is DERIVED from this object rather than written out, so a rename moves the production
 * value and the classifier's set together and a literal cannot go stale — the shape of mistake that
 * left `shouldClearForOwnPackageEvent` dead for months (issue #33).
 */
object AwarenessOverlayWindow {

    /** The accessibility class name every awareness-overlay view reports. */
    val CLASS_NAME: String = AwarenessOverlayWindow::class.java.name

    /** What `EventClassifier` is constructed with. One identity today; a set so it can be two. */
    val CLASS_NAMES: Set<String> = setOf(CLASS_NAME)

    /** The root container of a multi-line awareness overlay (the interaction counter). */
    class Container(context: Context) : LinearLayout(context) {
        override fun getAccessibilityClassName(): CharSequence = CLASS_NAME
    }

    /** Any text inside an awareness overlay, including one that is the whole overlay. */
    class Label(context: Context) : TextView(context) {
        override fun getAccessibilityClassName(): CharSequence = CLASS_NAME
    }
}
