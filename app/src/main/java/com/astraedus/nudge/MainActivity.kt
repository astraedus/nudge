package com.astraedus.nudge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.service.NudgeMonitorService
import com.astraedus.nudge.ui.theme.NudgeTheme
import com.astraedus.nudge.ui.navigation.NudgeNavGraph
import com.astraedus.nudge.ui.widget.WidgetDeepLink
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

    /**
     * The route a notification or a home-screen widget asked us to open, until the nav graph has
     * consumed it.
     *
     * State rather than a value read once in [onCreate], because this Activity is `singleTop`: a
     * widget tapped while the app is already running delivers through [onNewIntent] with no new
     * composition to read the intent. Cleared on consumption so tapping the SAME widget again, after
     * navigating away, navigates again rather than being swallowed as an unchanged key.
     */
    private var deepLinkRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepMonitorServiceInSync()
        requestNotificationPermissionIfNeeded()

        deepLinkRoute = routeFrom(intent)

        setContent {
            NudgeTheme {
                NudgeNavGraph(
                    nudgePreferences = nudgePreferences,
                    deepLinkRoute = deepLinkRoute,
                    onDeepLinkConsumed = { deepLinkRoute = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // setIntent so getIntent() and this state cannot disagree about which intent is current.
        setIntent(intent)
        deepLinkRoute = routeFrom(intent)
    }

    /**
     * ONE reader for both deep-link mechanisms.
     *
     * [EXTRA_OPEN_SETTINGS] is translated into a route rather than kept as a parallel path. Its
     * `PendingIntent` is already sitting inside protection alerts posted on real phones, so the
     * constant has to keep working, but it does not have to keep being a second mechanism, and
     * `WidgetDeepLink.routeFor` refusing an unknown route protects both.
     */
    private fun routeFrom(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) return WidgetDeepLink.ROUTE_SETTINGS
        return WidgetDeepLink.routeFor(intent.getStringExtra(WidgetDeepLink.EXTRA_ROUTE))
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
                        // sync(), not start()/stop() by hand: one lifecycle API, so "the service
                        // exists exactly when monitoring is on" is decided in one place rather
                        // than at each of the four call sites that can change the answer.
                        NudgeMonitorService.sync(this@MainActivity, shouldMonitor)
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
