package com.astraedus.nudge.ui.lock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.service.NudgeAccessibilityService
import com.astraedus.nudge.service.StrictModeEscapeManager
import com.astraedus.nudge.ui.components.ChallengeDialog
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Full-screen Strict Mode unlock challenge shown ON TOP of a protected Settings escape route
 * (the Nudge accessibility-service toggle, or Nudge's App Info / Force-stop / Uninstall page).
 *
 * Launched by [NudgeAccessibilityService.maybeGuardSettingsEscape] when Strict Mode is on and the
 * user has landed on one of those screens outside the post-unlock grace window.
 *
 * Outcomes (both safety-critical — the user must NEVER be trapped):
 *  - **Unlock** (typed the code): open a [StrictModeEscapeManager] grace window so the user can
 *    finish the disable they committed to without being re-challenged, then [finish] — which drops
 *    them back onto the Settings screen they were on.
 *  - **"I changed my mind" / back / dismiss**: send the user HOME via the accessibility service's
 *    [NudgeAccessibilityService.GLOBAL_ACTION_HOME] (with a HOME-intent fallback), bouncing them out
 *    of Settings, then [finish].
 *
 * The challenge string is freshly generated here (difficulty from prefs) and is always solvable.
 */
@AndroidEntryPoint
class StrictModeGuardActivity : ComponentActivity() {

    @Inject lateinit var nudgePreferences: NudgePreferences
    @Inject lateinit var escapeManager: StrictModeEscapeManager

    companion object {
        const val EXTRA_CHALLENGE_LENGTH = "challenge_length"

        /**
         * True while a guard activity is on screen, so the accessibility service does not stack a
         * second guard on top of the first when Settings emits more window events behind it.
         */
        @Volatile
        var isActive: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true

        val length = intent.getIntExtra(EXTRA_CHALLENGE_LENGTH, StrictModeChallenge.DEFAULT_LENGTH)
        val target = StrictModeChallenge.generate(length)

        setContent {
            NudgeTheme {
                // A solid full-screen surface so the underlying Settings screen is fully obscured;
                // the dialog can't be dismissed *into* the protected screen.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val challengeTarget = remember { target }
                        ChallengeDialog(
                            target = challengeTarget,
                            prompt = "Strict Mode is protecting this screen. " +
                                "Unlock to change Nudge's system settings, or go back.",
                            onUnlock = { onUnlocked() },
                            onCancel = { onChangedMind() }
                        )
                    }
                }
            }
        }
    }

    /** Typed the code: open the grace window so the user can complete their action, then close. */
    private fun onUnlocked() {
        escapeManager.grantGrace()
        finish()
    }

    /** Backed out: reliably send the user HOME (out of Settings), then close. */
    private fun onChangedMind() {
        goHome()
        finish()
    }

    /**
     * Send the user home. Prefer the accessibility service's GLOBAL_ACTION_HOME (cleanly exits the
     * Settings screen); fall back to a HOME intent if the service is unavailable. This path must
     * ALWAYS land the user on the home screen — never trap them.
     */
    private fun goHome() {
        val dispatched = NudgeAccessibilityService.requestGoHome()
        if (!dispatched) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        // Back is treated as "I changed my mind": go home, never back into the protected screen.
        onChangedMind()
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
    }
}
