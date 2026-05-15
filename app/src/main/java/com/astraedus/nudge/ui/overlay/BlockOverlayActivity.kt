package com.astraedus.nudge.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: implement overlay content based on block mode
        setContent {
            NudgeTheme {
            }
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        // Prevent dismissing the overlay with back button
    }
}
