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
 * that used to. The gate makes the cooldown's authority *derived* rather than *remembered*: no cache
 * entry for the package means no rule wants anything from it, so there is nothing to enforce.
 *
 * Pure, so the rule can be a unit test instead of a device session.
 */
object CooldownGate {

    /**
     * @param hasRuleEntry whether the counter cache currently holds an entry for this package, i.e.
     *   some enabled rule still wants foreground awareness of it. This is the authority.
     * @param isInCooldown whether an auto-kick cooldown timer is still running for the package.
     * @return true only when both agree. A cooldown without a rule behind it is stale state, and
     *   the caller must clear it rather than act on it.
     */
    fun shouldEnforce(hasRuleEntry: Boolean, isInCooldown: Boolean): Boolean =
        hasRuleEntry && isInCooldown

    /**
     * True when a cooldown is armed for a package no rule covers any more — the caller should drop
     * it so the map cannot accumulate enforcement authority for deleted rules.
     */
    fun isStale(hasRuleEntry: Boolean, isInCooldown: Boolean): Boolean =
        !hasRuleEntry && isInCooldown
}
