package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape guard on how `NudgeMonitorService.onStartCommand` handles a REFUSED `startForeground`.
 *
 * ## The crash this pins (Play vitals, 1.31.3 / vc82, 2026-11-26)
 * `ForegroundServiceStartNotAllowedException` thrown from `Service.startForeground` at
 * `NudgeMonitorService.kt:261`, uncaught, on an Android 14 Infinix NOTE 30 Pro: five crashes, each
 * taking the accessibility service (same process) down with it, and `START_STICKY` inviting the OS
 * to restart the service into the same refusal.
 *
 * `onStartCommand` needs a live `Service`, a `NotificationManager` and Hilt, none of which run on the
 * JVM, so the behaviour is tested on the pure guard ([ForegroundStartGuardTest]) and this test pins
 * that the service actually routes through it and does the right thing with a refusal.
 */
class MonitorStartForegroundContractTest {

    private val service = SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeMonitorService.kt")
    private val onStartCommand: String by lazy {
        SourceScan.bodyOf(SourceScan.code(service), "override fun onStartCommand(")
    }

    @Test
    fun `startForeground runs inside the guard`() {
        val guarded = Regex("""tryStartForeground\s*\{[^}]*\bstartForeground\(""")
        assertTrue(
            "onStartCommand must call startForeground inside tryStartForeground { }; a bare call " +
                "lets ForegroundServiceStartNotAllowedException crash the whole process (vc82)",
            guarded.containsMatchIn(onStartCommand)
        )
        assertEquals(
            "exactly one startForeground call, and it is the guarded one",
            1,
            Regex("""\bstartForeground\(""").findAll(onStartCommand).count()
        )
    }

    @Test
    fun `a refusal stops the service, unsticky, before it claims to be running`() {
        val denied = Regex("""if\s*\(\s*denied\s*!=\s*null\s*\)""").find(onStartCommand)
        assertTrue("onStartCommand must branch on the guard's refusal", denied != null)
        val branch = SourceScan.bodyOf(onStartCommand.substring(denied!!.range.first), "if")

        assertTrue(
            "a refused service must stop itself: left started-but-not-foreground it is a " +
                "background service the platform will kill anyway",
            Regex("""\bstopSelf\(\s*startId\s*\)""").containsMatchIn(branch)
        )
        assertTrue(
            "a refused service must return START_NOT_STICKY: START_STICKY asks the OS to restart " +
                "it into the same refusal, a crash loop on the phones that refuse",
            branch.contains("return START_NOT_STICKY")
        )
        assertFalse("the refusal branch must not mark the service running", branch.contains("isRunning = true"))
        assertFalse("the refusal branch must not start the health poll", branch.contains("startHealthPoll()"))

        val branchEnd = onStartCommand.indexOf(branch, denied.range.first) + branch.length
        listOf("isRunning = true", "startHealthPoll()", "notificationGate.onPosted(").forEach { step ->
            assertTrue(
                "$step must come only AFTER the refusal branch: a refused start is not running, " +
                    "has posted nothing, and must not poll",
                onStartCommand.indexOf(step) >= branchEnd
            )
        }
    }
}
