import { beforeEach, describe, expect, it } from 'vitest';

import {
  loadDay,
  loadSeenItems,
  saveDay,
  saveSeenItems,
  todayUsageSnapshot,
} from '../../src/background/storage';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { emptyDayUsage } from '../../src/core/stats';
import { surfaceKey } from '../../src/core/surfaceKeys';
import { resetBrowser } from './fakeApis';

/**
 * The read-repair and day-stamping behaviour storage.ts owns for v0.3's count axis.
 *
 * `chrome.storage` itself comes from `wxt/testing`'s `fakeBrowser` — a real in-memory
 * implementation, not a stub — so writing a raw pre-v0.3 shape straight at it (bypassing
 * `saveDay`, which always writes a complete `DayUsage`) is the only way to reproduce what
 * an upgrading user's storage genuinely looks like: a rollup with no `items` field at all.
 */

const NOW = new Date(2026, 8, 20, 12, 0, 0);

beforeEach(() => {
  resetBrowser();
});

describe('loadDay', () => {
  it('fills items: 0 on a rollup written before v0.3, rather than leaving it undefined', async () => {
    const dayKey = localDayKey(NOW);
    // A hand-built pre-v0.3 shape: no `items` field, exactly what storage.local held before
    // this release. `undefined + 1` is NaN, and no `NaN >= limit` comparison is ever true,
    // so a count budget built on an un-repaired rollup would fail open, silently.
    await chrome.storage.local.set({
      [`usage:${dayKey}`]: {
        'youtube.com': { activeSec: 60, blocked: 1, walkedAway: 0, hourly: new Array(24).fill(0) },
      },
    });

    const day = await loadDay(dayKey);

    expect(day['youtube.com']?.items).toBe(0);
    expect(day['youtube.com']?.activeSec).toBe(60);
    expect(day['youtube.com']?.blocked).toBe(1);
  });
});

describe('todayUsageSnapshot', () => {
  it('returns both the minute and the count axis for every key', async () => {
    const dayKey = localDayKey(NOW);
    await saveDay(dayKey, {
      'youtube.com': { ...emptyDayUsage(), activeSec: 600 },
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), activeSec: 300, items: 7 },
    });

    const snapshot = await todayUsageSnapshot(NOW);

    expect(snapshot.ms['youtube.com']).toBe(600_000);
    expect(snapshot.ms[surfaceKey('youtube.com', 'shorts')]).toBe(300_000);
    expect(snapshot.counts[surfaceKey('youtube.com', 'shorts')]).toBe(7);
    // A plain domain never carries an item count of its own — "one item" is a property of
    // a gate's stream — but the snapshot still reports 0 rather than an absent key, so a
    // compiler reading `usage.counts[key] ?? 0` and one reading it bare never disagree.
    expect(snapshot.counts['youtube.com']).toBe(0);
  });

  it('reads zero on both axes for a day with nothing recorded', async () => {
    const snapshot = await todayUsageSnapshot(NOW);
    expect(snapshot).toEqual({ ms: {}, counts: {} });
  });
});

describe('loadSeenItems / saveSeenItems', () => {
  it('discards a blob stamped with a different day', async () => {
    await saveSeenItems({ day: '2020-01-01', items: { [surfaceKey('youtube.com', 'shorts')]: ['x'] } });

    const state = await loadSeenItems(NOW);

    expect(state).toEqual({ day: localDayKey(NOW), items: {} });
  });

  it('round-trips a blob stamped with today', async () => {
    const today = localDayKey(NOW);
    await saveSeenItems({ day: today, items: { [surfaceKey('youtube.com', 'shorts')]: ['a', 'b'] } });

    const state = await loadSeenItems(NOW);

    expect(state).toEqual({ day: today, items: { [surfaceKey('youtube.com', 'shorts')]: ['a', 'b'] } });
  });

  it('reads empty when nothing has been saved yet', async () => {
    const state = await loadSeenItems(NOW);
    expect(state).toEqual({ day: localDayKey(NOW), items: {} });
  });
});
