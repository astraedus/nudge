import { beforeEach, describe, expect, it } from 'vitest';

import {
  applyRules,
  applyTempAllows,
  compileRules,
  compiledRuleCount,
  redirectOpenTabs,
  youtubeWhitelistPaths,
  type UsageByKey,
} from '../../src/background/dnr';
import { saveDay } from '../../src/background/storage';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { emptyDayUsage } from '../../src/core/stats';
import { surfaceKey } from '../../src/core/surfaceKeys';
import { enrichEntries } from '../../src/core/channels';
import type { ChannelEntry } from '../../src/core/settingsSchema';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser } from './fakeApis';

/**
 * What the network layer does, asserted as the thing the USER experiences: a URL either
 * lands on the block page, is explicitly let through, or is never touched at all.
 *
 * Written this way on purpose. The repo has already paid once for tests that encoded the
 * branch a compiler took rather than the outcome, and happily asserted a bug (see the
 * redirect-loop lesson in extension/CLAUDE.md). "There is a rule whose regexFilter equals
 * X" is that same mistake in a new place — it passes just as well when the pattern is
 * subtly unmatchable, which is the failure that actually ships.
 *
 * So this resolves a URL against the compiled set the way Chrome does: every matching rule
 * competes, highest priority wins, `allow` beats `redirect` only by outranking it.
 */
type Outcome = 'block-page' | 'allowed' | 'untouched';

function resolve(rules: chrome.declarativeNetRequest.Rule[], url: string): Outcome {
  const matching = rules.filter((rule) => {
    const pattern = rule.condition.regexFilter;
    return pattern !== undefined && new RegExp(pattern).test(url);
  });
  if (matching.length === 0) return 'untouched';

  const winner = matching.reduce((best, rule) =>
    (rule.priority ?? 0) > (best.priority ?? 0) ? rule : best,
  );
  return winner.action.type === 'allow' ? 'allowed' : 'block-page';
}

const MIDDAY = new Date(2026, 8, 20, 12, 0, 0);
const NO_USAGE: UsageByKey = {};

function channel(overrides: Partial<ChannelEntry>): ChannelEntry {
  return { channelId: null, handle: null, displayName: 'x', addedAt: 0, ...overrides };
}

beforeEach(() => {
  resetBrowser();
});

describe('site rules', () => {
  it('sends a Hard Block site to the block page', () => {
    const rules = compileRules(
      settings({ rules: [siteRule({ domain: 'youtube.com', mode: 'HARD_BLOCK' })] }),
      NO_USAGE,
      MIDDAY,
    );
    expect(resolve(rules, 'https://www.youtube.com/')).toBe('block-page');
  });

  it('leaves every site alone while Nudge itself is switched off', () => {
    const rules = compileRules(
      settings({
        globalEnabled: false,
        rules: [siteRule({ domain: 'youtube.com', mode: 'HARD_BLOCK' })],
      }),
      NO_USAGE,
      MIDDAY,
    );
    expect(resolve(rules, 'https://www.youtube.com/')).toBe('untouched');
  });

  it('opens a limit-only Allow site normally while it is under budget', () => {
    const rule = siteRule({ domain: 'reddit.com', mode: 'ALLOW', dailyLimitMinutes: 30 });
    const rules = compileRules(
      settings({ rules: [rule] }),
      { 'reddit.com': 29 * 60_000 },
      MIDDAY,
    );
    expect(resolve(rules, 'https://www.reddit.com/r/all')).toBe('untouched');
  });

  it('blocks the same limit-only Allow site once the budget is spent', () => {
    const rule = siteRule({ domain: 'reddit.com', mode: 'ALLOW', dailyLimitMinutes: 30 });
    const rules = compileRules(
      settings({ rules: [rule] }),
      { 'reddit.com': 30 * 60_000 },
      MIDDAY,
    );
    expect(resolve(rules, 'https://www.reddit.com/r/all')).toBe('block-page');
  });

  it('carries the whole original URL to the block page, query string included', () => {
    const [rule] = compileRules(
      settings({ rules: [siteRule({ domain: 'youtube.com' })] }),
      NO_USAGE,
      MIDDAY,
    );
    const url = 'https://www.youtube.com/watch?v=abc&t=30';
    const pattern = new RegExp(rule!.condition.regexFilter!);
    // `\0` is the ENTIRE match, which is only the whole URL because the pattern is anchored.
    expect(url.replace(pattern, '$&')).toBe(url);
    expect(rule!.action.redirect?.regexSubstitution).toContain('?target=\\0');
  });
});

