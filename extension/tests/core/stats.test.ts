import { describe, expect, it } from 'vitest';
import {
  addActiveSeconds,
  allTimeTotals,
  calculateStreak,
  coerceDayUsage,
  emptyDayUsage,
  hourlyHeatmap,
  lastNDayKeys,
  recordBlocked,
  recordItemView,
  recordWalkedAway,
  surfaceActiveSeconds,
  surfaceItemViews,
  topSites,
  totalActiveSeconds,
  weeklySeries,
} from '../../src/core/stats';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import type { DayUsage, UsageByDay } from '../../src/core/protocol';

function usageWith(overrides: Partial<DayUsage> = {}): DayUsage {
  return {
    activeSec: overrides.activeSec ?? 0,
    blocked: overrides.blocked ?? 0,
    walkedAway: overrides.walkedAway ?? 0,
    hourly: overrides.hourly ?? new Array<number>(24).fill(0),
    items: overrides.items ?? 0,
  };
}

function buildDay(entries: Record<string, Partial<DayUsage>>): Record<string, DayUsage> {
  const result: Record<string, DayUsage> = {};
  for (const [domain, partial] of Object.entries(entries)) {
    result[domain] = usageWith(partial);
  }
  return result;
}

/** i days before `now`, as a local calendar day key. Mirrors the streak's own walk-back. */
function keyForDaysAgo(now: Date, daysAgo: number): string {
  return localDayKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() - daysAgo));
}

/** The calendar day immediately after `key`, computed independently of lastNDayKeys. */
function nextDayKey(key: string): string {
  const [y, m, d] = key.split('-').map(Number);
  return localDayKey(new Date(y as number, (m as number) - 1, (d as number) + 1));
}

describe('emptyDayUsage', () => {
  it('returns the all-zero shape with a 24-length hourly array and items: 0 (v0.3)', () => {
    const usage = emptyDayUsage();
    expect(usage).toEqual({
      activeSec: 0,
      blocked: 0,
      walkedAway: 0,
      hourly: new Array(24).fill(0),
      items: 0,
    });
    expect(usage.hourly).toHaveLength(24);
  });

  it('returns independent hourly arrays across calls', () => {
    const a = emptyDayUsage();
    const b = emptyDayUsage();
    expect(a.hourly).not.toBe(b.hourly);
  });
});

/**
 * `items` (v0.3) does not exist on any rollup written before the count axis shipped. A
 * stored rollup is normalized on every READ (never migrated in place — see the doc comment
 * on `coerceDayUsage`), so this is what stands between a pre-v0.3 rollup and a NaN count
 * budget that can never trip.
 */
describe('coerceDayUsage', () => {
  it('repairs a stored pre-v0.3 rollup (no `items` field at all) to items: 0, not NaN', () => {
    const legacy = { activeSec: 120, blocked: 1, walkedAway: 0, hourly: new Array(24).fill(0) };
    const repaired = coerceDayUsage(legacy);
    expect(repaired.items).toBe(0);
    expect(Number.isNaN(repaired.items)).toBe(false);
    // The rest of the shape is preserved, not just defaulted alongside items.
    expect(repaired.activeSec).toBe(120);
    expect(repaired.blocked).toBe(1);
  });

  it('keeps a real items count when the stored rollup already has one', () => {
    const stored = { activeSec: 0, blocked: 0, walkedAway: 0, hourly: new Array(24).fill(0), items: 7 };
    expect(coerceDayUsage(stored).items).toBe(7);
  });

  it('reads a non-numeric items field as 0 rather than propagating garbage', () => {
    const stored = { activeSec: 0, blocked: 0, walkedAway: 0, hourly: [], items: 'seven' };
    expect(coerceDayUsage(stored).items).toBe(0);
  });

  it('repairs a short hourly array to a valid length-24 array, keeping the values it has', () => {
    const shortHourly = { hourly: [1, 2, 3] };
    const repaired = coerceDayUsage(shortHourly);
    expect(repaired.hourly).toHaveLength(24);
    expect(repaired.hourly.slice(0, 3)).toEqual([1, 2, 3]);
    expect(repaired.hourly.slice(3).every((v) => v === 0)).toBe(true);
  });

  it('repairs an hourly array containing garbage entries, zero-filling only the bad ones', () => {
    const garbageHourly = { hourly: [1, 'two', null, 4, undefined] };
    const repaired = coerceDayUsage(garbageHourly);
    expect(repaired.hourly).toHaveLength(24);
    expect(repaired.hourly[0]).toBe(1);
    expect(repaired.hourly[1]).toBe(0);
    expect(repaired.hourly[2]).toBe(0);
    expect(repaired.hourly[3]).toBe(4);
    expect(repaired.hourly[4]).toBe(0);
  });

  it('falls back to the all-zero shape for non-object input', () => {
    for (const garbage of [null, undefined, 'nope', 42, true]) {
      expect(coerceDayUsage(garbage)).toEqual(emptyDayUsage());
    }
  });

  it('does not mutate the input value', () => {
    const stored = { activeSec: 5, blocked: 0, walkedAway: 0, hourly: [1, 2], items: 3 };
    const snapshot = JSON.parse(JSON.stringify(stored));
    coerceDayUsage(stored);
    expect(stored).toEqual(snapshot);
  });
});

