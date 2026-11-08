import { describe, expect, it } from 'vitest';

import { activeChannelWindow, resolveYoutubeFeatures } from '../../src/core/applies';
import { featureSummary, featureSummaryParts, isNothingActive } from '../../src/core/featureSummary';
import type {
  ChannelEntry,
  ScheduleChannelOverride,
  ScheduleOverride,
} from '../../src/core/settingsSchema';
import {
  effectiveChannelLists,
  isWhitelistedVideoUrl,
  limitSparesWhitelist,
  ruleUsesSubscriptions,
  survivesLimitCrossing,
} from '../../src/core/youtubeAccess';
import { featuresWith, scheduleOverride, siteRule } from '../helpers/rules';

function channel(handle: string, channelId: string | null = null): ChannelEntry {
  return { channelId, handle, displayName: handle, addedAt: 0 };
}

const STUDY = channel('study', 'UCstudy0000000000000001');
const JUNK = channel('junk', 'UCjunk00000000000000001');
const SUB = channel('sub', 'UCsub000000000000000001');

// 2026-11-02 is a Monday; every instant is explicit so the suite never depends on today.
const MON_10AM = new Date(2026, 10, 2, 10, 0);
const MON_8AM = new Date(2026, 10, 2, 8, 0);
const SAT_10AM = new Date(2026, 10, 7, 10, 0);

const override = (o: Partial<ScheduleChannelOverride> = {}): ScheduleChannelOverride => ({
  channelMode: 'WHITELIST',
  channels: [STUDY],
  allowSubscriptions: false,
  ...o,
});

const weekdayWindow = (o: Partial<ScheduleOverride> = {}) =>
  scheduleOverride({
    days: [1, 2, 3, 4, 5],
    startMinute: 540,
    endMinute: 1020,
    mode: 'HARD_BLOCK',
    channels: override(),
    ...o,
  });

/** YouTube open all day with a blacklist; a study whitelist only inside the window. */
function youtubeRule(
  schedule: ScheduleOverride | null = weekdayWindow(),
  youtube: Parameters<typeof featuresWith>[1] = {
    youtube: { channelMode: 'BLACKLIST', channels: [JUNK] },
  },
) {
  return siteRule({ mode: 'ALLOW', schedule, features: featuresWith('youtube', youtube) });
}

describe('resolveYoutubeFeatures', () => {
  it('uses the window list inside the window', () => {
    const resolved = resolveYoutubeFeatures(youtubeRule(), MON_10AM);
    expect(resolved?.channelMode).toBe('WHITELIST');
    expect(resolved?.channels).toEqual([STUDY]);
  });

  it('uses the default list outside the window', () => {
    const resolved = resolveYoutubeFeatures(youtubeRule(), MON_8AM);
    expect(resolved?.channelMode).toBe('BLACKLIST');
    expect(resolved?.channels).toEqual([JUNK]);
  });

  it('uses the default list on a day the window does not cover', () => {
    expect(resolveYoutubeFeatures(youtubeRule(), SAT_10AM)?.channelMode).toBe('BLACKLIST');
  });

  it.each([
    ['23:30', new Date(2026, 10, 2, 23, 30), 'WHITELIST'],
    ['05:00', new Date(2026, 10, 3, 5, 0), 'WHITELIST'],
    ['12:00', new Date(2026, 10, 2, 12, 0), 'BLACKLIST'],
  ])('an overnight window at %s is %s', (_label, now, expected) => {
    const rule = youtubeRule(weekdayWindow({ days: null, startMinute: 1380, endMinute: 360 }));
    expect(resolveYoutubeFeatures(rule, now)?.channelMode).toBe(expected);
  });

  it('ignores the window list when the schedule is disabled', () => {
    const rule = youtubeRule(weekdayWindow({ enabled: false }));
    expect(resolveYoutubeFeatures(rule, MON_10AM)?.channelMode).toBe('BLACKLIST');
  });

  it('returns the rule own settings object for a null override inside the window', () => {
    const rule = youtubeRule(weekdayWindow({ channels: null }));
    expect(resolveYoutubeFeatures(rule, MON_10AM)).toBe(rule.features?.youtube);
  });

  it('is undefined for a rule with no YouTube features', () => {
    const rule = siteRule({ domain: 'reddit.com', schedule: weekdayWindow({ channels: null }) });
    expect(resolveYoutubeFeatures(rule, MON_10AM)).toBeUndefined();
  });

  it('keeps every non-list field from the rule', () => {
    const rule = youtubeRule(weekdayWindow(), {
      youtube: {
        channelMode: 'BLACKLIST',
        channels: [JUNK],
        channelBlockMode: 'DELAY',
        creatorMode: true,
        subscribedChannels: [SUB],
      },
    });
    const resolved = resolveYoutubeFeatures(rule, MON_10AM);
    expect(resolved?.channelBlockMode).toBe('DELAY');
    expect(resolved?.creatorMode).toBe(true);
    expect(resolved?.subscribedChannels).toEqual([SUB]);
  });
});

