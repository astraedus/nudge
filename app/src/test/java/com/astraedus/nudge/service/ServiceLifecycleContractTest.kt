package com.astraedus.nudge.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard on the app's lifecycle wiring, in the same spirit as
 * `HomeScreenPassthroughContractTest` and `ScreenTimeSourceContractTest`: the defect was not in
 * any VALUE a unit test could inspect, it was in WHERE the code was — or rather, where it wasn't.
 *
 * The bug: `NudgeMonitorService.start()` had exactly ONE call site in the entire codebase, a
 * `BOOT_COMPLETED` broadcast, and the manifest declared no other action. So a fresh install ran
 * with no process-priority protection until the user's next reboot; a Play auto-update replaced
 * the process overnight and nothing brought it back; and nothing anywhere ever asked whether the
 * accessibility service was still enabled. Every failure was silent, and the app was strongest
 * right after a reboot and weakest right after every update.
 *
 * Value-level tests over [com.astraedus.nudge.domain.health.ProtectionWatchdog] pass just as
 * happily with every one of these call sites deleted. The wiring is what gets pinned here.
 */
class ServiceLifecycleContractTest {

    /**
     * The file's CODE, with comments stripped — these files deliberately document the defect they
     * were fixed for, naming `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` in prose, and scanning raw
     * text would let an explanation satisfy the test that protects it.
     */
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    /** XML carries no `//` comments; strip only the XML form. */
    private fun manifest(): String {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        val text = (candidates.firstOrNull { it.exists() } ?: error("manifest not found")).readText()
        return text.replace(Regex("""<!--[\s\S]*?-->"""), "")
    }

    private val bootReceiver = "main/java/com/astraedus/nudge/service/BootReceiver.kt"
    private val monitorService = "main/java/com/astraedus/nudge/service/NudgeMonitorService.kt"
    private val watchdogWorker = "main/java/com/astraedus/nudge/service/ProtectionWatchdogWorker.kt"
    private val protectionCheck = "main/java/com/astraedus/nudge/service/ProtectionCheck.kt"
    private val mainActivity = "main/java/com/astraedus/nudge/MainActivity.kt"
    private val application = "main/java/com/astraedus/nudge/NudgeApp.kt"

    /**
     * Every source file that could hold a `NudgeMonitorService.start` call. The point of the
     * assertion below is that there is MORE THAN ONE, so it has to look everywhere.
     */
    private fun allMainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    // --- The receiver ------------------------------------------------------------------------

    /**
     * An in-place update replaces the process, killing our foreground service. (Android rebinds
     * the accessibility service itself; it is OUR service that never came back.)
     * Play auto-updates run overnight, so without this action blocking silently switched off after
     * every release until the user next rebooted. We shipped six releases in twelve days once.
     */
    @Test
    fun `the manifest receiver listens for an app update, not only a reboot`() {
        val text = manifest()

        assertTrue(
            "AndroidManifest.xml must register BOOT_COMPLETED",
            text.contains("android.intent.action.BOOT_COMPLETED")
        )
        assertTrue(
            "AndroidManifest.xml must register MY_PACKAGE_REPLACED — a Play auto-update kills the " +
                "process and disables the a11y service, and nothing else brings monitoring back",
            text.contains("android.intent.action.MY_PACKAGE_REPLACED")
        )
    }

    /**
     * The guard shape matters as much as the manifest entry. `if (intent.action !=
     * ACTION_BOOT_COMPLETED) return` is an inequality against ONE action, so adding a second
     * action to the manifest would have compiled, shipped, and silently done nothing.
     */
    @Test
    fun `the receiver guard is a membership test, not an inequality against one action`() {
        val text = source(bootReceiver)

        assertFalse(
            "$bootReceiver must not early-return on `action != ACTION_BOOT_COMPLETED` — that " +
                "guard silently drops every action but one, however many the manifest declares",
            Regex("""action\s*!=\s*Intent\.ACTION_BOOT_COMPLETED""").containsMatchIn(text)
        )
        assertTrue(
            "$bootReceiver must handle ACTION_MY_PACKAGE_REPLACED",
            text.contains("ACTION_MY_PACKAGE_REPLACED")
        )
        assertTrue(
            "$bootReceiver must still handle ACTION_BOOT_COMPLETED",
            text.contains("ACTION_BOOT_COMPLETED")
        )
    }