describe('recordItemView', () => {
  it('adds one to items and is pure — returns a new object, never mutates the input', () => {
    const original = emptyDayUsage();
    const snapshot = JSON.parse(JSON.stringify(original));
    const next = recordItemView(original);
    expect(next).not.toBe(original);
    expect(original).toEqual(snapshot);
    expect(next.items).toBe(1);
  });

  it('accumulates across repeated calls', () => {
    let usage = emptyDayUsage();
    usage = recordItemView(usage);
    usage = recordItemView(usage);
    usage = recordItemView(usage);
    expect(usage.items).toBe(3);
  });

  it('clones the hourly array rather than sharing the reference (matches addActiveSeconds)', () => {
    const original = emptyDayUsage();
    const next = recordItemView(original);
    expect(next.hourly).not.toBe(original.hourly);
  });

  it('survives a rollup whose items is missing at runtime, rather than producing NaN', () => {
    // The normal repair point is coerceDayUsage on read, but recordItemView guards the same
    // failure mode itself (`typeof usage.items === 'number' ? usage.items : 0`) as defence
    // in depth: a v0.2-shaped object smuggled past the type system must not silently make
    // every future `items >= limit` comparison false forever.
    const legacy = {
      activeSec: 0,
      blocked: 0,
      walkedAway: 0,
      hourly: new Array(24).fill(0),
    } as DayUsage;
    const next = recordItemView(legacy);
    expect(next.items).toBe(1);
    expect(Number.isNaN(next.items)).toBe(false);
  });
});

describe('surfaceItemViews', () => {
  it("reads one surface's item count for a day", () => {
    const day = buildDay({ 'youtube.com#shorts': { items: 12 } });
    expect(surfaceItemViews(day, 'youtube.com', 'shorts')).toBe(12);
  });

  it('is 0 when the surface was never recorded that day', () => {
    const day = buildDay({ 'youtube.com#shorts': { items: 12 } });
    expect(surfaceItemViews(day, 'youtube.com', 'home')).toBe(0);
    expect(surfaceItemViews({}, 'youtube.com', 'shorts')).toBe(0);
  });
});

