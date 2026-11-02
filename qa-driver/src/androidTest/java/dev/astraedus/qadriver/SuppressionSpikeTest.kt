package dev.astraedus.qadriver

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Phase 0 spike (research memo android-device-testing-2026, Q1): can a UiAutomator test drive
 * another app while Nudge keeps blocking?
 *
 * Run twice:
 *  - flag ON (default): Nudge must stay in `Bound services` while the test drives YouTube, the
 *    fixture's Delay 5s rule must fire, and 20 `dumpWindowHierarchy` calls must not tear the
 *    service down (pain (a) of the bash harness: each `uiautomator dump` destroyed it for ~1s).
 *  - `-e qa.suppress true`: the control. Same steps without the flag must show the suppression
 *    (`Bound services:{}`, no decision), proving the flag is what keeps Nudge alive and that the
 *    first run was not passing for some other reason.
 *
 * Needs the bench fixture: a YouTube rule with mode DELAY and 5 seconds, and debug logging on.
 */
@RunWith(AndroidJUnit4::class)
class SuppressionSpikeTest {

    private val youtube = "com.google.android.youtube"
    private val decision = "block package=$youtube reason=delay_rule delaySeconds=5"

    private fun note(msg: String) = Log.i("QaSpike", msg)

    @Test
    fun drivingYouTubeKeepsNudgeBoundAndBlocking() {
        val device = QaDevice.device
        note("flags dontSuppress=${QaDevice.dontSuppress}")

        val boundAtStart = A11yState.isNudgeBound(A11yState.read())
        note("bound at start=$boundAtStart line=${A11yState.boundServicesLine(A11yState.read())}")

        device.pressHome()
        device.wait(Until.hasObject(By.pkg(device.launcherPackageName).depth(0)), 5_000)
        QaDevice.shell("am force-stop $youtube")
        QaDevice.shell("logcat -c")
        Thread.sleep(1_000)

        val context = InstrumentationRegistry.getInstrumentation().context
        val launch = context.packageManager.getLaunchIntentForPackage(youtube)
            ?: error("$youtube is not installed")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        var decided = false
        var boundSamples = 0
        var unboundSamples = 0
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (A11yState.isNudgeBound(A11yState.read())) boundSamples++ else unboundSamples++
            if (QaDevice.shell("logcat -d").contains(decision)) { decided = true; break }
            Thread.sleep(500)
        }
        note("decided=$decided boundSamples=$boundSamples unboundSamples=$unboundSamples")

        val overlay = device.wait(Until.hasObject(By.text("I changed my mind")), 8_000) == true
        note("overlay visible via UiAutomator=$overlay")

        // 20 hierarchy dumps, the operation `uiautomator dump` used to pay a service teardown for.
        val before = QaDevice.shell("logcat -d")
        val destroyedBefore = Regex("reason=service_destroyed").findAll(before).count()
        val connectedBefore = Regex("accessibility service connected").findAll(before).count()
        repeat(20) { device.dumpWindowHierarchy(ByteArrayOutputStream()) }
        Thread.sleep(2_000)
        val after = QaDevice.shell("logcat -d")
        val destroyedAfter = Regex("reason=service_destroyed").findAll(after).count()
        val connectedAfter = Regex("accessibility service connected").findAll(after).count()
        val boundAfterDumps = A11yState.isNudgeBound(A11yState.read())
        note(
            "dumps: service_destroyed $destroyedBefore->$destroyedAfter, " +
                "connected $connectedBefore->$connectedAfter, bound after=$boundAfterDumps"
        )

        if (QaDevice.dontSuppress) {
            assertEquals("Nudge was unbound in $unboundSamples samples while the test drove YouTube", 0, unboundSamples)
            assertTrue("no '$decision' line: the Delay rule did not fire", decided)
            assertTrue("the delay overlay never showed 'I changed my mind'", overlay)
            assertEquals("a hierarchy dump tore the service down", destroyedBefore, destroyedAfter)
            assertEquals("the service reconnected during the dumps", connectedBefore, connectedAfter)
            assertTrue("Nudge not bound after 20 dumps", boundAfterDumps)
        } else {
            // The control: without the flag the suppression must be observable, or the ON run
            // proves nothing about the flag.
            assertTrue("control run: Nudge stayed bound with no flag, so the suppression premise is wrong", unboundSamples > 0)
            assertFalse("control run: the Delay rule fired while suppressed", decided)
        }
    }
}
