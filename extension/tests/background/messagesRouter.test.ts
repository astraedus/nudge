import { beforeEach, describe, expect, it } from 'vitest';

import { handleRequest } from '../../src/background/messagesRouter';
import { featureSummary } from '../../src/core/featureSummary';
import { loadDay, loadSettings, saveDay, saveSettings } from '../../src/background/storage';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { emptyDayUsage } from '../../src/core/stats';
import { surfaceKey } from '../../src/core/surfaceKeys';
import type {
  BlockContext,
  ChannelObservedResult,
  PopupState,
  SaveResult,
  SiteConfig,
} from '../../src/core/protocol';
import type { ChannelEntry, NudgeSettings } from '../../src/core/settingsSchema';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser, type AttentionState } from './fakeApis';

const NOW = new Date(2026, 8, 20, 12, 0, 0);

let attention: AttentionState;

function channel(overrides: Partial<ChannelEntry>): ChannelEntry {
  return { channelId: null, handle: null, displayName: 'x', addedAt: 0, ...overrides };
}

async function seed(config: NudgeSettings): Promise<void> {
  await saveSettings(config);
}

async function ask<T>(request: unknown): Promise<T> {
  return (await handleRequest(request as never, NOW)) as T;
}

beforeEach(() => {
  resetBrowser();
  installDnr();
  attention = installAttention();
});

describe('GET_SITE_CONFIG', () => {
  const shortsGated = settings({
    rules: [
      siteRule({
        domain: 'youtube.com',
        mode: 'ALLOW',
        grayscale: true,
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'DELAY', delaySeconds: 20 } },
          hides: { comments: true },
          youtube: { channelMode: 'WHITELIST', channels: [channel({ handle: 'a' })] },
        }),
      }),
    ],
  });

  it('tells the content script what this page is supposed to do', async () => {
    await seed(shortsGated);
    const config = await ask<SiteConfig>({
      type: 'GET_SITE_CONFIG',
      url: 'https://www.youtube.com/',
    });

    expect(config.enabled).toBe(true);
    expect(config.platform).toBe('youtube');
    expect(config.siteMode).toBe('ALLOW');
    expect(config.siteApplies).toBe(false);
    expect(config.grayscale).toBe(true);
    expect(config.hides.comments).toBe(true);
    expect(config.youtube?.channelMode).toBe('WHITELIST');
    expect(config.gates).toEqual([
      { id: 'shorts', mode: 'DELAY', delaySeconds: 20, limitReached: false },
    ]);
  });

  it('reports a surface whose own budget is spent as a Hard Block', async () => {
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'ALLOW',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'OFF', dailyLimitMinutes: 10 } },
            }),
          }),
        ],
      }),
    );
    await saveDay(localDayKey(NOW), {
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), activeSec: 600 },
    });

    const config = await ask<SiteConfig>({
      type: 'GET_SITE_CONFIG',
      url: 'https://www.youtube.com/shorts/abc',
    });
    // Delay 0 because an exhausted budget has nothing left to wait out today.
    expect(config.gates).toEqual([
      { id: 'shorts', mode: 'HARD_BLOCK', delaySeconds: 0, limitReached: true },
    ]);
  });

  it('keeps enforcing a gate while a pause completed on the SITE is still live', async () => {
    // The network layer cannot hold this line: a temp-allow grant outranks the gate
    // redirect (deliberately — a pause completed ON a gate surface must not bounce back
    // into that gate's own redirect), so for the length of the grant the site's gate
    // surfaces are open at the network layer. The in-page gate is the ONLY thing left
    // standing between that grant and an ungated Shorts feed, so it must not soften.
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'DELAY',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'HARD_BLOCK' } },
            }),
          }),
        ],
      }),
    );
    await handleRequest({ type: 'COMPLETE_PAUSE', target: 'https://www.youtube.com/' });

    const config = await ask<SiteConfig>({
      type: 'GET_SITE_CONFIG',
      url: 'https://www.youtube.com/shorts/abc',
    });
    expect(config.gates).toEqual([
      { id: 'shorts', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false },
    ]);
  });

  it('turns everything off while Nudge itself is off', async () => {
    await seed({ ...shortsGated, globalEnabled: false });
    const config = await ask<SiteConfig>({
      type: 'GET_SITE_CONFIG',
      url: 'https://www.youtube.com/',
    });

    expect(config.enabled).toBe(false);
    expect(config.grayscale).toBe(false);
    expect(config.gates).toEqual([]);
    expect(config.youtube).toBeNull();
  });

  it('is inert on a site the user has no rule for', async () => {
    await seed(settings({ rules: [] }));
    const config = await ask<SiteConfig>({
      type: 'GET_SITE_CONFIG',
      url: 'https://www.instagram.com/reels/',
    });
    expect(config.enabled).toBe(false);
    expect(config.platform).toBe('instagram');
  });
});

