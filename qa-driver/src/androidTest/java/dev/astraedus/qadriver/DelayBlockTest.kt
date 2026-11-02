package dev.astraedus.qadriver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.astraedus.nudge.ui.testtags.UiTags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * delay-block (ported from `scripts/device-qa.sh`): a DELAY rule really gates the app, and really
 * lets it through when the countdown ends.
 *
 * Starts from Home: a completed countdown GRANTS a passthrough that survives a force-stop and
 * relaunch, and only leaving to the launcher revokes it (#58). A case that expects a block without
 * going Home first asserts on its predecessor's leftovers.
 */
@RunWith(AndroidJUnit4::class)
class DelayBlockTest : NudgeCase() {

    @Test
    fun delayRuleGatesYouTubeThenLetsItThrough() {
        assertTrue("Home did not reach the launcher", QaDevice.goHome())
        QaDevice.shell("am force-stop $youtube")
        Logcat.clear()
        QaDevice.launch(youtube)

        // The decision, from BlockEngine's own line: proves WHICH RULE fired.
        val decided = Logcat.await(delayDecision, 20_000)
        Logcat.save("DelayBlockTest-logcat")
        assertTrue("no '$delayDecision' line: YouTube was not gated", decided)

        // And what the user actually sees. The tag is LeaveButton's, which every block screen
        // renders; the copy check pins it to the DELAY screen ("Go Back" on a hard block).
        val leave = device.wait(Until.findObject(By.res(UiTags.LEAVE)), 8_000)
        assertNotNull("the delay overlay never showed its leave button", leave)
        shot("10-delay-overlay")
        assertTrue("the overlay's leave button is not the delay screen's", device.hasObject(By.text("I changed my mind")))
        assertTrue("the delay overlay does not name the app", device.hasObject(By.textContains("YouTube")))

        // The countdown's end is the overlay going away and YouTube coming to the front. Not a
        // wall-clock sleep: DelayContent only ticks while it is on screen (#8).
        val through = QaDevice.awaitForeground(youtube, 30_000)
        shot("11-youtube-open")
        assertTrue("the countdown finished but YouTube never came to the front (got '${QaDevice.foregroundPackage()}')", through)
        Logcat.save("DelayBlockTest-logcat")
    }
}
