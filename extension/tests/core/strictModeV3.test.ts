import { describe, expect, it } from 'vitest';

import { isWeakening } from '../../src/core/strictMode';
import type { GateSetting, NudgeSettings, SiteRule } from '../../src/core/settingsSchema';
import type { ChannelEntry } from '../../src/core/settingsSchema';
import { featuresWith, scheduleOverride, settings, siteRule } from '../helpers/rules';

/**
 * Strict Mode's weakening detector, across every field v0.2 added.
 *
 * A gate that misses a weakening is worse than no gate: the user believed the commitment
 * lock covered their settings, so they never built any other defence. Each axis is
 * therefore asserted in BOTH directions — softening is challenged, strengthening is not —
 * because a detector that returns `true` for everything passes half these cases while
 * making the product unusable and training users to resent the challenge.
 */

function withRule(overrides: Partial<SiteRule>): NudgeSettings {
  return settings({ rules: [siteRule({ id: 'r1', domain: 'youtube.com', ...overrides })] });
}

function withGate(gate: Partial<GateSetting>): NudgeSettings {
  return withRule({
    mode: 'ALLOW',
    features: featuresWith('youtube', { gates: { shorts: gate } }),
  });
}

function channel(handle: string): ChannelEntry {
  return { channelId: null, handle, displayName: `@${handle}`, addedAt: 0 };
}

function withChannels(
  channelMode: 'OFF' | 'BLACKLIST' | 'WHITELIST',
  handles: string[],
  siteMode: SiteRule['mode'] = 'ALLOW',
): NudgeSettings {
  return withRule({
    mode: siteMode,
    features: featuresWith('youtube', {
      youtube: { channelMode, channels: handles.map(channel) },
    }),
  });
}

describe('weakening: the site mode gained ALLOW', () => {
  it('Hard Block -> Allow is weakening', () => {
    expect(isWeakening(withRule({ mode: 'HARD_BLOCK' }), withRule({ mode: 'ALLOW' }))).toBe(true);
  });

  it('Breathing -> Allow is weakening (Allow is the weakest rung of all)', () => {
    expect(isWeakening(withRule({ mode: 'BREATHING' }), withRule({ mode: 'ALLOW' }))).toBe(true);
  });

  it('Allow -> Hard Block is not weakening', () => {
    expect(isWeakening(withRule({ mode: 'ALLOW' }), withRule({ mode: 'HARD_BLOCK' }))).toBe(false);
  });

  it('adding a daily limit to an Allow rule is not weakening', () => {
    const before = withRule({ mode: 'ALLOW', dailyLimitMinutes: null });
    const after = withRule({ mode: 'ALLOW', dailyLimitMinutes: 30 });
    expect(isWeakening(before, after)).toBe(false);
  });

  it('raising an Allow rule’s daily limit is weakening', () => {
    const before = withRule({ mode: 'ALLOW', dailyLimitMinutes: 30 });
    const after = withRule({ mode: 'ALLOW', dailyLimitMinutes: 120 });
    expect(isWeakening(before, after)).toBe(true);
  });
});

describe('weakening: grayscale', () => {
  it('turning grayscale off is weakening', () => {
    expect(isWeakening(withRule({ grayscale: true }), withRule({ grayscale: false }))).toBe(true);
  });

  it('turning grayscale on is not weakening', () => {
    expect(isWeakening(withRule({ grayscale: false }), withRule({ grayscale: true }))).toBe(false);
  });
});

