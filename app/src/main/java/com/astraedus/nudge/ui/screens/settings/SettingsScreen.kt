package com.astraedus.nudge.ui.screens.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.LockedToggle
import com.astraedus.nudge.domain.lock.SettingsWeakening
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.service.AccessibilityConnectionSignal
import com.astraedus.nudge.service.ProtectionStatus
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
    // See rememberPermissionStates: these three used to be `remember { mutableStateOf(x) }` —
    // computed once at first composition and never again — which is how a green tick survived a
    // dead accessibility service (an in-place update, or a phone that killed the process overnight).
    val permissionStates = rememberPermissionStates(context)
    val accessibilityEnabled = permissionStates.accessibility
    val overlayEnabled = permissionStates.overlay
    val usageStatsEnabled = permissionStates.usageStats
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
    // Strict Mode gates every protection-WEAKENING flip on this screen behind the unlock challenge.
    // One pending slot serves them all: the freshly generated target, the dialog prompt, and the
    // action to run once the user types it. Null = no dialog up.
    var pendingUnlock by remember { mutableStateOf<PendingSettingsUnlock?>(null) }

    pendingUnlock?.let { pending ->
        ChallengeDialog(
            target = pending.target,
            prompt = pending.prompt,
            onUnlock = {
                pendingUnlock = null
                pending.onUnlock()
            },
            onCancel = { pendingUnlock = null }
        )
    }

    // Route a toggle flip through the Strict Mode policy: weakening flips surface a fresh challenge,
    // strengthening flips (and everything while Strict Mode is off) apply immediately.
    fun applyLockedToggle(toggle: LockedToggle, enable: Boolean, apply: () -> Unit) {
        if (SettingsWeakening.requiresUnlock(toggle, enable, strictModeEnabled)) {
            pendingUnlock = PendingSettingsUnlock(
                target = StrictModeChallenge.generate(strictModeLength),
                prompt = unlockPrompt(toggle),
                onUnlock = apply
            )
        } else {
            apply()
        }
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
                // "Granted but not connected" (see ProtectionStatus) means the switch reads on but
                // the process is dead — turning the permission on again is not the fix, because it
                // is already on; only toggling the service off/on (or a reboot) drops it out of
                // AOSP's crashed-services set and lets it rebind.
                description = if (permissionStates.accessibilityCrashed) {
                    "Enabled, but your phone stopped it — turn it off and back on to restart blocking."
                } else {
                    "Required to detect foreground apps"
                },
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

            // Turning Strict Mode ON is immediate; turning it OFF is gated by the challenge.
            val onStrictModeChange: (Boolean) -> Unit = { wantOn ->
                applyLockedToggle(LockedToggle.STRICT_MODE, wantOn) {
                    coroutineScope.launch { preferences.setStrictModeEnabled(wantOn) }
                }
            }

            ListItem(
                headlineContent = { Text("Lock my settings (Strict Mode)") },
                supportingContent = {
                    Text("While on, undoing any protection — or turning this off — requires typing the unlock challenge.")
                },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingContent = {
                    Switch(checked = strictModeEnabled, onCheckedChange = onStrictModeChange)
                },
                modifier = Modifier.clickable { onStrictModeChange(!strictModeEnabled) }
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

            // This toggle is the ONLY thing that decides whether the pass appears on block overlays
            // (v1.10.0 — Strict Mode no longer hides it). It stays live under Strict Mode: turning
            // the pass ON re-opens a one-tap bypass, so that direction takes the unlock challenge;
            // turning it OFF strengthens protection and is always free.
            val onEmergencyPassChange: (Boolean) -> Unit = { enable ->
                applyLockedToggle(LockedToggle.EMERGENCY_PASS, enable) {
                    coroutineScope.launch { preferences.setEmergencyPassEnabled(enable) }
                }
            }

            ListItem(
                headlineContent = { Text("Daily 2-minute pass") },
                supportingContent = {
                    Text(
                        "Allow one 2-minute escape a day, shared across all blocked apps." +
                            if (strictModeEnabled) " Turning it on requires the unlock challenge." else ""
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                trailingContent = {
                    Switch(checked = emergencyPassEnabled, onCheckedChange = onEmergencyPassChange)
                },
                modifier = Modifier.clickable { onEmergencyPassChange(!emergencyPassEnabled) }
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

/** A Strict-Mode-gated settings flip waiting on its typed unlock. */
private data class PendingSettingsUnlock(
    val target: String,
    val prompt: String,
    val onUnlock: () -> Unit
)

/**
 * Dialog copy per lockable toggle. Only the WEAKENING direction is ever gated
 * ([SettingsWeakening]), so each toggle needs exactly one prompt.
 */
private fun unlockPrompt(toggle: LockedToggle): String = when (toggle) {
    LockedToggle.STRICT_MODE -> "Turn off Strict Mode"
    LockedToggle.EMERGENCY_PASS -> "Turn on the daily 2-minute pass"
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

/**
 * The permission ticks this screen draws. See [rememberPermissionStates].
 *
 * [accessibility] is deliberately not "is the setting string on" — see [readAccessibilityState] —
 * and [accessibilityCrashed] carries the one state that string can never distinguish from healthy:
 * granted by the user, dead in reality.
 */
@Immutable
private data class PermissionStates(
    val accessibility: Boolean,
    val accessibilityCrashed: Boolean,
    val overlay: Boolean,
    val usageStats: Boolean
)

private fun readPermissionStates(context: Context): PermissionStates {
    val accessibility = readAccessibilityState(context)
    return PermissionStates(
        accessibility = accessibility.working,
        accessibilityCrashed = accessibility.crashed,
        overlay = Settings.canDrawOverlays(context),
        usageStats = hasUsageStatsPermission(context)
    )
}

/** [readAccessibilityState]'s two-boolean answer, shared by every refresh path below. */
private data class AccessibilityReadout(val working: Boolean, val crashed: Boolean)

/**
 * The accessibility half of [readPermissionStates], on its own because it needs care the other two
 * don't: `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is INTENT, not liveness (see the class doc
 * on [ProtectionStatus] for the AOSP trace — a killed service is left IN that string forever, which
 * is our 3-star review, "It doesn't work sometimes"). [ProtectionStatus.isAccessibilityServiceWorking]
 * is the "granted AND connected" answer a tick must show, but this screen also needs the OTHER half
 * of that same pair — granted but NOT connected, i.e. crashed — to tell the user the real fix. Both
 * halves come from the same two reads, taken once here, rather than calling
 * [ProtectionStatus.isAccessibilityServiceConnected] a second time to get what `isWorking` already
 * computed once.
 */
private fun readAccessibilityState(context: Context): AccessibilityReadout {
    val granted = ProtectionStatus.isAccessibilityServiceGranted(context)
    val connected = ProtectionStatus.isAccessibilityServiceConnected(context)
    return AccessibilityReadout(working = granted && connected, crashed = granted && !connected)
}

/**
 * Keeps the three permission ticks honest after they leave this screen's control — the defect this
 * fixes: `remember { mutableStateOf(x) }` reads `x` once at first composition and never again, so a
 * grant revoked behind the app's back (an in-place update silently disables the accessibility
 * service; an OEM battery-optimizer kills the process and it lands in AOSP's crashed set) left a
 * green tick over a dead permission with no way for the user to tell (docs/BACKLOG.md).
 *
 * Three refresh paths, because the three permissions don't share a way to be watched, and because
 * the accessibility one has TWO halves that change at different moments:
 * - Accessibility has [ProtectionStatus.ACCESSIBILITY_SERVICES_URI], a `Settings.Secure` key that a
 *   `ContentObserver` can watch LIVE — it fires the instant the user (or a force-stop) flips it,
 *   including while they're sitting in the system Settings screen in a split window right next to
 *   this one. It will NEVER fire for a crash, because AOSP leaves a crashed service IN that string —
 *   which is exactly why this observer still reads both halves of [readAccessibilityState] on every
 *   fire rather than trusting the string alone.
 * - The bind that FOLLOWS that string being written is asynchronous, so the observer above fires
 *   while the service is granted-but-not-yet-connected, indistinguishable from crashed. Nothing
 *   read again afterwards, so re-enabling the service without leaving this screen (a split window,
 *   `adb shell settings put`, a quick-settings tile) LATCHED the ✖ and the "turn it off and back
 *   on" copy over a service that had been bound for twenty seconds. [AccessibilityConnectionSignal]
 *   fires from the service's own `onServiceConnected`/`onDestroy`, which is the bind completing:
 *   the earliest correct moment to look again, with no interval to tune.
 * - Overlay and usage-stats grants are `AppOpsManager` modes with no equivalent watchable key, so
 *   they can only be re-checked on `ON_RESUME` — which is also exactly the moment the user lands
 *   back here after visiting either system settings page this screen sends them to.
 *
 * Accessibility is re-read on resume too, so a stale value from before the screen was backgrounded
 * is never trusted over a fresh one.
 */
@Composable
private fun rememberPermissionStates(context: Context): PermissionStates {
    var state by remember { mutableStateOf(readPermissionStates(context)) }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val accessibility = readAccessibilityState(context)
                state = state.copy(
                    accessibility = accessibility.working,
                    accessibilityCrashed = accessibility.crashed
                )
            }
        }
        context.contentResolver.registerContentObserver(
            ProtectionStatus.ACCESSIBILITY_SERVICES_URI,
            /* notifyForDescendants = */ false,
            observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // Collecting a StateFlow delivers its current value first, so a bind that happened between the
    // initial read above and this effect starting is picked up too rather than missed.
    LaunchedEffect(context) {
        AccessibilityConnectionSignal.generation.collect {
            val accessibility = readAccessibilityState(context)
            state = state.copy(
                accessibility = accessibility.working,
                accessibilityCrashed = accessibility.crashed
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = readPermissionStates(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
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
