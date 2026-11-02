package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
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

    private fun read(relativePath: String): String = SourceScan.mainFile(relativePath).readText()

    private val monitorService = "java/com/astraedus/nudge/service/NudgeMonitorService.kt"
    private val accessibilityService =
        "java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"

    private fun serviceSources(): List<File> =
        SourceScan.kotlinSources("com/astraedus/nudge/service")

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
     * The degraded states must actually reach the user, on a channel that can be heard.
     *
     * This assertion moved in the 2026-09-07 merge and is worth explaining, because it looks like a
     * weakening and is not. It used to require this file to build the alert itself: an
     * `IMPORTANCE_DEFAULT` channel deep-linking to `Settings.ACTION_ACCESSIBILITY_SETTINGS`. Two
     * lanes had branched from the same commit and both built a "blocking is down" notification -
     * this one, and `ProtectionAlertNotifier` - and BOTH claimed notification id 2. Shipping both
     * would have meant two notifications for one condition, each overwriting the other's id, and
     * this service's `onDestroy` cancelling the watchdog's alert at the moment it came true.
     *
     * So there is one alert now and this file hands off to it. What is pinned is the property, not
     * the location: a degraded state reaches a HIGHER-importance channel than the permanent
     * `IMPORTANCE_LOW` one (a silent low-importance line is how "blocking is down" went unnoticed
     * for hours), the handoff is gated on `isDegraded` rather than on any single cause, and the
     * recovery prompt is still a notification rather than an Activity this service launches.
     *
     * The deep link deliberately goes to Nudge's own Settings screen instead of straight to the
     * system accessibility list: that screen carries the Play-mandated prominent-disclosure dialog,
     * and this app has been rejected on that gate once already (`docs/play-store.md`).
     */
    @Test
    fun `a degraded state reaches the user on a higher-importance channel`() {
        val body = code(read(monitorService))
        assertTrue(
            "the alert must be gated on ServiceHealth.isDegraded, not on any single cause",
            body.contains("isDegraded")
        )
        assertTrue(
            "the degraded branch must hand off to ProtectionCheck, the one decision path - two " +
                "notifications for one condition is what this merge removed",
            body.contains("ProtectionCheck.run(")
        )
        assertFalse(
            "this service must not post an alert of its own any more: a second poster means a " +
                "second notification id, and its onDestroy would cancel the watchdog's alert",
            body.contains("HEALTH_NOTIFICATION_ID") || body.contains("buildHealthAlert")
        )

        val notifier = code(read("java/com/astraedus/nudge/service/ProtectionAlertNotifier.kt"))
        assertTrue(
            "the alert channel's importance must beat the permanent IMPORTANCE_LOW one - " +
                "importance cannot be raised on an existing channel, so it needs its own",
            notifier.contains("IMPORTANCE_HIGH") || notifier.contains("IMPORTANCE_DEFAULT")
        )
        assertTrue(
            "the recovery prompt must be a notification the user taps, never an Activity a " +
                "service launches",
            notifier.contains("PendingIntent.getActivity(")
        )
        assertFalse(
            "the alert must not deep-link straight into the system accessibility list: the " +
                "re-grant flow has to pass Nudge's own Settings screen, which carries the " +
                "Play-mandated prominent disclosure (docs/play-store.md, rejected there once)",
            notifier.contains("ACTION_ACCESSIBILITY_SETTINGS")
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

    // --- 3b. The notification is posted on CHANGE, never on a clock (issue #63) --------------------

    /**
     * The defect: `NudgeMonitorService` called `notify()` on every health-poll tick, unconditionally.
     *
     * A user on a Pixel 10 Pro measured it with BetterBatteryStats — the
     * `NotificationManagerService:post:dev.astraedus.nudge` wakelock taken **267 times in 10h24m**,
     * ~5 minutes of CPU held overnight, Deep Doze repeatedly broken — and reported the tell that
     * names the cause: swipe the permanent notification away and it is back in seconds, with
     * identical text. `NotificationManagerService` takes that wakelock before it has looked at the
     * content, so there is no platform dedup to save us: a byte-identical re-post costs a real one.
     *
     * `StatusNotificationGateTest` owns the decision (with the counterfactual: 121 unchanged
     * evaluations cost 121 posts under the old rule and 1 under this one). What no JVM test can see
     * is whether this file still ASKS, so that is pinned here — the same split as
     * `ProtectionCheck`/`ProtectionWatchdog` one section up.
     */
    @Test
    fun `the ongoing notification is only posted when its words change`() {
        val body = code(read(monitorService))

        assertTrue(
            "NudgeMonitorService must consult StatusNotificationGate - the fix is the gate, not " +
                "a longer interval, because a longer interval still posts for nothing",
            body.contains("StatusNotificationGate()") && body.contains("notificationGate")
        )

        val notifyCalls = Regex("""\bmanager\.notify\(""").findAll(body).count()
        assertEquals(
            "there must be exactly one notify() call in this service, so there is exactly one " +
                "place the gate has to hold",
            1,
            notifyCalls
        )
        assertTrue(
            "the notify() must sit INSIDE a shouldPost() branch. An ungated notify is the whole " +
                "of issue #63, and it reads identically to a gated one at a glance.",
            body.indexOf("notificationGate.shouldPost(") in 0 until body.indexOf("manager.notify(")
        )
        assertTrue(
            "onPosted must be recorded AFTER the notify, never before: a notify that throws " +
                "(a revoked POST_NOTIFICATIONS grant on some OEM builds) must not leave the gate " +
                "believing something is on screen that never arrived",
            body.indexOf("manager.notify(") < body.lastIndexOf("notificationGate.onPosted(")
        )
    }

    /**
     * The second post path, and the one that was invisible. Every `startForegroundService` re-runs
     * `onStartCommand`, and `onStartCommand` MUST call `startForeground`, which is another post.
     * `NudgeAccessibilityService` calls `sync` from its `isGlobalEnabled` collector, and DataStore
     * re-emits the whole snapshot on a write to ANY key — including the write `ProtectionCheck`
     * makes on every watchdog cycle. So the ongoing notification was being re-posted on a schedule
     * nobody had designed, from a file that contains no clock.
     *
     * Two guards, because either alone leaves the path open: the service refuses a redundant start,
     * and the flow stops re-emitting an unchanged value.
     */
    @Test
    fun `a redundant start cannot re-post the notification`() {
        val body = code(read(monitorService))

        assertTrue(
            "start() must return early when the service is already running - its own KDoc has " +
                "always said 'if it is not already running', and until #63 nothing implemented it",
            Regex("""fun start\([^)]*\)[^{]*\{\s*if \(isRunning\) return""").containsMatchIn(body)
        )
        assertEquals(
            "startForeground must be called from exactly one place. It is a post, and a second " +
                "caller would be a second uncounted one.",
            1,
            Regex("""\bstartForeground\(""").findAll(body).count()
        )

        val preferences = code(
            SourceScan.kotlinFile("com/astraedus/nudge/data/preferences/NudgePreferences.kt").readText()
        )
        assertTrue(
            "isGlobalEnabled must be distinctUntilChanged. DataStore's data flow re-emits on " +
                "every write to every key, and NudgeAccessibilityService turns each emission into " +
                "NudgeMonitorService.sync -> start -> startForeground -> a notification post.",
            Regex("""isGlobalEnabled[\s\S]{0,300}?distinctUntilChanged\(\)""")
                .containsMatchIn(preferences)
        )
    }

    /**
     * Health is re-evaluated on the EVENTS that change it, with the interval as a backstop.
     *
     * The old comment argued "poll rather than observe: the system unbound our service fires no
     * callback we can receive in a process that was not running at the time". True, and an argument
     * for `ProtectionWatchdogWorker` — not for a 30-second timer inside a process that IS running,
     * where an unbind fires `AccessibilityConnectionSignal` from the accessibility service's own
     * `onDestroy`. The timer was doing work the signal already does, 1200 times a night.
     */
    @Test
    fun `health re-evaluation is driven by the signals, not only by the clock`() {
        val body = code(read(monitorService))

        assertTrue(
            "the service must re-evaluate on AccessibilityConnectionSignal - that signal IS the " +
                "unbind, and waiting out an interval for something that already fired is the " +
                "wakeup nobody needed",
            body.contains("AccessibilityConnectionSignal.generation")
        )
        assertTrue(
            "the wait must be bounded by HEALTH_POLL_INTERVAL_MS as a backstop, not replaced by " +
                "the events: a fault that announces nothing must still be found eventually",
            body.contains("withTimeoutOrNull(HEALTH_POLL_INTERVAL_MS)")
        )
        assertTrue(
            "the backstop interval must be minutes, not seconds. At 30s it was the single " +
                "largest source of the #63 wakelock storm, and with the gate in place a shorter " +
                "interval buys only wakeups.",
            NudgeMonitorService.HEALTH_POLL_INTERVAL_MS >= 60_000L
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
        val manifest = SourceScan.mainFile("AndroidManifest.xml").readText()
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

        val extraction = SourceScan.mainFile("res/xml/data_extraction_rules.xml").readText()
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

        val fullBackup = SourceScan.mainFile("res/xml/backup_rules.xml").readText()
        domains.forEach { domain ->
            assertTrue(
                "full-backup-content must exclude domain=$domain",
                fullBackup.contains("""domain="$domain"""")
            )
        }
    }
}
