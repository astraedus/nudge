package dev.astraedus.qadriver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.astraedus.nudge.ui.testtags.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * walkaway-count (ported from `scripts/device-qa.sh`): three declined launches move BOTH counters
 * by exactly +3, and every decline LEAVES the app.
 *
 * "Blocked" counts confrontations SHOWN and "Walked away" the ones declined, so a decline moves
 * both once (HomeViewModel's KDoc: the raw `wasBlocked` count used to double-count a walk-away).
 * Asserted twice, at two layers: the receiver's `state` (the DAO counts the Stats screen reads) and
 * the dashboard's week summary (what the user reads). A drift between them is itself a finding.
 */
@RunWith(AndroidJUnit4::class)
class WalkawayCountTest : NudgeCase() {

    private val declines = 3

    @Test
    fun threeDeclinesMoveBothCountersByThree() {
        val stateBefore = QaControl.state()
        val dashBefore = readWeekSummary()
        note("before: state shown=${stateBefore.getInt("shownAllTime")} walked=${stateBefore.getInt("walkedAwayAllTime")} dashboard=$dashBefore")

        for (i in 1..declines) {
            assertTrue("decline $i: Home did not reach the launcher", QaDevice.goHome())
            QaDevice.launch(youtube)
            // Tap FIRST, screenshot after: the overlay lives only for the 5s countdown, so a
            // screencap on the critical path is what loses the race.
            val leave = device.wait(Until.findObject(By.res(UiTags.LEAVE)), 20_000)
            assertNotNull("decline $i: no delay overlay to decline", leave)
            leave!!.click()
            shot("3$i-walkaway-declined")
            // Declining must LEAVE the app, not just dismiss the overlay on top of it.
            assertTrue(
                "decline $i: the launcher is not in front (got '${QaDevice.foregroundPackage()}'): the decline did not leave the app",
                QaDevice.awaitForeground(device.launcherPackageName, 10_000)
            )
        }

        val stateAfter = QaControl.state()
        val dashAfter = readWeekSummary()
        shot("34-dashboard-after")
        note("after: state shown=${stateAfter.getInt("shownAllTime")} walked=${stateAfter.getInt("walkedAwayAllTime")} dashboard=$dashAfter")

        assertEquals("Blocked (all-time, DAO) moved by", declines, stateAfter.getInt("shownAllTime") - stateBefore.getInt("shownAllTime"))
        assertEquals("Walked away (all-time, DAO) moved by", declines, stateAfter.getInt("walkedAwayAllTime") - stateBefore.getInt("walkedAwayAllTime"))
        assertEquals("Blocked (dashboard week summary) moved by", declines, dashAfter.first - dashBefore.first)
        assertEquals("Walked away (dashboard week summary) moved by", declines, dashAfter.second - dashBefore.second)
    }

    /**
     * Opens the dashboard through the app's own widget deep link (`nudge.nav_route home`, in
     * WidgetDeepLink's allowlist) and reads the week summary by its tag. A resumed MainActivity can
     * consume the deep link without popping a sub-screen, so Back is pressed while a Back control
     * OF NUDGE'S is showing: every sub-screen's TopAppBar has one and the dashboard's has none.
     * Scoped to Nudge's package because the system navigation bar's button is also "Back": the
     * first bench run matched it, pressed Back out of Nudge, and swiped the launcher's app drawer
     * open looking for the card.
     */
    private fun readWeekSummary(): Pair<Int, Int> {
        QaDevice.shell("am start -n ${QaDevice.NUDGE_PACKAGE}/com.astraedus.nudge.MainActivity --es nudge.nav_route home")
        assertTrue("Nudge did not come to the front", QaDevice.awaitForeground(QaDevice.NUDGE_PACKAGE, 10_000))
        repeat(4) {
            if (!device.wait(Until.hasObject(By.desc("Back").pkg(QaDevice.NUDGE_PACKAGE)), 1_500)) return@repeat
            device.pressBack()
        }
        repeat(4) {
            assertTrue(
                "HARNESS: left Nudge while looking for the week card (got '${QaDevice.foregroundPackage()}')",
                QaDevice.awaitForeground(QaDevice.NUDGE_PACKAGE, 3_000)
            )
            // HomeCharts.isEmpty: with no screen time AND no nudges this week the card collapses to
            // a hint and draws no summary. Only true before the first decline, after `reset`.
            if (device.hasObject(By.textContains("will chart here"))) return 0 to 0
            device.wait(Until.findObject(By.res(UiTags.WEEK_CARD)), 3_000)?.let { card ->
                // The card is clickable, so Compose merges its children into it; depending on the
                // Compose version the summary is the card's own text or a child node's. Read both.
                val texts = listOfNotNull(card.text, card.contentDescription) +
                    card.findObjects(By.textContains("walked away")).mapNotNull { it.text }
                return texts.firstNotNullOfOrNull { DeviceText.weekSummary(it) }
                    ?: error("the week card reads $texts, none of it '<n> blocked · <m> walked away'")
            }
            // Below the fold on a short screen.
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 30)
        }
        error("no week card (${UiTags.WEEK_CARD}) on the dashboard")
    }
}
