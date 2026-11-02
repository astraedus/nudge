package dev.astraedus.qadriver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every parse a qa-driver case asserts on against output shaped like the Pixel 3 bench's
 * (API 31). A wrong parse reads as an app bug on the bench, so each one also has the near-miss the
 * bash harness got wrong.
 */
class DeviceTextTest {

    private val pkg = "dev.astraedus.nudge"

    @Test
    fun `bound means the Bound services line, never the Enabled line under it`() {
        val suppressed = """
            User state[
                attributes:{id=0, touchExplorationEnabled=false}
                Bound services:{}
                Enabled services:{{dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService}}
        """.trimIndent()
        assertEquals("{}", DeviceText.boundServicesLine(suppressed))
        assertFalse(DeviceText.isNudgeBound(suppressed))

        val bound = suppressed.replace("Bound services:{}", "Bound services:{Service[label=Nudge - App Blocker, feedbackType[FEEDBACK_GENERIC]]}")
        assertTrue(DeviceText.isNudgeBound(bound))
        assertNull(DeviceText.boundServicesLine("no such line"))
    }

    @Test
    fun `resumed package comes from mResumedActivity`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{9a1 #42 type=standard A=10123:com.google.android.youtube U=0 visible=true}
                mResumedActivity: ActivityRecord{f00 u0 com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity t42}
            mLastPausedActivity: ActivityRecord{abc u0 dev.astraedus.nudge/com.astraedus.nudge.MainActivity t40}
        """.trimIndent()
        assertEquals("com.google.android.youtube", DeviceText.resumedPackage(dump))
        assertNull(DeviceText.resumedPackage("mLastPausedActivity: ActivityRecord{abc u0 x.y/.Z t1}"))
    }

    @Test
    fun `broadcast data keeps the JSON's own quotes`() {
        val out = """
            Broadcasting: Intent { act=dev.astraedus.nudge.qa.CMD flg=0x400000 cmp=dev.astraedus.nudge/com.astraedus.nudge.qa.QaControlReceiver (has extras) }
            Broadcast completed: result=0, data="{"rules":2,"cmd":"state","ok":true}"
        """.trimIndent()
        assertEquals("""{"rules":2,"cmd":"state","ok":true}""", DeviceText.broadcastData(out))
        assertNull(DeviceText.broadcastData("Broadcast completed: result=0"))
        assertNull(DeviceText.broadcastData("Broadcasting: Intent { }"))
    }

    @Test
    fun `the ongoing timestamp is id 1's even when another record is listed first`() {
        // The 2026-10-15 shape: a lingering id=4 reminder BEFORE the id=1 status notification.
        val dump = """
              NotificationRecord(0x0a: pkg=dev.astraedus.nudge user=UserHandle{0} id=4 tag=null importance=3 key=0|dev.astraedus.nudge|4|null|10123: Notification(channel=nudge_reminders))
                mUpdateTimeMs=1000
              NotificationRecord(0x0b: pkg=com.android.systemui user=UserHandle{0} id=1 tag=null importance=2)
                mUpdateTimeMs=5000
              NotificationRecord(0x0c: pkg=dev.astraedus.nudge user=UserHandle{0} id=1 tag=null importance=2 key=0|dev.astraedus.nudge|1|null|10123: Notification(channel=nudge_monitor))
                mUpdateTimeMs=2000
        """.trimIndent()
        assertEquals(DeviceText.NotifRecords(count = 2, ongoingUpdateTimeMs = 2000), DeviceText.notifRecords(dump, pkg))
    }

    @Test
    fun `an id 1 record with no timestamp never borrows its neighbour's`() {
        val dump = """
              NotificationRecord(0x0c: pkg=dev.astraedus.nudge user=UserHandle{0} id=1 tag=null)
              NotificationRecord(0x0a: pkg=dev.astraedus.nudge user=UserHandle{0} id=4 tag=null)
                mUpdateTimeMs=1000
        """.trimIndent()
        assertEquals(DeviceText.NotifRecords(count = 2, ongoingUpdateTimeMs = null), DeviceText.notifRecords(dump, pkg))
        // id=10 is not id=1.
        val ten = "  NotificationRecord(0x0d: pkg=dev.astraedus.nudge user=UserHandle{0} id=10 tag=null)\n    mUpdateTimeMs=7"
        assertNull(DeviceText.notifRecords(ten, pkg).ongoingUpdateTimeMs)
    }

    @Test
    fun `appop mode, unset op, wakefulness`() {
        assertEquals("ignore", DeviceText.appopMode("PICTURE_IN_PICTURE: ignore; time=+1m2s ago"))
        assertNull(DeviceText.appopMode("No operations."))
        assertEquals("Asleep", DeviceText.wakefulness("Power Manager State:\n  mWakefulness=Asleep\n  mWakefulnessChanging=false"))
        assertNull(DeviceText.wakefulness("nothing"))
    }

    @Test
    fun `count is non-overlapping occurrences`() {
        val log = "I BlockEngine: block package=a reason=x\nI BlockEngine: block package=a reason=x\n"
        assertEquals(2, DeviceText.count(log, "block package=a reason=x"))
        assertEquals(0, DeviceText.count(log, "reason=y"))
        assertEquals(0, DeviceText.count(log, ""))
    }

    @Test
    fun `week summary parses the dashboard line`() {
        assertEquals(3 to 2, DeviceText.weekSummary("3 blocked · 2 walked away"))
        // The card is clickable, so its children can arrive merged into one string.
        assertEquals(12 to 0, DeviceText.weekSummary("Last 7 days, 4h 10m, Nudges, 12 blocked · 0 walked away"))
        assertNull(DeviceText.weekSummary("3 blocked, 2 walked away"))
    }
}