describe('addActiveSeconds', () => {
  it('adds to activeSec and the given hour bucket without mutating the input', () => {
    const original = emptyDayUsage();
    const snapshot = JSON.parse(JSON.stringify(original));
    const next = addActiveSeconds(original, 30, 9);

    expect(next).not.toBe(original);
    expect(next.hourly).not.toBe(original.hourly);
    expect(original).toEqual(snapshot); // input untouched

    expect(next.activeSec).toBe(30);
    expect(next.hourly[9]).toBe(30);
    expect(next.hourly.filter((_v, i) => i !== 9).every((v) => v === 0)).toBe(true);
  });

  it('accumulates across repeated calls', () => {
    let usage = emptyDayUsage();
    usage = addActiveSeconds(usage, 10, 5);
    usage = addActiveSeconds(usage, 20, 5);
    usage = addActiveSeconds(usage, 5, 6);
    expect(usage.activeSec).toBe(35);
    expect(usage.hourly[5]).toBe(30);
    expect(usage.hourly[6]).toBe(5);
  });

  it('ignores negative seconds (no-op, but still a fresh object)', () => {
    const base = addActiveSeconds(emptyDayUsage(), 100, 8);
    const next = addActiveSeconds(base, -5, 8);
    expect(next).toEqual(base);
    expect(next).not.toBe(base);
  });

  it('ignores non-finite seconds', () => {
    const original = emptyDayUsage();
    expect(addActiveSeconds(original, Number.NaN, 5)).toEqual(original);
    expect(addActiveSeconds(original, Number.POSITIVE_INFINITY, 5)).toEqual(original);
  });

  it('rejects an out-of-range or non-integer hour', () => {
    const original = emptyDayUsage();
    expect(addActiveSeconds(original, 10, -1)).toEqual(original);
    expect(addActiveSeconds(original, 10, 24)).toEqual(original);
    expect(addActiveSeconds(original, 10, 3.5)).toEqual(original);
  });

  it('accepts the boundary hours 0 and 23', () => {
    expect(addActiveSeconds(emptyDayUsage(), 10, 0).hourly[0]).toBe(10);
    expect(addActiveSeconds(emptyDayUsage(), 10, 23).hourly[23]).toBe(10);
  });
});

describe('recordBlocked / recordWalkedAway', () => {
  it('recordBlocked increments blocked without mutating the input', () => {
    const original = emptyDayUsage();
    const snapshot = JSON.parse(JSON.stringify(original));
    const next = recordBlocked(original);
    expect(next).not.toBe(original);
    expect(original).toEqual(snapshot);
    expect(next.blocked).toBe(1);
    expect(next.walkedAway).toBe(0);
  });

  it('recordWalkedAway increments walkedAway without mutating the input', () => {
    const original = emptyDayUsage();
    const snapshot = JSON.parse(JSON.stringify(original));
    const next = recordWalkedAway(original);
    expect(next).not.toBe(original);
    expect(original).toEqual(snapshot);
    expect(next.walkedAway).toBe(1);
    expect(next.blocked).toBe(0);
  });

  it('chains correctly across multiple calls', () => {
    let usage = emptyDayUsage();
    usage = recordBlocked(usage);
    usage = recordBlocked(usage);
    usage = recordWalkedAway(usage);
    expect(usage.blocked).toBe(2);
    expect(usage.walkedAway).toBe(1);
  });
});

describe('totalActiveSeconds', () => {
  it('sums activeSec across every domain', () => {
    const day = buildDay({ a: { activeSec: 10 }, b: { activeSec: 20 } });
    expect(totalActiveSeconds(day)).toBe(30);
  });

  it('is 0 for an empty day', () => {
    expect(totalActiveSeconds({})).toBe(0);
  });
});

describe('topSites', () => {
  it('ranks domains by activeSec descending', () => {
    const day = buildDay({ a: { activeSec: 10 }, b: { activeSec: 100 }, c: { activeSec: 50 } });
    expect(topSites(day).map((s) => s.domain)).toEqual(['b', 'c', 'a']);
  });

  it('breaks ties alphabetically by domain', () => {
    const day = buildDay({ zeta: { activeSec: 50 }, alpha: { activeSec: 50 }, mu: { activeSec: 50 } });
    expect(topSites(day).map((s) => s.domain)).toEqual(['alpha', 'mu', 'zeta']);
  });

  it('mixes descending activeSec with alphabetical tie-break correctly', () => {
    const day = buildDay({
      b: { activeSec: 50 },
      a: { activeSec: 50 },
      z: { activeSec: 90 },
      m: { activeSec: 10 },
    });
    expect(topSites(day).map((s) => s.domain)).toEqual(['z', 'a', 'b', 'm']);
  });

  it('computes each domain\'s proportional fraction of the day total', () => {
    const day = buildDay({ a: { activeSec: 25 }, b: { activeSec: 75 } });
    const ranked = topSites(day);
    const a = ranked.find((s) => s.domain === 'a');
    const b = ranked.find((s) => s.domain === 'b');
    expect(a?.fraction).toBeCloseTo(0.25);
    expect(b?.fraction).toBeCloseTo(0.75);
  });

  it('is safe when the day total is zero -- fractions read 0, never NaN/Infinity', () => {
    const day = buildDay({ a: { activeSec: 0 }, b: { activeSec: 0 } });
    const ranked = topSites(day);
    expect(ranked.every((s) => s.fraction === 0)).toBe(true);
    expect(ranked.some((s) => Number.isNaN(s.fraction))).toBe(false);
  });

  it('respects the limit parameter', () => {
    const day = buildDay({ a: { activeSec: 30 }, b: { activeSec: 20 }, c: { activeSec: 10 } });
    expect(topSites(day, 2).map((s) => s.domain)).toEqual(['a', 'b']);
  });

  it('returns an empty array for an empty day', () => {
    expect(topSites({})).toEqual([]);
  });
});