describe('feature gates', () => {
  const shortsRule = (siteMode: 'ALLOW' | 'HARD_BLOCK') =>
    siteRule({
      domain: 'youtube.com',
      mode: siteMode,
      features: featuresWith('youtube', { gates: { shorts: { mode: 'HARD_BLOCK' } } }),
    });

  it('sends the Shorts page to the block page even though the site itself is allowed', () => {
    const rules = compileRules(settings({ rules: [shortsRule('ALLOW')] }), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/shorts/abc123')).toBe('block-page');
  });

  it('still opens the rest of that site normally', () => {
    const rules = compileRules(settings({ rules: [shortsRule('ALLOW')] }), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/watch?v=abc')).toBe('untouched');
    expect(resolve(rules, 'https://www.youtube.com/')).toBe('untouched');
  });

  it('outranks the site rule, so a gated surface is caught on a blocked site too', () => {
    const rules = compileRules(
      settings({ rules: [shortsRule('HARD_BLOCK')] }),
      NO_USAGE,
      MIDDAY,
    );
    const shorts = rules.filter((rule) =>
      new RegExp(rule.condition.regexFilter!).test('https://www.youtube.com/shorts/x'),
    );
    const site = rules.find((rule) => rule.condition.regexFilter?.includes('[/?#]'));
    const topShorts = Math.max(...shorts.map((rule) => rule.priority ?? 0));
    expect(topShorts).toBeGreaterThan(site!.priority ?? 0);
  });

  it('gates a surface whose own budget is spent even when its mode is Off', () => {
    const rule = siteRule({
      domain: 'youtube.com',
      mode: 'ALLOW',
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'OFF', dailyLimitMinutes: 10 } },
      }),
    });
    const under = compileRules(
      settings({ rules: [rule] }),
      { [surfaceKey('youtube.com', 'shorts')]: 9 * 60_000 },
      MIDDAY,
    );
    const spent = compileRules(
      settings({ rules: [rule] }),
      { [surfaceKey('youtube.com', 'shorts')]: 10 * 60_000 },
      MIDDAY,
    );
    expect(resolve(under, 'https://www.youtube.com/shorts/abc')).toBe('untouched');
    expect(resolve(spent, 'https://www.youtube.com/shorts/abc')).toBe('block-page');
  });

  it('does not let the SITE budget close a gate, or the GATE budget close the site', () => {
    const rule = siteRule({
      domain: 'instagram.com',
      mode: 'ALLOW',
      dailyLimitMinutes: 60,
      features: featuresWith('instagram', {
        gates: { reels: { mode: 'OFF', dailyLimitMinutes: 5 } },
      }),
    });
    const rules = compileRules(
      settings({ rules: [rule] }),
      {
        'instagram.com': 10 * 60_000,
        [surfaceKey('instagram.com', 'reels')]: 5 * 60_000,
      },
      MIDDAY,
    );
    expect(resolve(rules, 'https://www.instagram.com/reels/')).toBe('block-page');
    expect(resolve(rules, 'https://www.instagram.com/p/abc/')).toBe('untouched');
  });

  it('matches a home-feed gate on the root only, not on every page of the site', () => {
    const rule = siteRule({
      domain: 'instagram.com',
      mode: 'ALLOW',
      features: featuresWith('instagram', { gates: { home: { mode: 'BREATHING' } } }),
    });
    const rules = compileRules(settings({ rules: [rule] }), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.instagram.com/')).toBe('block-page');
    expect(resolve(rules, 'https://www.instagram.com/?hl=en')).toBe('block-page');
    expect(resolve(rules, 'https://www.instagram.com/someone/')).toBe('untouched');
  });

  it('ignores the features of a switched-off rule', () => {
    const rules = compileRules(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'ALLOW',
            enabled: false,
            features: featuresWith('youtube', { gates: { shorts: { mode: 'HARD_BLOCK' } } }),
          }),
        ],
      }),
      NO_USAGE,
      MIDDAY,
    );
    expect(resolve(rules, 'https://www.youtube.com/shorts/abc')).toBe('untouched');
  });
});

