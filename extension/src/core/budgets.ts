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
