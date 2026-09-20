/**
 * Daily usage rollups and dashboard aggregations. PURE — zero chrome.* imports, no I/O.
 *
 * `DayUsage` / `UsageByDay` are defined in ./protocol (the storage.local shape) and reused
 * verbatim here, not redefined. All mutation-shaped operations (`addActiveSeconds`,
 * `recordBlocked`, `recordWalkedAway`) are pure — they return a NEW `DayUsage` and never
 * touch the object passed in, so the background service worker can safely keep the old
 * reference around (e.g. for a diff) after calling them.
 */

import { localDayKey } from './scheduleEvaluator';
import type { DayUsage, UsageByDay } from './protocol';
import { isSurfaceKey, surfaceKey } from './surfaceKeys';
import type { GateId } from './platforms';

const HOURS_PER_DAY = 24;

/**
 * The SITE rollups of one day, with feature-surface buckets filtered out.
 *
 * A gate budget is measured in a bucket keyed `domain#gate` that lives in the same day map
 * as the sites (see `core/surfaceKeys.ts`), and the time in it is a SUBSET of the site's —
 * the same minute of Shorts is counted in `youtube.com` and in `youtube.com#shorts`. So
 * anything that lists or sums "domains" has to go through here, or the day's total silently
 * double-counts gated browsing and the stats table grows a phantom "youtube.com#shorts"
 * site.
 *
 * It is a shared helper rather than a filter repeated at each call site precisely because
 * "which aggregations are domain-shaped" is not obvious from the outside: `hourlyHeatmap`
 * and `calculateStreak` need it just as much as `topSites` does, and the ones that miss it
 * fail silently, with numbers that merely look a bit high.
 */
function siteEntries(day: Record<string, DayUsage>): [string, DayUsage][] {
  return Object.entries(day).filter(([key]) => !isSurfaceKey(key));
}

/**
 * One feature surface's active seconds for a day, or 0 when nothing was recorded.
 *
 * The dashboard's "of which Shorts: 12m" line. Exported so the UI reads the bucket through
 * the same key builder the tracker writes it with, rather than assembling the string itself.
 */
export function surfaceActiveSeconds(
  day: Record<string, DayUsage>,
  domain: string,
  gateId: GateId,
): number {
  return day[surfaceKey(domain, gateId)]?.activeSec ?? 0;
}

/**
 * One feature surface's ITEM count for a day, or 0 when nothing was recorded (v0.3).
 *
 * The dashboard's "of which Shorts: 12m, 34 Shorts" line. Same key builder as the writer,
 * for the same reason as `surfaceActiveSeconds`.
 */
export function surfaceItemViews(
  day: Record<string, DayUsage>,
  domain: string,
  gateId: GateId,
): number {
  return day[surfaceKey(domain, gateId)]?.items ?? 0;
}

function cloneHourly(hourly: readonly number[]): number[] {
  return [...hourly];
}

/** A fresh, all-zero rollup for a domain that hasn't been seen yet today. */
export function emptyDayUsage(): DayUsage {
  return {
    activeSec: 0,
    blocked: 0,
    walkedAway: 0,
    hourly: new Array<number>(HOURS_PER_DAY).fill(0),
    items: 0,
  };
}

/**
 * Normalize one STORED rollup into a complete `DayUsage`.
 *
 * Usage data carries no schema version and is never migrated in place: it is a rolling
 * local measurement, and rewriting months of it on every upgrade would be a lot of risk for
 * a number that is about to be replaced tomorrow anyway. So the shape is repaired on READ,
 * here, once, for every reader.
 *
 * This is what keeps a NEW numeric field from failing open. `items` (v0.3) does not exist
 * on any rollup written before it; `usage.items + 1` against a stored v0.2 rollup is
 * `undefined + 1` = NaN, every `NaN >= limit` comparison is false, and a count budget that
 * can never be over is a budget that silently never blocks.
 */
export function coerceDayUsage(value: unknown): DayUsage {
  const base = emptyDayUsage();
  if (!value || typeof value !== 'object') return base;
  const raw = value as Partial<DayUsage>;
  const hourly = Array.isArray(raw.hourly)
    ? Array.from({ length: HOURS_PER_DAY }, (_, h) =>
        typeof raw.hourly?.[h] === 'number' ? (raw.hourly[h] as number) : 0,
      )
    : base.hourly;
  return {
    activeSec: typeof raw.activeSec === 'number' ? raw.activeSec : 0,
    blocked: typeof raw.blocked === 'number' ? raw.blocked : 0,
    walkedAway: typeof raw.walkedAway === 'number' ? raw.walkedAway : 0,
    hourly,
    items: typeof raw.items === 'number' ? raw.items : 0,
  };
}

