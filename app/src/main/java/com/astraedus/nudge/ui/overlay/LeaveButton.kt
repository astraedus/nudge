package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one way OUT of a block screen: "I changed my mind" on the timed overlays, "Go Back" on the
 * hard block, "Go home" on Nuke. Every overlay body renders this, so leaving is the same big,
 * filled, centred target everywhere and the easiest thing to hit on the screen.
 *
 * It sits directly under the overlay's centre visual (countdown ring, hold target, breathing
 * circle). The visual itself is never made clickable: in HOLD mode the centre is the press-and-hold
 * target that lets the user IN, so the exit has to be a separate, larger control beneath it.
 * Styling only; [onClick] is whatever walk-away callback the body already had.
 */
@Composable
fun LeaveButton(
    text: String = "I changed my mind",
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            // Cap first, then fill: fillMaxWidth() ahead of widthIn(max) would pin the width to the
            // parent's and the cap would never bite.
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
