package com.astraedus.nudge.ui.components.rule

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.blockModeLabel

/**
 * The modes that actually gate an app, in ascending severity.
 *
 * Deliberately NOT `BlockMode.entries`: [BlockMode.NONE] must never appear in a mode picker.
 * At app level it is expressed by the "Block the whole app" switch, and for a SCHEDULED override
 * it would be an outright lie — a scheduled rule is additive, so a NONE scheduled rule adds
 * nothing rather than carving out an unblocked window during those hours.
 */
val BLOCKING_MODES: List<BlockMode> =
    listOf(BlockMode.HARD_BLOCK, BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING)

/**
 * One segment per [BLOCKING_MODES] entry.
 *
 * [compact] drops the selected-check icon and keeps each label on one line: with four modes in the
 * row the icon's 18dp + 8dp would cost more than a quarter of the width left for "Hard Block", and
 * the selected segment is already unmistakable from its colour. The default behaviour picker uses
 * it; the schedule and website pickers keep the stock segment.
 */
@Composable
fun BlockModeSegmentedRow(
    selected: BlockMode,
    onSelect: (BlockMode) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    compact: Boolean = false
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        BLOCKING_MODES.forEachIndexed { index, mode ->
            val shape = SegmentedButtonDefaults.itemShape(
                index = index,
                count = BLOCKING_MODES.size
            )
            if (compact) {
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = shape,
                    icon = {}
                ) {
                    Text(blockModeLabel(mode), maxLines = 1)
                }
            } else {
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = shape
                ) {
                    Text(blockModeLabel(mode))
                }
            }
        }
    }
}
