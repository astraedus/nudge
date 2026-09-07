package com.astraedus.nudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.service.NudgeMonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.astraedus.nudge.ui.theme.NudgeTheme
import com.astraedus.nudge.ui.navigation.NudgeNavGraph
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var nudgePreferences: NudgePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The monitor service used to have exactly one starter, BootReceiver, so a fresh install
        // ran no foreground service, and therefore had nothing that could notice the accessibility
        // service had been stopped, until the phone was next rebooted. Opening the app is the
        // other moment we reliably get, so it syncs too.
        lifecycleScope.launch {
            NudgeMonitorService.sync(
                applicationContext,
                nudgePreferences.isGlobalEnabled.first()
            )
        }
        enableEdgeToEdge()
        setContent {
            NudgeTheme {
                NudgeNavGraph(nudgePreferences = nudgePreferences)
            }
        }
    }
}