    /** Both paths stay gated on the master toggle: never start monitoring the user turned off. */
    @Test
    fun `the receiver still refuses to start monitoring the user turned off`() {
        assertTrue(
            "$bootReceiver must gate the service start on isGlobalEnabled",
            source(bootReceiver).contains("isGlobalEnabled")
        )
    }

    // --- The start paths ----------------------------------------------------------------------

    /**
     * The headline finding: one call site, on one event the user may not trigger for weeks.
     */
    @Test
    fun `the foreground service is started from more than the boot receiver`() {
        // start() OR sync(): sync is the lifecycle API the UI and the receiver use now, and it is
        // start-or-stop decided in one place. ProtectionCheck still calls start() directly, since
        // a watchdog restarting a dead service is not a master-toggle change.
        val callers = allMainSources()
            .filter {
                val text = it.readText()
                text.contains("NudgeMonitorService.start(") ||
                    text.contains("NudgeMonitorService.sync(")
            }
            .map { it.name }
            .toSet()

        assertTrue(
            "NudgeMonitorService.start must have more than one call site — it had exactly one " +
                "(BootReceiver), so a fresh install ran with no foreground service until the " +
                "user's next reboot. Found: $callers",
            callers.size > 1
        )
        assertTrue(
            "MainActivity must sync monitoring on app launch / master-toggle-on / onboarding " +
                "completion. Found callers: $callers",
            callers.contains("MainActivity.kt")
        )
        assertTrue(
            "The watchdog must be able to restart a dead service. It carries the verdict out from " +
                "ProtectionCheck, the body the periodic worker and the debug trigger share. " +
                "Found callers: $callers",
            callers.contains("ProtectionCheck.kt")
        )
    }

    /**
     * The one observer in MainActivity is what makes all three start paths one path. It has to
     * read BOTH flags: monitoring-on (so we never start what the user turned off) and
     * onboarding-complete (so a first-run user is not shown a notification claiming Nudge is
     * monitoring before they have granted it anything to monitor with).
     */
    @Test
    fun `MainActivity keeps the service in sync with the master toggle and onboarding`() {
        val text = source(mainActivity)

        assertTrue(
            "$mainActivity must observe isGlobalEnabled",
            text.contains("isGlobalEnabled")
        )
        assertTrue(
            "$mainActivity must observe isOnboardingComplete",
            text.contains("isOnboardingComplete")
        )
        // The stop half moved into sync() in the 2026-09-07 merge, so "the service exists exactly
        // when monitoring is on" is decided in ONE place rather than at each of the four call
        // sites that can change the answer. The invariant is unchanged, and is now pinned in both
        // halves: the screen calls sync, and sync is what stops.
        assertTrue(
            "$mainActivity must drive the service through NudgeMonitorService.sync",
            text.contains("NudgeMonitorService.sync(")
        )
        assertTrue(
            "sync() must stop the service when monitoring goes off: a permanent " +
                "ongoing notification over disabled monitoring is a false claim",
            Regex("""fun sync\([\s\S]{0,400}?stop\(context\)""")
                .containsMatchIn(source(monitorService))
        )
    }

    /**
     * Starting a foreground service from the background throws on Android 12+ unless an exemption
     * applies, and the watchdog starts it from a WorkManager worker. An uncaught throw in the one
     * component whose job is noticing failure would be its own silent death.
     */
    @Test
    fun `the service start survives a platform refusal`() {
        val text = source(monitorService)

        assertTrue(
            "$monitorService.start must catch IllegalStateException " +
                "(ForegroundServiceStartNotAllowedException) — the watchdog calls it from the background",
            text.contains("catch (_: IllegalStateException)")
        )
        assertTrue(
            "$monitorService must expose liveness so the watchdog can tell a dead service from a live one",
            text.contains("var isRunning")
        )
        assertTrue(
            "$monitorService must clear its liveness flag on destroy, or a killed service reads as alive",
            Regex("""fun onDestroy\(\)""").containsMatchIn(text)
        )
    }

    // --- The watchdog -------------------------------------------------------------------------

