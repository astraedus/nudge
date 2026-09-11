package com.astraedus.nudge.domain.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec is the contract between the device and the test suite: the service writes these lines,
 * the fixtures store them, and every replay test reads them back. An encoder and a decoder that
 * drifted apart would turn every committed capture into a DIFFERENT event stream than the device
 * saw, and the replay tests would keep passing against the wrong data — the worst possible failure
 * for a fixture suite. Hence the round-trip test over a fully-populated record.
 */
class AccessibilityEventCodecTest {

    private val fullyPopulated = AccessibilityEventRecord(
        type = A11yEventType.VIEW_SCROLLED,
        packageName = "com.instagram.android",
        className = "androidx.recyclerview.widget.RecyclerView",
        windowId = 9281,
        eventTimeMs = 3679807043L,
        fromIndex = 0,
        toIndex = 2,
        currentItemIndex = 4,
        itemCount = 20,
        scrollDeltaX = 0,
        scrollDeltaY = 1180,
        scrollX = 3,
        scrollY = 4120,
        maxScrollX = 0,
        maxScrollY = 99999,
        contentChangeTypes = 3,
        sourceViewId = "android:id/list",
        isScrollable = true
    )

    @Test
    fun `a fully populated record survives a round trip unchanged`() {
        assertEquals(fullyPopulated, AccessibilityEventCodec.decode(AccessibilityEventCodec.encode(fullyPopulated)))
    }

    @Test
    fun `a minimal record survives a round trip unchanged`() {
        val minimal = AccessibilityEventRecord(
            type = A11yEventType.WINDOW_STATE_CHANGED,
            packageName = "com.android.systemui"
        )
        assertEquals(minimal, AccessibilityEventCodec.decode(AccessibilityEventCodec.encode(minimal)))
    }

    @Test
    fun `every event type round trips`() {
        A11yEventType.entries.forEach { type ->
            val record = AccessibilityEventRecord(type = type, packageName = "p")
            assertEquals(type, AccessibilityEventCodec.decode(AccessibilityEventCodec.encode(record))?.type)
        }
    }

    /** Class names and view ids come from arbitrary apps; one quote must not corrupt a whole line. */
    @Test
    fun `strings containing quotes backslashes and control characters are escaped`() {
        val nasty = fullyPopulated.copy(
            className = """a"b\c""",
            sourceViewId = "x\ty\nz"
        )
        val line = AccessibilityEventCodec.encode(nasty)
        assertEquals(1, line.lines().size)
        assertEquals(nasty, AccessibilityEventCodec.decode(line))
    }

    @Test
    fun `comments blanks and malformed lines decode to null rather than throwing`() {
        assertNull(AccessibilityEventCodec.decode("# a11y capture: something"))
        assertNull(AccessibilityEventCodec.decode("   "))
        assertNull(AccessibilityEventCodec.decode("not json at all"))
        assertNull(AccessibilityEventCodec.decode("""{"t":"VIEW_SCROLLED","p":"""))
        assertNull(AccessibilityEventCodec.decode("""{"p":"com.x"}"""), )
    }

    /**
     * A capture taken before a field existed must still replay, or every old fixture becomes
     * worthless the first time the record grows a field.
     */
    @Test
    fun `unknown keys are ignored and missing keys fall back to the record defaults`() {
        val decoded = AccessibilityEventCodec.decode(
            """{"t":"VIEW_CLICKED","p":"com.x","somethingNew":"42"}"""
        )!!
        assertEquals(A11yEventType.VIEW_CLICKED, decoded.type)
        assertEquals("com.x", decoded.packageName)
        assertEquals(AccessibilityEventRecord.UNDEFINED, decoded.fromIndex)
        assertNull(decoded.sourceViewId)
    }

    @Test
    fun `decodeAll skips headers and keeps event order`() {
        val lines = sequenceOf(
            "# header",
            AccessibilityEventCodec.encode(fullyPopulated.copy(windowId = 1)),
            "",
            AccessibilityEventCodec.encode(fullyPopulated.copy(windowId = 2))
        )
        val decoded = AccessibilityEventCodec.decodeAll(lines)
        assertEquals(listOf(1, 2), decoded.map { it.windowId })
    }

    /** The encoder runs on the accessibility hot path; one line per event is load-bearing. */
    @Test
    fun `encoding never emits a newline`() {
        assertTrue(AccessibilityEventCodec.encode(fullyPopulated).none { it == '\n' || it == '\r' })
    }
    /**
     * The hostile characters a real app can put in a class name or a view id, which the hand-rolled
     * escaper has to survive because the fixture format has no external moving parts by design.
     *
     * U+2028 / U+2029 are the interesting ones: they are legal, unescaped, INSIDE a JSON string, and
     * a strict reader is entitled to accept them -- but they are also line terminators to plenty of
     * tooling, so a capture containing one would split into two records somewhere between logcat and
     * the test loader. DEL is the boundary case just past the C0 range the escaper handles, and a
     * lone surrogate is what a truncated UTF-16 read produces.
     */
    @Test
    fun `line separators control characters and a lone surrogate all round trip on one line`() {
        val hostile = fullyPopulated.copy(
            className = "a\u2028b\u2029c",
            sourceViewId = "x\u007Fy\u0000z\uD800"
        )

        val line = AccessibilityEventCodec.encode(hostile)

        assertEquals("the payload must stay on ONE line", 1, line.lines().size)
        assertFalse(
            "a bare U+2028 would split the record for any reader that treats it as a terminator",
            line.contains('\u2028')
        )
        assertFalse(line.contains('\u2029'))
        assertEquals(hostile, AccessibilityEventCodec.decode(line))
    }

}
