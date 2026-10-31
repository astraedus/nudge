package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterUserList

/**
 * Pure policy for the content filter's "Allowed words & sites" list (issue #78).
 *
 * Every entry is an exception, so the list weakens by GROWING and strengthens by shrinking. Strict
 * Mode gates the weakening direction only, the same rule [SettingsWeakening] applies to the
 * escape-hatch toggle: adding an entry under Strict Mode costs the unlock challenge, removing one is
 * always free. The one definition of "adds" lives here so the Settings screen and the backup import
 * ([ImportedSettingsWeakening]) can never disagree about it.
 *
 * That split is only sound because the list is monotonic: an extra entry can only excuse more, so a
 * removal can never unblock anything ([ContentFilterAllowlist] builds coverage as a union for
 * exactly this reason).
 *
 * No Android imports, unit-testable on the JVM.
 */
object ContentFilterAllowlistWeakening {

    /**
     * True when going from [current] to [next] adds at least one exception. Both sides are compared
     * in canonical form, so "CNC" against a stored "cnc" is not an addition, and an invalid entry
     * (which would be dropped on write anyway) never counts as one.
     */
    fun isWeakening(current: Collection<String>, next: Collection<String>): Boolean {
        val before = current.mapNotNullTo(HashSet()) { ContentFilterUserList.normalize(it) }
        return next.any { raw ->
            val entry = ContentFilterUserList.normalize(raw)
            entry != null && entry !in before
        }
    }

    /** True when this change must pass the unlock challenge first. Nothing is gated with Strict Mode off. */
    fun requiresUnlock(
        current: Collection<String>,
        next: Collection<String>,
        strictModeEnabled: Boolean
    ): Boolean = strictModeEnabled && isWeakening(current, next)
}
