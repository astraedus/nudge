package com.astraedus.nudge.ui.screens.home

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.R
import com.astraedus.nudge.ui.components.ProportionalBar

/**
 * "Blocked most" — the apps that pulled hardest, on the dashboard.
 *
 * Deliberately sits directly under the "Last 7 days" card: it is a week-scoped number and it
 * reads the same window that card charts (see `HomeViewModel.topBlockedFlow`), so the two are
 * one story rather than two that can disagree. The header opens the Interventions insight
 * screen — one of the two screens the owner never found because nothing on this dashboard said
 * they existed — and each row opens that app's own detail screen.
 *
 * The header, the range line and the action label are the SAME string resources the
 * `TopBlocked` home-screen widget renders. That card and that widget are one feature shown in
 * two places; giving them two copies of the phrase is how a translation, or an edit, ends up
 * applied to only one of them.
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
                        onClickLabel = stringResource(R.string.widget_top_blocked_open)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.widget_top_blocked_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.widget_top_blocked_range),
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
                    stringResource(R.string.home_top_blocked_empty),
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
            .clickable(
                onClick = onClick,
                onClickLabel = stringResource(R.string.home_top_blocked_row_open, app.label)
            )
            .defaultMinSize(minHeight = ROW_MIN_HEIGHT)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val icon = app.icon
        if (icon != null) {
            // Already rasterised at this box's pixel size, off the main thread, by
            // `HomeViewModel.resolveRows`. Nothing to decode, cache or remember here.
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(TOP_BLOCKED_ICON_SIZE)
            )
        } else {
            // Uninstalled, or an icon PackageManager would not give us. A generic block glyph,
            // never an emoji — this app uses Material iconography everywhere.
            Icon(
                Icons.Outlined.Block,
                contentDescription = null,
                modifier = Modifier.size(TOP_BLOCKED_ICON_SIZE),
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

        ProportionalBar(fraction = fraction, modifier = Modifier.weight(1f))

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

/**
 * The icon box on a top-blocked row.
 *
 * Not private, and not a bare `24` in two places: `HomeViewModel` rasterises each icon at the
 * device's pixel size for THIS box, so the size the bitmap is decoded at and the size it is
 * drawn at have to be one number. A 24 px bitmap stretched into 66 px of xxhdpi is visibly
 * soft, and this card sits at the top of the dashboard where an app is recognised by its icon.
 */
internal val TOP_BLOCKED_ICON_SIZE = 24.dp

/** Minimum comfortable tap target for a row that navigates. */
private val ROW_MIN_HEIGHT = 48.dp

/** App names are truncated rather than allowed to squeeze the bars to nothing. */
private val LABEL_WIDTH = 108.dp

private val COUNT_WIDTH = 32.dp
