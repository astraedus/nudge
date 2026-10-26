package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.export.ExportedSettings

/**
 * Pure policy for the third weakening surface: restoring a backup file's app SETTINGS over this
 * device's.
 *
 * Sibling of [RuleWeakening] (per-rule edits) and [SettingsWeakening] (Settings-screen toggle
 * flips). It exists as its own object rather than as another [LockedToggle] because the question is
 * a different one: those two ask "is flipping this switch in this direction weakening?", while an
 * import compares a whole incoming CONFIGURATION against the current one, field by field, where
 * most fields will be unchanged no-ops.
 *
 * Why it matters: an export file is a plain, hand-editable JSON document. Without this, a user
 * under Strict Mode could type `"strictModeEnabled": false` into a text editor and import their way
 * straight out of the commitment lock — the exact bypass Strict Mode exists to prevent, on the one
 * path that writes settings without going through the Settings screen.
 *
 * No Android imports — unit-testable on the JVM.
 */
object ImportedSettingsWeakening {

    /**
     * True when applying [incoming] over [current] would reduce protection on ANY axis.
     *
     * Each axis is judged independently, exactly as [RuleWeakening] does: softening one setting is
     * weakening even when another is strengthened, because the user must justify the part that
     * takes protection away.
     *
     * A null [incoming] (a backup carrying no settings) is never weakening. A null FIELD inside
     * [incoming] is likewise never weakening — the file does not carry that setting, so the
     * device's own value survives untouched and nothing changes.
     *
     * The custom block-message pools are deliberately not an axis. They change what the overlay
     * SAYS, never whether or how long it blocks.
     */
    fun isWeakening(current: ExportedSettings, incoming: ExportedSettings?): Boolean {
        if (incoming == null) return false

        // The commitment lock itself: turning it off is the largest weakening available anywhere.
        if (turnedOff(current.strictModeEnabled, incoming.strictModeEnabled)) return true

        // Strict Mode difficulty. A shorter challenge is less friction between the impulse and the
        // unlock, which is the entire mechanism -- so lowering it weakens the lock even though the
        // lock stays on. (RAISING it is strengthening and stays free.)
        if (lowered(current.strictModeChallengeLength, incoming.strictModeChallengeLength)) {
            return true
        }

        // The escape hatch weakens by being turned ON: it re-opens a one-tap bypass on every block
        // overlay. Same direction SettingsWeakening applies to LockedToggle.EMERGENCY_PASS.
        if (turnedOn(current.emergencyPassEnabled, incoming.emergencyPassEnabled)) return true

        // Content filter: the master switch and its strict-keyword sub-toggle both weaken by going
        // off, and its block mode weakens by softening (HARD_BLOCK > DELAY > BREATHING > NONE).
        if (turnedOff(current.contentFilterEnabled, incoming.contentFilterEnabled)) return true
        if (turnedOff(current.contentFilterStrictKeywords, incoming.contentFilterStrictKeywords)) {
            return true
        }
        if (incoming.contentFilterMode != null &&
            RuleWeakening.modeStrength(incoming.contentFilterMode) <
            RuleWeakening.modeStrength(current.contentFilterMode)
        ) {
            return true
        }
        // Its allowlist weakens by gaining entries: each one is an exception. A file that carries a
        // shorter list (or none of the device's entries) only strengthens, and is free.
        if (incoming.contentFilterAllowlist != null &&
            ContentFilterAllowlistWeakening.isWeakening(
                current.contentFilterAllowlist.orEmpty(),
                incoming.contentFilterAllowlist
            )
        ) {
            return true
        }
        // Its block list is the mirror image: it weakens by LOSING entries, because each one is a
        // block the device had and the file would drop. A file carrying more blocks only strengthens.
        if (incoming.contentFilterBlocklist != null &&
            ContentFilterBlocklistWeakening.isWeakening(
                current.contentFilterBlocklist.orEmpty(),
                incoming.contentFilterBlocklist
            )
        ) {
            return true
        }

        return false
    }

    /** A protective switch that was on and the file turns off. Absent in the file -> unchanged. */
    private fun turnedOff(current: Boolean?, incoming: Boolean?): Boolean =
        current == true && incoming == false

    /** A switch whose ON position weakens (the escape hatch), being switched on. */
    private fun turnedOn(current: Boolean?, incoming: Boolean?): Boolean =
        current == false && incoming == true

    /** A numeric difficulty the file lowers. Absent on either side -> nothing to compare. */
    private fun lowered(current: Int?, incoming: Int?): Boolean =
        current != null && incoming != null && incoming < current
}
