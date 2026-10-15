package com.astraedus.nudge.service

import com.astraedus.nudge.domain.windows.ScreenWindow
import com.astraedus.nudge.domain.windows.WindowBounds
import com.astraedus.nudge.domain.windows.WindowKind
import com.astraedus.nudge.domain.windows.WindowSnapshot

/**
 * One window as the platform reports it, before the owner package is known. The service's adapter
 * builds these from `AccessibilityWindowInfo`; everything after that is pure.
 *
 * @param resolvePackage reads the owner off the window's root node: a binder call, so it is only
 *   invoked for APPLICATION windows this reader has not resolved before.
 */
internal class RawWindow(
    val windowId: Int,
    val kind: WindowKind,
    val isActive: Boolean,
    val isFocused: Boolean,
    val isPictureInPicture: Boolean,
    val bounds: WindowBounds,
    val resolvePackage: () -> String?
)

/**
 * Turns the accessibility window list into a [WindowSnapshot], issues
 * [#76](https://github.com/astraedus/nudge/issues/76) and [#71](https://github.com/astraedus/nudge/issues/71).
 *
 * The Android half is deliberately thin and lives in the service (`readRawWindows`), exactly like
 * [PipWindowProbe]'s reader: this class owns the two things worth testing, the owner cache and the
 * fail-soft rule.
 *
 * ## Cost
 *
 * `getWindows()` is one binder call. Resolving an owner is one more per window (its root node), so
 * owners are cached by window id: a window's id is stable for its whole life and its owner never
 * changes, so after the first sight of a window the steady state is ONE binder call per refresh.
 * Only APPLICATION windows are resolved at all; a keyboard or an overlay is identified by its type
 * and nobody needs its package. Ids that left the list are pruned on every read, so the cache is
 * bounded by what is on screen.
 *
 * ## Failure direction
 *
 * Any exception, or an unknown display size, yields null: "no window evidence", which every
 * consumer treats as the behaviour this app had before the snapshot existed.
 */
internal class WindowSnapshotReader(
    private val readRawWindows: () -> List<RawWindow>,
    private val readDisplaySize: () -> Pair<Int, Int>?
) {

    private val ownerByWindowId = HashMap<Int, String?>()

    fun read(): WindowSnapshot? = try {
        val raw = readRawWindows()
        val size = readDisplaySize()
        val present = raw.mapTo(HashSet()) { it.windowId }
        ownerByWindowId.keys.retainAll(present)
        val windows = raw.map { window ->
            ScreenWindow(
                windowId = window.windowId,
                kind = window.kind,
                packageName = if (window.kind == WindowKind.APPLICATION) ownerOf(window) else null,
                isActive = window.isActive,
                isFocused = window.isFocused,
                isPictureInPicture = window.isPictureInPicture,
                bounds = window.bounds
            )
        }
        if (size == null) null else WindowSnapshot(windows, size.first, size.second)
    } catch (_: Exception) {
        null
    }

    private fun ownerOf(window: RawWindow): String? {
        if (ownerByWindowId.containsKey(window.windowId)) return ownerByWindowId[window.windowId]
        val owner = window.resolvePackage()?.takeIf { it.isNotBlank() }
        // An unreadable root is NOT cached: it is usually a window still being laid out, and the
        // next read should try again rather than remember "no owner" for the window's whole life.
        if (owner != null) ownerByWindowId[window.windowId] = owner
        return owner
    }

    companion object {
        /**
         * How long a `TYPE_WINDOWS_CHANGED` burst may reuse the last read. Same cadence as the
         * other bounded reads on this path (the issue #7 active-window check, and the PiP probe this
         * replaced): a stream of window events must not become a stream of binder calls.
         */
        const val THROTTLE_MS: Long = 500L

        /** Sentinel for "never read". See [shouldRead]. */
        const val NEVER: Long = Long.MIN_VALUE

        /**
         * Should the window list be re-read now?
         *
         * Always when [force] (a `TYPE_WINDOW_STATE_CHANGED`, which the platform recomputes the list
         * for right before dispatching), always on the very first read, otherwise once per
         * [throttleMs]. The [NEVER] sentinel is compared, never subtracted: `now - Long.MIN_VALUE`
         * overflows, and a zero-initialised timestamp would report a cache hit at clock 0 and
         * answer "no windows" forever, the regression the PiP probe's own tests used to guard.
         */
        fun shouldRead(force: Boolean, lastReadAt: Long, nowMs: Long, throttleMs: Long = THROTTLE_MS): Boolean =
            force || lastReadAt == NEVER || nowMs - lastReadAt >= throttleMs

        /**
         * A one-line, stable description of what changed, for the debug log. Logged only when it
         * differs from the previous one, so a capture shows the window list next to the events
         * without a line per event (lessons 2026-09-12: instrument the surface you cannot see).
         */
        fun describe(snapshot: WindowSnapshot?): String {
            if (snapshot == null) return "unavailable"
            return snapshot.windows.joinToString(prefix = "display=${snapshot.displayWidth}x${snapshot.displayHeight} [", postfix = "]") { w ->
                buildString {
                    append(w.windowId).append(':').append(w.kind.name.lowercase())
                    w.packageName?.let { append(':').append(it) }
                    if (w.isFocused) append(":focused")
                    if (w.isActive) append(":active")
                    if (w.isPictureInPicture) append(":pip")
                    if (w.kind == WindowKind.APPLICATION && !snapshot.isFullscreen(w)) {
                        append(":").append(w.bounds.width).append('x').append(w.bounds.height)
                    }
                }
            }
        }
    }
}