describe('weakening: the schedule window is now a second, independent mode axis', () => {
  it('softening the mode inside the window is weakening, even with the default untouched', () => {
    const before = withRule({
      mode: 'ALLOW',
      schedule: scheduleOverride({ mode: 'HARD_BLOCK' }),
    });
    const after = withRule({ mode: 'ALLOW', schedule: scheduleOverride({ mode: 'BREATHING' }) });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('deleting a BLOCKING window from an Allow rule is weakening', () => {
    // "Block only during work hours" -> no protection at all.
    const before = withRule({ mode: 'ALLOW', schedule: scheduleOverride({ mode: 'HARD_BLOCK' }) });
    const after = withRule({ mode: 'ALLOW', schedule: null });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('deleting an ALLOWANCE window from a blocking rule is NOT weakening', () => {
    // Removing "open at lunchtime" makes the site MORE blocked. Challenging a user for
    // strengthening their own settings is how a gate loses its credibility.
    const before = withRule({ mode: 'HARD_BLOCK', schedule: scheduleOverride({ mode: 'ALLOW' }) });
    const after = withRule({ mode: 'HARD_BLOCK', schedule: null });
    expect(isWeakening(before, after)).toBe(false);
  });

  it('disabling a blocking window is weakening', () => {
    const before = withRule({ mode: 'ALLOW', schedule: scheduleOverride({ mode: 'HARD_BLOCK' }) });
    const after = withRule({
      mode: 'ALLOW',
      schedule: scheduleOverride({ mode: 'HARD_BLOCK', enabled: false }),
    });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('shortening the pause inside the window is weakening', () => {
    const before = withRule({ schedule: scheduleOverride({ mode: 'DELAY', delaySeconds: 60 }) });
    const after = withRule({ schedule: scheduleOverride({ mode: 'DELAY', delaySeconds: 5 }) });
    expect(isWeakening(before, after)).toBe(true);
  });
});

describe('weakening: feature gates', () => {
  it('softening a gate mode is weakening', () => {
    expect(isWeakening(withGate({ mode: 'HARD_BLOCK' }), withGate({ mode: 'DELAY' }))).toBe(true);
  });

  it('turning a gate off is weakening', () => {
    expect(isWeakening(withGate({ mode: 'DELAY' }), withGate({ mode: 'OFF' }))).toBe(true);
  });

  it('turning a gate on is not weakening', () => {
    expect(isWeakening(withGate({ mode: 'OFF' }), withGate({ mode: 'DELAY' }))).toBe(false);
  });

  it('shortening a gate’s pause is weakening', () => {
    const before = withGate({ mode: 'DELAY', delaySeconds: 60 });
    const after = withGate({ mode: 'DELAY', delaySeconds: 5 });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('raising a gate’s daily budget is weakening', () => {
    const before = withGate({ mode: 'OFF', dailyLimitMinutes: 10 });
    const after = withGate({ mode: 'OFF', dailyLimitMinutes: 60 });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('removing a gate’s daily budget is weakening', () => {
    const before = withGate({ mode: 'OFF', dailyLimitMinutes: 10 });
    const after = withGate({ mode: 'OFF', dailyLimitMinutes: null });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('adding a budget where there was none is not weakening', () => {
    const before = withGate({ mode: 'OFF', dailyLimitMinutes: null });
    const after = withGate({ mode: 'OFF', dailyLimitMinutes: 10 });
    expect(isWeakening(before, after)).toBe(false);
  });

  it('dropping the whole features block is weakening', () => {
    const before = withGate({ mode: 'HARD_BLOCK' });
    const after = withRule({ mode: 'ALLOW', features: null });
    expect(isWeakening(before, after)).toBe(true);
  });
});

describe('weakening: hide toggles', () => {
  it('switching a hide off is weakening', () => {
    const before = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { hides: { comments: true } }),
    });
    const after = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { hides: { comments: false } }),
    });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('switching a hide on is not weakening', () => {
    const before = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { hides: { comments: false } }),
    });
    const after = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { hides: { comments: true } }),
    });
    expect(isWeakening(before, after)).toBe(false);
  });
});

describe('weakening: the YouTube channel lists cut in OPPOSITE directions', () => {
  it('adding a channel to a WHITELIST is weakening', () => {
    // A whitelist is default-deny, so a longer list allows MORE.
    const before = withChannels('WHITELIST', ['veritasium']);
    const after = withChannels('WHITELIST', ['veritasium', 'mrbeast']);
    expect(isWeakening(before, after)).toBe(true);
  });

  it('removing a channel from a WHITELIST is not weakening', () => {
    const before = withChannels('WHITELIST', ['veritasium', 'mrbeast']);
    const after = withChannels('WHITELIST', ['veritasium']);
    expect(isWeakening(before, after)).toBe(false);
  });

  it('removing a channel from a BLACKLIST is weakening', () => {
    // A blacklist is default-allow, so a shorter list blocks LESS. The exact opposite of
    // the whitelist case above, which is why no single "did the array shrink" rule works.
    const before = withChannels('BLACKLIST', ['veritasium', 'mrbeast']);
    const after = withChannels('BLACKLIST', ['veritasium']);
    expect(isWeakening(before, after)).toBe(true);
  });

  it('adding a channel to a BLACKLIST is not weakening', () => {
    const before = withChannels('BLACKLIST', ['veritasium']);
    const after = withChannels('BLACKLIST', ['veritasium', 'mrbeast']);
    expect(isWeakening(before, after)).toBe(false);
  });

  it('switching a WHITELIST to a BLACKLIST is weakening', () => {
    // Both are "a list is configured", but only one is default-deny — and this is the
    // switch a user reaches for at exactly the moment the whitelist is doing its job.
    const before = withChannels('WHITELIST', ['veritasium']);
    const after = withChannels('BLACKLIST', ['veritasium']);
    expect(isWeakening(before, after)).toBe(true);
  });

  it('turning a list off is weakening; turning one on is not', () => {
    expect(isWeakening(withChannels('BLACKLIST', ['a']), withChannels('OFF', ['a']))).toBe(true);
    expect(isWeakening(withChannels('OFF', ['a']), withChannels('WHITELIST', ['a']))).toBe(false);
  });

  it('matches a channel by EITHER identifier, so re-saving an enriched entry is not weakening', () => {
    // The same channel is captured by handle from a feed card and by id from a watch page;
    // an identity check on one field alone would read the enrichment as "a new channel".
    const before = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: {
          channelMode: 'WHITELIST',
          channels: [{ channelId: null, handle: 'veritasium', displayName: 'V', addedAt: 0 }],
        },
      }),
    });
    const after = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: {
          channelMode: 'WHITELIST',
          channels: [
            { channelId: 'UCveritasium00000000001', handle: 'veritasium', displayName: 'Veritasium', addedAt: 0 },
          ],
        },
      }),
    });
    expect(isWeakening(before, after)).toBe(false);
  });

  it('softening the mode applied to a disallowed channel is weakening', () => {
    const before = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: { channelMode: 'WHITELIST', channelBlockMode: 'HARD_BLOCK' },
      }),
    });
    const after = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: { channelMode: 'WHITELIST', channelBlockMode: 'BREATHING' },
      }),
    });
    expect(isWeakening(before, after)).toBe(true);
  });

  it('turning autoplay-off back off is weakening', () => {
    const before = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { youtube: { disableAutoplay: true } }),
    });
    const after = withRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { youtube: { disableAutoplay: false } }),
    });
    expect(isWeakening(before, after)).toBe(true);
  });
});

describe('weakening: identity', () => {
  it('an unchanged v3 settings object with every feature configured is not weakening', () => {
    const configured = withRule({
      mode: 'DELAY',
      grayscale: true,
      dailyLimitMinutes: 30,
      schedule: scheduleOverride({ mode: 'HARD_BLOCK' }),
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'HARD_BLOCK', dailyLimitMinutes: 10 } },
        hides: { comments: true, homeFeed: true },
        youtube: { channelMode: 'WHITELIST', channels: [channel('veritasium')] },
      }),
    });
    expect(isWeakening(configured, JSON.parse(JSON.stringify(configured)))).toBe(false);
  });

  it('adding a brand-new rule is never weakening', () => {
    const before = settings({ rules: [] });
    const after = withRule({ mode: 'HARD_BLOCK' });
    expect(isWeakening(before, after)).toBe(false);
  });
});
