package dev.astraedus.qadriver

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * notif-idle (#23 / #63, ported from `scripts/device-qa.sh`): the ongoing status notification
 * posts when its claim changes and stays silent while nothing does.
 *
 * The change-driven half runs FIRST, while the process is certainly alive and the device awake:
 * `NudgeMonitorService` re-evaluates on EVENTS (an unbind signals from the service's `onDestroy`, a
 * rebind from `onServiceConnected`), with a five-minute poll as the only backstop. Coming out of
 * the idle window the monitor may legitimately be gone (#63's battery fix wants that), so a
 * post-idle expectation would measure the harness. Prove posting-on-change while it is observable,
 * then the silence on its own.
 *
 * Runs about seven minutes with the default `-e qa.idleSecs 300`, so the runner starts it detached
 * on the device (a dropped adb session must not end it) and polls for the result.
 */
@RunWith(AndroidJUnit4::class)
class NotifIdleTest : NudgeCase() {

    private val idleSecs = QaArgs.int("qa.idleSecs", 300)
    private val a11yComponent = "${QaDevice.NUDGE_PACKAGE}/com.astraedus.nudge.service.NudgeAccessibilityService"
    private var savedServices: String? = null
    private var revoked = false
    private var stayOnChanged = false

    private fun records() = DeviceText.notifRecords(
        QaDevice.shell("dumpsys notification --noredact"),
        QaDevice.NUDGE_PACKAGE
    )

    /** Waits for the ongoing notification's last post to move off [previous]; the new value, or null. */
    private fun awaitRepost(previous: Long, timeoutMs: Long): Long? {
        var now: Long? = null
        QaDevice.poll(timeoutMs, 3_000) {
            now = records().ongoingUpdateTimeMs
            now != null && now != previous
        }
        return now?.takeIf { it != previous }
    }

    /**
     * Revoking the grant, VERIFIED. `settings delete`, not `put … ''`: the shell call has no quoting,
     * so an empty-string argument cannot be passed, and a missing key reads as "no services" to the
     * accessibility manager. A fault-injection test must confirm the fault took effect before
     * asserting on the reaction, or it invents product bugs ("could not inject" and "the app ignored
     * it" must never share a message).
     */
    private fun revokeA11y() {
        savedServices = QaDevice.shell("settings get secure enabled_accessibility_services").trim()
        QaDevice.shell("settings delete secure enabled_accessibility_services")
        revoked = true
        assertTrue(
            "FAULT NOT INJECTED: Nudge is still bound after clearing enabled_accessibility_services; a harness problem, NOT an app finding",
            A11yState.awaitBound(false, 10_000)
        )
    }

    /** Puts the saved grant back (never the literal "null" `settings get` prints for an unset key). */
    private fun restoreA11y() {
        val services = savedServices?.takeIf { it.isNotBlank() && it != "null" } ?: a11yComponent
        QaDevice.shell("settings put secure enabled_accessibility_services $services")
        QaDevice.shell("settings put secure accessibility_enabled 1")
        revoked = false
    }

    @After
    fun restoreDevice() {
        if (revoked) restoreA11y()
        if (stayOnChanged) QaDevice.shell("svc power stayon true")
    }

    @Test
    fun postsOnChangeAndStaysQuietWhileIdle() {
        val baseline = records()
        val u0 = baseline.ongoingUpdateTimeMs
        assertNotNull("no ongoing Nudge notification (id=1) to watch: $baseline", u0)
        note("baseline $baseline")

        // Half 1a: revoking the grant makes "Nudge is active" false, so exactly one post is owed.
        revokeA11y()
        val onRevoke = awaitRepost(u0!!, 45_000)
        assertNotNull("the ongoing notification did NOT update after the grant was revoked: it claims Nudge is active while it is not (#23)", onRevoke)
        note("grant revoked -> notification updated $u0 -> $onRevoke")
        // ...and then stops. One post per change is the invariant; only checking the first half
        // would pass a build that re-posts on a timer the moment anything happens.
        Thread.sleep(12_000)
        val settled = records().ongoingUpdateTimeMs
        assertEquals("the notification kept re-posting after the state settled (#63)", onRevoke, settled)

        // Half 1b: restoring the grant owes one more post.
        restoreA11y()
        val onRestore = awaitRepost(settled!!, 45_000)
        assertNotNull("the ongoing notification did NOT update after the grant was restored", onRestore)
        note("grant restored -> notification updated $settled -> $onRestore")
        assertTrue("HARNESS: Nudge did not rebind after the grant was restored", A11yState.awaitBound(true, 20_000))
        Logcat.save("NotifIdleTest-change")

        // Half 2: nothing changes, so nothing may post. The #63 gate. `stayon true` (the runner's
        // setting, so the other cases can drive the UI) would beat KEYCODE_SLEEP while charging and
        // leave this half measuring an AWAKE phone.
        val beforeIdle = records()
        QaDevice.shell("svc power stayon false")
        stayOnChanged = true
        Thread.sleep(1_000)
        QaDevice.shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(3_000)
        val wake = DeviceText.wakefulness(QaDevice.shell("dumpsys power"))
        note("idling ${idleSecs}s from $beforeIdle, mWakefulness=$wake")
        assertNotEquals("HARNESS: the screen did not go off, so this half would measure an awake phone", "Awake", wake)
        // Wall clock, not Thread.sleep's monotonic clock, which does not advance while suspended.
        val idleUntil = System.currentTimeMillis() + idleSecs * 1_000L
        while (System.currentTimeMillis() < idleUntil) Thread.sleep(5_000)
        QaDevice.shell("svc power stayon true")
        stayOnChanged = false

        val afterIdle = records()
        note("after idle $afterIdle")
        assertEquals("the ongoing notification was re-posted while idle (#63 regressed)", beforeIdle.ongoingUpdateTimeMs, afterIdle.ongoingUpdateTimeMs)
        assertEquals("the number of Nudge notifications changed while idle", beforeIdle.count, afterIdle.count)
    }
}
