package com.astraedus.nudge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.service.NudgeMonitorService
import com.astraedus.nudge.ui.theme.NudgeTheme
import com.astraedus.nudge.ui.navigation.NudgeNavGraph
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var nudgePreferences: NudgePreferences

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepMonitorServiceInSync()
        requestNotificationPermissionIfNeeded()

        val openSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

        setContent {
            NudgeTheme {
                NudgeNavGraph(
                    nudgePreferences = nudgePreferences,
                    openSettingsOnLaunch = openSettings
                )
            }
        }
    }

    /**
     * The app's real start path for the foreground service.
     *
     * `NudgeMonitorService` used to be started from exactly one place — a `BOOT_COMPLETED`
     * broadcast — so a fresh install ran with no process-priority protection at all until the
     * user's next reboot, and every app update took it away again. One observer here covers all
     * three moments monitoring should come up, because each of them is a change in this same pair
     * of flags while this Activity is on screen:
     *
     *  - app launch with monitoring already on,
     *  - the master toggle being switched on (or off — the service stops, because a "Nudge is
     *    active" notification over disabled monitoring is a lie),
     *  - onboarding completing, which writes `onboardingComplete` without ever leaving here.
     *
     * Gated on onboarding too, so a first-run user is not shown a notification claiming Nudge is
     * monitoring before they have granted it anything to monitor with.
     */
    private fun keepMonitorServiceInSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    nudgePreferences.isGlobalEnabled,
                    nudgePreferences.isOnboardingComplete
                ) { enabled, onboarded -> enabled && onboarded }
                    .distinctUntilChanged()
                    .collect { shouldMonitor ->
                        if (shouldMonitor) {
                            NudgeMonitorService.start(this@MainActivity)
                        } else {
                            NudgeMonitorService.stop(this@MainActivity)
                        }
                    }
            }
        }
    }

    /**
     * `POST_NOTIFICATIONS` is declared in the manifest but is a runtime grant from Android 13.
     * Without it the watchdog's "blocking has stopped" alert is dropped on the floor and the
     * ongoing monitor notification never appears — i.e. every cue that protection is alive or dead
     * goes missing on exactly the modern devices this is meant to protect. Asking once on launch,
     * with no dialog of our own, because the system prompt already explains itself and a refusal
     * costs the user nothing else in the app.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** Set by the protection alert so its tap lands on the screen that fixes the problem. */
        const val EXTRA_OPEN_SETTINGS = "nudge.open_settings"
    }
}