describe('GET_BLOCK_CONTEXT', () => {
  it('speaks for the GATE when the blocked page was a gate surface', async () => {
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'ALLOW',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'BREATHING', delaySeconds: 25 } },
            }),
          }),
        ],
      }),
    );

    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://www.youtube.com/shorts/abc',
    });

    expect(context.gateId).toBe('shorts');
    expect(context.gateLabel).toBe('Shorts');
    expect(context.decision.type).toBe('BLOCK');
    if (context.decision.type === 'BLOCK') {
      expect(context.decision.mode).toBe('BREATHING');
      expect(context.decision.delaySeconds).toBe(25);
    }
  });

  it('speaks for the SITE on any other page of it', async () => {
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'HARD_BLOCK',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'BREATHING' } },
            }),
          }),
        ],
      }),
    );

    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://www.youtube.com/watch?v=abc',
    });

    expect(context.gateId).toBeNull();
    expect(context.decision.type).toBe('BLOCK');
    if (context.decision.type === 'BLOCK') expect(context.decision.mode).toBe('HARD_BLOCK');
  });

  it('offers the allowed channels as a way in when a whitelist is active', async () => {
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'HARD_BLOCK',
            features: featuresWith('youtube', {
              youtube: {
                channelMode: 'WHITELIST',
                channels: [channel({ handle: 'veritasium', displayName: 'Veritasium' })],
              },
            }),
          }),
        ],
      }),
    );

    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://www.youtube.com/',
    });
    expect(context.allowedChannels.map((entry) => entry.displayName)).toEqual([
      'Veritasium',
    ]);
  });

  it('offers none when the list is a blacklist, or on an ordinary site', async () => {
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'HARD_BLOCK',
            features: featuresWith('youtube', {
              youtube: { channelMode: 'BLACKLIST', channels: [channel({ handle: 'a' })] },
            }),
          }),
          siteRule({ id: 'r2', domain: 'reddit.com', mode: 'HARD_BLOCK' }),
        ],
      }),
    );

    const youtube = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://www.youtube.com/',
    });
    const reddit = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://www.reddit.com/',
    });
    expect(youtube.allowedChannels).toEqual([]);
    expect(reddit.allowedChannels).toEqual([]);
  });
});

