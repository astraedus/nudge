package com.astraedus.nudge.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.astraedus.nudge.ui.hasGrayscalePermission
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrayscaleGuideScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val permissionGranted by remember { mutableStateOf(hasGrayscalePermission(context)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grayscale Mode Setup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Status indicator
            PermissionStatusCard(granted = permissionGranted)

            if (permissionGranted) {
                // Permission already granted -- show success only
                Spacer(Modifier.height(16.dp))
            } else {
                // Explanation
                Text(
                    "Grayscale mode makes your phone screen black-and-white when blocked apps " +
                        "are open, making them less appealing.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "This requires a one-time setup because Android restricts apps from " +
                        "changing display settings directly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Option 1: Wireless
                SectionHeader("Option 1: Wireless (Android 11+, no computer needed)")

                NumberedStep(1, "Go to Settings > About Phone > tap \"Build number\" 7 times to enable Developer Options")
                NumberedStep(2, "Go to Settings > Developer Options > enable \"Wireless debugging\"")
                NumberedStep(3, "Tap \"Pair device with pairing code\" and note the pairing code and port")
                NumberedStep(4, "Open a terminal app (like Termux) and run the pairing command:")

                AdbCommandCard(
                    command = "adb pair <ip>:<port>",
                    label = "Then enter the pairing code when prompted",
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    context = context
                )

                NumberedStep(5, "Then run this command to grant the permission:")

                AdbCommandCard(
                    command = "adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS",
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    context = context
                )

                Spacer(Modifier.height(8.dp))

                // Option 2: With a Computer
                SectionHeader("Option 2: With a Computer")

                NumberedStep(1, "Install ADB on your computer (search \"install adb\" for your OS)")
                NumberedStep(2, "Enable USB Debugging on your phone (Settings > Developer Options > USB Debugging)")
                NumberedStep(3, "Connect your phone via USB")
                NumberedStep(4, "Run this command on your computer:")

                AdbCommandCard(
                    command = "adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS",
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    context = context
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "That's it! One-time setup. Grayscale will work automatically after this.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionStatusCard(granted: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                Color(0xFF1B5E20).copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (granted) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50)
                )
                Column {
                    Text(
                        "Permission Granted",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        "Grayscale mode is ready to use. Enable it per-app in rule settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "Not Granted",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Follow the guide below to enable grayscale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AdbCommandCard(
    command: String,
    label: String? = null,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", command))
                scope.launch {
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy command",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
            )
        }
    }
}
