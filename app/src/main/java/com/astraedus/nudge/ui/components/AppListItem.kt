package com.astraedus.nudge.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * One app in a list: its 40dp icon, its name and, when given, one [supportingText] line (a rule
 * summary, say) in the list's secondary colour.
 *
 * An app with no icon (uninstalled, but a rule still names it) gets a [TonalIcon] of the same size,
 * so its name lines up with every other row instead of jumping to the left edge.
 */
@Composable
fun AppListItem(
    appName: String,
    icon: Drawable?,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(appName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supportingText?.let { text ->
            { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            if (icon != null) {
                // Rasterised at the box's real pixel size: a fixed 48px bitmap was upscaled (soft)
                // on every screen denser than mdpi.
                val px = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
                val bitmap = remember(icon, px) { icon.toBitmap(px, px).asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = appName,
                    modifier = Modifier.size(ICON_SIZE)
                )
            } else {
                TonalIcon(Icons.Default.Android)
            }
        },
        trailingContent = trailingContent,
        modifier = modifier
    )
}

/** The leading icon's box, shared with [TonalIcon] so app and group rows line up. */
private val ICON_SIZE = 40.dp
