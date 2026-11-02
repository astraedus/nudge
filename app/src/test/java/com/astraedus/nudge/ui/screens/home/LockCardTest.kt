package com.astraedus.nudge.ui.screens.home

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.model.BlockRuleData
import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 (pure unit): the Home lock card's selection (spec 3.8). Which All apps rule, if any, the card
 * names at a given moment, what it says, and whether a clock is needed behind it. The 1-minute
 * ticker itself is a `flow {}` in `HomeViewModel`; everything it decides is decided here.
 */
class LockCardTest {

    /** Monday 2026-11-02, at [hour]:[minute]. ISO day 1. */
    private fun monday(hour: Int, minute: Int = 0): Calendar =
        GregorianCalendar(2026, Calendar.NOVEMBER, 2, hour, minute)

    private fun allApps(
        id: Long,
        enabled: Boolean = true,
        days: List<Int>? = null,
        start: Int? = null,
        end: Int? = null,
        exceptPackages: Set<String> = emptySet(),
        exceptGroupNames: List<String> = emptyList()
    ) = BlockRuleData(
        id = id,
        packageName = null,
        groupId = null,
        mode = BlockMode.HARD_BLOCK,
        delaySeconds = 0,
        dailyLimitMinutes = null,
        enabled = enabled,
        scheduleDays = days,
        scheduleStartMinute = start,
        scheduleEndMinute = end,
        allApps = true,
        exceptPackages = exceptPackages,
        exceptGroupNames = exceptGroupNames
    )

    private val labels = mapOf("com.spotify" to "Spotify", "com.maps" to "Maps")

    private fun card(rules: List<BlockRuleData>, now: Calendar) =
        lockCardFor(rules, now, labels, ScheduleEvaluator(FirstUnlockState.NONE))

    // -- Shown only while an enabled All apps rule is active ---------------------------------

    @Test
    fun `no rules - no card`() {
        assertNull(card(emptyList(), monday(10)))
    }

    @Test
    fun `an All apps rule that is off - no card`() {
        assertNull(card(listOf(allApps(1, enabled = false)), monday(10)))
    }

    @Test
    fun `an app rule or a group rule is not an All apps rule`() {
        val appRule = allApps(1).copy(allApps = false, packageName = "com.instagram")
        val groupRule = allApps(2).copy(allApps = false, groupId = 5)

        assertNull(card(listOf(appRule, groupRule), monday(10)))
    }

    @Test
    fun `a scheduled rule outside its window - no card`() {
        val weekdays = allApps(1, days = listOf(1, 2, 3, 4, 5), start = 9 * 60, end = 17 * 60)

        assertNull(card(listOf(weekdays), monday(8, 59)))
        assertNull(card(listOf(weekdays), monday(18)))
    }

    @Test
    fun `a rule for other days - no card`() {
        val weekends = allApps(1, days = listOf(6, 7))

        assertNull(card(listOf(weekends), monday(10)))
    }

    // -- What it says -----------------------------------------------------------------------

    @Test
    fun `a rule that is on all the time - the headline, and no until line`() {
        val shown = card(listOf(allApps(7)), monday(10))!!

        assertEquals(7L, shown.ruleId)
        assertEquals("All apps are blocked", shown.headline)
        assertNull(shown.untilMs)
        assertNull(shown.untilText { error("an all-the-time rule has no end to format") })
    }

    @Test
    fun `a scheduled rule - until its window end, in the clock text the caller formats`() {
        val rule = allApps(1, days = listOf(1, 2, 3, 4, 5), start = 9 * 60, end = 17 * 60)

        val shown = card(listOf(rule), monday(10, 30))!!

        assertEquals(monday(17).timeInMillis, shown.untilMs)
        assertEquals("Until 5:00 PM", shown.untilText { "5:00 PM" })
        assertEquals("Until 17:00", shown.untilText { "17:00" })
    }

    @Test
    fun `the title names the allowed groups then apps, and skips an app that is not installed`() {
        val rule = allApps(
            1,
            exceptPackages = setOf("com.spotify", "com.gone"),
            exceptGroupNames = listOf("Work")
        )

        assertEquals("All apps except Work, Spotify are blocked", card(listOf(rule), monday(10))!!.headline)
    }

    // -- Disappears at the window end -------------------------------------------------------

    @Test
    fun `the card is there until the minute the window ends, and gone from that minute`() {
        val rule = allApps(1, start = 9 * 60, end = 17 * 60)

        assertEquals(1L, card(listOf(rule), monday(16, 59))!!.ruleId)
        assertNull(card(listOf(rule), monday(17, 0)))
    }

    // -- Several active: the one that ends last ---------------------------------------------

    @Test
    fun `several active - all the time outlasts a scheduled rule`() {
        val scheduled = allApps(1, start = 9 * 60, end = 17 * 60)
        val always = allApps(2)

        assertEquals(2L, card(listOf(scheduled, always), monday(10))!!.ruleId)
        assertEquals(2L, card(listOf(always, scheduled), monday(10))!!.ruleId)
    }

    @Test
    fun `several scheduled - the later end wins`() {
        val early = allApps(1, start = 9 * 60, end = 12 * 60)
        val late = allApps(2, start = 9 * 60, end = 17 * 60)

        val shown = card(listOf(late, early), monday(10))!!

        assertEquals(2L, shown.ruleId)
        assertEquals(monday(17).timeInMillis, shown.untilMs)
    }

    @Test
    fun `a rule that is off or out of window does not compete`() {
        val off = allApps(1, enabled = false)
        val later = allApps(2, start = 18 * 60, end = 22 * 60)
        val live = allApps(3, start = 9 * 60, end = 12 * 60)

        assertEquals(3L, card(listOf(off, later, live), monday(10))!!.ruleId)
    }

    @Test
    fun `rules ending together - the oldest, so the card does not flip between them`() {
        val a = allApps(4, start = 9 * 60, end = 17 * 60)
        val b = allApps(9, start = 10 * 60, end = 17 * 60)

        assertEquals(4L, card(listOf(b, a), monday(11))!!.ruleId)
        assertEquals(4L, card(listOf(a, b), monday(11))!!.ruleId)
    }

    // -- The clock behind the card ------------------------------------------------------------

    @Test
    fun `no clock when no All apps rule is scheduled - nothing polls`() {
        assertFalse(lockCardNeedsClock(emptyList()))
        assertFalse(lockCardNeedsClock(listOf(allApps(1))))
        // Scheduled, but off, or not an All apps rule: still nothing to tick for.
        assertFalse(lockCardNeedsClock(listOf(allApps(1, enabled = false, start = 0, end = 60))))
        assertFalse(
            lockCardNeedsClock(listOf(allApps(1, start = 0, end = 60).copy(allApps = false, packageName = "a.b")))
        )
    }

    @Test
    fun `a clock when an enabled All apps rule has days or hours`() {
        assertTrue(lockCardNeedsClock(listOf(allApps(1, start = 9 * 60, end = 17 * 60))))
        assertTrue(lockCardNeedsClock(listOf(allApps(1, days = listOf(6, 7)))))
    }

    @Test
    fun `the ticker waits to the top of the next minute`() {
        assertEquals(60_000L, millisToNextMinute(0L))
        assertEquals(1L, millisToNextMinute(59_999L))
        assertEquals(30_000L, millisToNextMinute(120_000L + 30_000L))
        // Always positive: a delay of 0 would spin.
        assertTrue((0L..200_000L step 7_919L).all { millisToNextMinute(it) in 1..60_000 })
    }
}
