package com.astraedus.nudge.ui.backup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.usecase.ExportRulesUseCase
import com.astraedus.nudge.domain.usecase.ImportOutcome
import com.astraedus.nudge.domain.usecase.ImportPreview
import com.astraedus.nudge.domain.usecase.ImportRulesUseCase
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Immutable
data class BackupUiState(
    val importPreview: ImportPreview? = null,
    val importOutcome: ImportOutcome? = null,
    val importError: String? = null,
    /** Result of a "Save backup", shown as its own dialog. Null = nothing to report. */
    val saveMessage: String? = null
)

/**
 * Backup and restore, for every screen that offers it.
 *
 * Deliberately its OWN ViewModel rather than a second copy of these methods per screen: backup is
 * reachable from the Active Rules overflow menu AND from Settings (it used to be findable only via
 * a stat card -- a trained QA agent searching exhaustively concluded the feature did not exist),
 * and the Strict Mode gate below is exactly the kind of logic that must not exist twice. It also
 * costs a hosting screen nothing it does not need: Settings gets backup without dragging in the
 * installed-app scan and the rules flow that `ActiveRulesViewModel` exists for.
 *
 * Every platform side-effect -- reading the picked file, writing the chosen document, handing the
 * file to the share sheet -- arrives as a lambda from the screen, so this class stays JVM-testable
 * and the `Uri` plumbing stays where the `Context` is.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val exportRulesUseCase: ExportRulesUseCase,
    private val importRulesUseCase: ImportRulesUseCase,
    nudgePreferences: NudgePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening import is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    // --- Export ---

    /**
     * Writes the backup to a document the user picked, via [write].
     *
     * The export is built only once a destination exists, so backing out of the file picker costs
     * nothing -- serializing an unbounded history is real work (see [ExportRulesUseCase]).
     *
     * [write] reports whether the bytes landed; a storage provider can refuse, and a backup the
     * user believes they have is worse than one they know they do not.
     */
    fun saveBackup(write: suspend (String) -> Boolean) {
        viewModelScope.launch {
            val json = exportRulesUseCase.invoke()
            val saved = write(json)
            _uiState.value = _uiState.value.copy(
                saveMessage = if (saved) SAVE_SUCCESS_MESSAGE else SAVE_FAILURE_MESSAGE
            )
        }
    }

    /**
     * Hands the backup to [share] (the system share sheet).
     *
     * Kept alongside "Save backup" rather than replaced by it: sending the file straight to another
     * device or a password manager is a legitimate destination, it just cannot be the ONLY one --
     * "save to this phone" is not an `ACTION_SEND` target on Android.
     */
    fun shareBackup(share: suspend (String) -> Unit) {
        viewModelScope.launch { share(exportRulesUseCase.invoke()) }
    }

    fun dismissSaveMessage() {
        _uiState.value = _uiState.value.copy(saveMessage = null)
    }

    // --- Import ---

    /**
     * Reads and previews an import file.
     *
     * [readJson] is a lambda rather than a String because the file is read on the IO dispatcher
     * here: an export carries the user's whole block history, so both the read and the parse are
     * unbounded work that must not run on the UI thread from a file-picker callback.
     */
    fun previewImport(readJson: suspend () -> String?) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { readJson() }
            if (json == null) {
                _uiState.value = _uiState.value.copy(
                    importError = "Could not read that file.",
                    importPreview = null
                )
                return@launch
            }
            val preview = importRulesUseCase.preview(json)
            val error = preview.result.error
            _uiState.value = if (error != null) {
                _uiState.value.copy(importError = error, importPreview = null)
            } else {
                _uiState.value.copy(importPreview = preview, importError = null)
            }
        }
    }

    /**
     * Writes the previewed import.
     *
     * A backup carries the user's app SETTINGS as well as their rules, so an import can WEAKEN
     * protection -- a hand-edited `"strictModeEnabled": false` would otherwise be a one-tap way out
     * of the commitment lock. When it does, the whole import goes through the same [StrictModeGate]
     * every other weakening action uses.
     *
     * The WHOLE import is gated, not just the settings step: a half-applied restore (rules in,
     * settings out) is a state the user's backup never described, and gating everything is the
     * fail-closed reading. Rules-only and history-only files never weaken anything, so the common
     * case is untouched, as is every file written before settings existed.
     *
     * The confirmation dialog is dismissed up front, so the challenge dialog does not stack on top
     * of it; cancelling the challenge leaves the device exactly as it was.
     */
    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        _uiState.value = _uiState.value.copy(importPreview = null)
        viewModelScope.launch {
            val write: suspend () -> Unit = {
                val outcome = importRulesUseCase.execute(preview.result)
                _uiState.value = _uiState.value.copy(importOutcome = outcome)
            }
            if (importRulesUseCase.weakensProtection(preview.result)) {
                strictModeGate.run(prompt = "Import settings that reduce protection", action = write)
            } else {
                write()
            }
        }
    }

    fun cancelImport() {
        _uiState.value = _uiState.value.copy(importPreview = null, importError = null)
    }

    fun clearImportOutcome() {
        _uiState.value = _uiState.value.copy(importOutcome = null, importError = null)
    }

    // --- Strict Mode challenge ---

    /** Called from the challenge dialog; runs the pending import on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. The import never runs. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }

    companion object {
        const val SAVE_SUCCESS_MESSAGE =
            "Backup saved. It holds your rules, your history and your app settings."

        /**
         * Named as a failure rather than reported as nothing: a save that quietly did not happen
         * leaves the user believing they have a backup, which is the one thing worse than not
         * having one.
         */
        const val SAVE_FAILURE_MESSAGE =
            "Could not write the backup there. Try again, or pick a different folder."
    }
}
