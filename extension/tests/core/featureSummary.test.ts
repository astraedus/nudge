import { describe, expect, it } from 'vitest';

import {
  NOTHING_ACTIVE,
  featureSummary,
  featureSummaryParts,
  isNothingActive,
} from '../../src/core/featureSummary';
import { featuresWith, scheduleOverride, siteRule } from '../helpers/rules';

/**
 * Three surfaces print this line (the popup card, the sites-list card, the rule editor) and
 * they are built by different layers, so the risk being tested is DRIFT: the same rule
 * describing itself differently depending on where the user looks.
 */

describe('featureSummary', () => {
  it('says nothing for a plain Hard Block — the mode chip is the whole story', () => {
    expect(featureSummary(siteRule({ mode: 'HARD_BLOCK' }))).toBeNull();
  });

  it('reports a daily limit', () => {
    expect(featureSummary(siteRule({ mode: 'ALLOW', dailyLimitMinutes: 30 }))).toBe('30m/day');
  });

  it('reports grayscale', () => {
    expect(featureSummary(siteRule({ mode: 'ALLOW', grayscale: true }))).toBe('Grayscale');
  });

  it('reports a gated surface with its pause', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'DELAY', delaySeconds: 15 } },
      }),
    });
    expect(featureSummary(rule)).toBe('Shorts: Delay 15s');
  });

  it('never prints a pause next to a Hard Block, because there is no countdown to wait out', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'HARD_BLOCK', delaySeconds: 45 } },
      }),
    });
    expect(featureSummary(rule)).toBe('Shorts: Hard Block');
  });

  it('reports an OFF gate that still carries a budget', () => {
    // "Unlimited Shorts until you have spent 10 minutes on them" is one of the two shapes
    // this feature exists for, so an OFF gate is not automatically inactive.
    const rule = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'OFF', dailyLimitMinutes: 10 } },
      }),
    });
    expect(featureSummary(rule)).toBe('Shorts: 10m/day');
  });

  /**
   * The COUNT axis (v0.3): a count-only gate must not summarise as "no effect" — it is the
   * only thing standing between the user and an afternoon of Shorts, and both budget axes
   * are independent, so both must be able to print, together or alone.
   */
  describe('the count budget (v0.3)', () => {
    it('reports an OFF gate whose only budget is a daily item count, pluralised via the registry noun', () => {
      const rule = siteRule({
        mode: 'ALLOW',
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'OFF', dailyLimitCount: 20 } },
        }),
      });
      expect(featureSummary(rule)).toBe('Shorts: 20 Shorts/day');
    });

    it('uses the SINGULAR noun when the count is exactly 1', () => {
      const rule = siteRule({
        mode: 'ALLOW',
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'OFF', dailyLimitCount: 1 } },
        }),
      });
      expect(featureSummary(rule)).toBe('Shorts: 1 Short/day');
    });

    it('lists BOTH budgets together, minutes first, when a gate carries both axes at once', () => {
      const rule = siteRule({
        mode: 'ALLOW',
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'OFF', dailyLimitMinutes: 10, dailyLimitCount: 20 } },
        }),
      });
      expect(featureSummary(rule)).toBe('Shorts: 10m/day, 20 Shorts/day');
    });

    it('still prints the mode label instead of a budget when the gate is actively gating, even with a count set', () => {
      // A gate whose mode is a real block mode already says so ("Shorts: Delay 15s"); the
      // budget clause is only for the OFF-but-budgeted shape, so this must not double up.
      const rule = siteRule({
        mode: 'ALLOW',
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'DELAY', delaySeconds: 15, dailyLimitCount: 20 } },
        }),
      });
      expect(featureSummary(rule)).toBe('Shorts: Delay 15s');
    });

    it('uses the item noun on Instagram Reels and TikTok too, not just YouTube Shorts', () => {
      const reels = siteRule({
        domain: 'instagram.com',
        mode: 'ALLOW',
        features: featuresWith('instagram', {
          gates: { reels: { mode: 'OFF', dailyLimitCount: 5 } },
        }),
      });
      expect(featureSummary(reels)).toBe('Reels: 5 Reels/day');

      const forYou = siteRule({
        domain: 'tiktok.com',
        mode: 'ALLOW',
        features: featuresWith('tiktok', {
          gates: { foryou: { mode: 'OFF', dailyLimitCount: 5 } },
        }),
      });
      expect(featureSummary(forYou)).toBe('For You feed: 5 videos/day');
    });

    it('falls back to a bare "N/day" for a gate the registry gives no item noun, rather than crashing', () => {
      // No shipped gate can actually reach this state through the editor (itemNoun and
      // itemPaths are pinned together by the registry invariant tests), but the pure
      // function itself has to stay defensive against a hand-edited/imported settings blob
      // that sets a count on a gate the registry never offered one for.
      const rule = siteRule({
        domain: 'instagram.com',
        mode: 'ALLOW',
        features: featuresWith('instagram', {
          gates: { explore: { mode: 'OFF', dailyLimitCount: 20 } },
        }),
      });
      expect(featureSummary(rule)).toBe('Explore: 20/day');
    });
  });

  it('names a single hidden element and counts several', () => {
    const one = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { hides: { comments: true } }),
    });
    expect(featureSummary(one)).toBe('Comments hidden');

    const many = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', { hides: { comments: true, homeFeed: true } }),
    });
    expect(featureSummary(many)).toBe('2 elements hidden');
  });

  it('describes a whitelist and a blacklist differently, because they mean opposite things', () => {
    const whitelist = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: {
          channelMode: 'WHITELIST',
          channels: [{ channelId: null, handle: 'a', displayName: '@a', addedAt: 0 }],
        },
      }),
    });
    expect(featureSummary(whitelist)).toBe('Only 1 channel allowed');

    const blacklist = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: {
          channelMode: 'BLACKLIST',
          channels: [
            { channelId: null, handle: 'a', displayName: '@a', addedAt: 0 },
            { channelId: null, handle: 'b', displayName: '@b', addedAt: 0 },
          ],
        },
      }),
    });
    expect(featureSummary(blacklist)).toBe('2 channels blocked');
  });

  it('ignores a configured list whose mode is OFF', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        youtube: {
          channelMode: 'OFF',
          channels: [{ channelId: null, handle: 'a', displayName: '@a', addedAt: 0 }],
        },
      }),
    });
    expect(featureSummary(rule)).toBeNull();
  });

  it('joins several active things in a stable order', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      dailyLimitMinutes: 30,
      grayscale: true,
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'DELAY', delaySeconds: 15 } },
        hides: { comments: true },
      }),
    });
    expect(featureSummary(rule)).toBe('30m/day · Grayscale · Shorts: Delay 15s · Comments hidden');
  });

  it('orders gates by the registry, not by however the object was written', () => {
    // Stable output is the point: a card that reshuffles its own summary between renders
    // reads as a bug even when every part of it is correct.
    const rule = siteRule({
      domain: 'instagram.com',
      mode: 'ALLOW',
      features: featuresWith('instagram', {
        gates: { home: { mode: 'HARD_BLOCK' }, reels: { mode: 'HARD_BLOCK' } },
      }),
    });
    expect(featureSummaryParts(rule)).toEqual(['Reels: Hard Block', 'Home feed: Hard Block']);
  });
});

