package com.astraedus.nudge.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the two properties that make the QA trigger worth having, both of which are about WHERE the
 * code is rather than any value a unit test can inspect.
 *
 * 1. **It runs production's code.** The protection alert is user-facing safety machinery that had
 *    never once been observed firing: WorkManager will not run [ProtectionWatchdogWorker] early
 *    (`cmd jobscheduler run -f` finishes the job in ~12ms without `doWork()` executing), so the
 *    only way to see the alert was to be holding the phone across two consecutive natural cycles
 *    while the service happened to be dead. A trigger that re-derived the check would have made
 *    that worse, not better, it would go green while the shipping path stayed broken. Worker and
 *    trigger must both be one call to [ProtectionCheck].
 * 2. **It cannot ship.** The receiver is exported, because `am broadcast` runs as the shell uid.
 *    The guarantee that this does not become a release-build surface is that the class and its
 *    manifest entry live in the `debug` source set and are therefore absent from a release APK
 *    entirely, not a `BuildConfig.DEBUG` branch, which would still be present and still be
 *    reachable if the branch were ever edited away.
 *
 * These are source-level assertions on purpose: a JVM test cannot see a variant's merged manifest,
 * and a test that IMPORTED the debug-only receiver would not compile in the release unit-test
 * variant that `./gradlew test` also runs.
 */
class WatchdogDebugTriggerContractTest {

    private fun file(relativePath: String): File =
        listOf(File("src/$relativePath"), File("app/src/$relativePath"))
            .firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}")

    /** Code only. These files document the defect in prose, so raw text would self-satisfy. */
    private fun source(relativePath: String): String =
        file(relativePath).readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun xml(relativePath: String): String =
        file(relativePath).readText().replace(Regex("""<!--[\s\S]*?-->"""), "")

    private val receiver = "debug/java/com/astraedus/nudge/service/WatchdogDebugReceiver.kt"
    private val debugManifest = "debug/AndroidManifest.xml"
    private val mainManifest = "main/AndroidManifest.xml"
    private val worker = "main/java/com/astraedus/nudge/service/ProtectionWatchdogWorker.kt"
    private val check = "main/java/com/astraedus/nudge/service/ProtectionCheck.kt"

    // --- 1. The trigger runs the same code the schedule runs ---------------------------------

    @Test
    fun `the periodic worker and the debug trigger both run the one shared check`() {
        assertTrue(
            "$worker must delegate to ProtectionCheck.run, the trigger is only meaningful if the " +
                "scheduled path runs the same function",
            source(worker).contains("ProtectionCheck.run(")
        )
        assertTrue(
            "$receiver must invoke ProtectionCheck.run",
            source(receiver).contains("ProtectionCheck.run(")
        )
    }

    /**
     * The failure mode this forbids: a trigger that gathers its own signals and decides for itself
     * passes on a device while the scheduled path is dead, which is precisely the belief we were
     * trying to stop holding on faith.
     */
    @Test
    fun `the debug trigger holds no check logic of its own`() {
        val text = source(receiver)

        listOf(
            "ProtectionWatchdog.decide(" to "the decision belongs to the pure, tested policy object",
            "ProtectionStatus.isAccessibilityServiceGranted(" to "signal gathering belongs to ProtectionCheck",
            "ProtectionStatus.isAccessibilityServiceConnected(" to "signal gathering belongs to ProtectionCheck",
            "ProtectionAlertNotifier.notify(" to "posting the alert is part of the path under test",
            "NudgeMonitorService.start(" to "carrying out the verdict is part of the path under test"
        ).forEach { (fragment, why) ->
            assertFalse(
                "$receiver must not contain `$fragment`, $why. A trigger that runs different " +
                    "code than production is worse than no trigger.",
                text.contains(fragment)
            )
        }
    }

    /**
     * The extras stage persisted INPUTS a real earlier cycle would have written (the confirming
     * cycle's degraded flag, the 12-hour cooldown clock). Anything beyond `recordProtectionCheck`
     * would be the trigger inventing state the production path cannot produce.
     */
    @Test
    fun `the debug trigger stages state only through the same persistence the check uses`() {
        val text = source(receiver)

        assertTrue(
            "$receiver must stage QA state via NudgePreferences.recordProtectionCheck, the same " +
                "writer the real check uses",
            text.contains("recordProtectionCheck(")
        )
        // The trigger is useless to the next person if the invocation only exists in this file.
        val doc = listOf(
            File("../docs/architecture/service-lifecycle-and-watchdog.md"),
            File("docs/architecture/service-lifecycle-and-watchdog.md")
        ).firstOrNull { it.exists() } ?: error("watchdog architecture doc not found")
        assertTrue(
            "${doc.name} must document the ADB invocation, or the trigger is undiscoverable",
            doc.readText().contains("dev.astraedus.nudge.debug.RUN_WATCHDOG")
        )
    }

    // --- 2. It cannot reach a release build ---------------------------------------------------

    @Test
    fun `the trigger lives in the debug source set, not in main`() {
        assertTrue(
            "The receiver must be at src/$receiver so it is compiled into debug builds only",
            file(receiver).isFile
        )
        assertFalse(
            "A copy of WatchdogDebugReceiver must never appear in src/main, the debug source set " +
                "IS the guarantee that it cannot ship",
            File(file(receiver).path.replace("/debug/", "/main/")).exists()
        )
    }

    @Test
    fun `the receiver is declared only in the debug manifest`() {
        assertTrue(
            "src/$debugManifest must declare the receiver",
            xml(debugManifest).contains("WatchdogDebugReceiver")
        )
        assertTrue(
            "src/$debugManifest must declare the RUN_WATCHDOG action the ADB command sends",
            xml(debugManifest).contains("dev.astraedus.nudge.debug.RUN_WATCHDOG")
        )
        assertFalse(
            "src/$mainManifest must not mention WatchdogDebugReceiver, a main-manifest entry is " +
                "merged into the RELEASE variant, where the class does not even exist",
            xml(mainManifest).contains("WatchdogDebugReceiver")
        )
        assertFalse(
            "src/$mainManifest must not declare the debug trigger action",
            xml(mainManifest).contains("dev.astraedus.nudge.debug")
        )
    }

    @Test
    fun `no shipping code references the debug trigger`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val referrers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("WatchdogDebugReceiver") }
            .map { it.name }
            .toList()

        assertTrue(
            "Nothing in src/main may reference WatchdogDebugReceiver, the class is absent from " +
                "release builds, so a reference is a release-only compile failure. Found: $referrers",
            referrers.isEmpty()
        )
    }
}
