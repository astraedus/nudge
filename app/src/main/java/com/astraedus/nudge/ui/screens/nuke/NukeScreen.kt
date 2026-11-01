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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                    onEmergencyEnd = viewModel::endWithEmergencyCode
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
                Text(
                    "Apps to nuke",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            item {
                Text(
                    "Your phone, messages, clock, launcher, keyboard and Settings are never on this list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                AppListItem(
                    appName = row.label,
                    icon = row.icon,
                    trailingContent = {
                        Checkbox(
                            checked = row.nuked,
                            onCheckedChange = { viewModel.toggleApp(row.packageName) }
                        )
                    },
                    onClick = { viewModel.toggleApp(row.packageName) }
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

    NukeUnlockHost(
        state = unlock,
        onScanned = viewModel::onUnlockScanned,
        onUseEmergencyCode = viewModel::useEmergencyCode,
        onVerifyEmergency = viewModel::verifyEmergency,
        onBackToChoice = viewModel::backToUnlockChoice,
        onCancel = viewModel::cancelUnlock
    )
}
