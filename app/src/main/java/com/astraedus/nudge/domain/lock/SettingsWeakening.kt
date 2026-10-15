package com.astraedus.nudge.domain.lock

/**
 * The Settings-screen master switches that Strict Mode protects.
 *
 * Sibling concept to `RuleWeakening`, which covers per-rule edits; this covers the global toggles.
 */
enum class LockedToggle {
    /** The commitment lock itself. */
    STRICT_MODE,

    /** The "daily 2-minute pass" escape-hatch master switch. */
    EMERGENCY_PASS,

    /** The "Block restricted websites" content-filter master switch. */
    CONTENT_FILTER,

    /** The content filter's "Strict keyword matching" sub-toggle. */
    CONTENT_FILTER_STRICT_KEYWORDS
}

/**
 * Pure policy for which Settings flips must pass the typed unlock challenge.
 *
 * Same rule Strict Mode applies everywhere else — gate protection-WEAKENING actions, never
 * strengthening ones:
 * - [LockedToggle.STRICT_MODE]: turning the lock OFF weakens; turning it on is free.
 * - [LockedToggle.EMERGENCY_PASS]: turning the pass ON weakens (it re-opens a one-tap bypass on
 *   every block overlay); turning it off is free.
 * - [LockedToggle.CONTENT_FILTER] and [LockedToggle.CONTENT_FILTER_STRICT_KEYWORDS]: turning the
 *   filter (or its strict keyword matching) OFF weakens; turning it on is free.
 *
 * The pass toggle is deliberately **not frozen** while Strict Mode is on (v1.10.0). Freezing it was
 * the old design and it also hid the pass from the overlays outright; now a user who opted into the
 * escape hatch keeps it, and re-opening it merely costs a challenge. Overlay visibility is governed
 * by the toggle alone (see `ui/overlay/resolveEmergencyPassState`).
 */
object SettingsWeakening {

    /** True when flipping [toggle] to [enable] must pass the unlock challenge first. */
    fun requiresUnlock(toggle: LockedToggle, enable: Boolean, strictModeEnabled: Boolean): Boolean =
        strictModeEnabled && isWeakening(toggle, enable)

    private fun isWeakening(toggle: LockedToggle, enable: Boolean): Boolean = when (toggle) {
        LockedToggle.STRICT_MODE,
        LockedToggle.CONTENT_FILTER,
        LockedToggle.CONTENT_FILTER_STRICT_KEYWORDS -> !enable
        LockedToggle.EMERGENCY_PASS -> enable
    }
}
