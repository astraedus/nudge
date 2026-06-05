package com.astraedus.nudge.ui.screens.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.ui.components.AccessibilityDisclosureDialog
import com.astraedus.nudge.ui.hasGrayscalePermission
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGrayscaleGuide: () -> Unit = {}
) {
    val context = LocalContext.current
    val accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    val overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val usageStatsEnabled by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    val preferences = remember { NudgePreferences(context.applicationContext) }
    val debugLoggingEnabled by preferences.isDebugLoggingEnabled.collectAsStateWithLifecycle(initialValue = false)
    val coroutineScope = rememberCoroutineScope()
    var versionTapCount by rememberSaveable { mutableIntStateOf(0) }
    var developerOptionsVisible by rememberSaveable { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onConfirm = {
                showAccessibilityDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDismiss = {
                showAccessibilityDisclosure = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Permissions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            PermissionItem(
                title = "Accessibility Service",
                description = "Required to detect foreground apps",
                granted = accessibilityEnabled,
                icon = { Icon(Icons.Outlined.Accessibility, contentDescription = null) },
                onClick = {
                    showAccessibilityDisclosure = true
                }
            )

            PermissionItem(
                title = "Overlay Permission",
                description = "Required to show block screens",
                granted = overlayEnabled,
                icon = { Icon(Icons.Outlined.Layers, contentDescription = null) },
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            PermissionItem(
                title = "Usage Stats Access",
                description = "Required to track app usage",
                granted = usageStatsEnabled,
                icon = { Icon(Icons.Outlined.QueryStats, contentDescription = null) },
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )

            PermissionItem(
                title = "Grayscale Permission",
                description = if (hasGrayscalePermission(context)) "Granted" else "Tap to see setup guide",
                granted = hasGrayscalePermission(context),
                icon = { Icon(Icons.Outlined.InvertColors, contentDescription = null) },
                onClick = onNavigateToGrayscaleGuide
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "About",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = {
                    Text("Nudge v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.clickable {
                    if (!developerOptionsVisible) {
                        versionTapCount += 1
                        if (versionTapCount >= 7) {
                            developerOptionsVisible = true
                            Toast.makeText(context, "Developer options enabled", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Source Code & Feedback") },
                supportingContent = { Text("Open source on GitHub. Report bugs or suggest features.") },
                leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/astraedus/nudge"))
                    )
                }
            )

            ListItem(
                headlineContent = { Text("License") },
                supportingContent = { Text("GPL-3.0") },
                leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) }
            )

            if (developerOptionsVisible) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    "Developer Options",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                ListItem(
                    headlineContent = { Text("Debug Logging") },
                    supportingContent = { Text("Write diagnostic logs to Logcat") },
                    leadingContent = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = debugLoggingEnabled,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    preferences.setDebugLoggingEnabled(enabled)
                                }
                            }
                        )
                    },
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            preferences.setDebugLoggingEnabled(!debugLoggingEnabled)
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = icon,
        trailingContent = {
            Text(
                if (granted) "✔" else "✖",
                color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleLarge
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(context.packageName)
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

// hasGrayscalePermission is now in com.astraedus.nudge.ui.PermissionUtils