describe('SET_GRAYSCALE', () => {
  const strictYoutube = (grayscale: boolean) =>
    settings({
      strictMode: { enabled: true, challengeLength: 12 },
      rules: [siteRule({ domain: 'youtube.com', grayscale })],
    });

  it('greys a site straight away — a stricter setting is never challenged', async () => {
    await seed(strictYoutube(false));
    const result = await ask<SaveResult>({
      type: 'SET_GRAYSCALE',
      domain: 'youtube.com',
      grayscale: true,
    });

    expect(result.ok).toBe(true);
    expect((await loadSettings()).rules[0]?.grayscale).toBe(true);
  });

  it('makes the user type the Commitment Lock code to turn it back off', async () => {
    await seed(strictYoutube(true));
    const refused = await ask<SaveResult>({
      type: 'SET_GRAYSCALE',
      domain: 'youtube.com',
      grayscale: false,
    });

    expect(refused.ok).toBe(false);
    expect(refused.challenge).toBeTruthy();
    // The gate is in the WORKER: the setting did not change just because a page asked.
    expect((await loadSettings()).rules[0]?.grayscale).toBe(true);

    const accepted = await ask<SaveResult>({
      type: 'SET_GRAYSCALE',
      domain: 'youtube.com',
      grayscale: false,
      challengeResponse: refused.challenge,
    });
    expect(accepted.ok).toBe(true);
    expect((await loadSettings()).rules[0]?.grayscale).toBe(false);
  });

  it('refuses a domain with no rule to change', async () => {
    await seed(settings({ rules: [] }));
    const result = await ask<SaveResult>({
      type: 'SET_GRAYSCALE',
      domain: 'youtube.com',
      grayscale: true,
    });
    expect(result).toEqual({ ok: false, reason: 'no-rule' });
  });
});

describe('ADD_SITE', () => {
  it('seeds a known platform with its feature surfaces, ready to configure', async () => {
    await seed(settings({ rules: [] }));
    await ask({ type: 'ADD_SITE', domain: 'youtube.com', mode: 'ALLOW', delaySeconds: 0 });

    const [rule] = (await loadSettings()).rules;
    expect(rule?.mode).toBe('ALLOW');
    expect(rule?.features?.platform).toBe('youtube');
    expect(rule?.features?.gates.shorts).toBeDefined();
  });

  it('leaves an ordinary site without a features block', async () => {
    await seed(settings({ rules: [] }));
    await ask({
      type: 'ADD_SITE',
      domain: 'news.example.com',
      mode: 'HARD_BLOCK',
      delaySeconds: 30,
    });

    const [rule] = (await loadSettings()).rules;
    expect(rule?.features).toBeNull();
    expect(rule?.delaySeconds).toBe(30);
  });
});

describe('GET_POPUP_STATE', () => {
  it('describes the current site the same way the network layer treats it', async () => {
    attention.tabs = [{ id: 1, url: 'https://www.youtube.com/' }];
    const rule = siteRule({
      domain: 'youtube.com',
      mode: 'ALLOW',
      dailyLimitMinutes: 10,
      grayscale: true,
      features: featuresWith('youtube', {
        gates: { shorts: { mode: 'DELAY', delaySeconds: 15 } },
        hides: { comments: true },
      }),
    });
    await seed(settings({ rules: [rule] }));
    await saveDay(localDayKey(NOW), {
      'youtube.com': { ...emptyDayUsage(), activeSec: 600 },
    });

    const state = await ask<PopupState>({ type: 'GET_POPUP_STATE' });
    expect(state.currentDomain).toBe('youtube.com');
    expect(state.currentMode).toBe('ALLOW');
    // Allowed by mode, but the budget is gone — so it IS in force, and the popup must not
    // call it "Allowed" while the user is looking at a block page.
    expect(state.currentApplies).toBe(true);
    expect(state.currentGrayscale).toBe(true);
    // The WIRING, not the wording: the popup must print the same sentence the dashboard's
    // rule card does for this rule. The wording itself is pinned once, in
    // tests/core/featureSummary.test.ts — asserting a literal here would be a second copy
    // of it, which is the drift the shared module exists to prevent.
    expect(state.currentFeatureSummary).not.toBeNull();
    expect(state.currentFeatureSummary).toBe(featureSummary(rule));
  });

  it('has nothing to say about a site with no rule', async () => {
    attention.tabs = [{ id: 1, url: 'https://news.example.com/' }];
    await seed(settings({ rules: [] }));

    const state = await ask<PopupState>({ type: 'GET_POPUP_STATE' });
    expect(state.currentMode).toBeNull();
    expect(state.currentApplies).toBe(false);
    expect(state.currentFeatureSummary).toBeNull();
  });
});


