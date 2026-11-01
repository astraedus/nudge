package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The block screen's footer: "Rule: <name>" and, when the block carries an end time,
 * "Until 8:42 AM" under it. ONE composable used by every block body that shows a rule line
 * (hard, delay, hold, breathing), so the four cannot drift. Renders nothing when both are null.
 */
@Composable
fun RuleFooter(ruleName: String?, until: String?) {
    if (ruleName == null && until == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))
        if (ruleName != null) {
            Text(
                text = "Rule: $ruleName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (until != null) {
            Text(
                text = until,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
