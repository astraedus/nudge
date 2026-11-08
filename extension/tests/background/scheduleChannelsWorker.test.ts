import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SCHEDULE_ALARM, handleAlarm } from '../../src/background/alarmsHub';
import { compileRules } from '../../src/background/dnr';
import { handleRequest } from '../../src/background/messagesRouter';
import { SCHEDULE_EDGE_KEY, loadSettings, saveSettings } from '../../src/background/storage';
import type { ChannelObservedResult, SiteConfig, UsageSnapshot } from '../../src/core/protocol';
import type { ChannelEntry, NudgeSettings, ScheduleOverride } from '../../src/core/settingsSchema';
import { featuresWith, scheduleOverride, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser, type DnrState } from './fakeApis';

/**
 * A schedule window's own channel list, through the four worker paths that read or write it:
 * the DNR compile, the schedule-edge alarm, the settings write and CHANNEL_OBSERVED.
 */

const STUDY_ID = 'UCstudy0000000000000001';
const STUDY = (id: string | null = STUDY_ID): ChannelEntry => ({
  channelId: id,
  handle: 'study',
  displayName: id === null ? '@study' : 'Study',
  addedAt: 0,
});
const JUNK: ChannelEntry = {
  channelId: 'UCjunk00000000000000001',
  handle: 'junk',
  displayName: 'Junk',
  addedAt: 0,
};

// 2026-11-02 is a Monday. The window is Mon-Fri 09:00-17:00.
const INSIDE = new Date(2026, 10, 2, 10, 0);
const BEFORE = new Date(2026, 10, 2, 8, 59);
const EDGE = new Date(2026, 10, 2, 9, 0);
const NO_USAGE: UsageSnapshot = { ms: {}, counts: {} };

function window(overrides: Partial<ScheduleOverride> = {}): ScheduleOverride {
  return scheduleOverride({
    days: [1, 2, 3, 4, 5],
    startMinute: 540,
    endMinute: 1020,
    mode: 'HARD_BLOCK',
    channels: { channelMode: 'WHITELIST', channels: [STUDY()], allowSubscriptions: false },
    ...overrides,
  });
}

function youtubeSettings(
  ruleMode: 'ALLOW' | 'HARD_BLOCK',
  sched: ScheduleOverride,
  defaults: Parameters<typeof featuresWith>[1] = {
    youtube: { channelMode: 'BLACKLIST', channels: [JUNK] },
  },
): NudgeSettings {
  return settings({
    rules: [
      siteRule({
        domain: 'youtube.com',
        mode: ruleMode,
        schedule: sched,
        features: featuresWith('youtube', defaults),
      }),
    ],
  });
}

const allowRulesFor = (rules: chrome.declarativeNetRequest.Rule[], needle: string) =>
  rules.filter(
    (rule) =>
      rule.action.type === 'allow' && rule.condition.regexFilter?.includes(needle) === true,
  );

let dnr: DnrState;

