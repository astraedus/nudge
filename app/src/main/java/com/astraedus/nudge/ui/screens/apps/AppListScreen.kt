package com.astraedus.nudge.ui.screens.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.AppListItem
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.components.TonalIcon

/**
 * Manage Apps, the one list of rules (spec 3.2): the user's groups (a group is one decision covering
 * many apps), "Block several apps" to make another, the apps that have rules of their own, then
 * every other app. Each section shows only when it has rows; one search filters them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAppConfig: (String) -> Unit,
    /** Opens the group editor: an existing group's id, or null for a new one. */
    onOpenGroup: (Long?) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    val searching = state.searchQuery.isNotBlank()
    val listState = rememberLazyListState()

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
                onValueChange = { viewModel.updateSearchQuery(it) },
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
                            groups = state.sections.groups,
                            showNewGroup = state.sections.showNewGroup,
                            onToggleGroup = viewModel::toggleGroup,
                            onOpenGroup = onOpenGroup
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
                                        onCheckedChange = { viewModel.toggleApp(app) }
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
 * Groups heading and rows when there are any, then "Block several apps" unless a search is showing.
 */
@Composable
private fun TopSection(
    groups: List<GroupRow>,
    showNewGroup: Boolean,
    onToggleGroup: (GroupRow) -> Unit,
    onOpenGroup: (Long?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                modifier = Modifier.clickable { onOpenGroup(null) }
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
