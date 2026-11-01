package com.astraedus.nudge.ui.screens.groups

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.lock.GroupUsage
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Immutable
data class GroupEditorState(
    val isLoading: Boolean = true,
    /** Null for a group that has not been saved yet. */
    val groupId: Long? = null,
    /** The saved name, for the top bar. Blank for a new group. */
    val savedName: String = "",
    val name: String = "",
    /** Members, sorted by name. A member that is no longer installed shows its package name. */
    val members: List<InstalledAppsRepository.AppInfo> = emptyList(),
    val form: GroupRuleForm = GroupRuleForm(),
    /** What "Add apps" offers: installed apps minus the essentials (spec 5.4), sorted by name. */
    val pickerApps: List<InstalledAppsRepository.AppInfo> = emptyList(),
    val showPicker: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    /** Leaving with unsaved edits: "Discard changes?". */
    val showDiscardConfirmation: Boolean = false,
    /**
     * What the editor opened with (the saved group, or the blank new-group form), which is what
     * "unsaved edits" are measured against. Null while loading.
     */
    val baseline: GroupDraft? = null,
    /** Saved, deleted or discarded: the screen pops. */
    val isFinished: Boolean = false
) {
    val canSave: Boolean get() = !isLoading && name.isNotBlank() && members.isNotEmpty() && form.isComplete
    val memberPackages: Set<String> get() = members.mapTo(LinkedHashSet()) { it.packageName }

    /** Everything a save would write, in the shape [baseline] is kept in. */
    val draft: GroupDraft get() = GroupDraft(name.trim(), memberPackages, form)

    /**
     * Leaving now would lose something. Measured against what the editor opened with rather than
     * "was anything touched", so changing a value and changing it back is not an edit, and a new
     * group the user typed nothing into leaves without a question.
     */
    val isDirty: Boolean get() = baseline != null && !isFinished && draft != baseline
}

/** The editable content of a group: its name (trimmed, as saved), its apps, and its rule form. */
data class GroupDraft(
    val name: String,
    val members: Set<String>,
    val form: GroupRuleForm
)

/**
 * The group editor (spec 3.3): a group's apps, and how and when the group blocks them, saved as one
 * write. Strict Mode gates a save that makes the group protect less, and deleting a group that
 * blocks (spec 5.6); the decision itself is the pure [groupSaveWeakens] / [groupDeleteWeakens].
 */
@HiltViewModel
class GroupEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val essentialApps: EssentialApps,
    nudgePreferences: NudgePreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val requestedId: Long? =
        savedStateHandle.get<Long>(ARG_GROUP_ID)?.takeIf { it > 0L }

    private val _state = MutableStateFlow(GroupEditorState(groupId = requestedId))
    val state: StateFlow<GroupEditorState> = _state.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /** The saved state at load time: what a save is compared against. Null for a new group. */
    private var savedMembers: Set<String>? = null
    private var savedRule: BlockRule? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val installed = installedAppsRepository.getInstalledApps()
        val essentials = withContext(ioDispatcher) { essentialApps.packages() }
        val byPackage = installed.associateBy { it.packageName }
        val pickerApps = installed
            .filterNot { it.packageName in essentials }
            .sortedBy { it.appName.lowercase() }

        val id = requestedId
        if (id == null) {
            _state.update {
                it.copy(
                    isLoading = false,
                    pickerApps = pickerApps,
                    baseline = GroupDraft(name = "", members = emptySet(), form = it.form)
                )
            }
            return
        }
        val group = repository.getAllGroups().first().firstOrNull { it.id == id }
        if (group == null) {
            // Deleted elsewhere (or a stale link): there is nothing to edit.
            _state.update { it.copy(isFinished = true) }
            return
        }
        val members = repository.getGroupMembers(id).first().mapTo(LinkedHashSet()) { it.packageName }
        val rule = ownGroupRule(id, repository.getAllRules().first())
        savedMembers = members
        savedRule = rule
        _state.update {
            it.copy(
                isLoading = false,
                savedName = group.name,
                name = group.name,
                members = members.map { pkg -> byPackage[pkg] ?: placeholder(pkg) }.sortedByName(),
                form = GroupRuleForm.from(rule),
                pickerApps = pickerApps,
                baseline = GroupDraft(group.name.trim(), members, GroupRuleForm.from(rule))
            )
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun openPicker() = _state.update { it.copy(showPicker = true) }

    fun dismissPicker() = _state.update { it.copy(showPicker = false) }

    fun applyPicked(picked: Set<String>) = _state.update { s ->
        val pickable = s.pickerApps.mapTo(HashSet()) { it.packageName }
        val next = membersAfterPick(s.memberPackages, picked, pickable)
        val known = (s.pickerApps + s.members).associateBy { it.packageName }
        s.copy(
            showPicker = false,
            members = next.map { known[it] ?: placeholder(it) }.sortedByName()
        )
    }

    fun removeMember(packageName: String) = _state.update { s ->
        s.copy(members = s.members.filterNot { it.packageName == packageName })
    }

    fun updateForm(transform: (GroupRuleForm) -> GroupRuleForm) =
        _state.update { it.copy(form = transform(it.form)) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val newRule = s.form.toRule(savedRule)
            val newMembers = s.memberPackages
            val groupId = s.groupId
            val weakens = groupId != null && groupSaveWeakens(
                groupId = groupId,
                oldMembers = savedMembers,
                newMembers = newMembers,
                usage = GroupUsage.of(groupId, repository.getAllRules().first()),
                oldRule = savedRule,
                newRule = newRule
            )
            val write: suspend () -> Unit = {
                repository.saveGroup(groupId, s.name, newMembers, newRule)
                _state.update { it.copy(isFinished = true) }
            }
            if (weakens) strictModeGate.run(prompt = "Save changes that reduce blocking", action = write)
            else write()
        }
    }

    /**
     * Back, or the top bar's arrow. Leaves straight away when nothing would be lost; otherwise asks
     * first, because leaving the editor used to drop unsaved edits without a word (device QA, PR 2).
     */
    fun requestLeave() = _state.update {
        if (it.isDirty) it.copy(showDiscardConfirmation = true) else it.copy(isFinished = true)
    }

    fun discardChanges() = _state.update { it.copy(showDiscardConfirmation = false, isFinished = true) }

    fun keepEditing() = _state.update { it.copy(showDiscardConfirmation = false) }

    fun requestDelete() = _state.update { it.copy(showDeleteConfirmation = true) }

    fun dismissDelete() = _state.update { it.copy(showDeleteConfirmation = false) }

    fun confirmDelete() {
        val groupId = _state.value.groupId ?: return
        _state.update { it.copy(showDeleteConfirmation = false) }
        viewModelScope.launch {
            val weakens = groupDeleteWeakens(
                groupId = groupId,
                members = savedMembers.orEmpty(),
                usage = GroupUsage.of(groupId, repository.getAllRules().first())
            )
            val delete: suspend () -> Unit = {
                repository.deleteGroupWithRules(groupId)
                _state.update { it.copy(isFinished = true) }
            }
            if (weakens) strictModeGate.run(prompt = "Delete this group", action = delete)
            else delete()
        }
    }

    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    fun cancelChallenge() = strictModeGate.cancel()

    companion object {
        const val ARG_GROUP_ID = "groupId"

        private fun placeholder(packageName: String) =
            InstalledAppsRepository.AppInfo(packageName = packageName, appName = packageName, icon = null)

        private fun List<InstalledAppsRepository.AppInfo>.sortedByName() =
            sortedBy { it.appName.lowercase() }
    }
}