/** Add one viewed item to a rollup. Pure, returns a new `DayUsage`. */
export function recordItemView(usage: DayUsage): DayUsage {
  return {
    ...usage,
    items: (typeof usage.items === 'number' ? usage.items : 0) + 1,
    hourly: cloneHourly(usage.hourly),
  };
}

/**
 * Add active seconds to a rollup, both to the running total and to the given local hour's
 * bucket. Pure — returns a new `DayUsage`, never mutates `usage`.
 *
 * Guards (both make the call a no-op, returning an unchanged copy, so `activeSec` and
 * `sum(hourly)` never drift apart from each other):
 * - negative or non-finite `seconds` is ignored.
 * - `hour` must be an integer 0..23; anything else is ignored.
 */
export function addActiveSeconds(usage: DayUsage, seconds: number, hour: number): DayUsage {
  const validHour = Number.isInteger(hour) && hour >= 0 && hour <= 23;
  if (!Number.isFinite(seconds) || seconds < 0 || !validHour) {
    return { ...usage, hourly: cloneHourly(usage.hourly) };
  }
  const hourly = cloneHourly(usage.hourly);
  hourly[hour] = (hourly[hour] ?? 0) + seconds;
  return { ...usage, activeSec: usage.activeSec + seconds, hourly };
}

/** Increment the blocked count. Pure — returns a new `DayUsage`. */
export function recordBlocked(usage: DayUsage): DayUsage {
  return { ...usage, blocked: usage.blocked + 1, hourly: cloneHourly(usage.hourly) };
}

/** Increment the walked-away count. Pure — returns a new `DayUsage`. */
export function recordWalkedAway(usage: DayUsage): DayUsage {
  return { ...usage, walkedAway: usage.walkedAway + 1, hourly: cloneHourly(usage.hourly) };
}

/** Sum of active seconds across every SITE rolled up for one day. */
export function totalActiveSeconds(day: Record<string, DayUsage>): number {
  let total = 0;
  for (const [, usage] of siteEntries(day)) {
    total += usage.activeSec;
  }
  return total;
}

export interface TopSite {
  domain: string;
  activeSec: number;
  /** This domain's share of the day's total active seconds, 0..1. 0 when the day total is 0. */
  fraction: number;
}

/**
 * Domains for one day, ranked by active seconds descending. Ties break alphabetically by
 * domain for a stable, deterministic order. Safe when the day's total is 0 (every fraction
 * reads 0 rather than dividing by zero).
 */
export function topSites(day: Record<string, DayUsage>, limit?: number): TopSite[] {
  const total = totalActiveSeconds(day);
  const entries: TopSite[] = siteEntries(day).map(([domain, usage]) => ({
    domain,
    activeSec: usage.activeSec,
    fraction: total > 0 ? usage.activeSec / total : 0,
  }));
  entries.sort((a, b) => {
    if (b.activeSec !== a.activeSec) return b.activeSec - a.activeSec;
    if (a.domain < b.domain) return -1;
    if (a.domain > b.domain) return 1;
    return 0;
  });
  return typeof limit === 'number' ? entries.slice(0, limit) : entries;
}

/**
 * The last `n` local calendar day keys ending with today (today last, oldest first).
 * Steps by calendar day via the `Date(y, m, d - i)` constructor — never by subtracting a
 * fixed 86_400_000ms — so the result is correct across a DST transition (a "day" some
 * dates is 23h or 25h of wall-clock time, but it is still exactly one calendar day).
 */
export function lastNDayKeys(now: Date, n: number): string[] {
  const keys: string[] = [];
  for (let i = n - 1; i >= 0; i--) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
    keys.push(localDayKey(day));
  }
  return keys;
}

export interface WeeklySeriesPoint {
  day: string;
  activeSec: number;
  blocked: number;
  walkedAway: number;
}

/**
 * Per-day totals (summed across all domains) for the given day keys, zero-filled for any
 * day absent from `usage`. Order follows `dayKeys` as given (pass `lastNDayKeys` output for
 * the standard oldest-first, ending-today series).
 */
