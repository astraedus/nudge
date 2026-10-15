package com.astraedus.nudge.service

/**
 * One window, reduced to what picture-in-picture detection cares about.
 *
 * [isApplicationWindow] is load-bearing, not decoration — see [PipWindowProbe.pipPackages].
 *
 * [packageName] is null for a window whose owner was not resolved. Since #76/#71 the service derives
 * these from its one [com.astraedus.nudge.domain.windows.WindowSnapshot], which resolves the owner
 * of every APPLICATION window once per window id (see [WindowSnapshotReader]).
 */
data class PipWindow(
    val packageName: String?,
    val isPictureInPicture: Boolean,
    val isApplicationWindow: Boolean
)

/**
 * Answers "which packages are currently playing in a picture-in-picture window?" for issue #19.
 *
 * **Why this exists.** When Nudge's block overlay backgrounds an app, the app can enter PiP and keep
 * playing in a floating always-on-top window. Our overlay is fullscreen and is the top resumed
 * activity and still loses — platform behaviour, not an overlay bug. Detecting it is the only thing
 * Nudge can do in code (the remedy is the per-app PiP permission in Settings), and the accessibility
 * window list is the only place the state is visible.
 *
 * **Cost discipline.** This used to own a throttled `getWindows()` read of its own. Since issues
 * #76 and #71 the service reads the window list ONCE per window change into a
 * [com.astraedus.nudge.domain.windows.WindowSnapshot] (the throttle and its sentinel moved to
 * [WindowSnapshotReader.shouldRead]) and PiP is derived from it, so two readers can no longer
 * disagree about what is on screen. What is left here is the pure part, which is the part that
 * carried the v1.12.0 field failure.
 */
internal class PipWindowProbe private constructor() {

    companion object {
        /**
         * Owners of every APPLICATION window currently in picture-in-picture.
         *
         * **The `isApplicationWindow` filter is the fix for the v1.12.0 field failure, do not drop
         * it.** On a live Pixel 3 / API 31 bubble, `dumpsys accessibility` shows TWO windows with
         * `pictureInPicture=true`:
         *
         * ```
         * title=Picture-in-Picture menu, type=TYPE_SYSTEM,      layer=2, pictureInPicture=true
         * title=YouTube,                 type=TYPE_APPLICATION, layer=1, pictureInPicture=true
         * ```
         *
         * SystemUI's PiP *menu* is flagged PiP too, and it sorts BEFORE the app's own window. The
         * first implementation took the first flagged window it found, resolved its owner to
         * `com.android.systemui`, compared that against the blocked package, got no match, and
         * returned silently — so detection never fired once in the field and logged nothing to say
         * why. Only the application window identifies the app that is actually escaping.
         *
         * Returns a SET rather than a single package: "is P in PiP?" is the question every caller
         * actually has, and answering it by set membership cannot be broken by another PiP-flagged
         * window sorting first.
         *
         * A PiP window whose owner could not be resolved is skipped rather than guessed at: callers
         * compare against packages they are blocking, and a wrong match would suppress a real block.
         */
        fun pipPackages(windows: List<PipWindow>): Set<String> = windows
            .asSequence()
            .filter { it.isPictureInPicture && it.isApplicationWindow }
            .mapNotNull { it.packageName?.takeIf(String::isNotBlank) }
            .toSet()

        /**
         * Of [pipPackages], those that are NOT the foreground app — i.e. packages whose only
         * presence on screen is a floating PiP window.
         *
         * This is the distinction the whole fix turns on. An accessibility event carrying package P
         * normally means "P is in front", and every evaluation path in the service assumes it. A PiP
         * window breaks that assumption: P has a window and fires events while the user is somewhere
         * else entirely. Treating those as foreground entries is what re-blocked YouTube nine times
         * in five minutes and inflated the all-time Blocked count by eleven during a single incident
         * — while the tester was navigating inside Nudge itself.
         *
         * @param activeWindowPackage owner of the active window, or null when it could not be read.
         *   Null means every PiP package counts as PiP-only, which is the safe reading: a window in
         *   PiP is by definition not the fullscreen foreground app, and the active-window check is
         *   only a guard for the brief transition when the user expands the bubble back to
         *   fullscreen. A missed block here self-corrects the moment PiP ends.
         */
        fun pipOnlyPackages(pipPackages: Set<String>, activeWindowPackage: String?): Set<String> =
            if (activeWindowPackage == null) pipPackages else pipPackages - activeWindowPackage
    }
}
