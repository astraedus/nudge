package com.astraedus.nudge.ui.widget

import com.astraedus.nudge.data.db.BlockHistoryFixture
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.usage.WeeklyUsage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Today at a glance" shows the same three numbers as the dashboard it mirrors - including the
 * corrected one.
 *
 * The widget deliberately reads the SAME repository query the Blocked tile observes, so the two
 * can never compute one number two ways. That was already true while the shared query was wrong,
 * which is exactly why the widget inherited the double count: it agreed with a tile that read
 * `210 blocked / 29 walked away` for 181 confrontations. Agreement is only worth having on the
 * right query, so this pins which one it reads.
 */
class WidgetTodayBlockedCountTest {

    private val timeTracker = TimeTracker()
    private val dayStart = timeTracker.startOfToday()
    private val dayEnd = timeTracker.startOfDayDaysBefore(dayStart, -1)

    private fun deps(shown: Int, walkAways: Int): NudgeWidgetEntryPoint {
        val history = BlockHistoryFixture.day(dayStart, shown = shown, walkAways = walkAways, allowed = 6)
        val usage = mockk<UsageRepository>(relaxed = true)
        every { usage.getShownCountForDay(dayStart, dayEnd) } returns
            MutableStateFlow(BlockHistoryFixture.shownCount(history, dayStart, dayEnd))
        every { usage.getChangedMindCountForDay(dayStart, dayEnd) } returns
            MutableStateFlow(BlockHistoryFixture.changedMindCount(history, dayStart, dayEnd))

        val screenTime = mockk<ScreenTimeProvider>(relaxed = true)
        every { screenTime.hasPermission() } returns true
        every { screenTime.getWeeklyUsage(any()) } answers {
            WeeklyUsage(listOf(firstArg()), listOf(emptyMap()))
        }

        val entryPoint = mockk<NudgeWidgetEntryPoint>(relaxed = true)
        every { entryPoint.usageRepository() } returns usage
        every { entryPoint.screenTimeProvider() } returns screenTime
        every { entryPoint.timeTracker() } returns timeTracker
        return entryPoint
    }

    @Test
    fun `a walk-away is one walk-away on the widget, not two extra blocks`() = runTest {
        val snapshot = WidgetReads.today(deps(shown = 10, walkAways = 4))

        assertEquals(14, snapshot.blocked)
        assertEquals(4, snapshot.walkedAway)
    }

    @Test
    fun `a day of nothing but walk-aways does not double on the widget`() = runTest {
        val snapshot = WidgetReads.today(deps(shown = 0, walkAways = 7))

        assertEquals(7, snapshot.blocked)
        assertEquals(7, snapshot.walkedAway)
    }

}
