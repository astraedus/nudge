package com.astraedus.nudge.ui.overlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.service.NudgeAccessibilityService
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {

    @Inject lateinit var usageRepository: UsageRepository

    companion object {
        const val EXTRA_BLOCK_MODE = "block_mode"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_FEATURE_KEY = "feature_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NudgeAccessibilityService.isOverlayActive = true

        val modeName = intent.getStringExtra(EXTRA_BLOCK_MODE) ?: BlockMode.HARD_BLOCK.name
        val mode = try {
            BlockMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            BlockMode.HARD_BLOCK
        }
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 15)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""

        setContent {
            NudgeTheme {
                when (mode) {
                    BlockMode.HARD_BLOCK -> {
                        HardBlockContent(
                            packageName = packageName,
                            onGoBack = { navigateHome() }
                        )
                    }

                    BlockMode.DELAY -> {
                        DelayContent(
                            delaySeconds = delaySeconds,
                            onComplete = { onTimerComplete() },
                            onCancel = { navigateHome() }
                        )
                    }

                    BlockMode.BREATHING -> {
                        BreathingContent(
                            delaySeconds = delaySeconds,
                            onComplete = { onTimerComplete() },
                            onCancel = { navigateHome() }
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
            NudgeAccessibilityService.grantPassthrough(
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