describe('lastNDayKeys', () => {
  it('returns n keys, oldest first, ending with today', () => {
    const now = new Date(2026, 5, 15, 10, 30); // arbitrary local time, no DST edge
    const keys = lastNDayKeys(now, 5);
    expect(keys).toHaveLength(5);
    expect(keys[4]).toBe(localDayKey(now));
    expect(new Set(keys).size).toBe(5);
  });

  it('is strictly consecutive with no gaps or repeats', () => {
    const now = new Date(2026, 5, 15, 10, 30);
    const keys = lastNDayKeys(now, 10);
    for (let i = 0; i < keys.length - 1; i++) {
      expect(nextDayKey(keys[i] as string)).toBe(keys[i + 1]);
    }
  });

  it('handles n=1 (just today)', () => {
    const now = new Date(2026, 5, 15, 10, 30);
    expect(lastNDayKeys(now, 1)).toEqual([localDayKey(now)]);
  });

  it('is DST-safe across a late-March window (EU-style spring-forward date)', () => {
    const now = new Date(2026, 2, 29, 12, 0); // March 29, local noon
    const keys = lastNDayKeys(now, 7);
    expect(keys).toHaveLength(7);
    expect(new Set(keys).size).toBe(7); // 7 DISTINCT calendar days
    expect(keys[6]).toBe(localDayKey(now));
    for (let i = 0; i < keys.length - 1; i++) {
      expect(nextDayKey(keys[i] as string)).toBe(keys[i + 1]);
    }
  });

  it('is DST-safe across a late-October window (EU-style fall-back date)', () => {
    const now = new Date(2026, 9, 30, 12, 0); // October 30, local noon
    const keys = lastNDayKeys(now, 7);
    expect(keys).toHaveLength(7);
    expect(new Set(keys).size).toBe(7); // 7 DISTINCT calendar days
    expect(keys[6]).toBe(localDayKey(now));
    for (let i = 0; i < keys.length - 1; i++) {
      expect(nextDayKey(keys[i] as string)).toBe(keys[i + 1]);
    }
  });
});

describe('weeklySeries', () => {
  it('zero-fills days with no data', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const keys = lastNDayKeys(now, 3);
    const usage: UsageByDay = {
      [keys[2] as string]: buildDay({ a: { activeSec: 60, blocked: 1, walkedAway: 2 } }),
    };
    const series = weeklySeries(usage, keys);
    expect(series).toHaveLength(3);
    expect(series[0]).toEqual({ day: keys[0], activeSec: 0, blocked: 0, walkedAway: 0 });
    expect(series[1]).toEqual({ day: keys[1], activeSec: 0, blocked: 0, walkedAway: 0 });
    expect(series[2]).toEqual({ day: keys[2], activeSec: 60, blocked: 1, walkedAway: 2 });
  });

  it('sums across multiple domains for the same day', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const key = localDayKey(now);
    const usage: UsageByDay = {
      [key]: buildDay({
        a: { activeSec: 10, blocked: 1, walkedAway: 0 },
        b: { activeSec: 20, blocked: 0, walkedAway: 3 },
      }),
    };
    const series = weeklySeries(usage, [key]);
    expect(series[0]).toEqual({ day: key, activeSec: 30, blocked: 1, walkedAway: 3 });
  });

  it('preserves the order of the given day keys', () => {
    const usage: UsageByDay = {};
    const series = weeklySeries(usage, ['2026-01-03', '2026-01-01', '2026-01-02']);
    expect(series.map((s) => s.day)).toEqual(['2026-01-03', '2026-01-01', '2026-01-02']);
  });
});