describe('a page on a SUBDOMAIN of a ruled site', () => {
  /**
   * Live QA 2026-09-20, on en.wikipedia.org with a `wikipedia.org` Allow + 1-minute rule.
   * DNR governs every subdomain, but the popup and the grayscale toggle compared rule
   * domains by exact string, so the popup offered to block a site that was already ruled
   * and the toggle failed silently with `no-rule`. Both now resolve the way DNR does.
   */
  const wikipedia = siteRule({
    domain: 'wikipedia.org',
    mode: 'ALLOW',
    dailyLimitMinutes: 1,
  });

  beforeEach(() => {
    attention.tabs = [{ id: 1, url: 'https://en.wikipedia.org/wiki/Cat' }];
  });

  it('the popup reports the governing rule, not "no rule here"', async () => {
    await seed(settings({ rules: [wikipedia] }));

    const state = await ask<PopupState>({ type: 'GET_POPUP_STATE' });

    expect(state.currentRule?.domain).toBe('wikipedia.org');
    expect(state.currentMode).toBe('ALLOW');
  });

  it('the popup counts down the bucket the tracker actually fills', async () => {
    await seed(settings({ rules: [wikipedia] }));
    // The tracker attributes a page on en.wikipedia.org to the RULE's domain.
    await saveDay(localDayKey(NOW), {
      'wikipedia.org': { ...emptyDayUsage(), activeSec: 30 },
    });

    const state = await ask<PopupState>({ type: 'GET_POPUP_STATE' });

    expect(state.currentUsageSeconds).toBe(30);
    expect(state.currentRemainingMs).toBe(30_000);
  });

  it('the grayscale toggle finds the rule instead of answering no-rule', async () => {
    await seed(settings({ rules: [wikipedia] }));

    const result = await ask<SaveResult>({
      type: 'SET_GRAYSCALE',
      domain: 'en.wikipedia.org',
      grayscale: true,
    });

    expect(result.ok).toBe(true);
    const [saved] = (await loadSettings()).rules;
    expect(saved?.grayscale).toBe(true);
  });

  it('still refuses a site that genuinely has no rule', async () => {
    await seed(settings({ rules: [wikipedia] }));

    const result = await ask<SaveResult>({
      type: 'SET_GRAYSCALE',
      domain: 'notwikipedia.org',
      grayscale: true,
    });

    expect(result).toMatchObject({ ok: false, reason: 'no-rule' });
  });
});

