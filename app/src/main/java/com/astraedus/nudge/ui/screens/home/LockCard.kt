package com.astraedus.nudge.ui.screens.home

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.ui.components.rule.AllAppsIcon
import com.astraedus.nudge.ui.components.rule.RuleSummary
import java.util.Calendar
import java.util.Date

/**
 * What the Home lock card shows (spec 3.8): the All apps rule that is blocking right now.
 *
 * [untilMs] is when its window ends (epoch ms), or null for a rule that is on all the time, which
 * shows no "Until" line.
 */
@Immutable
data class LockCard(
    val ruleId: Long,
    /** "All apps except Spotify, Maps"; the card reads "<title> are blocked". */
    val title: String,
    val untilMs: Long?
) {
    val headline: String get() = "$title are blocked"

    /** "Until 8:42 AM", with [formatClock] turning the end into the user's 12/24-hour text. */
    fun untilText(formatClock: (Long) -> String): String? =
        untilMs?.let { RuleSummary.untilText(formatClock(it)) }
}

/**
 * The card to show at [now], or null when no All apps rule is blocking (spec 3.8). Pure.
 *
 * A rule counts when it is on and in its window at [now]. Essentials play no part: they are never
 * covered, but the card is about the RULE being on, not about any one app. With several active, the
 * one that ends last wins, and "All the time" ends last; ties go to the oldest rule so the card does
 * not flip between two rules that end together.
 *
 * [appLabels] (package to launcher label) names the rule's allowed apps for the title; an allowed
 * app that is not installed is left out, as everywhere else the rule is named.
 */
fun lockCardFor(
    rules: List<BlockRuleData>,
    now: Calendar,
    appLabels: Map<String, String>,
    scheduler: ScheduleEvaluator = ScheduleEvaluator()
): LockCard? {
    val endOf = HashMap<Long, Long?>()
    val winner = rules
        .filter { it.enabled && RuleTarget.of(it) is RuleTarget.AllApps && scheduler.isActiveAt(it, now) }
        .sortedBy { it.id }
        .onEach { endOf[it.id] = scheduler.windowEndAt(it, now) }
        // No end (all the time, or a window that never closes within a week) outlasts every end.
        .maxByOrNull { endOf[it.id] ?: Long.MAX_VALUE }
        ?: return null
    return LockCard(
        ruleId = winner.id,
        title = RuleSummary.allAppsTitle(winner, appLabels),
        untilMs = endOf[winner.id]
    )
}

/**
 * Does the card need a clock behind it? Only a scheduled rule starts or ends on its own, so with no
 * enabled scheduled All apps rule nothing about the card changes with time and nothing polls.
 */
fun lockCardNeedsClock(rules: List<BlockRuleData>): Boolean = rules.any {
    it.enabled && RuleTarget.of(it) is RuleTarget.AllApps &&
        (
            it.scheduleDays != null || it.scheduleStartMinute != null ||
                it.scheduleEndMinute != null || it.firstUnlockMinutes != null
            )
}

/** Milliseconds from [nowMs] to the next whole minute: the schedule's own resolution. */
fun millisToNextMinute(nowMs: Long): Long = MINUTE_MS - nowMs.mod(MINUTE_MS)

private const val MINUTE_MS = 60_000L

/**
 * The Home lock card: same `surfaceVariant` card as [NukeCard], under it. Tap opens the rule.
 * Shown only while an All apps rule is blocking; the caller passes null otherwise and draws nothing.
 */
@Composable
fun LockCardTile(
    card: LockCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val until = card.untilText { DateFormat.getTimeFormat(context).format(Date(it)) }
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = card.title)
            .semantics { contentDescription = listOfNotNull(card.headline, until).joinToString(". ") },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                AllAppsIcon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
                if (until != null) {
                    Text(
                        until,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.6f)
            )
        }
    }
}
