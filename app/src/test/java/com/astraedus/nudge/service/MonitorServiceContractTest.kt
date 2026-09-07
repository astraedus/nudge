package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard on what a *service* is allowed to do to the user's screen, and on the app's
 * backup posture.
 *
 * ## The report this came from
 * A QA session on 2026-09-07 reported Nudge "throwing MainActivity in front of Telegram" during the
 * window after the nightly backup killed the process, with no rule targeting Telegram and no block
 * events recorded — and attributed it to [NudgeMonitorService] relaunching the UI. The source says
 * otherwise: nothing in this app has ever called `startActivity` with `MainActivity`, and the
 * monitor service reads no rules and makes no decisions. That the explanation was *plausible* is
 * the point. A foreground service that could surface its own UI over an arbitrary app would be
 * indistinguishable, from outside, from exactly this report — so the property is worth pinning
 * before someone adds it for a good-sounding reason (a "service stopped, tap to restart" prompt is
 * the obvious one, and it belongs in a notification).
 *
 * None of this is JVM-testable behaviourally: `Service`, `NotificationManager` and the accessibility
 * bind are all platform. The defect class lives in the SHAPE of the code, so this pins the shape,
 * the way `HomeScreenPassthroughContractTest` and `OverlayBackAndInsetsContractTest` already do.
 */
class MonitorServiceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("main source set not found from working dir ${File("").absolutePath}")

    private fun read(relativePath: String): String {
        val file = File(sourceRoot(), relativePath)
        assertTrue("$relativePath must exist", file.exists())
        return file.readText()
    }

    private val monitorService = "java/com/astraedus/nudge/service/NudgeMonitorService.kt"
    private val accessibilityService =
        "java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"

    private fun serviceSources(): List<File> =
        File(sourceRoot(), "java/com/astraedus/nudge/service")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Strip `//` line comments and `/* */` blocks so prose about a pattern is not read as the pattern. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    // --- 1. No service may put Nudge's own main UI over another app -------------------------------

    /**
     * The rule, stated over the whole package rather than the one file that was accused: no class
     * in `service/` may pass a `MainActivity` intent to `startActivity`. A `PendingIntent` is
     * explicitly fine — that is a destination for a tap the *user* makes.
     */
    @Test
    fun `no service starts MainActivity`() {
        serviceSources().forEach { file ->
            val body = code(file.readText())
            if (!body.contains("MainActivity")) return@forEach

            // Every MainActivity intent in a service must be consumed by PendingIntent.getActivity.
            assertTrue(
                "${file.name} references MainActivity but never builds a PendingIntent from it — " +
                    "a service may only offer Nudge's UI as a notification tap target",
                body.contains("PendingIntent.getActivity(")
            )
            Regex("""startActivity\(([^)]*)\)""").findAll(body).forEach { match ->
                assertFalse(
                    "${file.name} calls startActivity(${match.groupValues[1].trim()}) with a " +
                        "MainActivity intent — a service must never surface Nudge's own UI over " +
                        "whatever the user is doing",
                    match.groupValues[1].contains("MainActivity", ignoreCase = true)
                )
            }
        }
    }

    /**
     * Sharper still for the monitor service: it holds a notification and polls health. It has no
     * business launching anything, so it contains no `startActivity` call of any kind.
     */
    @Test
    fun `the monitor service launches no activity at all`() {
        val body = code(read(monitorService))
        assertFalse(
            "NudgeMonitorService must not call startActivity — recovery prompts go through a " +
                "notification, never an Activity launch from a service",
            body.contains("startActivity(")
        )
        assertTrue(
            "the only MainActivity use here is a notification tap target",
            body.contains("PendingIntent.getActivity(")
        )
    }

    /**
     * And it makes no enforcement decisions. The QA read assumed it "independently polls foreground
     * app usage"; it must stay the case that it cannot, because a second, rule-less decision maker
     * is precisely how a blocker starts blocking apps nobody configured.
     */
    @Test
    fun `the monitor service reads no rules and evaluates nothing`() {
        val body = code(read(monitorService))
        listOf(
            "BlockRule",
            "ruleRepository",
            "RuleRepository",
            "EvaluateBlockUseCase",
            "BlockOverlayActivity",
            "usageStatsManager",
            "UsageStatsManager"
        ).forEach { forbidden ->
            assertFalse(
                "NudgeMonitorService must not reference $forbidden — all enforcement belongs to " +
                    "NudgeAccessibilityService, gated on a rule matching the foreground package",
                body.contains(forbidden)
            )
        }
    }

    // --- 2. The recovery prompt is a notification -------------------------------------------------

    /**
     * The degraded states must actually reach the user. An `IMPORTANCE_LOW` line on the permanent
     * channel is how "blocking is down" stayed invisible for hours, so a second, higher-importance
     * channel has to exist and the deep link has to go to accessibility settings.
     */
    @Test
    fun `a degraded state raises a separate accessibility-settings notification`() {
        val body = code(read(monitorService))
        assertTrue(
            "a second notification channel is needed: importance cannot be raised on an existing one",
            body.contains("IMPORTANCE_DEFAULT")
        )
        assertTrue(
            "the recovery prompt must deep-link to accessibility settings",
            body.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS")
        )
        assertTrue(
            "the alert must be gated on ServiceHealth.isDegraded, not on any single cause",
            body.contains("isDegraded")
        )
    }

    /**
     * Notification copy is a function of health, not a constant. The original defect was literally a
     * hardcoded "Nudge is active" that outlived the service being active.
     */
    @Test
    fun `notification text is derived from ServiceHealth`() {
        val body = code(read(monitorService))
        assertTrue(
            "copy must come from ServiceHealth.notificationCopy()",
            body.contains("notificationCopy()")
        )
        assertFalse(
            "no hardcoded status string may survive in the service — that was the bug",
            body.contains("\"Nudge is active\"") || body.contains("\"Monitoring app usage\"")
        )
    }

    // --- 3. The monitor service's lifecycle tracks the master toggle -------------------------------

    /**
     * `start()` used to have one caller (boot) and `stop()` none. A fresh install therefore ran no
     * foreground service until the next reboot — so nothing could have noticed the accessibility
     * service was down — and once running it never stopped, so it kept claiming to be active after
     * the user switched Nudge off.
     */
    @Test
    fun `every place the master toggle can change syncs the monitor service`() {
        mapOf(
            "java/com/astraedus/nudge/service/BootReceiver.kt" to "boot",
            "java/com/astraedus/nudge/MainActivity.kt" to "app launch",
            accessibilityService to "the master toggle"
        ).forEach { (path, occasion) ->
            assertTrue(
                "$path must call NudgeMonitorService.sync — the service's existence has to track " +
                    "the master toggle at $occasion",
                code(read(path)).contains("NudgeMonitorService.sync(")
            )
        }
    }

    /**
     * Health is polled, not observed: "the system unbound our service" fires no callback we can
     * receive in a process that was not running when it happened.
     */
    @Test
    fun `health is polled and the tick is individually guarded`() {
        val body = code(read(monitorService))
        assertTrue("a poll interval must exist", body.contains("HEALTH_POLL_INTERVAL_MS"))
        assertTrue(
            "the try must wrap the ITERATION, not the loop: one throwing tick must not end the " +
                "poll for the life of the process (tasks/lessons.md, 2026-09-01)",
            body.contains("while (isActive)") && body.indexOf("while (isActive)") <
                body.indexOf("catch (e: Exception)")
        )
    }

    // --- 4. Enforcement stays gated on a rule that still exists ------------------------------------

    /**
     * The auto-kick cooldown is the one path that blocks before reading a rule and writes no
     * `UsageEvent`. Both instances of it (app and web) go through [com.astraedus.nudge.domain.block.CooldownGate].
     */
    @Test
    fun `both cooldown paths are gated on a live rule entry`() {
        val body = code(read(accessibilityService))
        assertEquals(
            "both the app-level and the web cooldown must ask CooldownGate.shouldEnforce",
            2,
            Regex("""CooldownGate\.shouldEnforce\(""").findAll(body).count()
        )
        assertEquals(
            "both must also drop a cooldown left behind by a deleted rule",
            2,
            Regex("""CooldownGate\.isStale\(""").findAll(body).count()
        )
        assertFalse(
            "no cooldown branch may test isInCooldown on its own again",
            Regex("""if\s*\(\s*!?tracker\.isInCooldown\(""").containsMatchIn(body)
        )
    }

    // --- 5. Backup posture -------------------------------------------------------------------------

    /**
     * Two reasons, one attribute. Privacy: a no-INTERNET app advertising "all data local" must not
     * have Google silently holding a copy of every rule and every usage row. Reliability: running a
     * full backup force-kills the process, which is what produced the 01:59 window where Settings
     * said "Enabled, but your phone stopped it".
     */
    @Test
    fun `auto backup is off on every API level`() {
        val manifest = File(sourceRoot(), "AndroidManifest.xml").readText()
        assertTrue(
            "allowBackup must be false — a nightly full_backup kills an always-on blocker, and " +
                "uploads a local-only app's database to Google Drive",
            manifest.contains("""android:allowBackup="false"""")
        )
        assertTrue(
            "API 31+ needs dataExtractionRules: allowBackup alone no longer governs device transfer",
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules"""")
        )
        assertTrue(
            "API 26-30 needs fullBackupContent",
            manifest.contains("""android:fullBackupContent="@xml/backup_rules"""")
        )
    }

    /**
     * The rules files must exclude everything from BOTH transports. An exclusion list that names
     * only `database` would still ship DataStore preferences (the master toggle, Strict Mode state)
     * to a new phone.
     */
    @Test
    fun `both backup rule files exclude every domain`() {
        val domains = listOf("root", "database", "sharedpref", "file", "external")

        val extraction = File(sourceRoot(), "res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { transport ->
            val section = extraction.substringAfter("<$transport>").substringBefore("</$transport>")
            assertTrue("$transport section must exist", section.isNotBlank())
            domains.forEach { domain ->
                assertTrue(
                    "$transport must exclude domain=$domain",
                    section.contains("""domain="$domain"""")
                )
            }
        }

        val fullBackup = File(sourceRoot(), "res/xml/backup_rules.xml").readText()
        domains.forEach { domain ->
            assertTrue(
                "full-backup-content must exclude domain=$domain",
                fullBackup.contains("""domain="$domain"""")
            )
        }
    }
}