describe('hourlyHeatmap', () => {
  it('sums hourly buckets across domains for the given day', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const key = localDayKey(now);
    const hourlyA = new Array<number>(24).fill(0);
    hourlyA[3] = 100;
    const hourlyB = new Array<number>(24).fill(0);
    hourlyB[3] = 50;
    hourlyB[10] = 20;
    const usage: UsageByDay = {
      [key]: buildDay({ a: { hourly: hourlyA }, b: { hourly: hourlyB } }),
    };
    const heatmap = hourlyHeatmap(usage, key);
    expect(heatmap).toHaveLength(24);
    expect(heatmap[3]).toBe(150);
    expect(heatmap[10]).toBe(20);
    expect(heatmap.filter((_v, i) => i !== 3 && i !== 10).every((v) => v === 0)).toBe(true);
  });

  it('returns all zeros for a day with no data', () => {
    const heatmap = hourlyHeatmap({}, '2026-06-15');
    expect(heatmap).toEqual(new Array(24).fill(0));
  });
});

describe('allTimeTotals', () => {
  it('sums blocked/walkedAway across every day and domain', () => {
    const usage: UsageByDay = {
      '2026-01-01': buildDay({ a: { blocked: 2, walkedAway: 1 }, b: { blocked: 1 } }),
      '2026-01-02': buildDay({ a: { walkedAway: 3 } }),
    };
    expect(allTimeTotals(usage)).toEqual({ blocked: 3, walkedAway: 4 });
  });

  it('is all-zero for empty usage', () => {
    expect(allTimeTotals({})).toEqual({ blocked: 0, walkedAway: 0 });
  });
});

/**
 * calculateStreak matrix. Mirrors the exact Kotlin walk in
 * app/src/main/java/com/astraedus/nudge/ui/screens/stats/StatsCalculator.kt:
 *
 *   for (i in 0..6) {
 *       ...
 *       if (hadWalkedAway || hadBlocked) streak++
 *       else if (i == 0 && dayEvents.isEmpty()) continue
 *       else break
 *   }
 *
 * i.e. only TODAY (i=0) gets a free pass when it has zero events of any kind; every other
 * day that lacks a blocked/walked-away event breaks the walk, whether or not it has other
 * activity recorded.
 */
describe('calculateStreak', () => {
  it('is 0 with no data at all', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    expect(calculateStreak({}, now)).toBe(0);
  });

  it('is 1 when only today has a blocked event', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: { blocked: 1 } }),
    };
    expect(calculateStreak(usage, now)).toBe(1);
  });

  it('is 1 when only today has a walked-away event (counts the same as blocked)', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: { walkedAway: 1 } }),
    };
    expect(calculateStreak(usage, now)).toBe(1);
  });

  it('counts consecutive days with blocked/walked-away events', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: { blocked: 1 } }),
      [keyForDaysAgo(now, 1)]: buildDay({ a: { walkedAway: 2 } }),
      [keyForDaysAgo(now, 2)]: buildDay({ a: { blocked: 3 } }),
    };
    expect(calculateStreak(usage, now)).toBe(3);
  });

  it('BREAKS on a gap day with no events at all, UNLESS that day is today', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: { blocked: 1 } }),
      // day 1 (yesterday) intentionally absent from the map entirely.
      [keyForDaysAgo(now, 2)]: buildDay({ a: { blocked: 1 } }),
    };
    // Yesterday's total absence breaks the walk before day 2 is ever reached, even though
    // day 2 itself has a blocked event -- matches the Kotlin `else -> break` for i != 0.
    expect(calculateStreak(usage, now)).toBe(1);
  });

  it('skips (does not break) TODAY specifically when today has zero events of any kind', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      // today (i=0) absent entirely -> dayEvents.isEmpty() && i==0 -> `continue`, not break.
      [keyForDaysAgo(now, 1)]: buildDay({ a: { blocked: 1 } }),
    };
    expect(calculateStreak(usage, now)).toBe(1);
  });

  it('breaks when a day exists in the map but has 0 blocked and 0 walkedAway, even with activeSec > 0', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: { activeSec: 300, blocked: 0, walkedAway: 0 } }),
    };
    // Today has recorded activity (not "isEmpty()"), so it does NOT get the today-only free
    // pass -- it must break like any other non-qualifying day.
    expect(calculateStreak(usage, now)).toBe(0);
  });

  it('treats a present-but-all-zero day the same as an absent day (today still gets skipped)', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: {} }), // present in the map, but truly all-zero
      [keyForDaysAgo(now, 1)]: buildDay({ a: { blocked: 1 } }),
    };
    expect(calculateStreak(usage, now)).toBe(1);
  });

  it('does not let a day further back re-extend the streak past an earlier break', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {
      [keyForDaysAgo(now, 0)]: buildDay({ a: { blocked: 1 } }),
      [keyForDaysAgo(now, 1)]: buildDay({ a: { blocked: 1 } }),
      [keyForDaysAgo(now, 2)]: buildDay({ a: { activeSec: 60 } }), // browsed, not blocked -> breaks
      [keyForDaysAgo(now, 3)]: buildDay({ a: { blocked: 1 } }), // would extend it, but is unreachable
    };
    expect(calculateStreak(usage, now)).toBe(2);
  });

  it('never looks back further than a 7-day window (i in 0..6)', () => {
    const now = new Date(2026, 5, 15, 9, 0);
    const usage: UsageByDay = {};
    for (let i = 0; i <= 9; i++) {
      usage[keyForDaysAgo(now, i)] = buildDay({ a: { blocked: 1 } });
    }
    expect(calculateStreak(usage, now)).toBe(7);
  });
});

