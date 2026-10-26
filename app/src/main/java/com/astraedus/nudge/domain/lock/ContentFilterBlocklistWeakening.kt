package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.domain.ContentFilterUserList

/**
 * Pure policy for the content filter's "Blocked words & sites" list: the mirror of
 * [ContentFilterAllowlistWeakening], with the direction inverted.
 *
 * A blocked entry takes protection UP, so the list strengthens by growing and weakens by shrinking.
 * Strict Mode gates the weakening direction only: REMOVING an entry under Strict Mode costs the
 * unlock challenge, adding one is always free. The same rule this app applies everywhere else, and
 * the reason the Settings screen and the backup importer share this one definition
 * ([ImportedSettingsWeakening]).
 *
 * No Android imports, unit-testable on the JVM.
 */
object ContentFilterBlocklistWeakening {

    /**
     * True when going from [current] to [next] loses at least one block. Both sides are compared in
     * canonical form, so "CRYPTO" against a stored "crypto" is not an addition, and an invalid entry
     * (which would be dropped on write anyway) never counts as one.
     */
    fun isWeakening(current: Collection<String>, next: Collection<String>): Boolean {
        val after = next.mapNotNullTo(HashSet()) { ContentFilterUserList.normalize(it) }
        return current.any { raw ->
            val entry = ContentFilterUserList.normalize(raw)
            entry != null && entry !in after
        }
    }

    /** True when this change must pass the unlock challenge first. Nothing is gated with Strict Mode off. */
    fun requiresUnlock(
        current: Collection<String>,
        next: Collection<String>,
        strictModeEnabled: Boolean
    ): Boolean = strictModeEnabled && isWeakening(current, next)
}