beforeEach(() => {
  resetBrowser();
  dnr = installDnr();
  installAttention();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('DNR compile reads the window list', () => {
  it('allows the study channel inside the window, where the site is blocked', () => {
    const rules = compileRules(youtubeSettings('ALLOW', window()), NO_USAGE, INSIDE);
    expect(allowRulesFor(rules, STUDY_ID)).not.toHaveLength(0);
    // The default blacklist's channel is not an allow rule in either list.
    expect(allowRulesFor(rules, JUNK.channelId!)).toHaveLength(0);
  });

  it('compiles no allow rule outside the window: the site is open, nothing to carve out', () => {
    const rules = compileRules(youtubeSettings('ALLOW', window()), NO_USAGE, BEFORE);
    expect(allowRulesFor(rules, STUDY_ID)).toHaveLength(0);
    expect(rules).toHaveLength(0);
  });

  it('a Hard Block rule carves out the window list inside and nothing outside', () => {
    const config = youtubeSettings('HARD_BLOCK', window());
    expect(allowRulesFor(compileRules(config, NO_USAGE, INSIDE), STUDY_ID)).not.toHaveLength(0);
    // Outside, the default is a blacklist: the whole site is blocked, study included.
    expect(allowRulesFor(compileRules(config, NO_USAGE, BEFORE), STUDY_ID)).toHaveLength(0);
  });
});

describe('the schedule-edge alarm for a channel-only edge', () => {
  it('recompiles to the window list and records the edge', async () => {
    // Site mode is Hard Block both inside and outside the window, so ONLY the list differs.
    await saveSettings(
      youtubeSettings('HARD_BLOCK', window(), { youtube: { channelMode: 'OFF', channels: [] } }),
    );
    vi.useFakeTimers();

    vi.setSystemTime(BEFORE);
    await handleAlarm({ name: SCHEDULE_ALARM } as chrome.alarms.Alarm);
    expect(allowRulesFor(dnr.dynamic, STUDY_ID)).toHaveLength(0);

    vi.setSystemTime(EDGE);
    await handleAlarm({ name: SCHEDULE_ALARM } as chrome.alarms.Alarm);
    expect(allowRulesFor(dnr.dynamic, STUDY_ID)).not.toHaveLength(0);

    // Open tabs refetch their config when this changes.
    const stored = await chrome.storage.local.get(SCHEDULE_EDGE_KEY);
    expect(stored[SCHEDULE_EDGE_KEY]).toBe(EDGE.getTime());
  });
});

describe('saveSettings when storage.sync refuses the write', () => {
  const named = (name: string) =>
    settings({ rules: [siteRule({ domain: 'reddit.com', id: name })] });

  it('reads back the NEW settings, not the stale synced copy', async () => {
    await saveSettings(named('old'));
    vi.spyOn(chrome.storage.sync, 'set').mockRejectedValueOnce(
      new Error('QUOTA_BYTES_PER_ITEM quota exceeded'),
    );
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    await saveSettings(named('new'));

    // Counterfactual: before the fix the failed write was only swallowed, the old copy stayed
    // in sync, and loadSettings (which prefers sync) returned 'old'.
    expect((await loadSettings()).rules[0]?.id).toBe('new');
    expect(await chrome.storage.sync.get('nudge:settings')).toEqual({});
  });

  it('puts the settings back in sync on the next save that fits', async () => {
    vi.spyOn(chrome.storage.sync, 'set').mockRejectedValueOnce(new Error('quota'));
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await saveSettings(named('first'));
    await saveSettings(named('second'));

    expect((await chrome.storage.sync.get('nudge:settings'))['nudge:settings']).toBeDefined();
    expect((await loadSettings()).rules[0]?.id).toBe('second');
  });
});

describe('CHANNEL_OBSERVED with a window list', () => {
  it('teaches a handle-only entry in the window list its channel id', async () => {
    await saveSettings(
      youtubeSettings(
        'ALLOW',
        window({
          channels: { channelMode: 'WHITELIST', channels: [STUDY(null)], allowSubscriptions: false },
        }),
        { youtube: { channelMode: 'OFF', channels: [] } },
      ),
    );

    const result = (await handleRequest(
      {
        type: 'CHANNEL_OBSERVED',
        channelId: STUDY_ID,
        handle: '@study',
        displayName: 'Study',
      } as never,
      INSIDE,
    )) as ChannelObservedResult;

    expect(result).toEqual({ ok: true, changed: true, reason: 'enriched' });
    const stored = await loadSettings();
    expect(stored.rules[0]?.schedule?.channels?.channels).toEqual([STUDY()]);
    // The default list was empty and stays so: enrichment never adds a channel.
    expect(stored.rules[0]?.features?.youtube?.channels).toEqual([]);
  });
});

describe('GET_SITE_CONFIG with a window list', () => {
  const ask = (now: Date) =>
    handleRequest(
      { type: 'GET_SITE_CONFIG', url: 'https://www.youtube.com/watch?v=abc' } as never,
      now,
    ) as Promise<SiteConfig>;

  it('names the active window, computed by the worker, so the gate footer can say why', async () => {
    await saveSettings(youtubeSettings('ALLOW', window()));

    const inside = await ask(INSIDE);
    expect(inside.channelWindow).toEqual({ startMinute: 540, endMinute: 1020 });
    expect(inside.youtube?.channelMode).toBe('WHITELIST');
  });

  it('is null outside the window (the default list holds) and for "same as default"', async () => {
    await saveSettings(youtubeSettings('ALLOW', window()));
    const outside = await ask(BEFORE);
    expect(outside.channelWindow).toBeNull();
    expect(outside.youtube?.channelMode).toBe('BLACKLIST');

    await saveSettings(youtubeSettings('ALLOW', window({ channels: null })));
    expect((await ask(INSIDE)).channelWindow).toBeNull();
  });
});
