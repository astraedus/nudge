package com.astraedus.nudge.domain.bounce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BounceDetector]'s `countability` contract (#181), with the answer as a plain map the test
 * changes: only COUNTS counts; NOT_AN_APP is neither an app nor a way out; HOME is not an app but
 * is going home; UNKNOWN is recorded and counts once the answer changes, through [BounceDetector.reevaluate].
 * Thresholds come from the detector's constants; time is a number we move.
 */
class BounceDetectorCountabilityTest {

    private var now = 1_000_000L
    private val answers = HashMap<String, Countability>()
    private val notCounted = mutableListOf<Pair<String, Countability>>()

    private val d = BounceDetector(
        countability = { answers[it] ?: Countability.COUNTS },
        onNotCounted = { pkg, why -> notCounted += pkg to why }
    )

    private val walled = "com.instagram.android"
    private fun app(i: Int) = "com.example.app$i"
    private val others = (1 until BounceDetector.MIN_APPS).map(::app)

    private fun step() { now += 10_000L }

    @Test
    fun `a NOT_AN_APP package neither counts nor is a way out of the walled app`() {
        val sheet = "com.android.intentresolver"
        answers[sheet] = Countability.NOT_AN_APP
        d.onWall(walled, now)
        step(); d.onAppOpened(sheet, now)
        step(); d.onWall(walled, now) // not a new wall: the sheet was not leaving
        others.forEach { step(); assertNull(d.onAppOpened(it, now)) }
        // MIN_APPS apps, one wall: silent.
        assertEquals(listOf(sheet to Countability.NOT_AN_APP), notCounted)
    }

    @Test
    fun `a HOME package is not an app but is going home`() {
        val launcher = "com.teslacoilsw.launcher"
        answers[launcher] = Countability.HOME
        d.onWall(walled, now)
        step(); assertNull(d.onAppOpened(launcher, now))
        step(); assertNull(d.onWall(walled, now)) // wall 2: they went home and came back
        others.dropLast(1).forEach { step(); assertNull(d.onAppOpened(it, now)) }
        step()
        val alert = d.onAppOpened(others.last(), now)
        assertNotNull(alert)
        assertEquals(BounceDetector.MIN_APPS, alert!!.count)
        assertEquals((listOf(walled) + others).toSet(), alert.packages.toSet())
    }

    @Test
    fun `an UNKNOWN package counts once its answer lands, through reevaluate`() {
        val late = others.last()
        answers[late] = Countability.UNKNOWN
        d.onWall(walled, now)
        others.forEach { step(); assertNull(d.onAppOpened(it, now)) }
        step(); d.onAppOpened(walled, now)
        step(); assertNull("one app not known yet: MIN_APPS - 1", d.onWall(walled, now))

        step(); assertNull("still unknown", d.reevaluate(now))
        answers[late] = Countability.COUNTS
        step()
        val alert = d.reevaluate(now)
        assertEquals(BounceDetector.MIN_APPS, alert?.count)
        assertNull("fired once: cooling down", d.reevaluate(now))
    }

    @Test
    fun `reevaluate does nothing while disarmed or after the window lapsed`() {
        assertNull(d.reevaluate(now))
        d.onWall(walled, now)
        now += BounceDetector.WINDOW_MS + 1
        assertNull(d.reevaluate(now))
        // ...and the lapse dropped the streak: one more app does not resurrect it.
        assertNull(d.onAppOpened(app(1), now))
    }

    @Test
    fun `a left-out package is reported once per streak, and again in the next one`() {
        val sheet = "com.google.android.gms"
        answers[sheet] = Countability.NOT_AN_APP
        d.onWall(walled, now)
        repeat(3) { step(); d.onAppOpened(sheet, now) }
        assertEquals(1, notCounted.size)

        now += BounceDetector.WINDOW_MS + 1
        d.onWall(walled, now) // a fresh streak
        step(); d.onAppOpened(sheet, now)
        assertEquals(2, notCounted.size)
    }
}
