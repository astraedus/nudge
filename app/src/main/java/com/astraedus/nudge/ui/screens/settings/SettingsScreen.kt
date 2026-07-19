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
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.ui.components.AccessibilityDisclosureDialog
import com.astraedus.nudge.ui.components.ChallengeDialog
import com.astraedus.nudge.ui.hasGrayscalePermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGrayscaleGuide: () -> Unit = {},
    onNavigateToMessagesEditor: () -> Unit = {}
) {
    val context = LocalContext.current
    val accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    val overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val usageStatsEnabled by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    val preferences = remember { NudgePreferences(context.applicationContext) }
    val debugLoggingEnabled by preferences.isDebugLoggingEnabled.collectAsStateWithLifecycle(initialValue = false)
    val contentFilterEnabled by preferences.contentFilterEnabled.collectAsStateWithLifecycle(initialValue = false)
    val contentFilterStrictKeywords by preferences.contentFilterStrictKeywords.collectAsStateWithLifecycle(initialValue = false)
    val strictModeEnabled by preferences.isStrictModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val strictModeLength by preferences.strictModeChallengeLength.collectAsStateWithLifecycle(
        initialValue = StrictModeChallenge.DEFAULT_LENGTH
    )
    val emergencyPassEnabled by preferences.emergencyPassEnabled.collectAsStateWithLifecycle(initialValue = true)
    val coroutineScope = rememberCoroutineScope()
    var versionTapCount by rememberSaveable { mutableIntStateOf(0) }
    var developerOptionsVisible by rememberSaveable { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    // Strict Mode: turning OFF is gated by the unlock challenge. We hold the active target here;
    // a fresh one is generated each time the user attempts to turn Strict Mode off.
    var strictModeOffChallenge by remember { mutableStateOf<String?>(null) }

    strictModeOffChallenge?.let { target ->
        ChallengeDialog(
            target = target,
            prompt = "Turn off Strict Mode",
            onUnlock = {
                strictModeOffChallenge = null
                coroutineScope.launch { preferences.setStrictModeEnabled(false) }
            },
            onCancel = { strictModeOffChallenge = null }
        )
    }

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
                "Content Filter",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Block restricted websites") },
                supportingContent = {
                    Text("Filters websites against a built-in content list. Works in supported browsers.")
                },
                leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = contentFilterEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                preferences.setContentFilterEnabled(enabled)
                            }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    coroutineScope.launch {
                        preferences.setContentFilterEnabled(!contentFilterEnabled)
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Strict keyword matching") },
                supportingContent = {
                    Text("Also blocks matching terms found in search queries.")
                },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = contentFilterStrictKeywords,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                preferences.setContentFilterStrictKeywords(enabled)
                            }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    coroutineScope.launch {
                        preferences.setContentFilterStrictKeywords(!contentFilterStrictKeywords)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Commitment Lock",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Lock my settings (Strict Mode)") },
                supportingContent = {
                    Text("While on, undoing any protection — or turning this off — requires typing the unlock challenge.")
                },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = strictModeEnabled,
                        onCheckedChange = { wantOn ->
                            if (wantOn) {
                                // Turning Strict Mode ON is immediate.
                                coroutineScope.launch { preferences.setStrictModeEnabled(true) }
                            } else {
                                // Turning OFF is gated: surface a fresh challenge.
                                strictModeOffChallenge = StrictModeChallenge.generate(strictModeLength)
                            }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    if (strictModeEnabled) {
                        strictModeOffChallenge = StrictModeChallenge.generate(strictModeLength)
                    } else {
                        coroutineScope.launch { preferences.setStrictModeEnabled(true) }
                    }
                }
            )

            // Difficulty selector. Changing difficulty is not a weakening action (it doesn't undo
            // any block), so it's editable freely even while Strict Mode is on.
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StrictModeDifficultyChip(
                    label = "Easy",
                    length = StrictModeChallenge.LENGTH_EASY,
                    selected = strictModeLength == StrictModeChallenge.LENGTH_EASY,
                    onSelect = { coroutineScope.launch { preferences.setStrictModeChallengeLength(it) } }
                )
                StrictModeDifficultyChip(
                    label = "Medium",
                    length = StrictModeChallenge.LENGTH_MEDIUM,
                    selected = strictModeLength == StrictModeChallenge.LENGTH_MEDIUM,
                    onSelect = { coroutineScope.launch { preferences.setStrictModeChallengeLength(it) } }
                )
                StrictModeDifficultyChip(
                    label = "Hard",
                    length = StrictModeChallenge.LENGTH_HARD,
                    selected = strictModeLength == StrictModeChallenge.LENGTH_HARD,
                    onSelect = { coroutineScope.launch { preferences.setStrictModeChallengeLength(it) } }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Escape Hatch",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Daily 2-minute pass") },
                supportingContent = {
                    Text("Allow one 2-minute escape a day, shared across all blocked apps. Disabled while Strict Mode is on.")
                },
                leadingContent = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = emergencyPassEnabled,
                        enabled = !strictModeEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                preferences.setEmergencyPassEnabled(enabled)
                            }
                        }
                    )
                },
                modifier = Modifier.clickable(enabled = !strictModeEnabled) {
                    coroutineScope.launch {
                        preferences.setEmergencyPassEnabled(!emergencyPassEnabled)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Personalize",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Edit block messages") },
                supportingContent = {
                    Text("Customize the motivational text shown on block and delay screens.")
                },
                leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onNavigateToMessagesEditor)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrictModeDifficultyChip(
    label: String,
    length: Int,
    selected: Boolean,
    onSelect: (Int) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onSelect(length) },
        label = { Text("$label ($length)") }
    )
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
