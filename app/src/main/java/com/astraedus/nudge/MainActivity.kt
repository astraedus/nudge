package com.astraedus.nudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.astraedus.nudge.ui.theme.NudgeTheme
import com.astraedus.nudge.ui.navigation.NudgeNavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NudgeTheme {
                NudgeNavGraph()
            }
        }
    }
}