describe('YouTube access through the window list', () => {
  const VIDEO = 'https://www.youtube.com/watch?v=abc123';
  const studyVideo = () => STUDY;

  it('exempts a window-whitelisted channel inside the window only', () => {
    const rules = [youtubeRule()];
    expect(isWhitelistedVideoUrl(rules, VIDEO, studyVideo, MON_10AM)).toBe(true);
    // Outside, the default BLACKLIST is in force and a blacklist spares nothing.
    expect(isWhitelistedVideoUrl(rules, VIDEO, studyVideo, MON_8AM)).toBe(false);
  });

  it('keeps a whitelisted video open past a spent limit only while the window is active', () => {
    const rules = [youtubeRule()];
    expect(survivesLimitCrossing(rules, VIDEO, studyVideo, MON_10AM)).toBe(true);
    expect(survivesLimitCrossing(rules, VIDEO, studyVideo, MON_8AM)).toBe(false);
  });

  it('limitSparesWhitelist follows the resolved list', () => {
    const rule = youtubeRule();
    const inside = resolveYoutubeFeatures(rule, MON_10AM);
    const outside = resolveYoutubeFeatures(rule, MON_8AM);
    expect(inside !== undefined && limitSparesWhitelist(inside)).toBe(true);
    expect(outside !== undefined && limitSparesWhitelist(outside)).toBe(false);
  });

  it('adds the synced subscriptions to the window whitelist only when the window asks', () => {
    const base = { subscribedChannels: [SUB], channelMode: 'BLACKLIST' as const, channels: [JUNK] };
    const withSubs = youtubeRule(
      weekdayWindow({ channels: override({ allowSubscriptions: true }) }),
      { youtube: base },
    );
    const without = youtubeRule(weekdayWindow(), { youtube: base });

    const lists = (rule: typeof withSubs, now: Date) =>
      effectiveChannelLists(resolveYoutubeFeatures(rule, now) ?? null, true);

    expect(lists(withSubs, MON_10AM).channels).toEqual([STUDY, SUB]);
    expect(lists(without, MON_10AM).channels).toEqual([STUDY]);
    // Outside the window the blacklist is back and subscriptions mean nothing.
    expect(lists(withSubs, MON_8AM)).toEqual({ channelMode: 'BLACKLIST', channels: [JUNK] });
  });
});

describe('ruleUsesSubscriptions', () => {
  it('is true for the default toggle', () => {
    const rule = youtubeRule(null, { youtube: { allowSubscriptions: true } });
    expect(ruleUsesSubscriptions(rule)).toBe(true);
  });

  it('is true for the window toggle alone', () => {
    const rule = youtubeRule(weekdayWindow({ channels: override({ allowSubscriptions: true }) }));
    expect(ruleUsesSubscriptions(rule)).toBe(true);
  });

  it('is false when neither asks, or the override is null', () => {
    expect(ruleUsesSubscriptions(youtubeRule())).toBe(false);
    expect(ruleUsesSubscriptions(youtubeRule(weekdayWindow({ channels: null })))).toBe(false);
  });
});