export function weeklySeries(usage: UsageByDay, dayKeys: readonly string[]): WeeklySeriesPoint[] {
  return dayKeys.map((day) => {
    const domains = usage[day];
    if (!domains) return { day, activeSec: 0, blocked: 0, walkedAway: 0 };
    let activeSec = 0;
    let blocked = 0;
    let walkedAway = 0;
    for (const [, usageForDomain] of siteEntries(domains)) {
      activeSec += usageForDomain.activeSec;
      blocked += usageForDomain.blocked;
      walkedAway += usageForDomain.walkedAway;
    }
    return { day, activeSec, blocked, walkedAway };
  });
}

/** 24 hourly active-second buckets for one day, summed across every domain. */
export function hourlyHeatmap(usage: UsageByDay, dayKey: string): number[] {
  const hourly = new Array<number>(HOURS_PER_DAY).fill(0);
  const domains = usage[dayKey];
  if (!domains) return hourly;
  for (const [, usageForDomain] of siteEntries(domains)) {
    for (let h = 0; h < HOURS_PER_DAY; h++) {
      hourly[h] = (hourly[h] ?? 0) + (usageForDomain.hourly[h] ?? 0);
    }
  }
  return hourly;
}

/**
 * Consecutive-day "streak" — direct port of Android's
 * `ui/screens/stats/StatsCalculator.kt#calculateStreak`. Kotlin source (quoted, with
 * `DAY_MS` = 24h and `referenceDayStartMs` defaulting to the start of today):
 *
 * ```kotlin
 * fun calculateStreak(weekEvents: List<UsageEvent>, referenceDayStartMs: Long = todayStart): Int {
 *     var streak = 0
 *     for (i in 0..6) {
 *         val dayStart = referenceDayStartMs - i * DAY_MS
 *         val dayEnd = dayStart + DAY_MS
 *         val dayEvents = weekEvents.filter { it.timestamp in dayStart until dayEnd }
 *         val hadWalkedAway = dayEvents.any { it.userChangedMind }
 *         val hadBlocked = dayEvents.any { it.wasBlocked }
 *         if (hadWalkedAway || hadBlocked) {
 *             streak++
 *         } else if (i == 0 && dayEvents.isEmpty()) {
 *             continue
 *         } else {
 *             break
 *         }
 *     }
 *     return streak
 * }
 * ```
 *
 * Rule implemented (exactly, not the loosened "any zero-event day is skipped" summary):
 * walk backwards from today (i=0) to 6 days ago. A day with >=1 blocked-or-walked-away
 * event increments the streak and the walk continues. Day i=0 (TODAY ONLY) gets a free
 * pass — skipped, not counted, not breaking — but only when it has ZERO events of ANY kind
 * (the "haven't used the phone yet today" case). Every other day (i=1..6, and i=0 when it
 * has SOME activity but no block/walk-away) that lacks a blocked-or-walked-away event
 * BREAKS the walk immediately, whether or not it has other recorded activity. Concretely: a
 * zero-event gap day earlier in the week still breaks the streak — only today is exempt.
 *
 * Port note: Android's `dayEvents.isEmpty()` means zero `UsageEvent` rows of ANY kind that
 * day (active-time tracking rows included, not just blocked/walked-away ones). The
 * extension has no per-event log, only aggregated `DayUsage` rollups per domain, so "day
 * has zero events" is reconstructed as "every domain rollup for that day is entirely zero"
 * (`activeSec === 0 && blocked === 0 && walkedAway === 0`) — true both when the day key is
 * absent from `usage` and when it is present but carries no recorded activity.
 */
export function calculateStreak(usage: UsageByDay, now: Date): number {
  let streak = 0;
  for (let i = 0; i <= 6; i++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
    const key = localDayKey(day);
    const domainsRecord = usage[key];
    const domains = domainsRecord ? siteEntries(domainsRecord).map(([, u]) => u) : [];

    const hadBlocked = domains.some((d) => d.blocked > 0);
    const hadWalkedAway = domains.some((d) => d.walkedAway > 0);
    const dayIsEmpty = domains.every((d) => d.activeSec === 0 && d.blocked === 0 && d.walkedAway === 0);

    if (hadBlocked || hadWalkedAway) {
      streak++;
    } else if (i === 0 && dayIsEmpty) {
      continue;
    } else {
      break;
    }
  }
  return streak;
}

/** All-time totals across every day and domain ever recorded. */
export function allTimeTotals(usage: UsageByDay): { blocked: number; walkedAway: number } {
  let blocked = 0;
  let walkedAway = 0;
  for (const domains of Object.values(usage)) {
    for (const [, usageForDomain] of siteEntries(domains)) {
      blocked += usageForDomain.blocked;
      walkedAway += usageForDomain.walkedAway;
    }
  }
  return { blocked, walkedAway };
}
