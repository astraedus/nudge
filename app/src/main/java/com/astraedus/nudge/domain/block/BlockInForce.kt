package com.astraedus.nudge.domain.block

/**
 * "Is the block this screen shows still in force?", by the clock alone (#170).
 *
 * [endsAt] is [com.astraedus.nudge.domain.model.BlockDecision.Block.endsAt]: when the deciding rule's
 * window ends, or when a spent daily limit resets at midnight, whichever is first. Null means nothing
 * on a clock ends it (a rule on all the time, Nuke), so it is in force for as long as the screen is up.
 *
 * Two readers, one answer:
 *  - the block screen closes itself at [endsAt] ([msUntil] is its wait), so a block whose rule is
 *    over does not keep covering the app;
 *  - "Use for 2 minutes" spends the day's ONE pass only on a block still in force. Tapping it on a
 *    block that has just ended closes the screen and keeps the pass.
 *
 * Pure: no clock is read here; the caller passes now.
 */
object BlockInForce {

    /** True while the clock has not reached [endsAt]. The instant itself is already over. */
    fun isInForce(endsAt: Long?, nowMs: Long): Boolean = endsAt == null || nowMs < endsAt

    /**
     * How long to wait from [nowMs] until the block ends, never negative: 0 means it is already over.
     * Null when nothing on a clock ends it, so the caller waits for nothing.
     */
    fun msUntil(endsAt: Long?, nowMs: Long): Long? = endsAt?.let { (it - nowMs).coerceAtLeast(0L) }
}
