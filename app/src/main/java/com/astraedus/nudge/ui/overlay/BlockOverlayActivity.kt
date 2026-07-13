package com.astraedus.nudge.ui.overlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.emergency.EmergencyPass
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.service.EmergencyPassManager
import com.astraedus.nudge.service.NudgeAccessibilityService
import com.astraedus.nudge.service.PassthroughManager
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {

    @Inject lateinit var usageRepository: UsageRepository
    @Inject lateinit var passthroughManager: PassthroughManager
    @Inject lateinit var nudgePreferences: NudgePreferences
    @Inject lateinit var emergencyPassManager: EmergencyPassManager

    companion object {
        const val EXTRA_BLOCK_MODE = "block_mode"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_FEATURE_KEY = "feature_key"
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_DAILY_TIME_REMAINING_MS = "daily_time_remaining_ms"
        const val EXTRA_DAILY_LIMIT_MINUTES = "daily_limit_minutes"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NudgeAccessibilityService.isOverlayActive = true
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
        NudgeAccessibilityService.isOverlayActive = true
        render(newIntent)
    }

    private fun render(intent: Intent) {
        val modeName = intent.getStringExtra(EXTRA_BLOCK_MODE) ?: BlockMode.HARD_BLOCK.name
        val mode = try {
            BlockMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            BlockMode.HARD_BLOCK
        }
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 15)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
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
        // Emergency "1-minute daily pass" UI state, computed once alongside the message pools so the
        // button/hint is correct on first composition. Skipped for the "web"/content-filter
        // pseudo-package (per-app pass is meaningless there).
        var canUseEmergencyPass = false
        var emergencyLocked = false
        var nextPassMs = 0L
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

            if (packageName.isNotEmpty() && packageName != "web") {
                val strictOn = nudgePreferences.isStrictModeEnabled.first()
                val passEnabled = nudgePreferences.emergencyPassEnabled.first()
                val usage = EmergencyPass.parse(nudgePreferences.emergencyPassUsage.first())
                val now = System.currentTimeMillis()
                canUseEmergencyPass = !strictOn && passEnabled &&
                    EmergencyPass.canUse(usage, packageName, now, EmergencyPass.LOCKOUT_MS)
                // Show the "next pass in Xh" hint only when the feature is on but spent — never when
                // Strict Mode is on or the feature is disabled (button is hidden entirely then).
                emergencyLocked = !strictOn && passEnabled && !canUseEmergencyPass
                nextPassMs = if (emergencyLocked) {
                    EmergencyPass.nextAvailableMs(usage, packageName, now, EmergencyPass.LOCKOUT_MS)
                } else 0L
            }
        }

        // Grant the pass and return to the blocked app. finish() brings it back to the foreground;
        // the service's isPassActive check then lets it through. NOT navigateHome and NOT a
        // "changed my mind" event — this is a deliberate escape, not a walk-away.
        val onUsePass = {
            emergencyPassManager.usePass(packageName)
            finish()
        }

        setContent {
            NudgeTheme {
                when (mode) {
                    BlockMode.HARD_BLOCK -> {
                        HardBlockContent(
                            packageName = packageName,
                            appLabel = appLabel,
                            dailyTimeRemainingMs = dailyTimeRemainingMs,
                            dailyLimitMinutes = dailyLimitMinutes,
                            onGoBack = { navigateHome() },
                            ruleName = ruleName,
                            messagePool = hardBlockPool,
                            canUseEmergencyPass = canUseEmergencyPass,
                            emergencyLocked = emergencyLocked,
                            nextPassMs = nextPassMs,
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
                            canUseEmergencyPass = canUseEmergencyPass,
                            emergencyLocked = emergencyLocked,
                            nextPassMs = nextPassMs,
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
                            canUseEmergencyPass = canUseEmergencyPass,
                            emergencyLocked = emergencyLocked,
                            nextPassMs = nextPassMs,
                            onUseEmergencyPass = onUsePass
                        )
                    }
                }
            }
        }
    }

    /** Timer finished -- user waited patiently, let them through to the blocked app. */
    private fun onTimerComplete() {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        if (pkg.isNotEmpty()) {
            passthroughManager.grant(
                packageName = pkg,
                featureKey = intent.getStringExtra(EXTRA_FEATURE_KEY)
            )
        }
        NudgeAccessibilityService.isOverlayActive = false
        finish()
    }

    private fun navigateHome() {
        // Log that user changed their mind
        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val mode = intent.getStringExtra(EXTRA_BLOCK_MODE) ?: ""
        CoroutineScope(Dispatchers.IO).launch {
            usageRepository.logEvent(
                UsageEvent(
                    packageName = pkg,
                    wasBlocked = true,
                    blockMode = mode,
                    userChangedMind = true
                )
            )
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        NudgeAccessibilityService.isOverlayActive = false
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        // All modes: back button navigates home (never back to the blocked app)
        navigateHome()
    }
}
