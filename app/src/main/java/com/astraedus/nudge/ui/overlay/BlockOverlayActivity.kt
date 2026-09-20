package com.astraedus.nudge.ui.overlay

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.emergency.EmergencyPass
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.usecase.RecordWalkAwayUseCase
import com.astraedus.nudge.service.BlockLaunchGuard
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
    @Inject lateinit var blockLaunchGuard: BlockLaunchGuard

    /** Posts the walk-away fail-safe finish. Cleared in [onDestroy]. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Set when this DELIVERY terminates as a walk-away, so [navigateHome] logs exactly one event
     * per attempt no matter how many times it is reached (button + back button, or a double tap).
     *
     * **Per delivery, and reset by [render], exactly like [renderToken].** It used to be set once
     * for the life of the activity, on the reasoning that a re-delivered block always arrives on an
     * activity that has not walked away yet, because [navigateHome] finished immediately. Deferring
     * that finish (see [WALK_AWAY_FINISH_FAILSAFE_MS]) made that false: this activity now stays
     * RESUMED for up to 1200ms after a walk-away, and a block for a DIFFERENT package delivered
     * through [onNewIntent] inside that window would have rendered on top of a latched flag, leaving
     * both "I changed my mind" and the back button dead. On a `HARD_BLOCK`, which has no completion
     * path, that is not a missed stat, it is a user with no way out at all.
     *
     * Resetting cannot let one attempt log two rows: the only delivery that could be the SAME
     * attempt is one for the same package, and `BlockLaunchGate.WALK_AWAY_TRANSITION_MS` (1500ms)
     * refuses those for longer than the 1200ms this activity can survive a walk-away. The two
     * constants are load-bearing in that direction too.
     */
    private val walkedAway = AtomicBoolean(false)

    /**
     * Incremented on every [render] so each delivered block composes under a fresh [key], discarding
     * the previous block's remembered countdown state. A counter rather than the block's contents:
     * two different apps can legitimately share a package-mode-delay triple, and re-delivering the
     * *same* block is still a new attempt that must start from full.
     */
    private var renderToken = 0

    /**
     * Which pending overlay in [BlockLaunchGuard] this ACTIVITY INSTANCE is, read once per
     * [render].
     *
     * [onDestroy] used to clear the guard's pending overlay unconditionally, which is wrong
     * whenever this instance is not the one that is pending. An overlay that finishes itself from
     * [onStop] while the service launches a replacement has its `onDestroy` run AFTER the new
     * instance's `onResume` -- so the dying instance wiped the live one's state, and the newly
     * blocked app's own start-up window events were read as the user getting past an overlay that
     * had only just gone up. Part of issue #36.
     */
    private var overlayId: Long = BlockLaunchGate.NO_OVERLAY_ID

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

        /**
         * How long the walk-away path waits for the dispatched "go home" to stop us, before
         * finishing anyway.
         *
         * The normal path is not this timer: `GLOBAL_ACTION_HOME` brings the launcher forward, this
         * activity is stopped, and [onStop] finishes it, from the BACKGROUND, where finishing pops
         * nothing forward. That is the whole point of not calling `finish()` inline (see
         * [navigateHome]). This exists only so that a `go home` the platform accepted and never
         * honoured cannot strand the user on an overlay whose buttons are already spent.
         *
         * Deliberately SHORTER than [com.astraedus.nudge.domain.block.BlockLaunchGate
         * .WALK_AWAY_TRANSITION_MS] (1500ms). The two numbers are one mechanism: if this fail-safe
         * ever does fire, the finish pops back to the blocked app's task, exactly the resurfacing
         * that issue #26 is about, and it must land while the service's walk-away window is still
         * open to suppress the block it would otherwise re-arm. Raising this above that constant
         * re-opens #26 for the failure case.
         */
        private const val WALK_AWAY_FINISH_FAILSAFE_MS = 1_200L
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
        registerBackHandler()
        render(intent)
    }

    /**
     * Back must ALWAYS walk away, never dismiss the overlay.
     *
     * From targetSdk 36 the platform runs predictive back by default: `onBackPressed()` is no longer
     * called and no KEYCODE_BACK is dispatched, so the override this replaced would have been dead
     * code and the system default would have taken over. That default is `finish()`, and finishing a
     * singleInstance activity with an empty taskAffinity pops straight back to the task underneath:
     * the blocked app. The back gesture would have become a one-swipe bypass of every block.
     *
     * An always-enabled callback keeps the pre-36 semantics exactly: [navigateHome] records the
     * walk-away (once, via [walkedAway]) and leaves for the launcher. Registered here in [onCreate]
     * rather than in [render] because this activity is singleInstance. [render] also runs from
     * [onNewIntent], which would stack a second callback on every re-delivered block.
     */
    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateHome()
            }
        })
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
        // A NEW delivery is a NEW attempt, and it gets its own walk-away budget and its own timers.
        // Both of these matter only on the [onNewIntent] path, and only because [navigateHome] no
        // longer finishes immediately: without them a block delivered during a walk-away's
        // transition would render over a latched [walkedAway] with a dead button and a dead back
        // gesture, and under a stale fail-safe belonging to the previous attempt. The fail-safe is
        // already inert by its [renderToken] guard; cancelling it here means nobody has to know
        // that to reason about this method.
        walkedAway.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        // Which pending overlay we are. Read here because render() runs synchronously from
        // onCreate/onNewIntent, i.e. immediately after the service's startActivity, so the guard's
        // current pending overlay IS this delivery's. See [overlayId].
        overlayId = blockLaunchGuard.currentOverlayId()

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
    /**
     * The overlay is now genuinely ON SCREEN, which is a different fact from "we started it".
     *
     * The service sets `isOverlayActive` at `startActivity`, hundreds of milliseconds before this
     * runs, and in that gap the blocked app is still starting up and still firing window events of
     * its own. Those were being read as the user getting PAST the block, which re-evaluated the app
     * and launched a second overlay, writing a second `wasBlocked` row for one entry. Reporting from
     * here is the only reliable evidence of when the gap closes: the accessibility stream cannot
     * supply it, because the overlay task's first window arrives ~600ms early carrying a framework
     * class name. See [com.astraedus.nudge.domain.block.BlockLaunchGate.isGenuineBypass].
     */
    override fun onResume() {
        super.onResume()
        blockLaunchGuard.onOverlayShown()
    }

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
     *  - **Exactly one event per delivery.** [walkedAway] gates the whole body, so a double tap, or
     *     a tap racing the back button, cannot log two walk-aways for one attempt. [render] resets
     *     it, because a re-delivered block is a new attempt and must not inherit a spent flag; see
     *     [walkedAway] for why that cannot double-count the same attempt. (The countdown cannot also
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
     *  - **Not handing the foreground back on the way out**, issue
     *     [#26](https://github.com/astraedus/nudge/issues/26), *"I changed my mind - have to click
     *     twice"*, three reporters, every time, never once on the bench Pixel 3. `GLOBAL_ACTION_HOME`
     *     removed the race over where the user ENDS UP, but not the one over what happens in
     *     between: it is dispatched asynchronously, and the `finish()` that used to run on the very
     *     next line pops this task immediately, revealing the blocked app underneath. On a device
     *     where that pop wins, the blocked app genuinely resumes and fires a real window event, the
     *     service sees a real foreground app with no overlay up, re-evaluates it (the debounce is
     *     long past, the user has been sitting on the overlay), and re-blocks. The second tap
     *     "works" only because by then the launcher is where the pop lands.
     *
     *     So the walk-away no longer finishes itself. `GLOBAL_ACTION_HOME` stops us, [onStop]
     *     finishes us, and a finish from the background pops nothing forward, the departure is
     *     ordered by the platform instead of raced against it. [WALK_AWAY_FINISH_FAILSAFE_MS] covers
     *     a go-home that is accepted and never honoured, and the service-side
     *     [com.astraedus.nudge.domain.block.BlockLaunchGate] window covers what that fail-safe would
     *     then expose. Belt and braces, because the failure mode here is the user being shown the
     *     screen they just dismissed.
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

        // Armed BEFORE the go-home is dispatched, never after: the whole point is to be in place
        // when the blocked app's window resurfaces, and that can happen on the very next frame.
        // Scoped to the package the user is sitting IN (the browser, for a web block), that is
        // whose window is underneath us, and whose re-entry must not be read as a fresh arrival.
        blockLaunchGuard.onWalkAwayStarted(passthroughPackage(intent))

        goHome()
        scheduleWalkAwayFinish()
    }

    /**
     * Finish anyway if the dispatched go-home never stops us. See [WALK_AWAY_FINISH_FAILSAFE_MS].
     *
     * Guarded on [renderToken] so a NEW block delivered through [onNewIntent] during the transition
     * is not killed by the previous attempt's timer, that block belongs to a different app or a
     * different rule and the user has not walked away from it.
     */
    private fun scheduleWalkAwayFinish() {
        val tokenAtWalkAway = renderToken
        val walkAwayPackage = passthroughPackage(intent)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && renderToken == tokenAtWalkAway) {
                nudgeLogger.w("walk-away go-home did not land, finishing overlay on the fail-safe")
                // RE-ARM THE WINDOW AT THE MOMENT OF THE POP, not just at the moment of the tap.
                //
                // This finish is the old #26 bug reproduced deliberately: it pops the blocked app
                // forward, that app fires a real window event, and the only thing that stops the
                // service re-blocking an app the user just declined is the walk-away window. Armed
                // once at the tap, the window had whatever was left of 1500ms minus the 1200ms
                // spent waiting -- 300ms for the pop, the app's resume and its window event to all
                // land, on precisely the slow devices that reported #26 in the first place. Past
                // that, the block came back and (before issue #36's invariant) counted itself.
                //
                // Re-arming measures the window from the event it is actually about. The constants
                // stay ordered as `BlockOverlayLaunchContractTest` pins them; this removes the need
                // for the margin between them to also be big enough for a device nobody has.
                blockLaunchGuard.onWalkAwayStarted(walkAwayPackage)
                finish()
            }
        }, WALK_AWAY_FINISH_FAILSAFE_MS)
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
        mainHandler.removeCallbacksAndMessages(null)
        NudgeAccessibilityService.markOverlayInactive()
        // Nothing is pending once we are gone. Leaving a stale pending overlay behind would let it
        // suppress a genuine bypass for the rest of its settle window -- but only OUR pending
        // overlay is ours to clear: a replacement instance is already on screen by the time a
        // finished one is destroyed. See [overlayId].
        blockLaunchGuard.onOverlayDismissed(overlayId)
    }
}
