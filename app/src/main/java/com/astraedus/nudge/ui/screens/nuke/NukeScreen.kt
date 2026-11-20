package com.astraedus.nudge.ui.screens.nuke

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.domain.nuke.NukeProfiles
import com.astraedus.nudge.ui.components.AppListItem
import com.astraedus.nudge.ui.nuke.NukeUnlockHost
import com.astraedus.nudge.ui.nuke.hasAnyCamera
import com.astraedus.nudge.ui.qr.QrCodeGenerator
import com.astraedus.nudge.ui.qr.QrShare
import com.astraedus.nudge.ui.qr.ScanQrContract

/**
 * The Nuke screen: the list, the paired code, and turning it on and off.
 *
 * One [LazyColumn] for the whole screen -- header items, then app rows -- rather than a
 * scrollable [androidx.compose.foundation.layout.Column] wrapped around a `LazyColumn`, which
 * would nest two scroll containers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NukeScreen(
    viewModel: NukeViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val scanRequest by viewModel.scanRequest.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val unlock by viewModel.unlock.collectAsStateWithLifecycle()
    val applied by viewModel.applied.collectAsStateWithLifecycle()
    val timerLeftMs by viewModel.timerLeftMs.collectAsStateWithLifecycle()

    // Which profile dialog is open. Names only, so they survive a rotation.
    var savingProfile by rememberSaveable { mutableStateOf(false) }
    var renamingProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingProfile by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val hasCamera = remember { hasAnyCamera(context) }

    var pendingPurpose by rememberSaveable { mutableStateOf<NukeScanPurpose?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanQrContract()) { payload ->
        pendingPurpose?.let { viewModel.onScanResult(it, payload) }
        pendingPurpose = null
    }

    LaunchedEffect(scanRequest) {
        val purpose = scanRequest ?: return@LaunchedEffect
        pendingPurpose = purpose
        val active = state.active
        val title = when (purpose) {
            NukeScanPurpose.TOGGLE ->
                if (active) "Scan your Nuke code to end Nuke" else "Scan your Nuke code to start Nuke"
            NukeScanPurpose.CONFIRM_NEW_QR -> "Scan the code you just saved"
            NukeScanPurpose.PAIR_EXISTING -> "Scan any QR code or barcode"
        }
        val subtitle = when (purpose) {
            NukeScanPurpose.TOGGLE -> if (active) "End Nuke" else "Start Nuke"
            NukeScanPurpose.CONFIRM_NEW_QR -> "Proves you kept it somewhere you can reach"
            NukeScanPurpose.PAIR_EXISTING -> "Any barcode you already have works"
        }
        scanner.launch(ScanQrContract.Request(title = title, subtitle = subtitle))
        viewModel.consumeScanRequest()
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    LaunchedEffect(applied) {
        val switched = applied ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Switched to “${switched.name}”.",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short
        )
        viewModel.consumeApplied()
        if (result == SnackbarResult.ActionPerformed) viewModel.undoApplyProfile(switched)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nuke") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            if (!state.introSeen) {
                item {
                    NukeIntroCard(onDismiss = viewModel::dismissIntro)
                }
            }

            item {
                NukeStatusCard(
                    state = state,
                    hasCamera = hasCamera,
                    onScanToggle = { viewModel.requestScan(NukeScanPurpose.TOGGLE) },
                    onNukeNow = viewModel::nukeNow,
                    onEmergencyEnd = viewModel::endWithEmergencyCode,
                    timerLeftMs = timerLeftMs,
                    onPickTimer = viewModel::setTimerChoice
                )
            }

            item {
                NukeKeyCard(
                    state = state,
                    hasCamera = hasCamera,
                    onCreateQr = viewModel::startCreateQr,
                    onPairExisting = viewModel::startPairExisting,
                    onUnpair = viewModel::unpair
                )
            }

            item {
                NukeListHeader(
                    canSave = state.canSaveProfile,
                    onSaveAsProfile = { savingProfile = true }
                )
            }

            item {
                Text(
                    "Your phone, messages, clock, launcher, keyboard and Settings are never on this list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Under the caption, so with no profiles the top of this section is exactly as before.
            if (state.profiles.isNotEmpty()) {
                item {
                    NukeProfileChips(
                        chips = state.profiles,
                        nukeActive = state.active,
                        onApply = viewModel::applyProfile,
                        onRename = { renamingProfile = it },
                        onDelete = { deletingProfile = it }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search apps…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true
                )
            }

            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(state.apps, key = { it.packageName }) { row ->
                // The whole row is the one toggle target (icon, name and box), as in AppPicker;
                // the box itself takes no clicks, so TalkBack reads one checkbox per app.
                AppListItem(
                    appName = row.label,
                    icon = row.icon,
                    modifier = Modifier.toggleable(
                        value = row.nuked,
                        role = Role.Checkbox,
                        onValueChange = { viewModel.toggleApp(row.packageName) }
                    ),
                    trailingContent = {
                        Checkbox(checked = row.nuked, onCheckedChange = null)
                    }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    val currentPairing = pairing
    if (currentPairing is NukePairing.ShowingNewQr) {
        NukePairingDialog(
            token = currentPairing.token,
            onSaveShare = {
                val bitmap = QrCodeGenerator.generate(currentPairing.token, 1024)
                QrShare.share(context, bitmap, "nudge-nuke-code.png", "Save your Nuke code")
            },
            onConfirmScan = { viewModel.requestScan(NukeScanPurpose.CONFIRM_NEW_QR) },
            onCancel = viewModel::cancelPairing
        )
    }

    if (savingProfile) {
        // Starts empty: the link is only offered when the list is not already a saved profile.
        // Typing an existing name saves over it ("Update").
        NukeProfileNameDialog(
            title = "Save as profile",
            initialName = "",
            confirmLabel = { name ->
                if (state.profiles.any { it.name.equals(NukeProfiles.normalizeName(name), ignoreCase = true) }) "Update" else "Save"
            },
            submit = viewModel::saveProfile,
            onDismiss = { savingProfile = false }
        )
    }

    renamingProfile?.let { oldName ->
        NukeProfileNameDialog(
            title = "Rename profile",
            initialName = oldName,
            confirmLabel = { "Rename" },
            submit = { newName -> viewModel.renameProfile(oldName, newName) },
            onDismiss = { renamingProfile = null }
        )
    }

    deletingProfile?.let { name ->
        NukeDeleteProfileDialog(
            name = name,
            onConfirm = {
                viewModel.deleteProfile(name)
                deletingProfile = null
            },
            onDismiss = { deletingProfile = null }
        )
    }

    NukeUnlockHost(
        state = unlock,
        onScanned = viewModel::onUnlockScanned,
        onUseEmergencyCode = viewModel::useEmergencyCode,
        onVerifyEmergency = viewModel::verifyEmergency,
        onBackToChoice = viewModel::backToUnlockChoice,
        onCancel = viewModel::cancelUnlock
    )
}
