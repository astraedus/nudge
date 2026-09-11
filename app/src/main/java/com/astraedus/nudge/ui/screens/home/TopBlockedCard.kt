package com.astraedus.nudge.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * "Blocked most this week" — the apps that pulled hardest, on the dashboard.
 *
 * Deliberately sits directly under the "Last 7 days" card: it is a week-scoped number and it
 * reads the same window that card charts (see `HomeViewModel.topBlocked`), so the two are one
 * story rather than two that can disagree. The header opens the Interventions insight screen —
 * one of the two screens the owner never found because nothing on this dashboard said they
 * existed — and each row opens that app's own detail screen.
 */
@Composable
fun TopBlockedCard(
    apps: List<TopBlockedApp>,
    onNavigateToInterventions: () -> Unit,
    onNavigateToAppDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClick = onNavigateToInterventions,
                        onClickLabel = "Open temptation patterns"
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Blocked most this week",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Last 7 days",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // No contentDescription: `clickable` merges descendant semantics, and the row
                // already carries the same phrase as its onClickLabel. Describing the chevron
                // too makes TalkBack read it twice.
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (apps.isEmpty()) {
                // The card stays put rather than vanishing: a card that disappears teaches
                // nothing, and "nothing was blocked" is itself a reading of the week.
                Text(
                    "Nothing blocked in the last 7 days. Rules you add will show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            // Bars are proportional to the STRONGEST app, not to the total: the question the
            // card answers is "which pulls hardest", so the leader is the full bar.
            val topCount = apps.first().count.coerceAtLeast(1)
            apps.forEach { app ->
                TopBlockedRow(
                    app = app,
                    fraction = app.count.toFloat() / topCount,
                    onClick = { onNavigateToAppDetail(app.packageName) }
                )
            }
        }
    }
}

@Composable
private fun TopBlockedRow(
    app: TopBlockedApp,
    fraction: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Open ${app.label} details")
            .defaultMinSize(minHeight = ROW_MIN_HEIGHT)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val icon = app.icon
        if (icon != null) {
            // Rasterise at the DEVICE's pixel size for a 24.dp box, not at a literal 24 px.
            // A 24 px bitmap stretched into 66 px of xxhdpi is visibly soft, and this card sits
            // at the top of the dashboard where every app icon is recognised by its shape.
            val iconPx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
            val bitmap = remember(icon, iconPx) { icon.toBitmap(iconPx, iconPx).asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE)
            )
        } else {
            // Uninstalled, or an icon PackageManager would not give us. A generic block glyph,
            // never an emoji — this app uses Material iconography everywhere.
            Icon(
                Icons.Outlined.Block,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            app.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        // Fixed width so the column of numbers lines up down the card rather than
        // jittering with each value's digit count.
        Box(modifier = Modifier.width(COUNT_WIDTH), contentAlignment = Alignment.CenterEnd) {
            Text(
                app.count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End
            )
        }
    }
}

private val ICON_SIZE = 24.dp

/** Minimum comfortable tap target for a row that navigates. */
private val ROW_MIN_HEIGHT = 48.dp

/** App names are truncated rather than allowed to squeeze the bars to nothing. */
private val LABEL_WIDTH = 108.dp

private val COUNT_WIDTH = 32.dp
