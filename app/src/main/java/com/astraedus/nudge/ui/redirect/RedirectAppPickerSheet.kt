package com.astraedus.nudge.ui.redirect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.redirect.RedirectAppPolicy
import com.astraedus.nudge.ui.components.AppListItem
import kotlinx.coroutines.launch

/** The picker for [controller], shown while it says so. One wiring for both surfaces. */
@Composable
fun RedirectAppPickerHost(controller: RedirectAppController) {
    if (!controller.pickerOpen) return
    RedirectAppPickerSheet(
        candidates = controller.candidates,
        currentPackage = controller.target?.packageName,
        onPick = controller::choose,
        onRemove = controller::remove,
        onDismiss = controller::dismissPicker
    )
}

/**
 * Pick (or remove) the redirect app. Used from the block overlay and from Settings.
 *
 * A Material 3 [ModalBottomSheet], which is the reason it is safe on the overlay: the sheet is a
 * separate DIALOG window with its own back dispatcher. While it is up, the back gesture (predictive
 * back included) closes the sheet and nothing else; the overlay's always-enabled walk-away callback
 * never sees it, and nothing here calls `finish()`. The overlay stays exactly where it was, still
 * blocking, still flagged active. See `docs/architecture/block-overlay-lifecycle.md`,
 * "The redirect app".
 *
 * @param candidates the rows, already filtered by `RedirectAppPolicy` (no Nudge, no blocked app,
 *   nothing on the Nuke list). Null while loading.
 * @param currentPackage the saved choice, or null. Non-null shows "Remove" and a tick on its row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedirectAppPickerSheet(
    candidates: List<InstalledAppsRepository.AppInfo>?,
    currentPackage: String?,
    onPick: (packageName: String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // Animate the sheet away, THEN report: removing it from composition mid-animation snaps it.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    val rows = remember(candidates, query) {
        candidates?.let { apps ->
            RedirectAppPolicy.pickerRows(apps, excluded = emptySet(), query = query) {
                RedirectAppPolicy.Candidate(it.packageName, it.appName)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pick a better app",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "It sits on every block screen. One tap takes you there instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (currentPackage != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { closeThen(onRemove) }) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Remove")
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Search apps…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            when {
                rows == null -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                ) { CircularProgressIndicator() }

                rows.isEmpty() -> Text(
                    text = if (query.isBlank()) "No apps to offer." else "No apps match \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )

                else -> LazyColumn(verticalArrangement = Arrangement.Top) {
                    items(rows, key = { it.packageName }) { app ->
                        AppListItem(
                            appName = app.appName,
                            icon = app.icon,
                            modifier = Modifier.clickable { closeThen { onPick(app.packageName) } },
                            trailingContent = if (app.packageName == currentPackage) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Current choice",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                    item {
                        Text(
                            text = "Apps you block, and apps on your Nuke list, are not offered: " +
                                "going there would only meet another block.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
