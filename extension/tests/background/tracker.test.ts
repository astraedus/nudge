import { beforeEach, describe, expect, it } from 'vitest';

import { accountAndSwitch, trackedGate } from '../../src/background/tracker';
import {
  loadDay,
  saveDay,
  saveSettings,
  saveTrackerState,
} from '../../src/background/storage';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { emptyDayUsage } from '../../src/core/stats';
import { surfaceKey } from '../../src/core/surfaceKeys';
import type { DayUsage } from '../../src/core/protocol';
import type { NudgeSettings } from '../../src/core/settingsSchema';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import {
  installAttention,
  installDnr,
  resetBrowser,
  type AttentionState,
  type DnrState,
} from './fakeApis';

/**
 * Feature budgets: an interval spent on a gate surface has to land in TWO buckets, and a
 * spent gate budget has to close that surface without closing the rest of the site.
 *
 * Asserted as what the user gets — "the Shorts page is now on the block page and the video
 * I was watching is not" — rather than as which branch ran.
 */

const START = new Date(2026, 8, 20, 12, 0, 0).getTime();
const ONE_MINUTE = 60_000;

let dnr: DnrState;
let attention: AttentionState;

async function seed(config: NudgeSettings, at = START): Promise<void> {
  await saveSettings(config);
  await saveDay(localDayKey(new Date(at)), {});
}

/** Close out an interval that started `minutes` ago on `url`. */
async function spend(url: string | null, minutes: number, endsAt = START): Promise<void> {
  const domain = url === null ? null : new URL(url).hostname.replace(/^www\./, '');
  await saveTrackerState({ domain, url, since: endsAt - minutes * ONE_MINUTE });
  await accountAndSwitch(null, endsAt);
}

async function usage(key: string, at = START): Promise<DayUsage | undefined> {
  const day = await loadDay(localDayKey(new Date(at)));
  return day[key];
}

beforeEach(() => {
  resetBrowser();
  dnr = installDnr();
  attention = installAttention();
});

describe('attributing a focused interval', () => {
  it('counts time on Shorts against both YouTube and the Shorts surface', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com', mode: 'ALLOW' })] }));

    await spend('https://www.youtube.com/shorts/abc', 2);

    expect((await usage('youtube.com'))?.activeSec).toBe(120);
    expect((await usage(surfaceKey('youtube.com', 'shorts')))?.activeSec).toBe(120);
  });

  it('counts ordinary browsing against the site only', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com', mode: 'ALLOW' })] }));

    await spend('https://www.youtube.com/watch?v=abc', 2);

    expect((await usage('youtube.com'))?.activeSec).toBe(120);
    expect(await usage(surfaceKey('youtube.com', 'shorts'))).toBeUndefined();
  });

  it('creates no surface bucket for a site that is not a known platform', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'news.example.com', mode: 'ALLOW' })] }));

    await spend('https://news.example.com/shorts/abc', 5);

    const day = await loadDay(localDayKey(new Date(START)));
    expect(Object.keys(day)).toEqual(['news.example.com']);
  });

  it('measures a surface even when no gate is configured for it yet', async () => {
    // So the dashboard's "of which Shorts" line has history the day a user turns a budget
    // on, instead of starting from zero and reading like a bug.
    await seed(settings({ rules: [] }));

    await spend('https://www.youtube.com/shorts/abc', 3);

    expect((await usage(surfaceKey('youtube.com', 'shorts')))?.activeSec).toBe(180);
  });
});

describe('trackedGate', () => {
  it('names the surface a URL is on, and nothing for a page that is not one', () => {
    expect(trackedGate('youtube.com', 'https://www.youtube.com/shorts/abc')).toBe('shorts');
    expect(trackedGate('youtube.com', 'https://www.youtube.com/watch?v=abc')).toBeNull();
    expect(trackedGate('news.example.com', 'https://news.example.com/')).toBeNull();
    expect(trackedGate('youtube.com', null)).toBeNull();
  });
});

describe('crossing a gate budget', () => {
  const tenMinutesOfShorts = settings({
    rules: [
      siteRule({
        domain: 'youtube.com',
        mode: 'ALLOW',
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'OFF', dailyLimitMinutes: 10 } },
        }),
      }),
    ],
  });

  beforeEach(() => {
    attention.tabs = [
      { id: 1, url: 'https://www.youtube.com/shorts/abc' },
      { id: 2, url: 'https://www.youtube.com/watch?v=xyz' },
    ];
  });

  it('closes the Shorts tab and leaves the video playing', async () => {
    await seed(tenMinutesOfShorts);
    await saveDay(localDayKey(new Date(START)), {
      'youtube.com': { ...emptyDayUsage(), activeSec: 9 * 60 },
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), activeSec: 9 * 60 },
    });

    await spend('https://www.youtube.com/shorts/abc', 2);

    expect(attention.navigatedTo[1]).toContain('blocked.html?target=');
    expect(attention.navigatedTo[2]).toBeUndefined();
  });

  it('recompiles the network rules, so walking back into Shorts blocks again', async () => {
    await seed(tenMinutesOfShorts);
    await saveDay(localDayKey(new Date(START)), {
      'youtube.com': { ...emptyDayUsage(), activeSec: 9 * 60 },
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), activeSec: 9 * 60 },
    });

    await spend('https://www.youtube.com/shorts/abc', 2);

    const matches = (url: string) =>
      dnr.dynamic.some((rule) => new RegExp(rule.condition.regexFilter!).test(url));
    expect(matches('https://www.youtube.com/shorts/def')).toBe(true);
    expect(matches('https://www.youtube.com/watch?v=xyz')).toBe(false);
  });

  it('does nothing while the surface is still under its budget', async () => {
    await seed(tenMinutesOfShorts);

    await spend('https://www.youtube.com/shorts/abc', 2);

    expect(attention.navigatedTo).toEqual({});
    expect(dnr.dynamic).toHaveLength(0);
  });

  it('does not fire again on the next tick once the budget is already spent', async () => {
    await seed(tenMinutesOfShorts);
    await saveDay(localDayKey(new Date(START)), {
      'youtube.com': { ...emptyDayUsage(), activeSec: 20 * 60 },
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), activeSec: 20 * 60 },
    });

    await spend('https://www.youtube.com/shorts/abc', 1);

    expect(attention.navigatedTo).toEqual({});
  });
});

describe('crossing the site budget', () => {
  it('closes every tab on the site, gate surfaces included', async () => {
    attention.tabs = [
      { id: 1, url: 'https://www.youtube.com/shorts/abc' },
      { id: 2, url: 'https://www.youtube.com/watch?v=xyz' },
      { id: 3, url: 'https://news.example.com/' },
    ];
    await seed(
      settings({
        rules: [
          siteRule({ domain: 'youtube.com', mode: 'ALLOW', dailyLimitMinutes: 10 }),
        ],
      }),
    );
    await saveDay(localDayKey(new Date(START)), {
      'youtube.com': { ...emptyDayUsage(), activeSec: 9 * 60 },
    });

    await spend('https://www.youtube.com/watch?v=xyz', 2);

    expect(Object.keys(attention.navigatedTo).sort()).toEqual(['1', '2']);
  });
});
