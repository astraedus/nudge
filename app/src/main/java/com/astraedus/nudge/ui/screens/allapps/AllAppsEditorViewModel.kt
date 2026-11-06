package com.astraedus.nudge.ui.screens.allapps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.RuleArming
import com.astraedus.nudge.data.repository.target
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.packages.PackageFacts
import com.astraedus.nudge.domain.usecase.SetDayStartMinuteUseCase
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.components.PickerSelection
import com.astraedus.nudge.ui.lock.StrictModeGate
import com.astraedus.nudge.ui.screens.groups.membersAfterPick
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The All apps editor (spec 3.4): one "everything except these" rule, with its allowed apps and
 * groups, how it blocks and when, saved as one write. Strict Mode gates a save that makes the rule
 * protect less (an allowed app or group added, off, softer, a shorter window) and deleting a rule
 * that is on (spec 5.6); the decision itself is the pure [allAppsSaveWeakens] / [allAppsDeleteWeakens].
 *
 * The rule is read once; the rule tables (rules, groups, members) are read live, so "Blocks N apps",
 * a group's member count and "Also blocked by" stay right after a trip to the group editor.
 */
@HiltViewModel
class AllAppsEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val essentialApps: EssentialApps,
    private val packageFacts: PackageFacts,
    private val nudgePreferences: NudgePreferences,
    private val setDayStartMinuteUseCase: SetDayStartMinuteUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    /** The rule being edited, or null for a new one. */
    val ruleId: Long? = savedStateHandle.get<Long>(ARG_RULE_ID)?.takeIf { it > 0L }

    private val _state = MutableStateFlow(AllAppsEditorState(ruleId = ruleId))
    val state: StateFlow<AllAppsEditorState> = _state.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /** The saved rule at load time: what a save is compared against and copied from. Null when new. */
    private var savedRule: BlockRule? = null

    /** The two global first-unlock facts this editor displays. See [refreshFirstUnlockHints]. */
    @Volatile private var logicalDayStartMinute: Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE
    @Volatile private var firstUnlockStamp: Long = FirstUnlockWindow.NO_STAMP

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            nudgePreferences.dayStartMinute.collect { minute ->
                logicalDayStartMinute = minute
                refreshFirstUnlockHints()
            }
        }
        viewModelScope.launch {
            nudgePreferences.firstUnlockAt.collect { stamp ->
                firstUnlockStamp = stamp
                refreshFirstUnlockHints()
            }
        }
    }

    /**
     * "Starts at your next first unlock." is true exactly when today's unlock has already happened:
     * saving stamps `armedAt` after it, so the window cannot open until tomorrow (spec 5.3).
     */
    private fun refreshFirstUnlockHints() {
        _state.update {
            it.copy(
                dayStartMinute = logicalDayStartMinute,
                startsAtNextUnlock = RuleArming.startsAtNextUnlock(
                    previous = savedRule,
                    next = it.form.toRule(savedRule, it.allowedPackages, it.allowedGroupIds),
                    stampMs = firstUnlockStamp,
                    nowMs = System.currentTimeMillis(),
                    dayStartMinute = logicalDayStartMinute,
                    zone = ZoneId.systemDefault()
                )
            )
        }
    }

    private suspend fun load() {
        val id = ruleId
        val rule = id?.let { repository.getRule(it) }
        if (id != null && rule?.target() !is RuleTarget.AllApps) {
            // Deleted elsewhere, or a stale link: there is nothing to edit.
            _state.update { it.copy(isFinished = true) }
            return
        }
        savedRule = rule
        val form = AllAppsForm.from(rule)
        val allowedPackages = ExceptCsv.packages(rule?.exceptPackages)
        val allowedGroupIds = ExceptCsv.groupIds(rule?.exceptGroupIds)
        _state.update {
            it.copy(
                form = form,
                allowedPackages = allowedPackages,
                allowedGroupIds = allowedGroupIds,
                device = loadDevice(),
                baseline = AllAppsDraft(form, allowedPackages, allowedGroupIds)
            )
        }
        // The form now has a kind, so the "Starts at..." line can be right before the user touches
        // anything: an existing first-unlock rule opened after today's unlock must already say it.
        refreshFirstUnlockHints()
        combine(
            repository.getAllRules(),
            repository.getAllGroups(),
            repository.getAllGroupMembers()
        ) { rules, groups, members -> RuleTables(rules, groups, members) }
            .collect { tables -> _state.update { it.copy(tables = tables, isLoading = false) } }
    }

    /**
     * The installed apps, the essentials, and the essentials this phone can open, labelled. The
     * essentials list is the one Nuke and the engine read (spec 5.4), so the sheet shows exactly what
     * is never blocked.
     */
    private suspend fun loadDevice(): DeviceApps {
        val installed = installedAppsRepository.getInstalledApps().sortedBy { it.appName.lowercase() }
        val essentials = withContext(ioDispatcher) { essentialApps.packages() }
        val alwaysAllowed = essentials
            .filter { packageFacts.factsFor(it).launchable }
            .map { pkg ->
                InstalledAppsRepository.AppInfo(
                    packageName = pkg,
                    appName = installedAppsRepository.resolveAppName(pkg),
                    icon = installedAppsRepository.resolveIcon(pkg)
                )
            }
            .sortedBy { it.appName.lowercase() }
        return DeviceApps(
            installed = installed,
            essentials = essentials,
            browsers = essentialApps.browsers(),
            alwaysAllowed = alwaysAllowed
        )
    }

    fun setEnabled(enabled: Boolean) = updateForm { it.copy(enabled = enabled) }

    fun updateForm(transform: (AllAppsForm) -> AllAppsForm) {
        _state.update { it.copy(form = transform(it.form)) }
        // Picking "After first unlock" is what makes the "Starts at..." line relevant.
        refreshFirstUnlockHints()
    }

    /**
     * "New day starts at". One global setting, written through the use case that owns the Strict
     * Mode question: moving it can push today's unlock out of an enabled first-unlock rule's
     * logical day, which is weakening (spec 5.6).
     */
    fun setDayStartMinute(minute: Int) {
        viewModelScope.launch {
            val write: suspend () -> Unit = {
                setDayStartMinuteUseCase.execute(minute)
                logicalDayStartMinute = minute
                refreshFirstUnlockHints()
            }
            if (setDayStartMinuteUseCase.requiresUnlock(minute)) {
                strictModeGate.run(prompt = "Change when your day starts", action = write)
            } else {
                write()
            }
        }
    }

    fun openPicker() = _state.update { it.copy(showPicker = true) }

    fun dismissPicker() = _state.update { it.copy(showPicker = false) }

    /**
     * The picker's Done. Apps the picker could not show (no longer installed, or now essential) stay
     * allowed, exactly as the group editor keeps members it cannot show. Every group that exists is
     * offered, so the picked groups are the whole list (a stale id drops off here).
     */
    fun applyPicked(picked: PickerSelection) = _state.update { s ->
        val pickable = s.device.pickerApps.mapTo(HashSet()) { it.packageName }
        s.copy(
            showPicker = false,
            allowedPackages = membersAfterPick(s.allowedPackages, picked.packages, pickable),
            allowedGroupIds = picked.groupIds
        )
    }

    fun removeApp(packageName: String) = _state.update { s ->
        s.copy(allowedPackages = s.allowedPackages - packageName)
    }

    /** Takes the group off this rule's list; the group itself is untouched. */
    fun removeGroup(groupId: Long) = _state.update { s ->
        s.copy(allowedGroupIds = s.allowedGroupIds - groupId)
    }

    fun openAlwaysAllowed() = _state.update { it.copy(showAlwaysAllowed = true) }

    fun closeAlwaysAllowed() = _state.update { it.copy(showAlwaysAllowed = false) }

    /** Save, after "Block N apps all the time?" when the form is All the time + Hard block. */
    fun save() {
        val s = _state.value
        if (!s.canSave) return
        if (s.form.confirmsOnSave) _state.update { it.copy(showSaveConfirmation = true) }
        else commit()
    }

    fun confirmSave() {
        _state.update { it.copy(showSaveConfirmation = false) }
        commit()
    }

    fun dismissSaveConfirmation() = _state.update { it.copy(showSaveConfirmation = false) }

    private fun commit() {
        val s = _state.value
        if (!s.canSave) return
        val draft = s.draft
        val newRule = draft.form.toRule(savedRule, draft.allowedPackages, draft.allowedGroupIds)
        viewModelScope.launch {
            val write: suspend () -> Unit = {
                repository.saveAllAppsRule(newRule)
                _state.update { it.copy(isFinished = true) }
            }
            if (allAppsSaveWeakens(savedRule, newRule)) {
                strictModeGate.run(prompt = "Save changes that reduce blocking", action = write)
            } else {
                write()
            }
        }
    }

    /**
     * Back, or the top bar's arrow. Leaves straight away when nothing would be lost; otherwise asks
     * first, as the group editor does.
     */
    fun requestLeave() = _state.update {
        if (it.isDirty) it.copy(showDiscardConfirmation = true) else it.copy(isFinished = true)
    }

    fun discardChanges() = _state.update { it.copy(showDiscardConfirmation = false, isFinished = true) }

    fun keepEditing() = _state.update { it.copy(showDiscardConfirmation = false) }

    fun requestDelete() = _state.update { it.copy(showDeleteConfirmation = true) }

    fun dismissDelete() = _state.update { it.copy(showDeleteConfirmation = false) }

    fun confirmDelete() {
        val rule = savedRule ?: return
        _state.update { it.copy(showDeleteConfirmation = false) }
        viewModelScope.launch {
            val delete: suspend () -> Unit = {
                repository.deleteRule(rule.id)
                _state.update { it.copy(isFinished = true) }
            }
            if (allAppsDeleteWeakens(rule)) strictModeGate.run(prompt = "Delete this rule", action = delete)
            else delete()
        }
    }

    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    fun cancelChallenge() = strictModeGate.cancel()

    companion object {
        const val ARG_RULE_ID = "ruleId"
    }
}
