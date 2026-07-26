package com.astraedus.nudge.domain.lightsoff

import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import java.util.Calendar
import javax.inject.Inject

/**
 * The resolved state of Lights Off at one instant: is a window open, which apps the user allow-listed,
 * and how the window should be described to them.
 *
 * @param active whether the lockdown applies right now.
 * @param untilLabel `H:MM` end of the window, or null when there is nothing truthful to promise.
 * @param whitelist the user's allow-list. Does NOT include the system-critical safety floor, which is
 *   enforced separately in the accessibility service and is not user-editable.
 * @param ruleName label for the block overlay, e.g. `"Lights Off · until 7:00"`.
 */
data class LightsOffWindow(
    val active: Boolean,
    val untilLabel: String?,
    val whitelist: Set<String>,
    val ruleName: String
) {
    /** True if [packageName] stays awake during this window. */
    fun allows(packageName: String): Boolean = packageName in whitelist

    companion object {
        val INACTIVE = LightsOffWindow(
            active = false,
            untilLabel = null,
            whitelist = emptySet(),
            ruleName = LightsOffClock.RULE_NAME
        )
    }
}

/**
 * Single source of truth for "are the lights off right now, and until when".
 *
 * Both consumers go through here so they can never disagree:
 *  - [com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase] turns it into the pure booleans
 *    `BlockEngine` step 0 consumes (what actually blocks apps), and
 *  - `NudgeAccessibilityService` uses it for the hot-path cache and the persistent status
 *    notification (what the user is *told*).
 *
 * A user seeing "active until 7:00" while an app opens anyway would be exactly the trust failure this
 * feature is built to avoid, so the label and the enforcement are computed by the same code.
 *
 * The caller supplies both the state and the clock — nothing here reads preferences or
 * `System.currentTimeMillis()`, so every case is unit-testable with an explicit [Calendar].
 */
class LightsOffWindowResolver @Inject constructor(
    private val scheduleEvaluator: ScheduleEvaluator
) {

    /**
     * @param enabled the Lights Off master switch. When off, nothing else matters — a stale manual
     *   window must not resurrect a lockdown the user turned off.
     * @param profile the active profile (v1: `profiles[0]`), or null when none is stored yet.
     * @param manualUntilMs end of a manual "start now" window, epoch millis, or null.
     */
    fun resolve(
        enabled: Boolean,
        profile: LightsOffProfile?,
        manualUntilMs: Long?,
        now: Calendar
    ): LightsOffWindow {
        if (!enabled) return LightsOffWindow.INACTIVE

        val manualOpen = LightsOffClock.isManualWindowOpen(manualUntilMs, now.timeInMillis)
        val scheduleOpen = profile != null && profile.scheduleEnabled &&
            scheduleEvaluator.isActiveAt(
                days = profile.days,
                startMinute = profile.startMinute,
                endMinute = profile.endMinute,
                now = now
            )
        if (!manualOpen && !scheduleOpen) return LightsOffWindow.INACTIVE

        // Promise the LATER of the two ends: a manual window that outlasts the schedule (or a schedule
        // that outlasts a manual window) must never be advertised as ending early.
        val untilMs = listOfNotNull(
            manualUntilMs.takeIf { manualOpen },
            profile?.endMinute
                ?.takeIf { scheduleOpen }
                ?.let { LightsOffClock.nextOccurrenceOf(it, now) }
        ).maxOrNull()
        val untilLabel = untilMs?.let { LightsOffClock.formatTimestamp(it, now) }

        return LightsOffWindow(
            active = true,
            untilLabel = untilLabel,
            whitelist = profile?.whitelist?.toSet() ?: emptySet(),
            ruleName = LightsOffClock.ruleName(untilLabel)
        )
    }
}
