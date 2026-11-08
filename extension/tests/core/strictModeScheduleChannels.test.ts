import { describe, expect, it } from 'vitest';

import type {
  ChannelEntry,
  ChannelListMode,
  ScheduleChannelOverride,
  ScheduleOverride,
  YoutubeFeatureSettings,
} from '../../src/core/settingsSchema';
import { isWeakening } from '../../src/core/strictMode';
import { featuresWith, scheduleOverride, settings, siteRule } from '../helpers/rules';

/**
 * The Commitment Lock over a schedule window's own channel list (v0.8). The list in force
 * INSIDE the window is compared with the same rules as the default list, so a window cannot
 * be used to sneak a weaker list past the lock.
 */

function channel(handle: string, channelId: string | null = null): ChannelEntry {
  return { channelId, handle, displayName: handle, addedAt: 0 };
}

const A = channel('a');
const B = channel('b');
const X = channel('x');

const window = (
  channelMode: ChannelListMode,
  channels: ChannelEntry[],
  allowSubscriptions = false,
): ScheduleChannelOverride => ({ channelMode, channels, allowSubscriptions });

const schedule = (
  channels: ScheduleChannelOverride | null,
  overrides: Partial<ScheduleOverride> = {},
): ScheduleOverride =>
  scheduleOverride({
    mode: 'ALLOW',
    days: [1, 2, 3, 4, 5],
    startMinute: 540,
    endMinute: 1020,
    channels,
    ...overrides,
  });

/** One YouTube rule: its default list, plus a schedule (or none). */
function config(
  defaults: Partial<YoutubeFeatureSettings>,
  sched: ScheduleOverride | null,
) {
  return settings({
    rules: [
      siteRule({
        mode: 'ALLOW',
        schedule: sched,
        features: featuresWith('youtube', { youtube: defaults }),
      }),
    ],
  });
}

const OFF_DEFAULT = { channelMode: 'OFF' as const, channels: [] };
const withWindow = (w: ScheduleChannelOverride | null, sched: Partial<ScheduleOverride> = {}) =>
  config(OFF_DEFAULT, schedule(w, sched));

describe('window channel list: weakenings', () => {
  it.each<[string, ScheduleChannelOverride, ScheduleChannelOverride]>([
    ['adding a channel to the window whitelist', window('WHITELIST', [A]), window('WHITELIST', [A, B])],
    ['removing one from the window blacklist', window('BLACKLIST', [A, B]), window('BLACKLIST', [A])],
    ['window WHITELIST -> BLACKLIST', window('WHITELIST', [A]), window('BLACKLIST', [A])],
    ['window WHITELIST -> OFF', window('WHITELIST', [A]), window('OFF', [A])],
    [
      'window BLACKLIST[a] -> WHITELIST[x] (switching INTO a whitelist with entries)',
      window('BLACKLIST', [A]),
      window('WHITELIST', [X]),
    ],
    [
      'turning subscriptions on for a window whitelist',
      window('WHITELIST', [A], false),
      window('WHITELIST', [A], true),
    ],
  ])('%s', (_name, before, after) => {
    expect(isWeakening(withWindow(before), withWindow(after))).toBe(true);
  });

  it('dropping the override when the default list is weaker', () => {
    const before = config(
      { channelMode: 'BLACKLIST', channels: [A] },
      schedule(window('WHITELIST', [X])),
    );
    const after = config({ channelMode: 'BLACKLIST', channels: [A] }, schedule(null));
    expect(isWeakening(before, after)).toBe(true);
  });

  it.each([
    ['disabling the schedule', (s: ScheduleOverride) => ({ ...s, enabled: false })],
    ['deleting the schedule', () => null],
  ])('%s when the window whitelist was stricter than the default', (_name, change) => {
    const sched = schedule(window('WHITELIST', [X]));
    const defaults = { channelMode: 'BLACKLIST' as const, channels: [A] };
    expect(isWeakening(config(defaults, sched), config(defaults, change(sched)))).toBe(true);
  });
});

describe('window channel list: not weakenings', () => {
  it.each<[string, ScheduleChannelOverride, ScheduleChannelOverride]>([
    ['removing from the window whitelist', window('WHITELIST', [A, B]), window('WHITELIST', [A])],
    ['adding to the window blacklist', window('BLACKLIST', [A]), window('BLACKLIST', [A, B])],
    ['window OFF -> BLACKLIST', window('OFF', []), window('BLACKLIST', [A])],
    ['turning on an EMPTY window whitelist', window('OFF', []), window('WHITELIST', [])],
    [
      'turning subscriptions off',
      window('WHITELIST', [A], true),
      window('WHITELIST', [A], false),
    ],
  ])('%s', (_name, before, after) => {
    expect(isWeakening(withWindow(before), withWindow(after))).toBe(false);
  });

  it.each<[string, Partial<YoutubeFeatureSettings>]>([
    ['the same', { channelMode: 'WHITELIST', channels: [A] }],
    ['stronger', { channelMode: 'WHITELIST', channels: [] }],
  ])('dropping the override when the default is %s', (_name, defaults) => {
    // Window whitelist [A] -> "same as default": the default allows no more than A did.
    const before = config(defaults, schedule(window('WHITELIST', [A])));
    expect(isWeakening(before, config(defaults, schedule(null)))).toBe(false);
  });

  it('editing the override of a DISABLED schedule', () => {
    const off = { enabled: false };
    expect(
      isWeakening(
        withWindow(window('WHITELIST', [A]), off),
        withWindow(window('WHITELIST', [A, B, X]), off),
      ),
    ).toBe(false);
  });

  it('an enriched entry (handle-only -> handle + id) in the window list', () => {
    const learned = { ...A, channelId: 'UCaaaaaaaaaaaaaaaaaaaaaa' };
    expect(
      isWeakening(withWindow(window('WHITELIST', [A])), withWindow(window('WHITELIST', [learned]))),
    ).toBe(false);
  });

  it('an unchanged window', () => {
    expect(isWeakening(withWindow(window('WHITELIST', [A])), withWindow(window('WHITELIST', [A])))).toBe(
      false,
    );
  });
});
