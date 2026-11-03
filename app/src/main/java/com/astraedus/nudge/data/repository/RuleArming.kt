package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import java.time.ZoneId

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
     * Would a write of [next] over [previous] re-arm it, i.e. move `armedAt` to now?
     *
     * True when the rule is being created (and the caller is not carrying a stamp across a delete
     * and re-add), when it goes from off to on, or when it newly becomes a first-unlock rule. A
     * disabled write never arms.
     */
    fun shouldRearm(previous: BlockRule?, next: BlockRule): Boolean {
        if (!next.enabled) return false
        // A carried stamp means this is not a new rule in the sense that matters (App config
        // deletes and re-adds its rows); a bare one does.
        if (previous == null) return next.armedAt == NEVER_ARMED
        if (!previous.enabled) return true
        return previous.firstUnlockMinutes == null && next.firstUnlockMinutes != null
    }

    /**
     * [next] with the `armedAt` it should be stored with, given [previous] (the row it replaces, or
     * null when it is new) and the wall-clock [nowMs] of this write.
     */
    fun arm(next: BlockRule, previous: BlockRule?, nowMs: Long): BlockRule = when {
        // A disabled write keeps whatever it carries; nothing reads the stamp until it is on.
        !next.enabled -> next
        // Create, off-to-on, or newly first-unlock: this write is the arming moment.
        shouldRearm(previous, next) -> next.copy(armedAt = nowMs)
        // Everything else keeps the stored stamp, so a stronger save cannot end a running window.
        previous != null -> next.copy(armedAt = previous.armedAt)
        // New row with a stamp the caller carried across a delete and re-add.
        else -> next
    }

    /**
     * The `armedAt` a caller should put on [next] before a delete-and-re-add write, given the row it
     * replaces ([previous]). [NEVER_ARMED] means "let the repository arm it now", which is what a
     * create, an off-to-on switch and a newly first-unlock rule get; every other write carries the
     * old stamp (and only from a row that was enabled, so re-enabling does re-arm).
     *
     * The ViewModels use this because they write through [com.astraedus.nudge.data.repository.BlockRuleRepository.addRule],
     * which cannot see the row that was just deleted.
     */
    fun carriedArmedAt(previous: BlockRule?, next: BlockRule): Long =
        if (shouldRearm(previous, next)) NEVER_ARMED else previous?.armedAt ?: NEVER_ARMED

    /**
     * The editor's one honest sentence (spec 5.3, owner decision 2): true when saving [next] now
     * cannot open its window until the NEXT first unlock.
     *
     * It needs three facts, and the earlier version had only the first: today's stamp must be fresh,
     * AND either this save re-arms the rule ([shouldRearm]) or the stored stamp is already after
     * today's unlock. Without the second half the line appeared for an existing rule whose window
     * was running at that moment, because "an unlock happened today" is true for those too.
     *
     * A rule that is off, or not a first-unlock rule, never says it: it has no window to start.
     */
    fun startsAtNextUnlock(
        previous: BlockRule?,
        next: BlockRule,
        stampMs: Long,
        nowMs: Long,
        dayStartMinute: Int,
        zone: ZoneId
    ): Boolean {
        if (!next.enabled || next.firstUnlockMinutes == null) return false
        val dayStart = FirstUnlockWindow.logicalDayStart(nowMs, dayStartMinute, zone)
        val freshStamp = stampMs > FirstUnlockWindow.NO_STAMP &&
            stampMs <= nowMs &&
            stampMs >= dayStart
        if (!freshStamp) return false
        if (shouldRearm(previous, next)) return true
        val stored = previous?.armedAt ?: NEVER_ARMED
        return stored > stampMs
    }
}
