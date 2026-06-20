package com.astraedus.nudge.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.data.preferences.NudgePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lets the user edit the motivational messages shown on block/delay/breathing overlays.
 * One message per line; an empty field falls back to the built-in defaults
 * (see [com.astraedus.nudge.ui.overlay.NudgeMessages.resolvePool]).
 *
 * Uses the direct-[NudgePreferences] pattern (no ViewModel), matching the rest of Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesEditorScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { NudgePreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()

    // Seed the fields once from the stored prefs. We collect the first emission rather
    // than observing continuously so user keystrokes aren't clobbered by the DataStore flow.
    var titles by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitles by rememberSaveable { mutableStateOf<String?>(null) }
    var hardBlock by rememberSaveable { mutableStateOf<String?>(null) }

    // Seed once: if the fields haven't been loaded yet (e.g. fresh navigation, not a
    // config-change restore where rememberSaveable already holds the user's edits).
    LaunchedEffect(Unit) {
        if (titles == null) {
            titles = preferences.customDelayTitles.first()
            subtitles = preferences.customDelaySubtitles.first()
            hardBlock = preferences.customHardBlockMessages.first()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Block Messages") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Customize the messages shown when an app is blocked or delayed. " +
                    "One message per line — a random one is picked each time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            MessageField(
                label = "Delay title",
                value = titles ?: "",
                onValueChange = { titles = it },
                onReset = {
                    titles = ""
                    scope.launch { preferences.setCustomDelayTitles("") }
                }
            )

            MessageField(
                label = "Delay subtitle",
                value = subtitles ?: "",
                onValueChange = { subtitles = it },
                onReset = {
                    subtitles = ""
                    scope.launch { preferences.setCustomDelaySubtitles("") }
                }
            )

            MessageField(
                label = "Hard-block message",
                value = hardBlock ?: "",
                onValueChange = { hardBlock = it },
                onReset = {
                    hardBlock = ""
                    scope.launch { preferences.setCustomHardBlockMessages("") }
                }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        preferences.setCustomDelayTitles(titles ?: "")
                        preferences.setCustomDelaySubtitles(subtitles ?: "")
                        preferences.setCustomHardBlockMessages(hardBlock ?: "")
                        Toast.makeText(context, "Messages saved", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MessageField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        TextButton(onClick = onReset) {
            Text("Reset to defaults")
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        supportingText = {
            Text("One message per line. Leave empty to use the defaults.")
        }
    )

    Spacer(Modifier.height(16.dp))
}
