package com.astraedus.nudge.ui.overlay

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** One allowed app to offer on an All apps block screen. */
class AllowedApp(val packageName: String, val label: String, val icon: Drawable)

/**
 * "Open an allowed app": up to four icons with labels, on an All apps block. A tap hands the
 * package to [onOpen], which the overlay routes through its ONE walk-away path (the same exit the
 * redirect bubble uses). Renders nothing when [apps] is empty.
 */
@Composable
fun AllowedAppsRow(apps: List<AllowedApp>, onOpen: (packageName: String) -> Unit) {
    if (apps.isEmpty()) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Open an allowed app",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            apps.forEach { app ->
                val bitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = 76.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open ${app.label}") {
                            onOpen(app.packageName)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
