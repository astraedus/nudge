package com.astraedus.nudge.ui.overlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.emergency.EmergencyPass
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.usecase.RecordWalkAwayUseCase
import com.astraedus.nudge.service.EmergencyPassManager
import com.astraedus.nudge.service.NudgeAccessibilityService
import com.astraedus.nudge.service.PassthroughManager
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {

    @Inject lateinit var passthroughManager: PassthroughManager
    @Inject lateinit var nudgePreferences: NudgePreferences
    @Inject lateinit var emergencyPassManager: EmergencyPassManager
    @Inject lateinit var recordWalkAway: RecordWalkAwayUseCase
    @Inject lateinit var nudgeLogger: NudgeLog

    /**
     * Set the first time this activity terminates as a walk-away, so [navigateHome] logs exactly
     * one event per attempt no matter how many times it is reached (button + back button, or a
     * double tap). Not reset by [render]: a re-delivered block is a NEW attempt, but it also
     * arrives on an activity that has not walked away yet — once we have, we are finishing.
     */
    private val walkedAway = AtomicBoolean(false)

    /**
     * Incremented on every [render] so each delivered block composes under a fresh [key], discarding
     * the previous block's remembered countdown state. A counter rather than the block's contents:
     * two different apps can legitimately share a package-mode-delay triple, and re-delivering the
     * *same* block is still a new attempt that must start from full.
     */
    private var renderToken = 0

    companion object {
        const val EXTRA_BLOCK_MODE = "block_mode"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_FEATURE_KEY = "feature_key"
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_DAILY_TIME_REMAINING_MS = "daily_time_remaining_ms"
        const val EXTRA_DAILY_LIMIT_MINUTES = "daily_limit_minutes"

        /**
         * The package the passthrough grant and the emergency pass belong to, when that is NOT the
         * package being displayed. Only web blocks set it: a website is blocked under its app's
         * package (so the overlay says "Instagram" and the stats land on Instagram) while the app
         * the user is actually sitting in is the BROWSER.
         *
         * Without this, completing an instagram.com delay in Chrome granted a free pass to the
         * Instagram *app* -- and granted nothing at all to the browser the user was in.
         */
        const val EXTRA_PASSTHROUGH_PACKAGE = "passthrough_package"

        /**
         * The normalised web domain this block is for, or absent for an app block. Carried so the
         * grant is earned on COMPLETION like every other grant in the app; the service used to mark
         * the domain passed the moment it launched this overlay, so walking away still let the site
         * through.
         */
        const val EXTRA_WEB_DOMAIN = "web_domain"
    }

    /**
     * The package a grant applies to: the browser for a web block, the blocked app otherwise.
     * [EXTRA_PACKAGE_NAME] stays what is DISPLAYED and what stats are attributed to.
     */
    private fun passthroughPackage(intent: Intent): String =
        intent.getStringExtra(EXTRA_PASSTHROUGH_PACKAGE)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    /**
     * This activity is [android.R.attr.launchMode] singleInstance, so a re-block for a new app or
     * mode (e.g. the user tabbed out of a blocked app and back in, and the service re-fired) is
     * delivered here via [onNewIntent] — NOT onCreate, which never runs a second time. Adopt the
     * new intent so [onTimerComplete] / [navigateHome] read the right package, re-assert the
     * overlay flag, and rebuild the content for the new block instead of showing the stale one.
     */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        render(newIntent)
    }

    private fun render(intent: Intent) {
        val modeName = intent.getStringExtra(EXTRA_BLOCK_MODE) ?: BlockMode.HARD_BLOCK.name
        val mode = try {
            BlockMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            BlockMode.HARD_BLOCK
        }
        // BlockMode.NONE blocks nothing, so BlockEngine never produces a Block carrying it and this
        // activity should never be launched for one. If it somehow is, dismiss rather than render:
        // there is no "no-op overlay", and showing any of the three below would gate an app the
        // user explicitly chose not to gate.
        if (mode == BlockMode.NONE) {
            NudgeAccessibilityService.markOverlayInactive()
            finish()
            return
        }

        val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 15)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""

        // Assert the overlay flag (and WHICH app it is blocking) synchronously here rather than in
        // onCreate/onNewIntent: those run before the intent is parsed, and the service's issue-#19
        // picture-in-picture check needs the package, not just "something is up". render() is called
        // synchronously from both, so there is no window in which the flag is unset. Paths that
        // launch us without pre-marking (the daily-limit HARD_BLOCK in TimeRemainingHandler) are
        // covered here too.
        NudgeAccessibilityService.markOverlayActive(packageName)
        val ruleName = intent.getStringExtra(EXTRA_RULE_NAME)
        val dailyTimeRemainingMs = intent.getLongExtra(EXTRA_DAILY_TIME_REMAINING_MS, -1L)
            .let { if (it < 0) null else it }
        val dailyLimitMinutes = intent.getIntExtra(EXTRA_DAILY_LIMIT_MINUTES, -1)
            .let { if (it < 0) null else it }

        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { null }

        // Read the user's custom overlay messages BEFORE setContent so the first
        // composition already has the resolved pool. The overlay is shown instantly
        // on top of the blocked app, so a flash from default->custom message would be a
        // visible bug. DataStore reads of a tiny single-key prefs file are fast, so a
        // brief runBlocking on first-key emission here is acceptable and avoids that flash.
        val titlePool: List<String>
        val subtitlePool: List<String>
        val hardBlockPool: List<String>
        // Emergency "2-minute daily pass" UI state, computed once alongside the message pools so the
        // button/hint is correct on first composition. The lockout is GLOBAL (one pass per 24h across
        // all apps); the free window it grants is scoped to this app. Strict Mode is NOT consulted —
        // the pass is governed by its own Settings toggle alone (v1.10.0); see
        // [resolveEmergencyPassState], which owns the whole decision.
        var passState = EmergencyPassUiState()
        runBlocking {
            titlePool = NudgeMessages.resolvePool(
                nudgePreferences.customDelayTitles.first(), NudgeMessages.delayTitles
            )
            subtitlePool = NudgeMessages.resolvePool(
                nudgePreferences.customDelaySubtitles.first(), NudgeMessages.delaySubtitles
            )
            hardBlockPool = NudgeMessages.resolvePool(
                nudgePreferences.customHardBlockMessages.first(), NudgeMessages.hardBlockMessages
            )

            passState = resolveEmergencyPassState(
                packageName = packageName,
                passEnabled = nudgePreferences.emergencyPassEnabled.first(),
                usage = EmergencyPass.parse(nudgePreferences.emergencyPassUsage.first()),
                now = System.currentTimeMillis()
            )
        }

        // Grant the pass and return to the blocked app. finish() brings it back to the foreground;
        // the service's isPassActive check then lets it through. NOT navigateHome and NOT a
        // "changed my mind" event — this is a deliberate escape, not a walk-away.
        // Scoped to the package the user is actually IN, which for a web block is the browser --
        // the service checks `isPassActive` against the foreground package, so a window opened on
        // the rule's app package would never be seen and the escape would silently re-block.
        val onUsePass = {
            emergencyPassManager.usePass(passthroughPackage(intent))
            finish()
        }

        // Issue #15: a block delivered while the overlay is already up arrives via onNewIntent, and
        // setContent on an existing ComposeView REUSES the composition — same call positions, so
        // every `remember` slot survives. The new block's appLabel and delaySeconds are parameters
        // and update, but the countdown state does not, so the overlay showed the NEW app's name
        // over the PREVIOUS app's remaining seconds. The progress ring froze too, because
        // `remainingSeconds / delaySeconds` went off-scale (30 remaining over a 15s delay = 2.0).
        //
        // Keying the subtree on a per-delivery token discards all remembered state for a new block.
        // Done once here rather than by keying individual `remember` calls: this covers all three
        // overlays at once, and a per-`remember` key would have to be derived from the block's
        // identity anyway — keying on delaySeconds alone is NOT enough, since two apps sharing the
        // default 15s delay would still hand each other stale state.
        val blockToken = ++renderToken

        setContent {
            NudgeTheme {
                key(blockToken) {
                when (mode) {
                    // Unreachable: the early return above already finished us. Present so the
                    // `when` stays exhaustive and a future mode cannot silently fall through.
                    BlockMode.NONE -> Unit

                    BlockMode.HARD_BLOCK -> {
                        HardBlockContent(
                            packageName = packageName,
                            appLabel = appLabel,
                            dailyTimeRemainingMs = dailyTimeRemainingMs,
                            dailyLimitMinutes = dailyLimitMinutes,
                            onGoBack = { navigateHome() },
                            ruleName = ruleName,
                            messagePool = hardBlockPool,
                            canUseEmergencyPass = passState.canUse,
                            emergencyLocked = passState.locked,
                            nextPassMs = passState.nextPassMs,
                            onUseEmergencyPass = onUsePass
                        )
                    }

                    BlockMode.DELAY -> {
                        DelayContent(
                            delaySeconds = delaySeconds,
                            appLabel = appLabel,
                            dailyTimeRemainingMs = dailyTimeRemainingMs,
                            dailyLimitMinutes = dailyLimitMinutes,
                            onComplete = { onTimerComplete() },
                            onCancel = { navigateHome() },
                            ruleName = ruleName,
                            titlePool = titlePool,
                            subtitlePool = subtitlePool,
                            canUseEmergencyPass = passState.canUse,
                            emergencyLocked = passState.locked,
                            nextPassMs = passState.nextPassMs,
                            onUseEmergencyPass = onUsePass
                        )
                    }

                    BlockMode.BREATHING -> {
                        BreathingContent(
                            delaySeconds = delaySeconds,
                            appLabel = appLabel,
                            dailyTimeRemainingMs = dailyTimeRemainingMs,
                            dailyLimitMinutes = dailyLimitMinutes,
                            onComplete = { onTimerComplete() },
                            onCancel = { navigateHome() },
                            ruleName = ruleName,
                            subtitlePool = subtitlePool,
                            canUseEmergencyPass = passState.canUse,
                            emergencyLocked = passState.locked,
                            nextPassMs = passState.nextPassMs,
                            onUseEmergencyPass = onUsePass
                        )
                    }
                }
                }
            }
        }
    }

    /**
     * Leaving the overlay ABANDONS the block attempt (issue #8). This activity is singleInstance in
     * its own task with an empty taskAffinity, so tabbing out (Home, a recents switch, screen off)
     * only STOPPED it — it stayed alive in the background with its countdown still running, hit zero
     * invisibly, granted passthrough, and the blocked app then opened with no delay at all. Dismissing
     * on stop means the next entry into the blocked app is evaluated fresh and gets a fresh, full
     * delay, and no orphaned overlay task can linger.
     *
     * [isFinishing] guard: [onTimerComplete] / [navigateHome] / the emergency pass already finished us.
     * [isChangingConfigurations] guard: a rotation must NOT dismiss a live block.
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            NudgeAccessibilityService.markOverlayInactive()
            finish()
        }
    }

    /** Timer finished -- user waited patiently, let them through to the blocked app. */
    private fun onTimerComplete() {
        // Only grant passthrough if the overlay is genuinely on screen. A countdown that somehow
        // reached zero while backgrounded must never open the app: that silent grant was the
        // issue #8 bypass. Belt-and-braces behind the lifecycle-gated ticker and onStop above.
        val pkg = passthroughPackage(intent)
        if (pkg.isNotEmpty() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            passthroughManager.grant(
                packageName = pkg,
                featureKey = intent.getStringExtra(EXTRA_FEATURE_KEY),
                webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
            )
        }
        NudgeAccessibilityService.markOverlayInactive()
        finish()
    }

    /**
     * The user walked away: record it, then leave the blocked app behind.
     *
     * Reached from the "I changed my mind" / "Go Back" button on all three overlays and from the
     * back button. Deliberately does NOT grant passthrough — turning around is not permission to
     * enter, so the next attempt gets a fresh, full block.
     *
     * Three properties this path owes, each of which used to be missing:
     *
     *  - **Exactly one event.** [walkedAway] gates the whole body, so a double tap, or a tap racing
     *     the back button, cannot log two walk-aways for one attempt. (The countdown cannot also
     *     fire: [onTimerComplete] is the only other terminal path and it `finish()`es, while the
     *     ticker is cancelled below RESUMED.)
     *  - **A write that survives us.** [RecordWalkAwayUseCase] is a process-lifetime singleton, so
     *     the insert is not tied to this activity, which is destroyed microseconds later.
     *  - **Actually landing on the launcher.** A bare `startActivity(HOME)` raced our own
     *     [finish]: this activity is singleInstance in its own task with an empty taskAffinity, so
     *     finishing pops back to the task underneath — the blocked app — and whichever of the two
     *     the system got to first decided where the user ended up. That is the backlog item
     *     "'I changed my mind' can leave the user inside the blocked app". The accessibility
     *     service's `GLOBAL_ACTION_HOME` does not race us and is not subject to background
     *     activity-start restrictions; it is already the way [EmergencyPassManager],
     *     `AutoKickExecutor` and `StrictModeGuardActivity` go home. The HOME intent stays as the
     *     fallback for when the service is not connected.
     */
    private fun navigateHome() {
        if (!walkedAway.compareAndSet(false, true)) return

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val mode = intent.getStringExtra(EXTRA_BLOCK_MODE) ?: ""
        recordWalkAway.record(packageName = pkg, blockMode = mode)

        // The block is over the moment the user turns around; leaving the flag set until onDestroy
        // left a window in which the service still believed an overlay was up. onTimerComplete
        // already clears it here rather than relying on onDestroy, and so should this path.
        NudgeAccessibilityService.markOverlayInactive()
        goHome()
        finish()
    }

    /**
     * Send the user to the launcher. Prefers the accessibility service's `GLOBAL_ACTION_HOME`;
     * falls back to a HOME intent when the service is not connected. Never throws — failing to log
     * is a lost stat, but failing to leave would trap the user in the app they walked away from.
     */
    private fun goHome() {
        if (NudgeAccessibilityService.requestGoHome()) return
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } catch (e: Exception) {
            nudgeLogger.e("walk-away could not go home", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NudgeAccessibilityService.markOverlayInactive()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        // All modes: back button navigates home (never back to the blocked app)
        navigateHome()
    }
}
