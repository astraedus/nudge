package com.astraedus.nudge.ui.screens.groups

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.definition
import com.astraedus.nudge.di.IoDispatcher
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.lock.GroupUsage
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.usecase.SetDayStartMinuteUseCase
import com.astraedus.nudge.service.EssentialApps
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import com.astraedus.nudge.ui.components.rule.allAppsRulesAllowingGroup
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One member row: the app, and the checked category that brings it in (its trailing text), if any. */
@Immutable
data class GroupMemberRow(val app: InstalledAppsRepository.AppInfo, val category: AppCategory?)

/** One category chip: checked or not, and how many apps on this phone it brings in. */
@Immutable
data class CategoryChip(val category: AppCategory, val count: Int, val checked: Boolean)

@Immutable
data class GroupEditorState(
    val isLoading: Boolean = true,
    /** Null for a group that has not been saved yet. */
    val groupId: Long? = null,
    /** The saved name, for the top bar. Blank for a new group. */
    val savedName: String = "",
    val name: String = "",
    /** Who is in the group: checked categories, apps added by hand, "Removed by you". */
    val membersDraft: GroupMembersDraft = GroupMembersDraft(),
    /** Each installed app's category, by package: what the categories resolve over. */
    val installed: Map<String, AppCategory> = emptyMap(),
    /** The one essentials list: never a member, never counted. */
    val essentials: Set<String> = emptySet(),
    /** The one browser list: a browser is a member only when added by hand, never by category. */
    val browsers: Set<String> = emptySet(),
    /** Labels and icons of the installed apps, by package. */
    val apps: Map<String, InstalledAppsRepository.AppInfo> = emptyMap(),
    val form: GroupRuleForm = GroupRuleForm(),
    /**
     * Minutes from midnight when the logical day starts. One global setting, shown while editing
     * first-unlock rules. Wired by the ViewModel from preferences.
     */
    val dayStartMinute: Int = 4 * 60,
    /**
     * Whether a first-unlock rule edited here will not take effect until the next first unlock.
     * Wired by the ViewModel from the rule's arming state.
     */
    val startsAtNextUnlock: Boolean = false,
    /** What "Add apps" offers: installed apps minus the essentials (spec 5.4), sorted by name. */
    val pickerApps: List<InstalledAppsRepository.AppInfo> = emptyList(),
    val showPicker: Boolean = false,
    /** "Removed by you" is open. Collapsed by default: it is the exception, not the list. */
    val showRemoved: Boolean = false,
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
    /** The members on this phone, resolved exactly as the engine resolves them. */
    val memberPackages: Set<String> get() = membersDraft.members(installed, essentials, browsers)

    /** The member rows, sorted by name. A member that is no longer installed shows its package name. */
    val members: List<GroupMemberRow>
        get() = memberPackages
            .map { pkg ->
                GroupMemberRow(
                    app = apps[pkg] ?: placeholder(pkg),
                    category = installed[pkg]?.takeIf { membersDraft.matchesCategory(it, pkg in browsers) }
                )
            }
            .sortedBy { it.app.appName.lowercase() }

    /** "Removed by you", sorted by name. */
    val removed: List<InstalledAppsRepository.AppInfo>
        get() = membersDraft.removedByYou(installed, essentials, browsers)
            .map { apps[it] ?: placeholder(it) }
            .sortedBy { it.appName.lowercase() }

    /** The category chips, in the order the editor offers them. */
    val chips: List<CategoryChip>
        get() = membersDraft.chips(installed, essentials, browsers)

    /**
     * A group needs a name and something in it: an app, or a checked category (one with no app on
     * this phone yet still catches the next one installed).
     */
    val canSave: Boolean
        get() = !isLoading && name.isNotBlank() && form.isComplete &&
            (membersDraft.categories.isNotEmpty() || memberPackages.isNotEmpty())

    /** Everything a save would write, in the shape [baseline] is kept in. */
    val draft: GroupDraft get() = GroupDraft(name.trim(), membersDraft, form)

    /**
     * Leaving now would lose something. Measured against what the editor opened with rather than
     * "was anything touched", so changing a value and changing it back is not an edit, and a new
     * group the user typed nothing into leaves without a question.
     */
    val isDirty: Boolean get() = baseline != null && !isFinished && draft != baseline

    private companion object {
        fun placeholder(packageName: String) =
            InstalledAppsRepository.AppInfo(packageName = packageName, appName = packageName, icon = null)
    }
}

/** The editable content of a group: its name (trimmed, as saved), who is in it, and its rule form. */
data class GroupDraft(
    val name: String,
    val members: GroupMembersDraft,
    val form: GroupRuleForm
)

/**
 * The group editor (spec 3.3): a group's categories and apps, and how and when the group blocks
 * them, saved as one write. Strict Mode gates a save that makes the group protect less, and deleting
 * a group that blocks (spec 5.6); the decision itself is the pure [groupSaveWeakens] /
 * [groupDeleteWeakens], over members resolved on this phone.
 */
