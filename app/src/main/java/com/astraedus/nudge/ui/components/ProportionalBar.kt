package com.astraedus.nudge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The one "this much of that" bar in the app: a track with a filled head [fraction] as wide.
 *
 * There used to be two of these — the stats screen's per-app usage bar (8dp tall, 4dp radius)
 * and the dashboard's top-blocked bar (6dp tall, 3dp radius) — structurally identical and
 * visibly different for no reason anybody chose. That is the same defect class this package
 * keeps producing (`docs/architecture/stats-and-charts.md`: two answers to one question); it
 * just shows up as geometry instead of as a number. One component, one geometry, so the next
 * bar cannot drift either.
 *
 * The corner radius is DERIVED from the height rather than written down a second time, so the
 * pill cannot un-round itself the next time the bar gets thicker.
 *
 * **The caller owns the width**, the component owns everything else: pass
 * `Modifier.fillMaxWidth()` in a Column or `Modifier.weight(1f)` in a Row.
 *
 * [fraction] is clamped HERE rather than at each call site, because every call site computes it
 * by dividing by a leader or a total, and both `0 / 0` (NaN) and a value a hair over 1 are
 * reachable from real data. `Modifier.fillMaxWidth` guards neither — a NaN propagates straight
 * into layout — so the component that owns the geometry owns the clamp.
 */
@Composable
fun ProportionalBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val filled = barFillFraction(fraction)
    val shape = RoundedCornerShape(BAR_HEIGHT / 2)

    Box(
        modifier = modifier
            .height(BAR_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(filled)
                .height(BAR_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * The clamp, as a pure function, so the one piece of [ProportionalBar] that can be WRONG is
 * testable on the JVM without a device or a Compose harness (same split as `DurationInput`).
 *
 * NaN is the case a naive `coerceIn` misses: every comparison against NaN is false, so it falls
 * straight through the clamp and into `Modifier.fillMaxWidth`, which multiplies a constraint by
 * it. `0 / 0` is one empty week away on both call sites.
 */
fun barFillFraction(fraction: Float): Float =
    if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)

/**
 * 8dp, the taller of the two geometries this replaced.
 *
 * Picked over 6dp because on the stats screen the bar IS the content of its row — it is the
 * only thing carrying "how big is this app's share" — and thinning it there to match a
 * dashboard bar would trade legibility on the screen people open to read numbers for
 * consistency with the screen that only summarises them. Going the other way costs nothing:
 * the dashboard row it grew inside has a 48dp minimum height.
 */
private val BAR_HEIGHT = 8.dp
