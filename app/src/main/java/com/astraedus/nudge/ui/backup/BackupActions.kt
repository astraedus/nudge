package com.astraedus.nudge.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The three things a screen can offer: save a backup to this device, send one somewhere, restore
 * from one. Drop them into a menu, a settings list, or anything else.
 */
@Immutable
data class BackupActions(
    val saveBackup: () -> Unit,
    val shareBackup: () -> Unit,
    val importBackup: () -> Unit
)

/**
 * Registers the two document pickers and returns the actions that drive them.
 *
 * Pair it with [BackupDialogs] on the same [viewModel] -- this half opens pickers, that half shows
 * everything the user is told afterwards:
 *
 * ```
 * val backupViewModel: BackupViewModel = hiltViewModel()
 * val backup = rememberBackupActions(backupViewModel)
 * BackupDialogs(backupViewModel)
 * ```
 *
 * **Save uses `ACTION_CREATE_DOCUMENT`, not the share sheet.** "Save to device" is not an
 * `ACTION_SEND` target on Android: enumerated on a real phone, an export's share sheet was Drive,
 * Gmail, KDE Connect, Telegram, Bitwarden and Discord -- every one of them sending the file to a
 * cloud or another device. A zero-internet-permission privacy app has to be able to put a backup
 * in the user's own Downloads folder, and the file the share path writes lives in `cacheDir`,
 * where the system may evict it.
 */
@Composable
fun rememberBackupActions(viewModel: BackupViewModel): BackupActions {
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri: Uri? ->
        // Null = the user backed out of the picker. Nothing is exported in that case: building the
        // file is unbounded work, so it happens only once there is somewhere to put it.
        uri?.let { target -> viewModel.saveBackup { json -> writeJsonToUri(context, target, json) } }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // The read is handed to the ViewModel rather than done here: an export carries the whole
        // block history, so reading and parsing it must not run on the UI thread inside a
        // file-picker callback.
        uri?.let { picked -> viewModel.previewImport { readJsonFromUri(context, picked) } }
    }

    return remember(viewModel, saveLauncher, importLauncher, context) {
        BackupActions(
            saveBackup = { saveLauncher.launch(backupFileName()) },
            shareBackup = { viewModel.shareBackup { json -> shareBackupJson(context, json) } },
            // "*/*" alongside the JSON type because some file providers report a backup as
            // octet-stream, and a file the user cannot select is a restore they cannot do.
            importBackup = { importLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "*/*")) }
        )
    }
}

/**
 * Everything backup tells the user: the import preview, its result, its errors, the save result,
 * and the Strict Mode challenge an import that weakens protection has to pass.
 *
 * Separate from [rememberBackupActions] so neither call hides the other's job -- one opens system
 * pickers, this one renders dialogs.
 */
@Composable
fun BackupDialogs(viewModel: BackupViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Import backup") },
            text = { Text(buildImportPreviewMessage(preview)) },
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

    state.importOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { viewModel.clearImportOutcome() },
            title = { Text("Import Complete") },
            text = { Text(buildImportOutcomeMessage(outcome)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportOutcome() }) {
                    Text("OK")
                }
            }
        )
    }

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

    state.saveMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveMessage() },
            title = { Text("Save backup") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSaveMessage() }) {
                    Text("OK")
                }
            }
        )
    }
}

private suspend fun readJsonFromUri(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

/**
 * Writes [json] to the document the user chose. Returns false if nothing landed there.
 *
 * Mode "wt" TRUNCATES: overwriting yesterday's larger backup in "w" mode would leave its tail
 * behind and produce a file that is no longer valid JSON -- a corrupt backup that looks saved.
 */
private suspend fun writeJsonToUri(context: Context, uri: Uri, json: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray())
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

/** Writes the backup to the cache dir (off the main thread) and offers it to the share sheet. */
private suspend fun shareBackupJson(context: Context, json: String) {
    val uri = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, backupFileName())
        file.writeText(json)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = BACKUP_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, "Share backup"))
}
