package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.BlockRule

/**
 * When a rule's `armedAt` moves (spec 5.3, as corrected after review).
 *
 * The first-unlock promise is "a rule made or switched on now starts at your NEXT first unlock", so
 * a stamp saved at 08:05 after an 08:00 unlock cannot open today. The first attempt implemented
 * that as "stamp on every enabled write", which quietly broke the same promise from the other side:
 * every save re-armed, so during a morning window a stronger edit (30 -> 60 minutes), a rename, or
 * a save with nothing changed ended the window that was running. App config made it worse by
 * deleting and re-adding the package's rules, so even a no-op save looked like a new rule.
 *
 * The rule is therefore narrow: `armedAt` moves only when the write creates the rule, switches it
 * from off to on, or makes it a first-unlock rule for the first time. Every other write keeps the
 * old stamp, which is what keeps a running window running.
 *
 * A caller that carries the old stamp across a delete and re-add (App config does) is honoured:
 * a new row with a non-[NEVER_ARMED] stamp keeps that stamp instead of being re-armed.
 */
internal object RuleArming {

    /**
     * `armedAt` value for "this rule has never been armed": a row written before the column existed,
     * a rule created while switched off, or a rule that is not a first-unlock rule. Its own constant
     * rather than `FirstUnlockWindow.NO_STAMP`, because the two mean different things even though
     * both are zero.
     */
    const val NEVER_ARMED: Long = 0L

    /**
     * [next] with the `armedAt` it should be stored with, given [previous] (the row it replaces, or
     * null when it is new) and the wall-clock [nowMs] of this write.
     */
    fun arm(next: BlockRule, previous: BlockRule?, nowMs: Long): BlockRule {
        if (!next.enabled) return next
        val stamp = when {
            // New row: keep a stamp the caller carried across a delete and re-add, else arm now.
            previous == null -> next.armedAt.takeIf { it != NEVER_ARMED } ?: nowMs
            // Switched on: the rule starts at the next unlock.
            !previous.enabled -> nowMs
            // Newly a first-unlock rule: the same, because the window it opens is new.
            previous.firstUnlockMinutes == null && next.firstUnlockMinutes != null -> nowMs
            // Everything else, including a stronger window, keeps the stamp so a running window runs.
            else -> previous.armedAt
        }
        return next.copy(armedAt = stamp)
    }
}
