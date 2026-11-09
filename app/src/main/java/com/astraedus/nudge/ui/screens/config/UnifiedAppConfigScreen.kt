package com.astraedus.nudge.ui.screens.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.blockModeDescription
import com.astraedus.nudge.domain.model.FeatureMode
import com.astraedus.nudge.ui.components.DurationInput
import com.astraedus.nudge.ui.components.MinutesField
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.components.UsageReminderSection
import androidx.compose.ui.graphics.vector.ImageVector
import com.astraedus.nudge.ui.components.TonalIcon
import com.astraedus.nudge.ui.components.rule.AllAppsIcon
import com.astraedus.nudge.ui.components.rule.BlockModePicker
import com.astraedus.nudge.ui.components.rule.FirstUnlockFields
import com.astraedus.nudge.ui.components.rule.DailyLimitChips
import com.astraedus.nudge.ui.components.rule.DayStartDialog
import com.astraedus.nudge.ui.components.rule.InfoButton
import com.astraedus.nudge.ui.components.rule.MirrorCameraNote
import com.astraedus.nudge.ui.components.rule.rememberMirrorCameraAccess
import com.astraedus.nudge.ui.components.rule.shouldRequestCameraFor
import com.astraedus.nudge.ui.components.rule.ScheduleSection
import com.astraedus.nudge.ui.components.rule.WaitTimeChips
import com.astraedus.nudge.ui.hasGrayscalePermission
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnifiedAppConfigScreen(
    viewModel: UnifiedAppConfigViewModel,
    onNavigateBack: () -> Unit,
    /** Opens a group that covers this app, from "Also covered by". */
    onOpenGroup: (Long) -> Unit,
    /** Opens an All apps rule that covers this app, from "Also covered by". */
    onOpenAllAppsRule: (Long) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val coveredBy by viewModel.coveredBy.collectAsStateWithLifecycle()
    val coveredByAllApps by viewModel.coveredByAllApps.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDayStartDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onNavigateBack()
    }

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.appName.ifEmpty { state.packageName })
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) {
                        Text("Save")
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ MASTER TOGGLE ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
            }

            if (coveredBy.isNotEmpty() || coveredByAllApps.isNotEmpty()) {
                AlsoCoveredBy(
                    groups = coveredBy,
                    allApps = coveredByAllApps,
                    onOpenGroup = onOpenGroup,
                    onOpenAllAppsRule = onOpenAllAppsRule
                )
            }

            HorizontalDivider()

            // ═══ ALWAYS ACTIVE ═══
            SectionHeader("Always Active")

            // Daily Time Limit
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Daily Time Limit",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Set a daily usage budget for this app.\n\n" +
                            "Once you've used the app for this many minutes today, it switches to a hard block for the rest of the day. " +
                            "The block lands as soon as the budget runs out, even if you're still inside the app." +
                            // Honest about the one direction that does not work rather than letting
                            // the budget silently never count web time. The reverse DOES work and is
                            // worth saying: spending the budget in the app closes the website too.
                            if (state.webDomainEnabled) {
                                "\n\nThis budget counts time in the app only. Once it runs out the " +
                                    "websites below are hard-blocked as well, but time spent on " +
                                    "them does not yet count towards it -- use Auto-kick's time " +
                                    "trigger to limit the websites."
                            } else {
                                ""
                            }
                        )
                    }
                    Switch(
                        checked = state.dailyLimitEnabled,
                        onCheckedChange = viewModel::setDailyLimitEnabled
                    )
                }

                if (state.dailyLimitEnabled) {
                    DailyLimitChips(
                        minutes = state.dailyLimitMinutes,
                        onSelect = viewModel::setDailyLimitMinutes,
                        customDialogTitle = "Custom Daily Limit"
                    )

                    // Show time remaining sub-toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Show time remaining",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            InfoButton(
                                "Displays remaining daily time as a floating overlay.\n\n" +
                                "Changes color as time runs out: green (>50%), orange (25-50%), red (<25%)."
                            )
                        }
                        Switch(
                            checked = state.showTimeRemaining,
                            onCheckedChange = viewModel::setShowTimeRemaining
                        )
                    }
                }
            }

            // Interaction counter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Interaction counter",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    InfoButton(
                        "Shows a floating counter while you use this app.\n\n" +
                        "For YouTube/Instagram/TikTok: counts Reels or Shorts scrolled.\n" +
                        "For other apps: counts screen taps.\n\n" +
                        "Turns orange at 10, deep orange at 20, red at 30."
                    )
                }
                Switch(
                    checked = state.showCounter,
                    onCheckedChange = viewModel::setShowCounter
                )
            }

            // Grayscale
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Grayscale",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Makes your screen black-and-white while this app is open.\n\n" +
                            "Removes the color reward that makes scrolling engaging.\n\n" +
                            "Requires one-time ADB setup -- check Settings for the guide."
                        )
                    }
                    Text(
                        // Honest about the limitation rather than promising a setting that
                        // silently does nothing: grayscale rides inside a BlockDecision.Block, so
                        // with the app itself unblocked only a feature override can trigger it.
                        // See BlockMode.NONE.
                        if (state.blocksWholeApp) {
                            "Requires ADB permission setup"
                        } else {
                            "Requires ADB permission setup. With the whole app unblocked, this " +
                                "only applies while a blocked feature below is on screen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.grayscale,
                    onCheckedChange = { enabled ->
                        if (enabled && !hasGrayscalePermission(context)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Grayscale requires setup -- check Settings"
                                )
                            }
                        } else {
                            viewModel.setGrayscale(enabled)
                        }
                    }
                )
            }

            // Web domain blocking
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Block on web too",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Also blocks this app's website in the browser.\n\n" +
                            "Works even with \"Block the whole app\" off — the app opens " +
                            "normally while the website is still blocked, with the mode you " +
                            "pick here.\n\n" +
                            "Currently supports Chrome only."
                        )
                    }
                    Switch(
                        checked = state.webDomainEnabled,
                        onCheckedChange = viewModel::setWebDomainEnabled
                    )
                }

                if (state.webDomainEnabled) {
                    OutlinedTextField(
                        value = state.webDomains,
                        onValueChange = viewModel::setWebDomains,
                        label = { Text("Domains (comma-separated)") },
                        placeholder = { Text("instagram.com, www.instagram.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Subdomains like www. and m. are matched automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Websites enforce independently of the app-level mode (issue #21). While the
                    // app itself is blocked they simply follow its mode; when it is not, there is
                    // no app-level mode to follow, so the website mode is chosen here instead of
                    // silently enforcing nothing (which is what used to happen).
                    if (state.blocksWholeApp) {
                        Text(
                            "Websites use the same block mode as the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Website block mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        BlockModePicker(
                            selected = state.webBlockMode,
                            onSelect = viewModel::setWebBlockMode
                        )
                        Text(
                            "${state.appName} opens normally; these websites are still blocked. " +
                                blockModeDescription(state.webBlockMode, manyApps = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // ═══ DEFAULT BEHAVIOR ═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SectionHeader("Default Behavior")
                InfoButton("These settings apply whenever no scheduled override is active.")
            }

            // Whether the app itself is gated at all. Off => the app-level rule is BlockMode.NONE,
            // so the app opens freely and only the feature overrides below apply. This is the
            // switch that makes "block only Shorts, leave YouTube alone" expressible.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block the whole app", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.blocksWholeApp) {
                            "Opening ${state.appName} triggers the block below."
                        } else if (state.supportsFeatures) {
                            "${state.appName} opens normally. Only the features you turn on below are blocked."
                        } else {
                            "${state.appName} opens normally. Any daily limit below still applies."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.blocksWholeApp,
                    onCheckedChange = { viewModel.setBlocksWholeApp(it) }
                )
            }

            // Block mode picker: only meaningful when the app itself is blocked.
            if (state.blocksWholeApp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockModePicker(
                        selected = state.defaultMode,
                        onSelect = viewModel::setDefaultMode,
                        compact = true
                    )

                    Text(
                        blockModeDescription(state.defaultMode, manyApps = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Delay duration (if applicable). One `delaySeconds` is stored per rule and it feeds
            // whichever delay is live — the app's, or (with whole-app blocking off) the website
            // one — so the control has to stay reachable in the web-only case too, else a web
            // DELAY would be permanently stuck at whatever was last saved.
            if (state.showDelayDuration) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        state.delayDurationLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    WaitTimeChips(
                        seconds = state.defaultDelaySeconds,
                        onSelect = viewModel::setDefaultDelaySeconds,
                        customDialogTitle = "Custom ${state.delayDurationLabel}"
                    )
                }
            }

            // Keep the pass on short trips (#110), per app, opt-in. Only where opening the app
            // earns a pass (Delay, Hold, Breathing): a hard block has nothing to keep. Label only,
            // the details live behind the info button.
            if (state.showKeepPassOnShortTrips) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Keep the pass on short trips",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Once you have waited, going to the home screen or switching apps for a " +
                                "moment keeps your pass. Come back within 2 minutes and the app opens " +
                                "straight away.\n\n" +
                                "Off: going to the home screen ends the pass, and the next open waits again."
                        )
                    }
                    Switch(
                        checked = state.keepPassOnShortTrips,
                        onCheckedChange = viewModel::setKeepPassOnShortTrips
                    )
                }
            }

            // Auto-kick
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Auto-kick",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        InfoButton(
                            "Sends you to the home screen after a set number of scrolls/taps, or after a set amount " +
                            "of time in the app -- whichever fires first.\n\n" +
                            "The counter and timer reset when you re-open the app. This is the nuclear option for stopping infinite scroll." +
                            // The two triggers differ on the web: the timer measures browser time on
                            // the blocked site, while scrolls/taps arrive carrying the browser's
                            // package, not the site's, so they cannot be attributed to it.
                            if (state.webDomainEnabled) {
                                "\n\nThe time trigger also covers the websites below -- each site " +
                                    "gets its own timer and its own cooldown. The interaction " +
                                    "trigger is app-only."
                            } else {
                                ""
                            }
                        )
                    }
                    Switch(
                        checked = state.defaultAutoKickEnabled,
                        onCheckedChange = viewModel::setDefaultAutoKickEnabled
                    )
                }

                if (state.defaultAutoKickEnabled) {
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Whichever trigger fires first sends you to the home screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "After N interactions",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = state.defaultAutoKickByInteractions,
                                onCheckedChange = viewModel::setDefaultAutoKickByInteractions
                            )
                        }

                        if (state.defaultAutoKickByInteractions) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "After ${state.defaultAutoKickAfter} interactions",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = state.defaultAutoKickAfter.toFloat(),
                                    onValueChange = { viewModel.setDefaultAutoKickAfter(it.toInt()) },
                                    valueRange = 5f..100f,
                                    steps = 18,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        MinutesField(
                            value = state.defaultAutoKickAfterMinutesText,
                            onValueChange = viewModel::setDefaultAutoKickAfterMinutesText,
                            labelText = "Or after this long in the app",
                            supportingText = "Counts foreground time in one session. Leave blank for off."
                        )

                        MinutesField(
                            value = state.defaultAutoKickCooldownMinutesText,
                            onValueChange = viewModel::setDefaultAutoKickCooldownMinutesText,
                            labelText = "Cooldown",
                            supportingText = "Wait this long before you can re-open the app after an auto-close."
                        )
                    }
                }
            }

            // Usage reminder (#77): next to auto-kick, on the same clock, and never instead of it.
            UsageReminderSection(
                enabled = state.reminderEnabled,
                minutesText = state.remindAfterMinutesText,
                // The kick this rule will SAVE with, so the hint matches what the engine will do.
                kickMinutes = if (state.defaultAutoKickEnabled) {
                    DurationInput.resolveMinutes(
                        state.defaultAutoKickAfterMinutesText,
                        state.originalAutoKickAfterMinutes
                    )
                } else null,
                websitesConfigured = state.webDomainEnabled,
                onEnabledChange = viewModel::setReminderEnabled,
                onMinutesTextChange = viewModel::setRemindAfterMinutesText,
                info = { InfoButton(it) }
            )

            // ═══ FEATURE OVERRIDES ═══
            if (state.supportsFeatures) {
                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SectionHeader("Feature Rules")
                    InfoButton("Override behavior for specific app features. 'Inherit' uses the default behavior above.")
                }

                state.availableFeatures.forEach { feature ->
                    FeatureOverrideCard(
                        featureName = feature.displayName,
                        override = state.featureOverrides[feature.key] ?: FeatureOverride(),
                        onUpdate = { viewModel.setFeatureOverride(feature.key, it) }
                    )
                }

                // Tab Vanish -- covers the in-app tab (e.g. Instagram's Reels tab) while a rule
                // covering that feature resolves to a hard block, so the icon disappears rather
                // than merely refusing to open. Visibility asks the registry (supportsTabVanish),
                // never a hardcoded package check.
                if (state.supportsTabVanish) {
                    val vanishLabel = state.vanishableFeatureLabel ?: "Reels"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hide the $vanishLabel tab", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "While $vanishLabel is blocked, Nudge covers the $vanishLabel tab in " +
                                    "Instagram's bottom bar, so the icon is not there and tapping it does " +
                                    "nothing. The rest of Instagram works as normal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.tabVanish,
                            onCheckedChange = viewModel::setTabVanish
                        )
                    }
                }

                // Following steer -- experimental, default off. Opens Instagram's Following feed
                // instead of Home. Visibility asks the registry (supportsFollowingSteer).
                if (state.supportsFollowingSteer) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Open to Following instead of Home",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Experimental. When you open Instagram's home feed, Nudge switches it to " +
                                    "Following, so you see posts from people you follow rather than " +
                                    "suggested ones. You can switch back at any time. If Instagram changes " +
                                    "its layout this quietly stops working.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.followingSteer,
                            onCheckedChange = viewModel::setFollowingSteer
                        )
                    }
                }
            }

            HorizontalDivider()

            // ═══ SCHEDULED OVERRIDE ═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SectionHeader("Scheduled Override")
                InfoButton("Apply different settings during specific times. Outside this schedule, the default behavior above is used.")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enable schedule",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = state.scheduledOverrideEnabled,
                    onCheckedChange = viewModel::setScheduledOverrideEnabled
                )
            }

            if (state.scheduledOverrideEnabled) {
                val scheduleKinds = listOf(
                    "Days and times" to false,
                    "After first unlock" to true
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    scheduleKinds.forEachIndexed { index, (label, isFirstUnlock) ->
                        SegmentedButton(
                            selected = state.scheduledOverrideFirstUnlock == isFirstUnlock,
                            onClick = { viewModel.setScheduledOverrideFirstUnlock(isFirstUnlock) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = scheduleKinds.size
                            )
                        ) {
                            Text(label)
                        }
                    }
                }

                if (!state.scheduledOverrideFirstUnlock) {
                    ScheduleSection(
                        days = state.scheduleDays,
                        onDaysChange = viewModel::setScheduleDays,
                        startHour = state.scheduleStartHour,
                        startMinute = state.scheduleStartMinute,
                        onStartTimeChange = viewModel::setScheduleStartTime,
                        endHour = state.scheduleEndHour,
                        endMinute = state.scheduleEndMinute,
                        onEndTimeChange = viewModel::setScheduleEndTime,
                        mode = state.scheduledMode,
                        onModeChange = viewModel::setScheduledMode,
                        waitSeconds = state.scheduledDelaySeconds,
                        onWaitSecondsChange = viewModel::setScheduledDelaySeconds
                    ) {
                        ScheduledFeatureOverridesSection(
                            state = state,
                            onUpdate = viewModel::setScheduledFeatureOverride
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BlockModePicker(
                            selected = state.scheduledMode,
                            onSelect = viewModel::setScheduledMode
                        )
                        if (state.scheduledMode != BlockMode.HARD_BLOCK) {
                            WaitTimeChips(
                                seconds = state.scheduledDelaySeconds,
                                onSelect = viewModel::setScheduledDelaySeconds,
                                customDialogTitle = "Custom ${state.delayDurationLabel}"
                            )
                        }
                        // The same layout the group and All apps editors use, labels to the
                        // left: one first-unlock row for all three editors (review should-fix 3).
                        FirstUnlockFields(
                            days = state.scheduleDays,
                            onDaysChange = viewModel::setScheduleDays,
                            firstUnlockMinutes = state.firstUnlockMinutes,
                            onFirstUnlockMinutesChange = viewModel::setFirstUnlockMinutes,
                            dayStartMinute = state.dayStartMinute,
                            onDayStartClick = { showDayStartDialog = true },
                            startsAtNextUnlock = state.startsAtNextUnlock
                        )
                        ScheduledFeatureOverridesSection(
                            state = state,
                            onUpdate = viewModel::setScheduledFeatureOverride
                        )
                    }
                }
            }

            if (showDayStartDialog) {
                DayStartDialog(
                    minute = state.dayStartMinute,
                    onPick = viewModel::setDayStartMinute,
                    onDismiss = { showDayStartDialog = false }
                )
            }

            // ═══ DANGER ZONE ═══
            if (state.hasExistingRules) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = viewModel::showDeleteConfirmation,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove All Rules")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Delete confirmation dialog
    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("Remove all rules?") },
            text = {
                Text(
                    "This will delete all rules for ${state.appName.ifEmpty { state.packageName }}. " +
                    "The app will no longer be blocked."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissDeleteConfirmation()
                        viewModel.deleteAllRules()
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Feature overrides inside a scheduled override, shared by the days-and-times branch and the
 * first-unlock branch.
 */
@Composable
private fun ScheduledFeatureOverridesSection(
    state: UnifiedAppConfigState,
    onUpdate: (String, FeatureOverride) -> Unit
) {
    if (!state.supportsFeatures) return
    Spacer(Modifier.height(8.dp))
    Text(
        "Feature overrides during schedule",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    state.availableFeatures.forEach { feature ->
        FeatureOverrideCard(
            featureName = feature.displayName,
            override = state.scheduledFeatureOverrides[feature.key] ?: FeatureOverride(),
            onUpdate = { onUpdate(feature.key, it) }
        )
    }
}

// ═══ Reusable composables ═══

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureOverrideCard(
    featureName: String,
    override: FeatureOverride,
    onUpdate: (FeatureOverride) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                featureName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            // Mode selector. Picking Mirror asks for the camera, as the app-level picker does.
            val camera = rememberMirrorCameraAccess()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FeatureMode.entries.forEach { mode ->
                    FilterChip(
                        selected = override.mode == mode,
                        onClick = {
                            onUpdate(override.copy(mode = mode))
                            val blockMode = mode.toBlockMode()
                            if (blockMode != null && shouldRequestCameraFor(blockMode, camera.granted)) {
                                camera.requestIfNeeded()
                            }
                        },
                        label = {
                            Text(
                                featureModeLabel(mode),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                }
            }
            if (override.mode == FeatureMode.MIRROR) MirrorCameraNote(camera)

            // Expanded settings for every mode that spends a duration.
            if (override.mode.usesDuration) {
                // Delay duration chips
                WaitTimeChips(
                    seconds = override.delaySeconds,
                    onSelect = { onUpdate(override.copy(delaySeconds = it)) }
                )

                // Auto-kick toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-kick", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = override.autoKickEnabled,
                        onCheckedChange = { onUpdate(override.copy(autoKickEnabled = it)) }
                    )
                }

                if (override.autoKickEnabled) {
                    Text(
                        "After ${override.autoKickAfter} scrolls",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = override.autoKickAfter.toFloat(),
                        onValueChange = { onUpdate(override.copy(autoKickAfter = it.toInt())) },
                        valueRange = 5f..100f,
                        steps = 18,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MinutesField(
                        value = override.autoKickCooldownMinutesText,
                        onValueChange = { onUpdate(override.copy(autoKickCooldownMinutesText = it)) },
                        labelText = "Cooldown"
                    )
                }
            }
        }
    }
}

/**
 * The words on an in-app FEATURE override chip. Separate from [blockModeLabel] only because
 * [FeatureMode] carries [FeatureMode.INHERIT], which is not a block mode at all; the rest read the
 * same as the app-level picker on purpose.
 */
private fun featureModeLabel(mode: FeatureMode): String = when (mode) {
    FeatureMode.INHERIT -> "Inherit"
    FeatureMode.BLOCK -> "Block"
    FeatureMode.DELAY -> "Delay"
    FeatureMode.HOLD -> "Hold"
    FeatureMode.BREATHING -> "Breathing"
    FeatureMode.MIRROR -> "Mirror"
}

/**
 * "Also covered by" (spec 3.7): the groups that block this app as well, so "why is it blocked when
 * its own switch is off?" answers itself. Read-only; each row opens that group.
 */
@Composable
private fun AlsoCoveredBy(
    groups: List<CoveringGroup>,
    allApps: List<CoveringAllApps>,
    onOpenGroup: (Long) -> Unit,
    onOpenAllAppsRule: (Long) -> Unit
) {
    Column {
        Text(
            "Also covered by",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        groups.forEach { group ->
            // The same 40dp tonal folder a group row has on Manage Apps: one look per group.
            CoveringRow(Icons.Default.Folder, group.label) { onOpenGroup(group.groupId) }
        }
        allApps.forEach { rule ->
            CoveringRow(AllAppsIcon, rule.label) { onOpenAllAppsRule(rule.ruleId) }
        }
    }
}

@Composable
private fun CoveringRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TonalIcon(icon)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

