package com.astraedus.nudge.ui.redirect

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.astraedus.nudge.data.repository.InstalledAppsRepository

/**
 * Long-press length for the bubble. Longer than the platform's ~400ms so a slow, hesitant tap on a
 * block screen (exactly the moment someone is hesitating) opens the app they asked for rather than
 * the picker; still well short of feeling like a hold.
 */
internal const val REDIRECT_LONG_PRESS_MS = 600L

/**
 * The one "better app" on a block screen.
 *
 * - **Empty** ([target] null): a dashed ring with a plus, "Pick a better app". Tap or long-press
 *   opens the picker.
 * - **Set**: the app's icon and name. Tap = [onLaunch], which the overlay routes through its ONE
 *   walk-away path (the row, the departure window, no grant). Long-press (haptic) = [onEdit], to
 *   change or remove it.
 *
 * Stateless: whether a saved app may be shown at all was decided before this was composed
 * (`RedirectAppPolicy.resolve`), so a target here is always launchable and never a blocked app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RedirectAppBubble(
    target: InstalledAppsRepository.AppInfo?,
    onLaunch: (packageName: String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val base = LocalViewConfiguration.current
    val viewConfiguration = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = REDIRECT_LONG_PRESS_MS
        }
    }
    val description = if (target == null) {
        "Pick a better app to go to instead"
    } else {
        "Go to ${target.appName} instead"
    }

    CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = modifier
                .widthIn(max = 200.dp)
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (target == null) "Pick an app" else "Open ${target.appName}",
                    onLongClickLabel = "Change app",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEdit()
                    },
                    onClick = {
                        if (target == null) onEdit() else onLaunch(target.packageName)
                    }
                )
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (target == null) EmptyBubble() else AppBubble(target.icon, target.appName)

            Spacer(Modifier.height(8.dp))

            Text(
                text = target?.appName ?: "Pick a better app",
                style = MaterialTheme.typography.labelLarge,
                color = if (target == null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (target == null) "Somewhere worth going instead" else "Go here instead",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private val BubbleSize = 64.dp

@Composable
private fun EmptyBubble() {
    val ring = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BubbleSize)
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                drawCircle(
                    color = ring,
                    radius = (size.minDimension - strokeWidth) / 2f,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                        )
                    )
                )
            }
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun AppBubble(icon: Drawable?, label: String) {
    val container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BubbleSize)
            .clip(CircleShape)
            .background(container)
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
    ) {
        if (icon != null) {
            val bitmap = remember(icon) { icon.toBitmap(144, 144).asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(44.dp))
        } else {
            // No icon resolved: the app's initial, never a blank circle that reads as broken.
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