describe('the block page asking about a SUBDOMAIN whose limit is spent', () => {
  /**
   * QA R2 (2026-09-20), a new CRITICAL born from the subdomain fix missing one consumer.
   * The block page found the rule but read usage under the HOST while the tracker filled
   * the RULE's bucket, so a spent Allow rule looked under budget and the engine answered
   * ALLOW for a URL DNR had just redirected. The page bounced to the site, DNR redirected
   * again: 217 main-frame navigations in 8 seconds and a crashed renderer.
   *
   * This is the ENGINE INVARIANT in CLAUDE.md, "if any rule applies, the verdict is a
   * BLOCK", so it is asserted as the user-visible outcome, not as which key was read.
   */
  const spentLimit = settings({
    rules: [siteRule({ domain: 'wikipedia.org', mode: 'ALLOW', dailyLimitMinutes: 1 })],
  });

  beforeEach(async () => {
    await seed(spentLimit);
    // The tracker attributes a page on any subdomain to the RULE's bucket.
    await saveDay(localDayKey(NOW), {
      'wikipedia.org': { ...emptyDayUsage(), activeSec: 120 },
    });
  });

  it('blocks a subdomain page instead of bouncing it back into the redirect', async () => {
    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://en.wikipedia.org/wiki/Colour',
    });

    expect(context.decision.type).toBe('BLOCK');
  });

  it('says the limit is what did it, and names the rule that is enforcing', async () => {
    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://en.wikipedia.org/wiki/Colour',
    });

    expect(context.domain).toBe('wikipedia.org');
    if (context.decision.type !== 'BLOCK') throw new Error('expected a block');
    expect(context.decision.limitReached).toBe(true);
    expect(context.decision.mode).toBe('HARD_BLOCK');
  });

  it('answers the bare domain and a subdomain identically', async () => {
    const [bare, sub] = await Promise.all([
      ask<BlockContext>({ type: 'GET_BLOCK_CONTEXT', target: 'https://wikipedia.org/' }),
      ask<BlockContext>({
        type: 'GET_BLOCK_CONTEXT',
        target: 'https://en.wikipedia.org/wiki/Colour',
      }),
    ]);

    expect(sub.decision).toEqual(bare.decision);
    expect(sub.domain).toBe(bare.domain);
  });

  it('does not offer a fresh Delay countdown on a subdomain whose budget is gone', async () => {
    // The blast radius the report measured: a Delay rule returned DELAY with a full minute
    // remaining on the subdomain, so the limit was simply a no-op there.
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'wikipedia.org',
            mode: 'DELAY',
            delaySeconds: 15,
            dailyLimitMinutes: 1,
          }),
        ],
      }),
    );
    await saveDay(localDayKey(NOW), {
      'wikipedia.org': { ...emptyDayUsage(), activeSec: 120 },
    });

    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://en.wikipedia.org/wiki/Colour',
    });

    if (context.decision.type !== 'BLOCK') throw new Error('expected a block');
    expect(context.decision.mode).toBe('HARD_BLOCK');
    expect(context.decision.limitReached).toBe(true);
    expect(context.decision.dailyTimeRemainingMs).toBe(0);
  });

  it('counts a walk-away on a subdomain against the rule, not a separate host row', async () => {
    await ask({ type: 'WALKED_AWAY', target: 'https://en.wikipedia.org/wiki/Colour' });

    const day = await loadDay(localDayKey(NOW));
    expect(day['wikipedia.org']?.walkedAway).toBe(1);
    expect(day['en.wikipedia.org']).toBeUndefined();
  });
});

