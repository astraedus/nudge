package com.astraedus.nudge.ui.screens.allapps

import androidx.compose.runtime.Composable

/**
 * The All apps editor (spec 3.4). DAY-0 STUB: lane C builds it. The signature is the contract the
 * nav graph calls: [onOpenGroup] opens an allowed group's editor.
 */
@Composable
fun AllAppsEditorScreen(
    viewModel: AllAppsEditorViewModel,
    onNavigateBack: () -> Unit,
    onOpenGroup: (Long) -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val unused = Triple(viewModel, onNavigateBack, onOpenGroup)
}
