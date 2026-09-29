package com.astraedus.nudge.domain.block

/**
 * Whether an auto-kick cooldown may still block a package.
 *
 * ## Why this is a gate and not an `if`
 * `evaluateForegroundPackage` launches the block overlay for a package in cooldown **before** it
 * looks up a single rule, and that branch writes no `UsageEvent` — so it is the one enforcement
 * path in the app that can put Nudge's UI in front of an app the user has no rule for, and leave no
 * trace in the stats that it did. The cooldown is armed by [AutoKickExecutor] and lives in
 * `InteractionTracker`'s in-memory map; the rule that justified it lives in the database. Delete the
 * rule (or turn its auto-kick off) while a cooldown is armed and the two disagree: the map keeps
 * ejecting the user from an app nothing is configured to block, for the whole cooldown, with the
 * overlay naming a rule that no longer exists.
 *
 * The repo has been here before. Every other enforcement decision is a function of a rule that
 * currently matches the foreground package; this one was a function of state left behind by a rule
 * that used to. The gate makes the cooldown's authority *derived* rather than *remembered*: nothing
 * currently configuring an auto-kick for the package means there is nothing to enforce.
 *
 * ## Why the authority is "configures an auto-kick" and not "is tracked at all"
 * It was bare counter-cache membership, which was a good enough proxy while everything in that cache
 * was an auto-kick or an awareness overlay. v1.18.4 put every rule carrying a DAILY LIMIT in the
 * cache too (a budget needs the foreground clock, so it is enforced mid-session and not only on
 * re-entry), and membership then stopped answering this question: a user who turned auto-kick off
 * while keeping a daily limit would still have "an entry", so the armed cooldown would have gone on
 * ejecting them — the defect in the paragraph above, re-entering through its own proxy.
 * `CounterCacheEntry.configuresAutoKick` is the narrower evidence, and the only evidence.
 *
 * Pure, so the rule can be a unit test instead of a device session.
 */
object CooldownGate {

    /**
     * @param autoKickConfigured whether some enabled rule still configures an auto-kick for this
     *   package, by either trigger (interaction count or session minutes). This is the authority —
     *   see the class KDoc for why it is not "the counter cache holds an entry".
     * @param isInCooldown whether an auto-kick cooldown timer is still running for the package.
     * @return true only when both agree. A cooldown without a rule behind it is stale state, and
     *   the caller must clear it rather than act on it.
     */
    fun shouldEnforce(autoKickConfigured: Boolean, isInCooldown: Boolean): Boolean =
        autoKickConfigured && isInCooldown

    /**
     * True when a cooldown is armed for a package nothing configures an auto-kick for any more — the
     * caller should drop it so the map cannot accumulate enforcement authority for rules that have
     * been deleted, disabled, or had their auto-kick switched off.
     */
    fun isStale(autoKickConfigured: Boolean, isInCooldown: Boolean): Boolean =
        !autoKickConfigured && isInCooldown
}