@HiltViewModel
class GroupEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val essentialApps: EssentialApps,
    private val nudgePreferences: NudgePreferences,
    private val setDayStartMinuteUseCase: SetDayStartMinuteUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val requestedId: Long? =
        savedStateHandle.get<Long>(ARG_GROUP_ID)?.takeIf { it > 0L }

    private val _state = MutableStateFlow(GroupEditorState(groupId = requestedId))
    val state: StateFlow<GroupEditorState> = _state.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /**
     * The All apps rules, on or off, that list this group as allowed: "Allowed during" (spec 3.3).
     * Live, so a rule edited from here is current on the way back. A new group is in none. The flow
     * body, not the initialiser, reads the repositories, so nothing is read until it is collected.
     */
    val allowedDuring: StateFlow<List<AllAppsRuleItem>> = flow {
        val id = requestedId ?: return@flow
        val labels = installedAppsRepository.getInstalledApps().associate { it.packageName to it.appName }
        emitAll(
            combine(repository.getAllRules(), repository.getAllGroups()) { rules, groups ->
                allAppsRulesAllowingGroup(id, rules, groups, labels)
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The saved state at load time: what a save is compared against. Null for a new group. */
    private var savedDefinition: GroupDefinition? = null
    private var savedRule: BlockRule? = null

    /**
     * The two global first-unlock facts this editor displays: where the logical day starts, and the
     * stamp that decides whether a rule saved now waits for tomorrow. Held here rather than in the
     * state so [refreshFirstUnlockHints] can recompute from either one without re-reading DataStore.
     */
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
                startsAtNextUnlock = it.form.firstUnlock && FirstUnlockWindow.startsAtNextUnlock(
                    nowMs = System.currentTimeMillis(),
                    stampMs = firstUnlockStamp,
                    dayStartMinute = logicalDayStartMinute,
                    zone = ZoneId.systemDefault()
                )
            )
        }
    }

    private suspend fun load() {
        val installedApps = installedAppsRepository.getInstalledApps()
        val essentials = withContext(ioDispatcher) { essentialApps.packages() }
        val browsers = essentialApps.browsers()
        val installed = installedApps.associate { it.packageName to it.category }
        val apps = installedApps.associateBy { it.packageName }
        val pickerApps = installedApps
            .filterNot { it.packageName in essentials }
            .sortedBy { it.appName.lowercase() }

        val id = requestedId
        if (id == null) {
            _state.update {
                it.copy(
                    isLoading = false,
                    installed = installed,
                    essentials = essentials,
                    browsers = browsers,
                    apps = apps,
                    pickerApps = pickerApps,
                    baseline = GroupDraft(name = "", members = it.membersDraft, form = it.form)
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
        val definition = group.definition(repository.getGroupMembers(id).first())
        val rule = ownGroupRule(id, repository.getAllRules().first())
        savedDefinition = definition
        savedRule = rule
        val membersDraft = GroupMembersDraft.of(definition)
        _state.update {
            it.copy(
                isLoading = false,
                savedName = group.name,
                name = group.name,
                membersDraft = membersDraft,
                installed = installed,
                essentials = essentials,
                browsers = browsers,
                apps = apps,
                form = GroupRuleForm.from(rule),
                pickerApps = pickerApps,
                baseline = GroupDraft(group.name.trim(), membersDraft, GroupRuleForm.from(rule))
            )
        }
        // The form now has a kind, so the "Starts at..." line can be right before the user touches
        // anything: an existing first-unlock rule opened after today's unlock must already say it.
        refreshFirstUnlockHints()
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun openPicker() = _state.update { it.copy(showPicker = true) }

    fun dismissPicker() = _state.update { it.copy(showPicker = false) }

    fun applyPicked(picked: Set<String>) = _state.update { s ->
        val pickable = s.pickerApps.mapTo(HashSet()) { it.packageName }
        s.copy(
            showPicker = false,
            membersDraft = s.membersDraft.afterPick(picked, pickable, s.installed, s.essentials, s.browsers)
        )
    }

    fun toggleCategory(category: AppCategory) =
        _state.update { it.copy(membersDraft = it.membersDraft.toggleCategory(category)) }

    fun removeMember(packageName: String) = _state.update { s ->
        s.copy(membersDraft = s.membersDraft.remove(packageName, s.installed[packageName], packageName in s.browsers))
    }

    fun addBack(packageName: String) =
        _state.update { it.copy(membersDraft = it.membersDraft.addBack(packageName)) }

    fun toggleRemoved() = _state.update { it.copy(showRemoved = !it.showRemoved) }

    fun updateForm(transform: (GroupRuleForm) -> GroupRuleForm) {
        _state.update { it.copy(form = transform(it.form)) }
        // Picking "After first unlock" is what makes the "Starts at..." line relevant.
        refreshFirstUnlockHints()
    }

    /**
     * "New day starts at". One global setting, so it is written through the use case that owns the
     * Strict Mode question: moving it can push today's unlock out of an enabled first-unlock rule's
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

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val newRule = s.form.toRule(savedRule)
            val groupId = s.groupId
            val draft = s.membersDraft
            val weakens = groupId != null && groupSaveWeakens(
                old = savedDefinition,
                new = draft.definition(groupId),
                usage = GroupUsage.of(groupId, repository.getAllRules().first()),
                oldRule = savedRule,
                newRule = newRule,
                installed = s.installed,
                essentials = s.essentials,
                browsers = s.browsers
            )
            val write: suspend () -> Unit = {
                repository.saveGroup(groupId, s.name, draft.categories, draft.includes, draft.excludes, newRule)
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
            val s = _state.value
            val weakens = groupDeleteWeakens(
                old = savedDefinition ?: GroupDefinition(groupId),
                usage = GroupUsage.of(groupId, repository.getAllRules().first()),
                installed = s.installed,
                essentials = s.essentials,
                browsers = s.browsers
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
    }
}
