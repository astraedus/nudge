package com.astraedus.nudge.service

import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.Countability
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #181's debug trace: the bench report's "a fixture can show the inputs but not the detector's
 * own set". A fired check-in now writes WHICH packages made the number, and every package the count
 * left out writes why, so the reporter's next debug log settles the question on its own.
 *
 * Gated like every other trace line: nothing at all unless debug logging is on.
 */
class AccessibilityEventTraceBounceTest {

    private val lines = mutableListOf<String>()

    private fun trace(debug: Boolean): AccessibilityEventTrace {
        val logger = mockk<NudgeLogger> { every { isDebugEnabled } returns debug }
        return AccessibilityEventTrace(logger = logger, emit = { lines += it })
    }

    private fun json(prefix: String): JSONObject {
        val line = lines.single()
        assertTrue(line, line.startsWith(prefix))
        return JSONObject(line.removePrefix(prefix))
    }

    @Test
    fun `a fired apps check-in lists the packages it counted`() {
        val packages = listOf("com.instagram.android", "com.reddit.frontpage", "com.discord", "com.x")
        trace(debug = true).bounce(BounceAlert(count = 4, minutes = 3, walls = 2, packages = packages))

        val o = json(AccessibilityEventTrace.BOUNCE_PREFIX)
        assertEquals("apps", o.getString("unit"))
        assertEquals(4, o.getInt("count"))
        assertEquals(3, o.getInt("minutes"))
        assertEquals(2, o.getInt("walls"))
        val apps = o.getJSONArray("apps")
        assertEquals(packages, (0 until apps.length()).map { apps.getString(it) })
    }

    @Test
    fun `a fired sites check-in names no package and no domain`() {
        trace(debug = true).bounce(BounceAlert(count = 3, minutes = 2, unit = BounceAlert.Unit.SITES, walls = 3))

        val o = json(AccessibilityEventTrace.BOUNCE_PREFIX)
        assertEquals("sites", o.getString("unit"))
        assertEquals(0, o.getJSONArray("apps").length())
        assertFalse("no domain-shaped text", Regex("\\w\\.(com|org|net)").containsMatchIn(lines.single()))
    }

    @Test
    fun `every left-out reason is written, and each one is distinct`() {
        val t = trace(debug = true)
        val left = Countability.entries.filter { it != Countability.COUNTS }
        left.forEach { t.bounceSkip("com.android.intentresolver", it) }

        val reasons = lines.map {
            assertTrue(it, it.startsWith(AccessibilityEventTrace.BOUNCE_SKIP_PREFIX))
            val o = JSONObject(it.removePrefix(AccessibilityEventTrace.BOUNCE_SKIP_PREFIX))
            assertEquals("com.android.intentresolver", o.getString("pkg"))
            o.getString("why")
        }
        assertEquals(left.size, reasons.toSet().size)
        assertTrue("not_launchable" in reasons && "home" in reasons)
    }

    @Test
    fun `nothing is written while debug logging is off`() {
        val t = trace(debug = false)
        t.bounce(BounceAlert(count = 4, minutes = 1, packages = listOf("com.a")))
        t.bounceSkip("com.android.intentresolver", Countability.NOT_AN_APP)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `the bounce lines are not part of a capture's event stream`() {
        // scripts/a11y-capture.sh keeps only EV lines; a BOUNCE line must never look like one.
        listOf(AccessibilityEventTrace.BOUNCE_PREFIX, AccessibilityEventTrace.BOUNCE_SKIP_PREFIX).forEach {
            assertFalse(it, it.startsWith(AccessibilityEventTrace.PREFIX))
        }
    }
}
