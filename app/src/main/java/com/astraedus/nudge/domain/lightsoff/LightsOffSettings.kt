package com.astraedus.nudge.domain.lightsoff

/**
 * The complete Lights Off configuration as one value: the master switch plus the profile being
 * edited (v1 = `profiles[0]`). Bundled so [LightsOffWeakening] can compare a whole before/after
 * pair the way [com.astraedus.nudge.domain.lock.RuleWeakening] compares two `BlockRule`s, instead of
 * callers passing four loose arguments in the right order.
 */
data class LightsOffSettings(
    val enabled: Boolean = false,
    val profile: LightsOffProfile = LightsOffProfile()
)
