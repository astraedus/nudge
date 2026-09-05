package com.astraedus.nudge.ui.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Explains issue #19 to the user: when Nudge's block overlay backgrounds an app like YouTube, the
 * app can enter picture-in-picture and keep playing a Short in a floating window on top of the
 * overlay. The overlay is correctly full-screen and the topmost resumed activity — PiP is a
 * platform feature that lets another app's window float above everything, and there is no public
 * API for Nudge to disable PiP for another app itself. The only real fix is the OS-level per-app
 * "Picture-in-picture" toggle, which only the user can flip. This screen is shown once per app
 * (the accessibility service tracks which apps have already seen it) and deep-links to that
 * toggle via [PipSettings], degrading to manual instructions when the deep link can't resolve.
 *
 * Stats-honesty invariant: a PiP escape is NEITHER a block NOR a walk-away. This activity does not
 * touch [com.astraedus.nudge.service.NudgeAccessibilityService.isOverlayActive], does not log any
 * [com.astraedus.nudge.data.db.entity.UsageEvent], and does not grant passthrough — it stands in
 * for a block the overlay already lost, and recording it as one would misreport what happened. A
 * future edit must not add any of those three without re-checking this contract.
 */
@AndroidEntryPoint
class PipEscapeActivity : ComponentActivity() {

    private var resolvedTarget: PipSettingsTarget? = null

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"

        /**
         * True while the explainer is on screen. The accessibility service reads this to keep
         * swallowing events for the blocked app: this screen STANDS IN for the block overlay, so
         * re-evaluating behind it would relaunch the overlay on top of the explainer AND log a
         * second wasBlocked usage event for a block the user never re-triggered.
         */
        @Volatile
        var isActive: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true
        registerBackHandler()

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { null }

        val target = PipSettings.firstResolvable { candidate ->
            buildIntent(candidate, packageName).resolveActivity(packageManager) != null
        }
        resolvedTarget = target

        setContent {
            NudgeTheme {
                // Flips to the manual instructions if the deep link turns out not to open. See
                // [onOpenSettings] — the launch can fail even though the intent resolved.
                var launchFailed by rememberSaveable { mutableStateOf(false) }
                PipEscapeContent(
                    appLabel = appLabel,
                    packageName = packageName,
                    canOpenSettings = target != null && !launchFailed,
                    onOpenSettings = { if (!openSettings(packageName)) launchFailed = true },
                    onDismiss = { onDismiss() }
                )
            }
        }
    }

    private fun buildIntent(target: PipSettingsTarget, packageName: String): Intent =
        Intent(target.action).apply {
            if (target.usePackageUri) {
                data = Uri.fromParts("package", packageName, null)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Try every candidate destination in order until one actually launches, closing this screen on
     * success. Returns false when none of them opened, and then this screen deliberately STAYS UP
     * and swaps in the manual instructions.
     *
     * Why it is written this defensively: resolving an intent and launching it are different
     * questions, and on a real Pixel 3 the first version of this screen resolved a target at
     * onCreate, launched nothing on tap, and closed itself anyway — the user pressed the one button
     * on the screen and got a silent no-op. That is the same silent-failure class that cost this
     * feature a release. So:
     *
     *  - Every candidate is tried at tap time, not just the one resolved at onCreate.
     *  - `catch (Exception)`, not just [ActivityNotFoundException]: an OEM Settings that rejects the
     *    intent with a SecurityException would otherwise crash the user out of a screen whose only
     *    job is to be helpful.
     *  - Failure is VISIBLE. The deep link is this screen's whole value; if it cannot be delivered,
     *    the user must still be told where to go by hand.
     */
    private fun openSettings(packageName: String): Boolean {
        // resolvedTarget first (it is the one the button was offered for), then the rest as backups.
        val ordered = listOfNotNull(resolvedTarget) + PipSettings.targets().filter { it != resolvedTarget }
        for (target in ordered) {
            try {
                startActivity(buildIntent(target, packageName))
                dismiss()
                return true
            } catch (_: Exception) {
                // Try the next candidate.
            }
        }
        return false
    }

    /**
     * Close, clearing [isActive] FIRST.
     *
     * The service suspends all enforcement while that flag is set (this screen stands in for the
     * block overlay). Clearing it only in [onDestroy] would leave enforcement paused for the gap
     * between `finish()` and the system actually destroying us — a small but real window in which a
     * blocked app would not be blocked. Same reasoning as [BlockOverlayActivity.onStop], which
     * clears the overlay flag before finishing rather than waiting for teardown. [onDestroy] keeps
     * clearing it as a backstop for paths that never route through here (e.g. a process-level kill
     * of the task).
     */
    private fun dismiss() {
        isActive = false
        finish()
    }

    /**
     * Back / "Not now" / dismiss: just close. The block overlay this screen replaced is already
     * gone, so there is nothing left to protect and nowhere the user needs to be routed — unlike
     * [com.astraedus.nudge.ui.lock.StrictModeGuardActivity], forcing them home here would be
     * bouncing them out of an app they were never trying to escape.
     */
    private fun onDismiss() {
        dismiss()
    }

    /**
     * Back dismisses, exactly as the "Not now" button does.
     *
     * The system default would also finish this activity, so unlike the other two overlays nothing
     * user-visible hangs on this callback. It still matters: [dismiss] clears [isActive] BEFORE
     * finishing, and the default path would leave enforcement suspended until [onDestroy] runs. See
     * [dismiss]. Replaces an `onBackPressed()` override, which targetSdk 36 no longer calls.
     */
    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onDismiss()
            }
        })
    }

    /**
     * Same discipline as [BlockOverlayActivity.onStop]: leaving this screen (Home, recents, screen
     * off) must not leave an orphaned task lingering in this singleInstance/empty-taskAffinity
     * activity.
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            dismiss()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
    }
}