describe('YouTube channel whitelist', () => {
  const whitelisted = (channels: ChannelEntry[]) =>
    settings({
      rules: [
        siteRule({
          domain: 'youtube.com',
          mode: 'HARD_BLOCK',
          features: featuresWith('youtube', {
            youtube: { channelMode: 'WHITELIST', channels },
          }),
        }),
      ],
    });

  const veritasium = channel({ handle: 'veritasium', channelId: 'UCHnyfMqiRRG1u-2MsSQLbXA' });

  it('lets a video page and the allowed channel through while the site stays blocked', () => {
    const rules = compileRules(whitelisted([veritasium]), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/watch?v=abc')).toBe('allowed');
    expect(resolve(rules, 'https://www.youtube.com/@veritasium')).toBe('allowed');
    expect(resolve(rules, 'https://www.youtube.com/@veritasium/videos')).toBe('allowed');
    expect(resolve(rules, 'https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA')).toBe(
      'allowed',
    );
  });

  it('keeps the home feed, subscriptions, search and Shorts blocked', () => {
    const rules = compileRules(whitelisted([veritasium]), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/feed/subscriptions')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/results?search_query=cats')).toBe(
      'block-page',
    );
    expect(resolve(rules, 'https://www.youtube.com/shorts/abc')).toBe('block-page');
  });

  it('does not let a channel someone else allowed through', () => {
    const rules = compileRules(whitelisted([veritasium]), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/@someoneelse')).toBe('block-page');
  });

  it('carves nothing out when the list is empty or is a blacklist', () => {
    const empty = compileRules(whitelisted([]), NO_USAGE, MIDDAY);
    expect(resolve(empty, 'https://www.youtube.com/watch?v=abc')).toBe('block-page');

    const blacklist = settings({
      rules: [
        siteRule({
          domain: 'youtube.com',
          mode: 'HARD_BLOCK',
          features: featuresWith('youtube', {
            youtube: { channelMode: 'BLACKLIST', channels: [veritasium] },
          }),
        }),
      ],
    });
    expect(
      resolve(compileRules(blacklist, NO_USAGE, MIDDAY), 'https://www.youtube.com/watch?v=a'),
    ).toBe('block-page');
  });

  it('carves nothing out while the site rule is not in force anyway', () => {
    const allowSite = settings({
      rules: [
        siteRule({
          domain: 'youtube.com',
          mode: 'ALLOW',
          features: featuresWith('youtube', {
            youtube: { channelMode: 'WHITELIST', channels: [veritasium] },
          }),
        }),
      ],
    });
    const rules = compileRules(allowSite, NO_USAGE, MIDDAY);
    expect(rules.every((rule) => rule.action.type !== 'allow')).toBe(true);
  });

  it('plays an allowed channel under budget and blocks it once the budget is spent', () => {
    // A daily limit and a channel list answer DIFFERENT questions: the limit budgets HOW
    // MUCH of the site, the list restricts WHAT. Left carved out, "1 hour of YouTube a day"
    // would be UNLIMITED for every allowed channel and the limit would only ever bite the
    // content the user already asked for less of — exactly inverted.
    const limited = settings({
      rules: [
        siteRule({
          domain: 'youtube.com',
          mode: 'ALLOW',
          dailyLimitMinutes: 60,
          features: featuresWith('youtube', {
            youtube: { channelMode: 'WHITELIST', channels: [veritasium] },
          }),
        }),
      ],
    });

    const underBudget = compileRules(limited, { 'youtube.com': 10 * 60_000 }, MIDDAY);
    expect(resolve(underBudget, 'https://www.youtube.com/watch?v=abc')).toBe('untouched');
    expect(resolve(underBudget, 'https://www.youtube.com/@veritasium')).toBe('untouched');

    const spent = compileRules(limited, { 'youtube.com': 60 * 60_000 }, MIDDAY);
    expect(resolve(spent, 'https://www.youtube.com/watch?v=abc')).toBe('block-page');
    expect(resolve(spent, 'https://www.youtube.com/@veritasium')).toBe('block-page');
    expect(resolve(spent, 'https://www.youtube.com/')).toBe('block-page');
  });

  it('still carves out an allowed channel when the site is blocked by MODE, not by a limit', () => {
    // The negative of the case above: a spent budget is the only reason to withdraw the
    // carve-out. "Block YouTube, except these channels" must keep working — it is the
    // headline feature.
    const rules = compileRules(whitelisted([veritasium]), { 'youtube.com': 60 * 60_000 }, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/watch?v=abc')).toBe('allowed');
  });

  it('gives a channel identifier carrying regex metacharacters no carve-out at all', () => {
    // A hand-edited or imported settings blob must not be able to widen a whitelist into
    // "every channel is allowed". Two layers stop that and this pins BOTH, because either
    // one alone would pass the behavioural half: the identifier is escaped, AND anything
    // outside `[A-Za-z0-9_.-]` is dropped rather than interpolated.
    const hostile = channel({ handle: '.*', channelId: 'UC.*' });

    // Dropped: the only path this entry contributes is the unconditional /watch one.
    expect(youtubeWhitelistPaths([hostile])).toEqual(['/watch']);

    const rules = compileRules(whitelisted([hostile, veritasium]), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/@anything')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/channel/UCsomethingelse')).toBe(
      'block-page',
    );
    // The safe entry in the same list is unaffected.
    expect(resolve(rules, 'https://www.youtube.com/@veritasium')).toBe('allowed');
  });
});

describe('temporary grants', () => {
  it('outrank both the site redirect and a gate redirect', async () => {
    installDnr();
    const rules = compileRules(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'HARD_BLOCK',
            features: featuresWith('youtube', { gates: { shorts: { mode: 'HARD_BLOCK' } } }),
          }),
        ],
      }),
      NO_USAGE,
      MIDDAY,
    );
    await applyTempAllows(['youtube.com']);
    const session = await chrome.declarativeNetRequest.getSessionRules();

    // A completed pause has to actually open the page it was taken on — otherwise the user
    // is bounced straight back into the block page, which is an infinite redirect loop.
    expect(resolve([...rules, ...session], 'https://www.youtube.com/shorts/abc')).toBe(
      'allowed',
    );
    expect(resolve([...rules, ...session], 'https://www.youtube.com/')).toBe('allowed');
  });
});

