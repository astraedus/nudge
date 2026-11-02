package com.astraedus.nudge.ui.overlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.ui.theme.NudgeTheme
import kotlinx.coroutines.delay

/**
 * The whole-phone pause (issue #123): a full-screen question with a countdown.
 *
 * Not a block. It writes no `UsageEvent`, has no passthrough and no escape hatch beyond its two
 * buttons. "I'm done" goes Home, which is the answer the feature is hoping for. "Keep going" only
 * becomes available once the countdown ends, so the pause cannot be dismissed on reflex.
 */
class PhoneNudgeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MINUTES = "phone_nudge_minutes"
        const val EXTRA_PAUSE_SECONDS = "phone_nudge_pause_seconds"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 20)
        val pauseSeconds = intent.getIntExtra(EXTRA_PAUSE_SECONDS, 5).coerceAtLeast(0)

        // Back is not a way out: the two buttons are. Swallowing it keeps the countdown honest
        // without a custom dialog, and matches the block overlay's discipline.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            NudgeTheme {
                PhoneNudgeContent(
                    minutes = minutes,
                    pauseSeconds = pauseSeconds,
                    onKeepGoing = { finish() },
                    onDone = {
                        goHome()
                        finish()
                    }
                )
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }
}

@Composable
private fun PhoneNudgeContent(
    minutes: Int,
    pauseSeconds: Int,
    onKeepGoing: () -> Unit,
    onDone: () -> Unit
) {
    var remaining by remember { mutableIntStateOf(pauseSeconds) }
    LaunchedEffect(pauseSeconds) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$minutes minutes.",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "What were you going to do?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = if (remaining > 0) "$remaining" else " ",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I'm done, go Home")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onKeepGoing,
                enabled = remaining <= 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (remaining > 0) "Keep going in $remaining" else "Keep going")
            }
        }
    }
}
