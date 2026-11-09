package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.blockModeLabel
import com.astraedus.nudge.ui.components.blockModeShortLabel

/**
 * The modes that actually gate an app, in the order the pickers show them: Hard Block, then the
 * timed modes in the order they were added (Delay, Hold and Mirror cost the same at one duration,
 * see `RuleWeakening.modeStrength`).
 *
 * Deliberately NOT `BlockMode.entries`: [BlockMode.NONE] must never appear in a mode picker.
 * At app level it is expressed by the "Block the whole app" switch, and for a SCHEDULED override
 * it would be an outright lie: a scheduled rule is additive, so a NONE scheduled rule adds
 * nothing rather than carving out an unblocked window during those hours.
 */
val BLOCKING_MODES: List<BlockMode> =
    listOf(BlockMode.HARD_BLOCK, BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING, BlockMode.MIRROR)

/**
 * One chip per [BLOCKING_MODES] entry, wrapping onto a second line when the screen is narrow.
 *
 * Chips, not a segmented row: with five modes a segmented row gives each about a fifth of a 360dp
 * screen, and "Breathing" no longer fits in that at any readable size (four segments were already
 * the limit; see [blockModeShortLabel]). Chips also match the in-app feature override picker.
 *
 * [compact] uses the one-word labels ("Hard", not "Hard Block") where the picker shares a dense
 * card. Picking Mirror asks for the camera when it is not granted ([rememberMirrorCameraAccess]),
 * and while Mirror is selected without it, one line says the block will show a countdown instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockModePicker(
    selected: BlockMode,
    onSelect: (BlockMode) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    compact: Boolean = false
) {
    val camera = rememberMirrorCameraAccess()
    Column(modifier = modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BLOCKING_MODES.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = {
                        onSelect(mode)
                        if (shouldRequestCameraFor(mode, camera.granted)) camera.requestIfNeeded()
                    },
                    label = { Text(if (compact) blockModeShortLabel(mode) else blockModeLabel(mode), maxLines = 1) }
                )
            }
        }
        if (selected == BlockMode.MIRROR) MirrorCameraNote(camera)
    }
}