describe('compiledRuleCount', () => {
  /**
   * The e2e harness waits on this number to know the worker's rule set has caught up with a
   * settings write, and it cannot call `compileRules` itself (that builds redirect actions
   * through `chrome.runtime.getURL`, absent in the Playwright process). So the two must not
   * drift: a count that is too LOW makes the harness stop waiting early, which surfaces as
   * a flaky race rather than an honest failure — the worst kind of test infrastructure bug.
   *
   * Driven over states that exercise all three rule kinds rather than one happy path,
   * because the three are summed separately and only a mixed case catches a missed term.
   */
  const states: [string, ReturnType<typeof settings>, UsageByKey][] = [
    ['nothing configured', settings({ rules: [] }), NO_USAGE],
    [
      'a plain Hard Block',
      settings({ rules: [siteRule({ domain: 'example.com', mode: 'HARD_BLOCK' })] }),
      NO_USAGE,
    ],
    [
      'an Allow rule under budget',
      settings({
        rules: [siteRule({ domain: 'example.com', mode: 'ALLOW', dailyLimitMinutes: 30 })],
      }),
      NO_USAGE,
    ],
    [
      'the same Allow rule over budget',
      settings({
        rules: [siteRule({ domain: 'example.com', mode: 'ALLOW', dailyLimitMinutes: 30 })],
      }),
      { 'example.com': 45 * 60_000 },
    ],
    [
      'gates and a whitelist together',
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'HARD_BLOCK',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'HARD_BLOCK' } },
              youtube: {
                channelMode: 'WHITELIST',
                channels: [channel({ handle: 'veritasium' })],
              },
            }),
          }),
          siteRule({
            domain: 'instagram.com',
            mode: 'ALLOW',
            features: featuresWith('instagram', { gates: { reels: { mode: 'DELAY' } } }),
          }),
        ],
      }),
      NO_USAGE,
    ],
    [
      'Nudge switched off entirely',
      settings({
        globalEnabled: false,
        rules: [siteRule({ domain: 'example.com', mode: 'HARD_BLOCK' })],
      }),
      NO_USAGE,
    ],
  ];

  for (const [label, state, usage] of states) {
    it(`matches what compileRules actually produces: ${label}`, () => {
      expect(compiledRuleCount(state, usage, MIDDAY)).toBe(
        compileRules(state, usage, MIDDAY).length,
      );
    });
  }
});

describe('applyRules', () => {
  it('reads today’s usage itself, so a spent budget blocks without the caller saying so', async () => {
    const state = installDnr();
    const rule = siteRule({ domain: 'reddit.com', mode: 'ALLOW', dailyLimitMinutes: 10 });
    const config = settings({ rules: [rule] });

    await applyRules(config, MIDDAY);
    expect(resolve(state.dynamic, 'https://www.reddit.com/')).toBe('untouched');

    await saveDay(localDayKey(MIDDAY), {
      'reddit.com': { ...emptyDayUsage(), activeSec: 600 },
    });
    await applyRules(config, MIDDAY);
    expect(resolve(state.dynamic, 'https://www.reddit.com/')).toBe('block-page');
  });

  it('replaces the whole rule set rather than stacking ids', async () => {
    const state = installDnr();
    const config = settings({
      rules: [siteRule({ domain: 'a.com' }), siteRule({ id: 'r2', domain: 'b.com' })],
    });
    await applyRules(config, MIDDAY);
    await applyRules(config, MIDDAY);
    await applyRules(config, MIDDAY);

    expect(state.dynamic).toHaveLength(2);
    expect(new Set(state.dynamic.map((rule) => rule.id)).size).toBe(2);
  });
});

