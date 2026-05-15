package com.astraedus.nudge.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupWithCount(
    val group: AppGroup,
    val memberCount: Int
)

data class GroupListUiState(
    val groups: List<GroupWithCount> = emptyList(),
    val selectedGroup: AppGroup? = null,
    val selectedGroupMembers: List<String> = emptyList(),
    val installedApps: List<InstalledAppsRepository.AppInfo> = emptyList(),
    val showCreateDialog: Boolean = false,
    val showAddMemberDialog: Boolean = false
)

@HiltViewModel
class GroupViewModel @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<Long?>(null)
    private val _showCreateDialog = MutableStateFlow(false)
    private val _showAddMemberDialog = MutableStateFlow(false)

    val uiState: StateFlow<GroupListUiState> = combine(
        blockRuleRepository.getAllGroups(),
        selectedGroupId.flatMapLatest { id ->
            if (id != null) blockRuleRepository.getGroupMembers(id)
            else flowOf(emptyList())
        },
        _showCreateDialog,
        _showAddMemberDialog
    ) { groups, members, showCreate, showAdd ->
        val installed = installedAppsRepository.getInstalledApps()
        val groupsWithCount = groups.map { group ->
            GroupWithCount(group, 0) // count updated when members loaded
        }
        val selected = groups.find { it.id == selectedGroupId.value }
        GroupListUiState(
            groups = groupsWithCount,
            selectedGroup = selected,
            selectedGroupMembers = members.map { it.packageName },
            installedApps = installed,
            showCreateDialog = showCreate,
            showAddMemberDialog = showAdd
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GroupListUiState())

    fun selectGroup(group: AppGroup) {
        selectedGroupId.value = group.id
    }

    fun clearSelection() {
        selectedGroupId.value = null
    }

    fun showCreateDialog() {
        _showCreateDialog.value = true
    }

    fun hideCreateDialog() {
        _showCreateDialog.value = false
    }

    fun showAddMemberDialog() {
        _showAddMemberDialog.value = true
    }

    fun hideAddMemberDialog() {
        _showAddMemberDialog.value = false
    }

    fun createGroup(name: String) {
        viewModelScope.launch {
            blockRuleRepository.createGroup(AppGroup(name = name))
            _showCreateDialog.value = false
        }
    }

    fun addMember(packageName: String) {
        val groupId = selectedGroupId.value ?: return
        viewModelScope.launch {
            blockRuleRepository.addToGroup(AppGroupMember(groupId, packageName))
        }
    }

    fun removeMember(packageName: String) {
        val groupId = selectedGroupId.value ?: return
        viewModelScope.launch {
            blockRuleRepository.removeFromGroup(groupId, packageName)
        }
    }
}