    /**
     * Enqueued from `Application.onCreate`, the one callback that runs on EVERY process start —
     * a launch, a boot broadcast, an update, or WorkManager waking us. Scheduling it only from an
     * Activity would leave it unscheduled in exactly the sessions where the app is never opened,
     * which is when protection dies unnoticed.
     */
    @Test
    fun `the watchdog is armed on every process start`() {
        assertTrue(
            "$application must enqueue ProtectionWatchdogWorker in onCreate",
            source(application).contains("ProtectionWatchdogWorker.enqueue(")
        )
    }

    /**
     * The design error this test exists to stop coming back.
     *
     * The first version of this watchdog checked membership in
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and nothing else. Verified against AOSP
     * master: when an accessibility service's process is killed, `binderDied()` adds the component
     * to `mCrashedServices`, `updateServicesLocked()` `continue`s past it forever, and it is **left
     * in that setting**. So the watchdog would have sat there reading "enabled, all good" through
     * the entire failure it was written to catch. Liveness comes from the BOUND list
     * (`AccessibilityManager.getEnabledAccessibilityServiceList`), and the gap between the two is
     * the bug.
     */
    @Test
    fun `liveness is read from the bound-services list, not from the enabled setting`() {
        val status = source("main/java/com/astraedus/nudge/service/ProtectionStatus.kt")

        assertTrue(
            "ProtectionStatus must read liveness from getEnabledAccessibilityServiceList " +
                "(server-side mBoundServices). The settings string survives a process kill, so a " +
                "check built on it alone reports 'enabled' over a permanently dead service.",
            status.contains("getEnabledAccessibilityServiceList(")
        )
        assertTrue(
            "ProtectionStatus must expose intent and reality separately",
            status.contains("fun isAccessibilityServiceGranted(") &&
                status.contains("fun isAccessibilityServiceConnected(")
        )
        assertTrue(
            "ProtectionStatus must offer the combined answer every tick and gate should use",
            status.contains("fun isAccessibilityServiceWorking(")
        )
    }

    @Test
    fun `the watchdog reads both accessibility signals and holds no policy of its own`() {
        val text = source(protectionCheck)

        assertTrue(
            "$protectionCheck must read the enabled setting (user intent) through ProtectionStatus",
            text.contains("ProtectionStatus.isAccessibilityServiceGranted(")
        )
        assertTrue(
            "$protectionCheck must ALSO read boundness — granted is not connected, and only the " +
                "gap between them reveals a service the OS killed and will never rebind",
            text.contains("ProtectionStatus.isAccessibilityServiceConnected(")
        )
        assertTrue(
            "$protectionCheck must delegate the decision to the pure, tested ProtectionWatchdog",
            text.contains("ProtectionWatchdog.decide(")
        )
        assertFalse(
            "$protectionCheck must not branch on the master toggle itself — that rule lives in " +
                "ProtectionWatchdog, where 'never nag a user who opted out' is pinned by a test",
            Regex("""if\s*\(\s*!?\s*\w*[Gg]lobalEnabled""").containsMatchIn(text)
        )
    }

    /**
     * The check body lives outside the worker so the debug trigger can run the very same code, 
     * WorkManager cannot be made to run the worker on demand, so before the split the alert path
     * was unobservable (`WatchdogDebugTriggerContractTest` owns that half of the contract). The
     * worker keeps the SCHEDULE and nothing else; a check re-inlined here would silently become
     * the only version anyone tests.
     */
    @Test
    fun `the periodic worker holds the schedule, not the check`() {
        val text = source(watchdogWorker)

        assertTrue(
            "$watchdogWorker must run the shared ProtectionCheck",
            text.contains("ProtectionCheck.run(")
        )
        assertFalse(
            "$watchdogWorker must not gather signals itself, a second copy of the check is a " +
                "copy nobody can trigger, and the triggerable one would stop proving anything",
            text.contains("ProtectionStatus.isAccessibilityService")
        )
        assertFalse(
            "$watchdogWorker must not decide anything itself",
            text.contains("ProtectionWatchdog.decide(")
        )
    }

    /**
     * `KEEP`, not `REPLACE`: replacing the request on every app launch would push the next run 15
     * minutes out each time, so a user who opens Nudge often would be checked least.
     */
    @Test
    fun `re-arming the watchdog does not reset its schedule`() {
        assertTrue(
            "$watchdogWorker must enqueue with ExistingPeriodicWorkPolicy.KEEP",
            source(watchdogWorker).contains("ExistingPeriodicWorkPolicy.KEEP")
        )
    }
}
