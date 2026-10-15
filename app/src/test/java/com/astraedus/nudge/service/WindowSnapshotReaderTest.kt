package com.astraedus.nudge.service

import com.astraedus.nudge.domain.windows.WindowBounds
import com.astraedus.nudge.domain.windows.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1: the window reader's owner cache, its fail-soft rule, and the read throttle. */
class WindowSnapshotReaderTest {

    private val bounds = WindowBounds(0, 0, 1080, 2340)

    private fun raw(id: Int, kind: WindowKind, onResolve: () -> String?) = RawWindow(
        windowId = id, kind = kind, isActive = false, isFocused = false,
        isPictureInPicture = false, bounds = bounds, resolvePackage = onResolve
    )

    @Test
    fun `owners are resolved once per window id, and only for application windows`() {
        var resolves = 0
        var imeResolves = 0
        val list = listOf(
            raw(1, WindowKind.APPLICATION) { resolves++; "com.instagram.android" },
            raw(2, WindowKind.INPUT_METHOD) { imeResolves++; "com.touchtype.swiftkey" }
        )
        val reader = WindowSnapshotReader(readRawWindows = { list }, readDisplaySize = { 1080 to 2340 })
        repeat(3) { reader.read() }
        val snapshot = reader.read()!!
        assertEquals("one root read for the window's whole life, not one per refresh", 1, resolves)
        assertEquals("a keyboard is identified by its type, its root is never read", 0, imeResolves)
        assertEquals("com.instagram.android", snapshot.windows[0].packageName)
        assertNull(snapshot.windows[1].packageName)
    }

    @Test
    fun `an unreadable root is retried next time, not cached as no owner`() {
        var owner: String? = null
        val reader = WindowSnapshotReader(
            readRawWindows = { listOf(raw(1, WindowKind.APPLICATION) { owner }) },
            readDisplaySize = { 1080 to 2340 }
        )
        assertNull(reader.read()!!.windows[0].packageName)
        owner = "com.instagram.android"
        assertEquals("com.instagram.android", reader.read()!!.windows[0].packageName)
    }

    @Test
    fun `a failed read or an unknown display is no snapshot, never an empty one`() {
        assertNull(WindowSnapshotReader(readRawWindows = { error("binder died") }, readDisplaySize = { 1080 to 2340 }).read())
        assertNull(WindowSnapshotReader(readRawWindows = { emptyList() }, readDisplaySize = { null }).read())
    }

    // --- the throttle (moved here from the PiP probe, with its three guards) ---

    @Test
    fun `a burst of WINDOWS_CHANGED inside the throttle costs one read`() {
        assertTrue(WindowSnapshotReader.shouldRead(force = false, lastReadAt = WindowSnapshotReader.NEVER, nowMs = 1_000L))
        assertFalse(WindowSnapshotReader.shouldRead(force = false, lastReadAt = 1_000L, nowMs = 1_100L))
        assertFalse(WindowSnapshotReader.shouldRead(force = false, lastReadAt = 1_000L, nowMs = 1_499L))
    }

    @Test
    fun `the list is re-read once the throttle has elapsed`() {
        assertTrue(WindowSnapshotReader.shouldRead(force = false, lastReadAt = 1_000L, nowMs = 1_500L))
    }

    @Test
    fun `the very first read always happens, even at clock zero`() {
        // A zero-initialised timestamp would report a cache hit at clock 0 and never read again.
        assertTrue(WindowSnapshotReader.shouldRead(force = false, lastReadAt = WindowSnapshotReader.NEVER, nowMs = 0L))
    }

    @Test
    fun `a WINDOW_STATE_CHANGED always re-reads`() {
        // The platform recomputes the list just before dispatching it; the classifier needs that read.
        assertTrue(WindowSnapshotReader.shouldRead(force = true, lastReadAt = 1_000L, nowMs = 1_001L))
    }
}
