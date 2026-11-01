package com.astraedus.nudge.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astraedus.nudge.data.repository.InstalledAppsRepository

private typealias PickerApp = InstalledAppsRepository.AppInfo

private val SelectionSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() }
)

/**
 * Full-screen multi-select app picker (spec 3.6). Selection is local until Done; the caller
 * passes apps already stripped of essentials and sorted by name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPicker(
    apps: List<InstalledAppsRepository.AppInfo>,
    initiallySelected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(initiallySelected) }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, query) { filterPickerApps(apps, query) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(pickerTitle(selection.size)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        },
                        actions = {
                            TextButton(onClick = { onDone(selection) }) { Text("Done") }
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
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true
                    )

                    if (visible.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No apps match \"${query.trim()}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.packageName }) { app ->
                                val checked = app.packageName in selection
                                AppListItem(
                                    appName = app.appName,
                                    icon = app.icon,
                                    modifier = Modifier.toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = { selection = toggled(selection, app.packageName) }
                                    ),
                                    trailingContent = {
                                        Checkbox(checked = checked, onCheckedChange = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Trimmed, case-insensitive match on the app name; a blank query keeps every app in order. */
internal fun filterPickerApps(apps: List<PickerApp>, query: String): List<PickerApp> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.filter { it.appName.contains(q, ignoreCase = true) }
}

internal fun toggled(selection: Set<String>, packageName: String): Set<String> =
    if (packageName in selection) selection - packageName else selection + packageName

internal fun pickerTitle(count: Int): String = if (count == 0) "Add apps" else "$count selected"