describe('isNothingActive', () => {
  it('is true for an Allow rule with no limit, no grayscale and no feature on', () => {
    expect(isNothingActive(siteRule({ mode: 'ALLOW' }))).toBe(true);
  });

  it('is false as soon as anything is switched on', () => {
    expect(isNothingActive(siteRule({ mode: 'ALLOW', dailyLimitMinutes: 10 }))).toBe(false);
    expect(isNothingActive(siteRule({ mode: 'ALLOW', grayscale: true }))).toBe(false);
    expect(
      isNothingActive(
        siteRule({
          mode: 'ALLOW',
          features: featuresWith('youtube', { hides: { comments: true } }),
        }),
      ),
    ).toBe(false);
  });

  it('is false for a gate whose ONLY configured thing is a daily item count (v0.3)', () => {
    // "20 Shorts a day" alone is real protection — a rule this describes must never read as
    // having no effect.
    const rule = siteRule({
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'OFF', dailyLimitCount: 20 } },
      }),
    });
    expect(isNothingActive(rule)).toBe(false);
  });

  it('is false for a rule that only blocks inside a schedule window', () => {
    // "Block only during work hours" is an Allow rule with nothing else on, and it is very
    // much doing something.
    const rule = siteRule({
      mode: 'ALLOW',
      schedule: scheduleOverride({ mode: 'HARD_BLOCK' }),
    });
    expect(isNothingActive(rule)).toBe(false);
  });

  it('is false for any block mode', () => {
    expect(isNothingActive(siteRule({ mode: 'HARD_BLOCK' }))).toBe(false);
  });

  it('still reports a DISABLED rule by what it is configured to do', () => {
    // "Switched off" and "configured to do nothing" are different states with different
    // fixes; conflating them hides the second, which is the one the user needs told about.
    const rule = siteRule({ mode: 'ALLOW', enabled: false, dailyLimitMinutes: 10 });
    expect(isNothingActive(rule)).toBe(false);
  });

  it('exports the label so three surfaces cannot word it three ways', () => {
    expect(NOTHING_ACTIVE).toBe('Nothing active');
  });
});
