package com.astraedus.nudge.domain.windows

/**
 * What kind of window this is, reduced from `AccessibilityWindowInfo.TYPE_*`.
 *
 * Only [APPLICATION] is "an app the user can be in". Every other kind is drawn by or for something
 * else: the keyboard, a system or app overlay (`TYPE_APPLICATION_OVERLAY` reports as SYSTEM), an
 * accessibility service's own overlay (ours included), the split-screen divider.
 */
enum class WindowKind {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY_OVERLAY,

    /** Split-screen divider, magnification overlay, or a type this enum predates. */
    OTHER
}

/** A window rectangle in SCREEN coordinates, exactly as `getBoundsInScreen` reports it. */
data class WindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** The smallest rectangle containing both. */
    fun union(other: WindowBounds): WindowBounds = WindowBounds(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )
}

/**
 * One on-screen window, as pure data.
 *
 * @param packageName the owner, resolved ONLY for [WindowKind.APPLICATION] windows (resolving it is
 *   a binder read of the window's root node). Null for every other kind, and for an application
 *   window whose root could not be read. Never guess it.
 */
data class ScreenWindow(
    val windowId: Int,
    val kind: WindowKind,
    val packageName: String?,
    val isActive: Boolean,
    val isFocused: Boolean,
    val isPictureInPicture: Boolean,
    val bounds: WindowBounds
) {
    /** The window the user is interacting with: input focus, or the last one touched. */
    val holdsFocus: Boolean get() = isActive || isFocused
}

/**
 * Every window on screen at one moment, plus the display it is on.
 *
 * ## Why this exists ([#76](https://github.com/astraedus/nudge/issues/76), [#71](https://github.com/astraedus/nudge/issues/71))
 *
 * The service used to decide "package P is the app the user is in" from the EVENT'S package and
 * never look at the window that event came from. That is false in three ways the window list can
 * see and the event cannot:
 *
 *  - a window drawn by another package INSIDE or BESIDE the keyboard (inline autofill suggestions
 *    are rendered in the IME's own window; the Samsung clipboard and Galaxy AI writing tools are
 *    overlays) is not the user leaving the app they are typing in (#76);
 *  - an application window that is neither active nor focused, while another app's window is, is
 *    a panel beside what the user is in, not what they are in;
 *  - a NON-fullscreen application window (an OEM freeform / floating window, a split-screen half)
 *    sits ABOVE a fullscreen block overlay, so a touch on it is not the user getting past the
 *    overlay, and covering it is the only way to block it (#71).
 *
 * Picture-in-picture (#19) was the first instance of this and got a one-off probe; it now reads
 * from here too ([pipWindows]).
 *
 * ## Failure direction
 *
 * Every query answers on POSITIVE evidence only. A window id this snapshot does not contain, or a
 * package with no window in it, yields no claim at all — callers then behave exactly as they did
 * before the snapshot existed. The list can lag an event by a frame; an unknown is never a reason
 * to stop evaluating an app, because that is a missed block.
 */
data class WindowSnapshot(
    val windows: List<ScreenWindow>,
    val displayWidth: Int,
    val displayHeight: Int
) {

    /** The kind of window [windowId] is, or null when this snapshot does not contain it. */
    fun kindOf(windowId: Int): WindowKind? = windows.firstOrNull { it.windowId == windowId }?.kind

    /** True when [windowId] is a window we can see and it is NOT an application window. */
    fun isNonApplicationWindow(windowId: Int): Boolean {
        val kind = kindOf(windowId) ?: return false
        return kind != WindowKind.APPLICATION
    }

    /** Every application window owned by [packageName]. */
    fun applicationWindowsOf(packageName: String): List<ScreenWindow> = windows.filter {
        it.kind == WindowKind.APPLICATION && it.packageName == packageName
    }

    /** True when [packageName] owns an application window that is active or focused. */
    fun ownsFocusedApplicationWindow(packageName: String): Boolean =
        applicationWindowsOf(packageName).any { it.holdsFocus }

    /**
     * Positive evidence that an event from [packageName] is NOT the app the user is in: it owns
     * application windows, none of them active or focused, while ANOTHER package's application
     * window is.
     *
     * All three halves are load-bearing. A package with no window here could simply be newer than
     * the snapshot; a snapshot where no application window holds focus (focus on a system panel,
     * or a transition) is no evidence about who the user is in. Either answers false.
     */
    fun focusIsElsewhere(packageName: String): Boolean {
        val own = applicationWindowsOf(packageName)
        if (own.isEmpty() || own.any { it.holdsFocus }) return false
        return windows.any {
            it.kind == WindowKind.APPLICATION &&
                it.holdsFocus &&
                it.packageName != null &&
                it.packageName != packageName
        }
    }

    /**
     * Is [window] laid out over (practically) the whole display?
     *
     * A fullscreen app window can legitimately fall a little short of the display: on API 26-29 an
     * app that does not draw behind an opaque navigation bar gets a frame that stops at it, and a
     * cutout or status bar can trim the top. A floating or split window falls far short on at least
     * one axis. [FULLSCREEN_FRACTION] sits between the two.
     */
    fun isFullscreen(window: ScreenWindow): Boolean {
        if (displayWidth <= 0 || displayHeight <= 0) return true
        return window.bounds.width >= displayWidth * FULLSCREEN_FRACTION &&
            window.bounds.height >= displayHeight * FULLSCREEN_FRACTION
    }

    /**
     * Where [packageName] is FLOATING, or null when it is not.
     *
     * Floating means: it owns at least one non-PiP application window and NONE of its application
     * windows is fullscreen. Returns the union of those windows' bounds, so a popup menu the app
     * opened beside its floating window is under the same cover.
     *
     * Picture-in-picture is excluded on purpose. It has its own handling (#19: its events drive
     * nothing, and the remedy is the per-app PiP permission), and covering or pressing BACK on a
     * PiP bubble would change behaviour that shipped deliberately.
     *
     * An unknown display size answers null: "we cannot tell what fullscreen is" never licenses a
     * cover.
     */
    fun floatingBoundsOf(packageName: String): WindowBounds? {
        if (displayWidth <= 0 || displayHeight <= 0) return null
        val own = applicationWindowsOf(packageName).filter {
            !it.isPictureInPicture && !it.bounds.isEmpty
        }
        if (own.isEmpty() || own.any(::isFullscreen)) return null
        return own.map { it.bounds }.reduce(WindowBounds::union)
    }

    /** True when [packageName] is on screen only as a floating window. See [floatingBoundsOf]. */
    fun isFloating(packageName: String): Boolean = floatingBoundsOf(packageName) != null

    /**
     * The id of a floating window of [packageName] that currently holds focus, or null. A BACK
     * press only reaches the focused window, so this is what decides whether pressing it would hit
     * the blocked app or something else.
     */
    fun focusedFloatingWindowOf(packageName: String): Int? {
        if (!isFloating(packageName)) return null
        return applicationWindowsOf(packageName).firstOrNull { it.holdsFocus }?.windowId
    }

    companion object {
        /**
         * A window covering at least this fraction of the display on BOTH axes is fullscreen.
         *
         * 0.85 leaves room for an opaque navigation bar (~5% of the long axis on a 1080x2340
         * panel) and a status bar or cutout, while a split-screen half (~50% on one axis) and an
         * OEM freeform window (typically 55-75% on both) fall well below it.
         */
        const val FULLSCREEN_FRACTION = 0.85
    }
}
