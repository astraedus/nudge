package com.astraedus.nudge.qa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.astraedus.nudge.data.db.NudgeDatabase
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.usecase.ImportRulesUseCase
import com.astraedus.nudge.service.BlockLaunchGuard
import com.astraedus.nudge.service.NudgeAccessibilityService
import com.astraedus.nudge.service.PassthroughManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device-QA control channel. **`qa` build type only**: this file is in `src/qa` and its manifest
 * entry in `src/qa/AndroidManifest.xml`, so neither exists in a debug or release APK. Two gates keep
 * it that way: `verify<Variant>NoQaControl` fails any non-qa assemble/bundle whose merged manifest
 * names it, and `QaControlManifestTest` reads the release merged manifest on every `./gradlew test`.
 *
 * ## Why it exists
 *
 * The bash harness reached every precondition through the UI or through `pm clear`. `pm clear`
 * kills the process, which drops the accessibility binding, wipes the debug-logging switch and
 * raises the keyguard; the UI route meant a Maestro onboarding walk, and Maestro suppresses this app
 * (docs/TESTING.md, L6). The `:qa-driver` tests instead put the app in a known state through here,
 * WITHOUT restarting it, and read its state as one JSON line instead of scraping a hierarchy dump.
 *
 * ## Who may call it
 *
 * `android:permission="android.permission.DUMP"` on the receiver: only a sender holding DUMP gets
 * through, i.e. the shell uid (`adb shell am broadcast`, and the `:qa-driver` test's
 * `UiAutomation.executeShellCommand`, which runs as shell). DUMP is signature|privileged|development,
 * so no installed third-party app can hold it. A qa APK is debug-signed bench software, never
 * distributed, and this guard still means a stray app on the bench cannot wipe its rules.
 *
 * ## Commands (`--es cmd <name>`)
 *
 * - `reset`: the bench baseline. No rules, no groups, no history; Strict Mode and Nuke off; the
 *   master switch on; onboarding marked complete; debug logging ON (the logcat oracle needs it on a
 *   minified build, see `NudgeLogger`); every in-memory passthrough and launch-guard claim dropped.
 * - `seed-rules --es b64 <base64 of an export file>`: the file goes through the SAME parse and
 *   import as Settings -> Import ([ImportRulesUseCase.preview] then [ImportRulesUseCase.execute]),
 *   so a fixture can never be read differently from a user's backup. Base64, not a path or a raw
 *   string: `UiAutomation.executeShellCommand` splits on whitespace with no quoting, and a path
 *   under /data/local/tmp is an SELinux question this channel has no reason to ask.
 * - `state`: one JSON object. See [state] for the fields.
 *
 * Every command answers in the broadcast's result data (`am broadcast` prints it as `data="…"`) and
 * logs the same JSON under the `NudgeQa` tag. Logged with `android.util.Log`, not `NudgeLogger`: the
 * answer must not depend on the very switch `reset` turns on.
 *
 * Nothing here decides anything. Each command is a call into an existing seam, so a passing QA run
 * exercises the shipping code, not a qa re-implementation of it.
 */
class QaControlReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface QaControlEntryPoint {
        fun database(): NudgeDatabase
        fun nudgePreferences(): NudgePreferences
        fun blockRuleRepository(): BlockRuleRepository
        fun usageRepository(): UsageRepository
        fun importRulesUseCase(): ImportRulesUseCase
        fun blockLaunchGuard(): BlockLaunchGuard
        fun passthroughManager(): PassthroughManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        // An explicit `-n` broadcast reaches a receiver whatever its action, so the intent-filter
        // alone does not pin the action. Anything else is dropped before a dependency is touched.
        if (intent.action != ACTION_CMD) {
            Log.w(TAG, "ignored: action is not $ACTION_CMD")
            return
        }
        // Allowlist, never interpolated: an unknown or oversized value is reported by length only.
        val raw = intent.getStringExtra(EXTRA_CMD)
        val command = raw?.takeIf { it in COMMANDS }
        val pending = goAsync()
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            QaControlEntryPoint::class.java
        )
        // Room and DataStore suspend, so nothing runs in onReceive's main-thread window. goAsync()
        // keeps the broadcast (and its result data) alive until finish().
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val answer = try {
                when (command) {
                    CMD_RESET -> reset(deps)
                    CMD_SEED_RULES -> seedRules(deps, intent.getStringExtra(EXTRA_B64))
                    CMD_STATE -> state(deps)
                    else -> error("unknown cmd (length ${raw?.length ?: 0}; expected one of $COMMANDS)")
                }.put("cmd", command).put("ok", true)
            } catch (t: Throwable) {
                // An exception is a finding about the shipping code these commands call, so it is
                // reported, never swallowed into a silent no-op.
                JSONObject().put("cmd", command ?: JSONObject.NULL).put("ok", false)
                    .put("error", "${t.javaClass.simpleName}: ${t.message}")
            }
            val line = answer.toString()
            Log.i(TAG, line)
            try {
                pending.setResultData(line)
            } catch (_: RuntimeException) {
                // Not an ordered broadcast; the log line is the answer.
            }
            pending.finish()
        }
    }

    private suspend fun reset(deps: QaControlEntryPoint): JSONObject {
        // Order: in-memory claims first, so nothing the service holds can act on a rule that is
        // about to disappear; then the stores.
        deps.passthroughManager().clear()
        deps.blockLaunchGuard().reset()
        deps.database().clearAllTables()
        val prefs = deps.nudgePreferences()
        prefs.setStrictModeEnabled(false)
        prefs.setNukeActive(false)
        prefs.setGlobalEnabled(true)
        prefs.setOnboardingComplete(true)
        prefs.setDebugLoggingEnabled(true)
        return JSONObject().put("rules", deps.blockRuleRepository().getAllRules().first().size)
    }

    private suspend fun seedRules(deps: QaControlEntryPoint, b64: String?): JSONObject {
        require(!b64.isNullOrBlank()) { "seed-rules needs --es $EXTRA_B64 <base64 export file>" }
        // Bounded BEFORE decoding: a fixture is a few KB, and nothing here should allocate on a
        // caller's say-so.
        require(b64.length <= MAX_B64_CHARS) { "$EXTRA_B64 is ${b64.length} chars, max $MAX_B64_CHARS" }
        val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        val import = deps.importRulesUseCase()
        val preview = import.preview(json)
        preview.result.error?.let { error("the fixture did not parse: $it") }
        val outcome = import.execute(preview.result)
        outcome.error?.let { error("import failed: $it") }
        return JSONObject()
            .put("imported", outcome.importedCount)
            .put("duplicates", outcome.duplicateCount)
            .put("invalid", outcome.invalidCount)
            .put("rules", deps.blockRuleRepository().getAllRules().first().size)
    }

    /**
     * - `a11yConnected`: the service's own `instance` is set, i.e. it is bound AND connected. The
     *   driver checks `dumpsys accessibility` too; this is the app's side of the same fact.
     * - `foreground` / `overlayId` / `guard`: [BlockLaunchGuard], the one record of what Nudge
     *   believes is in front and which overlay it has in flight (`overlayId` -1 = none).
     * - `globalEnabled`, `debugLogging`, `rules`: the preconditions every block case needs.
     * - `shownAllTime` / `walkedAwayAllTime`: the same DAO counts the Stats screen reads.
     * - `recent`: the last [RECENT_EVENTS] usage events of the past hour, newest last: what Nudge
     *   RECORDED, which is the half of a decision the logcat line cannot show.
     */
    private suspend fun state(deps: QaControlEntryPoint): JSONObject {
        val prefs = deps.nudgePreferences()
        val guard = deps.blockLaunchGuard()
        val usage = deps.usageRepository()
        val since = System.currentTimeMillis() - RECENT_WINDOW_MS
        val recent = JSONArray()
        usage.getEventsSince(since).first()
            .sortedBy { it.timestamp }
            .takeLast(RECENT_EVENTS)
            .forEach { event ->
                recent.put(
                    JSONObject()
                        .put("pkg", event.packageName)
                        .put("t", event.timestamp)
                        .put("blocked", event.wasBlocked)
                        .put("mode", event.blockMode ?: JSONObject.NULL)
                        .put("changedMind", event.userChangedMind)
                )
            }
        return JSONObject()
            .put("a11yConnected", NudgeAccessibilityService.isConnected())
            .put("foreground", guard.foregroundPackage ?: JSONObject.NULL)
            .put("overlayId", guard.currentOverlayId())
            .put("guard", guard.stateDescription())
            .put("globalEnabled", prefs.isGlobalEnabled.first())
            .put("debugLogging", prefs.isDebugLoggingEnabled.first())
            .put("rules", deps.blockRuleRepository().getAllRules().first().size)
            .put("shownAllTime", usage.getAllTimeShownCount().first())
            .put("walkedAwayAllTime", usage.getAllTimeChangedMindCount().first())
            .put("recent", recent)
    }

    private companion object {
        const val TAG = "NudgeQa"
        const val ACTION_CMD = "dev.astraedus.nudge.qa.CMD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_B64 = "b64"
        const val CMD_RESET = "reset"
        const val CMD_SEED_RULES = "seed-rules"
        const val CMD_STATE = "state"
        val COMMANDS = setOf(CMD_RESET, CMD_SEED_RULES, CMD_STATE)

        /** 32 KiB of base64 (~24 KiB of export JSON); the bench fixture is ~1.5 KB. */
        const val MAX_B64_CHARS = 32 * 1024
        const val RECENT_EVENTS = 10
        const val RECENT_WINDOW_MS = 60 * 60 * 1000L
    }
}
