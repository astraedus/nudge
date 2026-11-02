package com.astraedus.nudge.ui.screens.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.AppListItem
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.components.TonalIcon
import com.astraedus.nudge.ui.components.rule.AllAppsIcon
import com.astraedus.nudge.ui.components.rule.AllAppsRuleItem
import com.astraedus.nudge.ui.screens.allapps.AllTheTimeConfirmDialog

/**
 * Manage Apps, the one list of rules (spec 3.2): the All apps rules, the user's groups (a group is
 * one decision covering many apps), "Block several apps" (a sheet: a group or an All apps rule), the apps that have rules of their own, then
 * every other app. Each section shows only when it has rows; one search filters them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAppConfig: (String) -> Unit,
    /** Opens the group editor: an existing group's id, or null for a new one. */
    onOpenGroup: (Long?) -> Unit,
    /** Opens the All apps editor: an existing rule's id, or null for a new one. */
    onOpenAllAppsRule: (Long?) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    val allAppsSwitchOn by viewModel.allAppsSwitchOn.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showBlockSeveral by rememberSaveable { mutableStateOf(false) }

    // A group just saved in the editor: back to the top, where the groups are. The top row only
    // grows in place, but the user may have been part-way down it (or into the apps) when they left.
    // See AppListViewModel.newlyAddedGroupId.
    LaunchedEffect(state.revealGroupId) {
        if (state.revealGroupId == null) return@LaunchedEffect
        listState.scrollToItem(0)
        viewModel.onGroupRevealed()
    }

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    // The switch asks what the editor's Save asks before a Hard block, all-the-time rule goes on.
    allAppsSwitchOn?.let { pending ->
        AllTheTimeConfirmDialog(
            blockedCount = pending.blockedCount,
            onConfirm = viewModel::confirmAllAppsSwitchOn,
            onDismiss = viewModel::dismissAllAppsSwitchOn
        )
    }

    if (showBlockSeveral) {
        BlockSeveralSheet(
            onPick = { option ->
                showBlockSeveral = false
                option.open(onOpenGroup, onOpenAllAppsRule)
            },
            onDismiss = { showBlockSeveral = false }
        )
    }

    AppListContent(
        state = state,
        listState = listState,
        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
        onNavigateBack = onNavigateBack,
        onNavigateToAppConfig = onNavigateToAppConfig,
        onOpenGroup = onOpenGroup,
        onOpenAllAppsRule = onOpenAllAppsRule,
        onToggleAllApps = viewModel::toggleAllApps,
        onToggleGroup = viewModel::toggleGroup,
        onToggleApp = { viewModel.toggleApp(it) },
        onBlockSeveral = { showBlockSeveral = true }
    )
}

/**
 * Manage Apps drawn from plain state: no ViewModel, no dialogs. [AppListScreen] owns the dialogs,
 * the sheet and the scroll-to-new-group effect, and passes the list state in; the screenshot
 * gallery (`ui/gallery/`) renders this directly from fixture state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListContent(
    state: AppListUiState,
    onSearchQueryChange: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToAppConfig: (String) -> Unit,
    onOpenGroup: (Long?) -> Unit,
    onOpenAllAppsRule: (Long?) -> Unit,
    onToggleAllApps: (AllAppsRuleItem) -> Unit,
    onToggleGroup: (GroupRow) -> Unit,
    onToggleApp: (AppRow) -> Unit,
    onBlockSeveral: () -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val searching = state.searchQuery.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Apps") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search apps and groups") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            // Nothing until the first state with the groups in it: the initial value has none, and
            // drawing "Block several apps" alone for a frame would make the groups pop in above it.
            if (!state.isLoading) LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                val rows = manageAppsRows(state.sections)
                items(
                    rows,
                    key = { it.key },
                    contentType = { it::class }
                ) { row ->
                    when (row) {
                        ManageAppsRow.Top -> TopSection(
                            allApps = state.sections.allApps,
                            groups = state.sections.groups,
                            showNewGroup = state.sections.showNewGroup,
                            onToggleAllApps = onToggleAllApps,
                            onOpenAllAppsRule = onOpenAllAppsRule,
                            onToggleGroup = onToggleGroup,
                            onOpenGroup = onOpenGroup,
                            onBlockSeveral = onBlockSeveral
                        )
                        is ManageAppsRow.Header -> ListSubheader(row.title)
                        is ManageAppsRow.App -> {
                            val app = row.app
                            AppListItem(
                                appName = app.appInfo.appName,
                                icon = app.appInfo.icon,
                                supportingText = app.supportingText,
                                modifier = Modifier.clickable { onNavigateToAppConfig(app.packageName) },
                                trailingContent = {
                                    Switch(
                                        checked = app.isOn,
                                        onCheckedChange = { onToggleApp(app) }
                                    )
                                }
                            )
                        }
                    }
                }
                // No group and no app matched: say so, rather than showing a blank list.
                if (searching && state.sections.nothingMatches) item(key = "no_matches") {
                    Text(
                        "No apps or groups match \"${state.searchQuery.trim()}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Everything above the app list, as ONE list row (see [ManageAppsRow] for why it must be one): the
 * All apps heading and rows, then the Groups heading and rows, each when there are any, then "Block
 * several apps" unless a search is showing.
 */
@Composable
private fun TopSection(
    allApps: List<AllAppsRuleItem>,
    groups: List<GroupRow>,
    showNewGroup: Boolean,
    onToggleAllApps: (AllAppsRuleItem) -> Unit,
    onOpenAllAppsRule: (Long?) -> Unit,
    onToggleGroup: (GroupRow) -> Unit,
    onOpenGroup: (Long?) -> Unit,
    onBlockSeveral: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (allApps.isNotEmpty()) ListSubheader("All apps")
        allApps.forEach { rule ->
            key(rule.ruleId) {
                ListItem(
                    headlineContent = {
                        Text(rule.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(rule.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { TonalIcon(AllAppsIcon) },
                    trailingContent = {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onToggleAllApps(rule) }
                        )
                    },
                    modifier = Modifier.clickable { onOpenAllAppsRule(rule.ruleId) }
                )
            }
        }
        if (groups.isNotEmpty()) ListSubheader("Groups")
        groups.forEach { group ->
            key(group.groupId) {
                ListItem(
                    headlineContent = {
                        Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(group.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { TonalIcon(Icons.Default.Folder) },
                    trailingContent = {
                        Switch(
                            checked = group.blocking,
                            onCheckedChange = { onToggleGroup(group) }
                        )
                    },
                    modifier = Modifier.clickable { onOpenGroup(group.groupId) }
                )
            }
        }
        if (showNewGroup) {
            ListItem(
                headlineContent = {
                    Text("Block several apps", color = MaterialTheme.colorScheme.primary)
                },
                leadingContent = { TonalIcon(Icons.Default.Add, primary = true) },
                modifier = Modifier.clickable(onClick = onBlockSeveral)
            )
        }
    }
}

/** An M3 list subheader: small, primary-coloured, on the list's 16dp edge. */
@Composable
private fun ListSubheader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}