describe('CHANNEL_OBSERVED', () => {
  const VERITASIUM_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';

  const whitelistOf = (channels: ChannelEntry[], overrides: Partial<NudgeSettings> = {}) =>
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
      ...overrides,
    });

  async function storedChannels(): Promise<ChannelEntry[]> {
    const stored = await loadSettings();
    return stored.rules[0]?.features?.youtube?.channels ?? [];
  }

  it('teaches a handle-only entry the channel id the watch page revealed', async () => {
    await seed(whitelistOf([channel({ handle: 'veritasium', displayName: '@veritasium' })]));

    const result = await ask<ChannelObservedResult>({
      type: 'CHANNEL_OBSERVED',
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
      displayName: 'Veritasium',
    });

    expect(result).toEqual({ ok: true, changed: true, reason: 'enriched' });
    expect(await storedChannels()).toEqual([
      {
        channelId: VERITASIUM_ID,
        handle: 'veritasium',
        displayName: 'Veritasium',
        addedAt: 0,
      },
    ]);
  });

  it('recompiles the network rules, so the learned id takes effect without a restart', async () => {
    const handleOnly = whitelistOf([
      channel({ handle: 'veritasium', displayName: '@veritasium' }),
    ]);
    // Compile through the real path first, so "before" is a genuinely populated rule set in
    // which the canonical channel URL simply has no carve-out — not an empty one.
    await ask<SaveResult>({ type: 'SAVE_SETTINGS', settings: handleOnly });
    const before = await chrome.declarativeNetRequest.getDynamicRules();
    expect(before.length).toBeGreaterThan(0);
    expect(
      before.some((rule) => rule.condition.regexFilter?.includes('veritasium') === true),
    ).toBe(true);
    expect(
      before.some((rule) => rule.condition.regexFilter?.includes(VERITASIUM_ID) === true),
    ).toBe(false);

    await ask({
      type: 'CHANNEL_OBSERVED',
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
      displayName: 'Veritasium',
    });

    // Recompiled by the SAME path a user edit takes, which is the whole reason this handler
    // goes through `handleSave` instead of writing settings itself.
    const after = await chrome.declarativeNetRequest.getDynamicRules();
    expect(
      after.some(
        (rule) =>
          rule.action.type === 'allow' &&
          rule.condition.regexFilter?.includes(VERITASIUM_ID) === true,
      ),
    ).toBe(true);
  });

  it('does nothing when the observation contradicts the stored entry', async () => {
    const stored = channel({
      channelId: VERITASIUM_ID,
      handle: 'veritasium',
      displayName: 'Veritasium',
    });
    await seed(whitelistOf([stored]));

    const result = await ask<ChannelObservedResult>({
      type: 'CHANNEL_OBSERVED',
      channelId: 'UCsXVk37bltHxD1rDPwtNM8Q',
      handle: '@veritasium',
      displayName: 'Someone Else',
    });

    expect(result).toEqual({ ok: true, changed: false, reason: 'contradiction' });
    expect(await storedChannels()).toEqual([stored]);
  });

  it('never adds a channel the user did not list', async () => {
    await seed(whitelistOf([channel({ handle: 'kurzgesagt', displayName: 'Kurzgesagt' })]));

    const result = await ask<ChannelObservedResult>({
      type: 'CHANNEL_OBSERVED',
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
      displayName: 'Veritasium',
    });

    expect(result).toEqual({ ok: true, changed: false, reason: 'no-match' });
    expect(await storedChannels()).toHaveLength(1);
  });

  it('is not refused by the Commitment Lock, in either list mode', async () => {
    // Enrichment cannot weaken protection (it adds no channel and drops none), so it must
    // not sit behind a Strict Mode challenge the user has no way to answer: the observation
    // comes from watching a video, not from a settings screen.
    for (const channelMode of ['WHITELIST', 'BLACKLIST'] as const) {
      resetBrowser();
      installDnr();
      attention = installAttention();
      await seed(
        settings({
          strictMode: { ...settings().strictMode, enabled: true },
          rules: [
            siteRule({
              domain: 'youtube.com',
              mode: 'HARD_BLOCK',
              features: featuresWith('youtube', {
                youtube: {
                  channelMode,
                  channels: [channel({ handle: 'veritasium', displayName: '@veritasium' })],
                },
              }),
            }),
          ],
        }),
      );

      const result = await ask<ChannelObservedResult>({
        type: 'CHANNEL_OBSERVED',
        channelId: VERITASIUM_ID,
        handle: '@veritasium',
        displayName: 'Veritasium',
      });

      expect(result.changed).toBe(true);
      expect((await storedChannels())[0]?.channelId).toBe(VERITASIUM_ID);
    }
  });

  it('merges a channel the user added twice by different routes', async () => {
    await seed(
      whitelistOf([
        channel({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 }),
        channel({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID, addedAt: 100 }),
      ]),
    );

    const result = await ask<ChannelObservedResult>({
      type: 'CHANNEL_OBSERVED',
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
      displayName: 'Veritasium',
    });

    expect(result).toEqual({ ok: true, changed: true, reason: 'merged' });
    expect(await storedChannels()).toEqual([
      {
        channelId: VERITASIUM_ID,
        handle: 'veritasium',
        displayName: 'Veritasium',
        addedAt: 100,
      },
    ]);
  });

  it('shrugs when no rule covers YouTube at all', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'reddit.com', features: null })] }));

    const result = await ask<ChannelObservedResult>({
      type: 'CHANNEL_OBSERVED',
      channelId: VERITASIUM_ID,
      handle: '@veritasium',
      displayName: 'Veritasium',
    });

    expect(result).toEqual({ ok: true, changed: false, reason: 'no-rule' });
  });
});