describe('feature summary with a window channel list', () => {
  const summaryOf = (channels: ScheduleChannelOverride | null, now?: Date) =>
    featureSummaryParts(youtubeRule(weekdayWindow({ channels })), now);

  it('prints the default list and the window list when no time is given', () => {
    expect(summaryOf(override({ channels: [STUDY, SUB] }))).toEqual([
      '1 channel blocked',
      'During schedule: only 2 channels allowed',
    ]);
  });

  it('prints a blacklist window as "N channels blocked"', () => {
    expect(summaryOf(override({ channelMode: 'BLACKLIST', channels: [JUNK, SUB] }))).toContain(
      'During schedule: 2 channels blocked',
    );
  });

  it.each<[string, ScheduleChannelOverride, string]>([
    ['an OFF list', override({ channelMode: 'OFF' }), 'During schedule: no channel list'],
    [
      'an empty blacklist',
      override({ channelMode: 'BLACKLIST', channels: [] }),
      'During schedule: no channel list',
    ],
    [
      'an empty whitelist with subscriptions',
      override({ channels: [], allowSubscriptions: true }),
      'During schedule: only subscriptions allowed',
    ],
    [
      'an empty whitelist without subscriptions',
      override({ channels: [] }),
      'During schedule: no channels allowed',
    ],
  ])('says so for %s', (_label, channels, expected) => {
    expect(summaryOf(channels)).toContain(expected);
  });

  it('omits the window part for "same as default" and for a disabled schedule', () => {
    expect(summaryOf(null)).toEqual(['1 channel blocked']);
    const disabled = youtubeRule(weekdayWindow({ enabled: false }));
    expect(featureSummaryParts(disabled)).toEqual(['1 channel blocked']);
  });

  it('prints only the window part when now is inside the window', () => {
    expect(summaryOf(override(), MON_10AM)).toEqual(['Now, until 17:00: only 1 channel allowed']);
  });

  it('says "Now" with the window end only while the window is live, never when merely configured', () => {
    expect(summaryOf(override(), MON_10AM)[0]).toMatch(/^Now, until 17:00: /);
    expect(summaryOf(override())).not.toContain('Now');
    expect(summaryOf(override(), MON_8AM).join('|')).not.toContain('Now');
  });

  it('names the end time of an overnight window, and drops "until" for an all-day one', () => {
    const overnight = youtubeRule(weekdayWindow({ days: null, startMinute: 1200, endMinute: 1425 }));
    expect(featureSummaryParts(overnight, new Date(2026, 10, 2, 21, 0))).toEqual([
      'Now, until 23:45: only 1 channel allowed',
    ]);
    const wraps = youtubeRule(weekdayWindow({ days: null, startMinute: 1320, endMinute: 360 }));
    // 02:00 is inside 22:00-06:00; the end is still 06:00.
    expect(featureSummaryParts(wraps, new Date(2026, 10, 3, 2, 0))).toEqual([
      'Now, until 06:00: only 1 channel allowed',
    ]);
    const allDay = youtubeRule(weekdayWindow({ startMinute: null, endMinute: null }));
    expect(featureSummaryParts(allDay, MON_10AM)).toEqual(['Now: only 1 channel allowed']);
  });

  it('prints both parts when now is outside the window', () => {
    expect(summaryOf(override(), MON_8AM)).toEqual([
      '1 channel blocked',
      'During schedule: only 1 channel allowed',
    ]);
  });

  it('joins the parts into the summary line', () => {
    expect(featureSummary(youtubeRule(weekdayWindow({ mode: 'ALLOW' })), MON_10AM)).toBe(
      'Now, until 17:00: only 1 channel allowed',
    );
  });

  it('is not "nothing active" for an Allow rule whose only effect is a window list', () => {
    const rule = youtubeRule(weekdayWindow({ mode: 'ALLOW' }), { youtube: { channelMode: 'OFF' } });
    expect(isNothingActive(rule)).toBe(false);
    // Control: the same rule without the window list does nothing.
    const bare = youtubeRule(weekdayWindow({ mode: 'ALLOW', channels: null }), {
      youtube: { channelMode: 'OFF' },
    });
    expect(isNothingActive(bare)).toBe(true);
  });
});

describe('activeChannelWindow', () => {
  it('returns the window span while its own list is in force', () => {
    expect(activeChannelWindow(youtubeRule(), MON_10AM)).toEqual({ startMinute: 540, endMinute: 1020 });
  });

  it('is null outside the window, for "same as default" and for a disabled schedule', () => {
    expect(activeChannelWindow(youtubeRule(), MON_8AM)).toBeNull();
    expect(activeChannelWindow(youtubeRule(weekdayWindow({ channels: null })), MON_10AM)).toBeNull();
    expect(activeChannelWindow(youtubeRule(weekdayWindow({ enabled: false })), MON_10AM)).toBeNull();
  });

  it('keeps null minutes for an all-day window', () => {
    const allDay = youtubeRule(weekdayWindow({ startMinute: null, endMinute: null }));
    expect(activeChannelWindow(allDay, MON_10AM)).toEqual({ startMinute: null, endMinute: null });
  });
});
