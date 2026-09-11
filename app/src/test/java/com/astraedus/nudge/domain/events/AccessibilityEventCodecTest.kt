package com.astraedus.nudge.domain.events

import org.junit.Assert.assertEquals
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
}
