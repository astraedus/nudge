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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.AppListItem
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.components.TonalIcon

/**
 * Manage Apps: the user's groups first (a group is one decision covering many apps), then
 * "Block several apps" to make another, then every installed app. One search filters both lists.
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

    // A group just saved in the editor: bring its row into view with the row above it (the header,
    // or the group before it) for context. See AppListViewModel.newlyAddedGroupId for why.
    LaunchedEffect(state.revealGroupId) {
        val id = state.revealGroupId ?: return@LaunchedEffect
        val index = AppListViewModel.groupRowIndex(state.groups, id)
        if (index >= 0) listState.scrollToItem((index - 1).coerceAtLeast(0))
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

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (state.groups.isNotEmpty()) {
                    item(key = "groups_header") { ListSubheader("Groups") }
                }
                items(state.groups, key = { "group_${it.groupId}" }) { group ->
                    ListItem(
                        headlineContent = { Text(group.title) },
                        supportingContent = { Text(group.summary) },
                        leadingContent = { TonalIcon(Icons.Default.Folder) },
                        trailingContent = {
                            Switch(
                                checked = group.blocking,
                                onCheckedChange = { viewModel.toggleGroup(group) }
                            )
                        },
                        modifier = Modifier.clickable { onOpenGroup(group.groupId) }
                    )
                }
                if (!searching) {
                    item(key = "new_group") {
                        ListItem(
                            headlineContent = {
                                Text("Block several apps", color = MaterialTheme.colorScheme.primary)
                            },
                            leadingContent = { TonalIcon(Icons.Default.Add, primary = true) },
                            modifier = Modifier.clickable { onOpenGroup(null) }
                        )
                    }
                }
                if (state.apps.isNotEmpty()) {
                    item(key = "apps_header") { ListSubheader("Apps") }
                }
                items(state.apps, key = { it.appInfo.packageName }) { app ->
                    AppListItem(
                        appName = app.appInfo.appName,
                        icon = app.appInfo.icon,
                        modifier = Modifier.clickable {
                            onNavigateToAppConfig(app.appInfo.packageName)
                        },
                        trailingContent = {
                            Switch(
                                checked = app.isBlocked,
                                onCheckedChange = {
                                    viewModel.toggleBlockRule(
                                        app.appInfo.packageName,
                                        app.isBlocked,
                                        app.ruleId
                                    )
                                }
                            )
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
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
