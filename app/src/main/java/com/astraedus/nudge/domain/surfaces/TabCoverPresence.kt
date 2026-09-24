package com.astraedus.nudge.domain.surfaces

import com.astraedus.nudge.domain.events.ForegroundSignal

/**
 * The one question "should the tab cover still be on screen?", answered from a classified
 * [ForegroundSignal] and the package the cover was drawn over.
 *
 * The cover is a `TYPE_ACCESSIBILITY_OVERLAY` window painted over a host app's nav bar. If it
 * outlives the app it was drawn for it becomes an opaque rectangle floating over something else, so
 * *something* has to tear it down — and the mistake available here is tearing it down from the six
 * different places in the service that each mean "the user is somewhere else". Those six disagree
 * about what "somewhere else" means, which is how issue #19 and issue #41 both happened. This object
 * is the single rule instead, reached from `applyForegroundSignal`, the one place that sees every
 * classification above every early return.
 *
 * ## Why the `when` is exhaustive with no `else`
 *
 * [ForegroundSignal] is a sealed interface, and a new variant added to it must force a decision
 * *here* rather than inherit one. An `else -> true` would silently keep the cover alive over a
 * signal nobody had considered; an `else -> false` would silently flicker it. The repo already makes
 * this argument for `A11yEventType` in `NudgeAccessibilityService`: the compiler is the only reviewer
 * guaranteed to read every new branch.
 */
object TabCoverPresence {

    /**
     * True when the cover drawn over [coveredPackage] should stay up after [signal].
     *
     * The split is between signals that CLAIM something about the foreground and signals that do
     * not. Only [ForegroundSignal.AppWindow], [ForegroundSignal.Home], [ForegroundSignal.OwnUi] and
     * [ForegroundSignal.SystemSurface] make a claim; every other variant exists precisely because it
     * does not, and a non-claim must never be read as "the user left".
     *
     * - [ForegroundSignal.AppWindow] — a real app window is in front. Keep the cover only if that
     *   app is the one it was drawn over. This is the branch that moves the cover off a host app
     *   when the user switches to a different one.
     * - [ForegroundSignal.Home] — the launcher. The user genuinely left; drop it.
     * - [ForegroundSignal.SystemSurface] — the shade, a permission dialog, the volume panel. These
     *   cover the host app's nav bar themselves, so a cover left underneath would be drawn over
     *   system UI. Drop it; the next `AppWindow` for the host app puts it back.
     * - [ForegroundSignal.OwnUi] — Nudge's own block overlay or app is in front of the user. Drop it.
     * - [ForegroundSignal.AwarenessOverlay] — **keep**. This is the case that would otherwise eat
     *   itself: the tab cover IS an awareness overlay. Its own window fires window and content
     *   events like any other, so a rule that tore the cover down on `AwarenessOverlay` would order
     *   the cover hidden the instant it appeared, on its own event. That is the identical shape as
     *   issue #41, where the awareness overlay's events moved `foregroundAfter` onto Nudge and every
     *   daily-limit tick was then refused while the user sat still in the blocked app. The signal
     *   makes NO claim about the foreground — the user is still looking at whatever was underneath.
     * - [ForegroundSignal.Transient] — a keyboard, a toast, a long-press popup. No claim; keep.
     * - [ForegroundSignal.PipOnly] — a picture-in-picture bubble firing events from somewhere the
     *   user is not (issue #19). No claim; keep.
     * - [ForegroundSignal.NotForeground] — a content change, a click, a scroll. Carries no window at
     *   all, which is the single most common signal while a user scrolls the host app. Keep, or the
     *   cover would blink on every scroll event.
     *
     * @param coveredPackage the package the cover was drawn over, or null when no cover is up (in
     *   which case an [ForegroundSignal.AppWindow] for any package cannot match, so the answer is
     *   false and the caller has nothing to tear down).
     */
    fun shouldKeep(signal: ForegroundSignal, coveredPackage: String?): Boolean = when (signal) {
        is ForegroundSignal.AppWindow -> signal.packageName == coveredPackage
        is ForegroundSignal.Home -> false
        is ForegroundSignal.SystemSurface -> false
        is ForegroundSignal.OwnUi -> false
        is ForegroundSignal.AwarenessOverlay -> true
        is ForegroundSignal.Transient -> true
        is ForegroundSignal.PipOnly -> true
        is ForegroundSignal.NotForeground -> true
    }
}
