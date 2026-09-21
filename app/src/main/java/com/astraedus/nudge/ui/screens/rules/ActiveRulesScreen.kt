package com.astraedus.nudge.ui.screens.rules

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.backup.BackupDialogs
import com.astraedus.nudge.ui.backup.BackupViewModel
import com.astraedus.nudge.ui.backup.rememberBackupActions
import com.astraedus.nudge.ui.components.StrictModeChallengeHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRulesScreen(
    viewModel: ActiveRulesViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRuleEditor: (String, Long) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    // Backup and restore are shared with Settings, ViewModel and all -- see
    // [com.astraedus.nudge.ui.backup.BackupViewModel].
    val backupViewModel: BackupViewModel = hiltViewModel()
    val backup = rememberBackupActions(backupViewModel)
    BackupDialogs(backupViewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Rules") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Save backup") },
                            onClick = {
                                showMenu = false
                                backup.saveBackup()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share backup") },
                            onClick = {
                                showMenu = false
                                backup.shareBackup()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import backup") },
                            onClick = {
                                showMenu = false
                                backup.importBackup()
                            }
                        )
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (state.groups.isEmpty() && !state.isLoading) {
                Text(
                    "No active rules yet. Go to Manage Apps to add some.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.groups.forEach { group ->
                AppRuleCard(
                    group = group,
                    onToggle = { viewModel.toggleAppEnabled(group.packageName, group.enabled) },
                    onClick = { onNavigateToRuleEditor(group.packageName, 0L) }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppRuleCard(
    group: ActiveRulesGroup,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (group.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            val icon = group.appIcon
            if (icon != null) {
                val bitmap = remember(icon) { icon.toBitmap(48, 48).asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = group.appName,
                    modifier = Modifier.size(40.dp),
                    alpha = if (group.enabled) 1f else 0.4f
                )
                Spacer(Modifier.width(12.dp))
            }

            // App name + summary
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    group.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (group.enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    group.summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (group.enabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Toggle switch for all rules
            Switch(
                checked = group.enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