/**
 * Feature-surface buckets live in the SAME day map as sites, keyed `domain#gate`, and the
 * seconds in them are a SUBSET of the site's — the same minute of Shorts is recorded twice.
 * So every aggregation that treats the map's keys as "the sites" has to exclude them, or
 * the day total silently runs high and the stats table grows a site the user does not have.
 *
 * These cases cover the whole class rather than one instance: every exported aggregation
 * gets the same mixed day, because the ones that would forget the filter fail quietly, with
 * numbers that merely look a bit too large.
 */
describe('feature-surface buckets are not sites', () => {
  const SHORTS_KEY = 'youtube.com#shorts';

  function hourAt(hour: number, seconds: number): number[] {
    const hourly = new Array<number>(24).fill(0);
    hourly[hour] = seconds;
    return hourly;
  }

  /** 10 minutes on YouTube, 4 of which were Shorts, plus 2 minutes on Reddit. */
  const mixedDay = (): Record<string, DayUsage> =>
    buildDay({
      'youtube.com': { activeSec: 600, blocked: 2, hourly: hourAt(9, 600) },
      [SHORTS_KEY]: { activeSec: 240, hourly: hourAt(9, 240) },
      'reddit.com': { activeSec: 120, hourly: hourAt(10, 120) },
    });

  it('counts the day’s time once, not once per bucket', () => {
    expect(totalActiveSeconds(mixedDay())).toBe(720);
  });

  it('keeps them out of the sites table', () => {
    expect(topSites(mixedDay()).map((site) => site.domain)).toEqual([
      'youtube.com',
      'reddit.com',
    ]);
  });

  it('keeps them out of the weekly series and the hourly heatmap', () => {
    const usage: UsageByDay = { '2026-09-20': mixedDay() };
    expect(weeklySeries(usage, ['2026-09-20'])[0]?.activeSec).toBe(720);
    expect(hourlyHeatmap(usage, '2026-09-20')[9]).toBe(600);
  });

  it('keeps them out of the all-time totals', () => {
    expect(allTimeTotals({ '2026-09-20': mixedDay() }).blocked).toBe(2);
  });

  it('never lets a surface bucket alone extend the streak', () => {
    const now = new Date(2026, 8, 20, 9, 0);
    const surfaceOnly: UsageByDay = {
      [localDayKey(now)]: buildDay({ [SHORTS_KEY]: { activeSec: 240 } }),
    };
    expect(calculateStreak(surfaceOnly, now)).toBe(0);
  });

  it('still reads one surface’s own total, which is what the dashboard shows', () => {
    expect(surfaceActiveSeconds(mixedDay(), 'youtube.com', 'shorts')).toBe(240);
    expect(surfaceActiveSeconds(mixedDay(), 'youtube.com', 'home')).toBe(0);
  });
});