describe('redirectOpenTabs', () => {
  it('pushes only the tabs on that surface when a gate budget runs out', async () => {
    const attention = installAttention();
    attention.tabs = [
      { id: 1, url: 'https://www.youtube.com/shorts/abc' },
      { id: 2, url: 'https://www.youtube.com/watch?v=xyz' },
      { id: 3, url: 'https://news.example.com/' },
    ];

    await redirectOpenTabs('youtube.com', (url) => url.includes('/shorts/'));

    expect(attention.navigatedTo[1]).toContain('blocked.html?target=');
    expect(attention.navigatedTo[2]).toBeUndefined();
    expect(attention.navigatedTo[3]).toBeUndefined();
  });

  it('pushes every tab on the site when the site budget runs out', async () => {
    const attention = installAttention();
    attention.tabs = [
      { id: 1, url: 'https://www.youtube.com/shorts/abc' },
      { id: 2, url: 'https://www.youtube.com/watch?v=xyz' },
    ];

    await redirectOpenTabs('youtube.com');

    expect(Object.keys(attention.navigatedTo)).toEqual(['1', '2']);
  });
});

describe('YouTube channel whitelist — enrichment closes the network-layer hole', () => {
  /**
   * The user typed "@veritasium", so the stored entry has a handle and NO id. The content
   * script then watches one of that channel's videos, which reveals the canonical id, and
   * the worker folds it into the entry (`enrichEntries`). The point of doing that is right
   * here: until the entry HAS an id, `/channel/UCxxxx…` gets no allow-rule at all, so a full
   * navigation to the channel's own canonical URL hits the site redirect even though the
   * user explicitly allowed that channel. Only the network layer can fix that — the content
   * script never runs, the block page has already won.
   */
  const VERITASIUM_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';

  const whitelistOf = (channels: ChannelEntry[]) =>
    settings({
      rules: [
        siteRule({
          domain: 'youtube.com',
          mode: 'HARD_BLOCK',
          features: featuresWith('youtube', {
            youtube: { channelMode: 'WHITELIST', channels },
          }),
        }),
      ],
    });

  const handleOnly = [channel({ handle: 'veritasium', displayName: '@veritasium' })];

  it('redirects the allowed channel own canonical URL while the entry has no id', () => {
    const rules = compileRules(whitelistOf(handleOnly), NO_USAGE, MIDDAY);
    expect(resolve(rules, 'https://www.youtube.com/@veritasium')).toBe('allowed');
    // The hole. Same channel, same permission, different URL shape.
    expect(resolve(rules, `https://www.youtube.com/channel/${VERITASIUM_ID}`)).toBe(
      'block-page',
    );
  });

  it('lets that same URL through once the entry has learned the id', () => {
    const { entries, changed } = enrichEntries(handleOnly, {
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
      displayName: 'Veritasium',
    });
    expect(changed).toBe(true);

    const rules = compileRules(whitelistOf(entries), NO_USAGE, MIDDAY);
    expect(resolve(rules, `https://www.youtube.com/channel/${VERITASIUM_ID}`)).toBe('allowed');
    expect(resolve(rules, `https://www.youtube.com/channel/${VERITASIUM_ID}/videos`)).toBe(
      'allowed',
    );
    // And the handle route it already had keeps working.
    expect(resolve(rules, 'https://www.youtube.com/@veritasium')).toBe('allowed');
  });

  it('opens nothing else: the rest of YouTube stays blocked after enrichment', () => {
    const { entries } = enrichEntries(handleOnly, {
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
    });
    const rules = compileRules(whitelistOf(entries), NO_USAGE, MIDDAY);

    expect(resolve(rules, 'https://www.youtube.com/')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/feed/subscriptions')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/shorts/abc')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/@someoneelse')).toBe('block-page');
    expect(resolve(rules, 'https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q')).toBe(
      'block-page',
    );
  });

  it('mirrors it for an id-only entry learning the handle', () => {
    const idOnly = [channel({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID })];
    const before = compileRules(whitelistOf(idOnly), NO_USAGE, MIDDAY);
    expect(resolve(before, 'https://www.youtube.com/@veritasium')).toBe('block-page');

    const { entries } = enrichEntries(idOnly, {
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
    });
    const after = compileRules(whitelistOf(entries), NO_USAGE, MIDDAY);
    expect(resolve(after, 'https://www.youtube.com/@veritasium')).toBe('allowed');
  });
});
