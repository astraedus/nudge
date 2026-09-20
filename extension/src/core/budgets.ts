/**
 * Daily-limit ("budget") math. PURE — zero chrome.* imports, no I/O.
 *
 * `SiteRule.dailyLimitMinutes` is `null` when a rule has no daily cap configured. Every
 * function here treats `null` as "no limit" and propagates that meaning to its return
 * value rather than special-casing it per call site.
 *
 * Boundary convention: "over budget" is `usedMs >= limitMs`, matching blockEngine.ts's
 * budget-exceeded check (`dailyUsageMs >= r.dailyLimitMinutes * 60_000`) — exactly-at-the-
 * limit already counts as over, it does not require usage to exceed the limit.
 */

import type { GateId } from './platforms';
import type { SiteRule } from './settingsSchema';

const MS_PER_MINUTE = 60_000;

/** Convert a daily limit in minutes to milliseconds. */
export function limitMs(limitMinutes: number): number {
  return limitMinutes * MS_PER_MINUTE;
}

/**
 * Remaining budget in ms for a rule with the given limit and today's usage so far.
 * `null` when no limit is set (unlimited budget). Floored at 0 — never negative, even once
 * usage has overshot the limit.
 */
export function remainingMs(limitMinutes: number | null, usedMs: number): number | null {
  if (limitMinutes === null) return null;
  return Math.max(0, limitMs(limitMinutes) - usedMs);
}

/**
 * True once usage has reached or passed the limit. Uses `>=` deliberately — exactly at the
 * limit already counts as over, matching blockEngine.ts's `budgetExceeded` check. `false`
 * when no limit is configured (an unlimited budget can never be "over").
 */
export function isOverBudget(limitMinutes: number | null, usedMs: number): boolean {
  if (limitMinutes === null) return false;
  return usedMs >= limitMs(limitMinutes);
}

/**
 * Fraction of the daily budget remaining, 0..1 (clamped both ends — usage past the limit
 * still reads 0, never negative; unused budget reads 1, never above it). `null` when no
 * limit is set.
 */
export function remainingFraction(limitMinutes: number | null, usedMs: number): number | null {
  if (limitMinutes === null) return null;
  const total = limitMs(limitMinutes);
  if (total <= 0) return 0;
  const used = Math.min(Math.max(0, usedMs), total);
  return (total - used) / total;
}

/**
 * The tightest (minimum) non-null `dailyLimitMinutes` across a set of rules — the cap that
 * actually governs remaining-time readouts when several rules apply at once. `null` when
 * none of the rules set a limit.
 */
export function tightestLimit(rules: readonly SiteRule[]): number | null {
  let min: number | null = null;
  for (const rule of rules) {
    if (rule.dailyLimitMinutes === null) continue;
    if (min === null || rule.dailyLimitMinutes < min) {
      min = rule.dailyLimitMinutes;
    }
  }
  return min;
}

/**
 * True only on the accounting tick that pushes usage from under-limit to at-or-over-limit.
 * This is what the background service uses to fire the mid-browsing "flip to blocked"
 * transition exactly once, instead of on every periodic accounting tick after the limit is
 * already exhausted.
 *
 * - `false` when `limitMinutes` is `null` (no limit, nothing to cross).
 * - `false` when usage was already at/over the limit before this tick (no new transition).
 * - `false` when usage is still under the limit after this tick (no transition yet).
 * - `true` only when `previousUsedMs` was under the limit and `nextUsedMs` is at/over it.
 */
export function crossesLimit(
  limitMinutes: number | null,
  previousUsedMs: number,
  nextUsedMs: number,
): boolean {
  if (limitMinutes === null) return false;
  const wasOver = isOverBudget(limitMinutes, previousUsedMs);
  const isOver = isOverBudget(limitMinutes, nextUsedMs);
  return !wasOver && isOver;
}

/* ============================================================ count budgets */

/**
 * The COUNT axis (v0.3): "20 Shorts a day, then the gate."
 *
 * Deliberately parallel functions rather than a generic one shared with the minute axis.
 * The two units are not interchangeable (minutes arrive as minutes and are compared in
 * milliseconds; items are already the unit they are compared in), and a single
 * `isOverLimit(limit, used)` would have had exactly one caller each while inviting a call
 * site to pass milliseconds where items were meant. The boundary convention IS shared and
 * is the important part: `>=`, so reaching the limit already counts as over, matching
 * `isOverBudget`.
 */

/** True once `usedCount` items have been viewed against a `limitCount` cap. */
export function isOverCount(limitCount: number | null, usedCount: number): boolean {
  if (limitCount === null) return false;
  return usedCount >= limitCount;
}

/**
 * Items still allowed today, floored at 0. `null` when no count limit is set (unlimited).
 */
export function remainingCount(limitCount: number | null, usedCount: number): number | null {
  if (limitCount === null) return null;
  return Math.max(0, limitCount - usedCount);
}

/**
 * True only on the increment that pushes the count from under-limit to at-or-over-limit.
 *
 * The count sibling of `crossesLimit`, and it exists for the same reason: the transition is
 * when the gate has to start redirecting and open tabs have to be pushed to the block page,
 * and doing that work on every subsequent increment would re-redirect a user who is already
 * looking at the block page.
 */
export function crossesCount(
  limitCount: number | null,
  previousCount: number,
  nextCount: number,
): boolean {
  if (limitCount === null) return false;
  return !isOverCount(limitCount, previousCount) && isOverCount(limitCount, nextCount);
}

/* ================================================== tightest gate cap lookup */

/**
 * The tightest cap one gate carries across every rule covering a domain.
 *
 * More than one rule can cover a host (`ruleResolver.rulesForDomain`), so "the Shorts
 * budget" is the strictest one on offer, exactly as `tightestLimit` does for the site. One
 * generic walker with a field selector rather than two near-identical loops: the axes
 * differ only in which field they read, and a second copy is where the two would drift.
 */
function tightestGateCap(
  rules: readonly SiteRule[],
  gateId: GateId,
  select: (gate: { dailyLimitMinutes: number | null; dailyLimitCount: number | null }) => number | null,
): number | null {
  let min: number | null = null;
  for (const rule of rules) {
    const gate = rule.features?.gates[gateId];
    const cap = gate === undefined ? null : select(gate);
    if (cap === null) continue;
    if (min === null || cap < min) min = cap;
  }
  return min;
}

/** The tightest MINUTE budget configured for one gate across the rules covering a domain. */
export function tightestGateMinutes(
  rules: readonly SiteRule[],
  gateId: GateId,
): number | null {
  return tightestGateCap(rules, gateId, (gate) => gate.dailyLimitMinutes);
}

/** The tightest COUNT budget configured for one gate across the rules covering a domain. */
export function tightestGateCount(
  rules: readonly SiteRule[],
  gateId: GateId,
): number | null {
  return tightestGateCap(rules, gateId, (gate) => gate.dailyLimitCount);
}
