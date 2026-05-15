package com.astraedus.nudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.astraedus.nudge.data.preferences.NudgePreferences
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
        enableEdgeToEdge()
        setContent {
            NudgeTheme {
                NudgeNavGraph(nudgePreferences = nudgePreferences)
            }
        }
    }
}
