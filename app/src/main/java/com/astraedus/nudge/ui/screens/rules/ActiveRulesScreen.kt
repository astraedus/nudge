package com.astraedus.nudge.ui.screens.rules

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRulesScreen(
    viewModel: ActiveRulesViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRuleEditor: (String, Long) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { readJsonFromUri(context, it) }?.let { json ->
            viewModel.previewImport(json)
        }
    }

    // Handle export: write to cache dir and share
    val exportJson = state.exportJson
    if (exportJson != null) {
        shareExportJson(context, exportJson)
        viewModel.clearExport()
    }

    // Import confirmation dialog
    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Import Rules") },
            text = {
                Text(
                    "Import ${preview.rules.size} rule(s)" +
                        if (preview.groups.isNotEmpty()) " and ${preview.groups.size} group(s)" else "" +
                        "?\n\nDuplicate rules will be skipped."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import result dialog
    state.importOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { viewModel.clearImportOutcome() },
            title = { Text("Import Complete") },
            text = {
                val message = buildString {
                    append("Imported: ${outcome.importedCount} rule(s)")
                    if (outcome.skippedCount > 0) append("\nSkipped (duplicates): ${outcome.skippedCount}")
                    if (outcome.groupsCreated > 0) append("\nGroups created: ${outcome.groupsCreated}")
                }
                Text(message)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportOutcome() }) {
                    Text("OK")
                }
            }
        )
    }

    // Import error dialog
    state.importError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearImportOutcome() },
            title = { Text("Import Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportOutcome() }) {
                    Text("OK")
                }
            }
        )
    }

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
                            text = { Text("Export Rules") },
                            onClick = {
                                showMenu = false
                                viewModel.exportRules()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import Rules") },
                            onClick = {
                                showMenu = false
                                importLauncher.launch(arrayOf("application/json", "*/*"))
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

private fun readJsonFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        null
    }
}

private fun shareExportJson(context: Context, json: String) {
    val file = File(context.cacheDir, "nudge-rules-export.json")
    file.writeText(json)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, "Export Rules"))
}
