import { beforeEach, describe, expect, it } from 'vitest';

import { handleRequest } from '../../src/background/messagesRouter';
import { featureSummary } from '../../src/core/featureSummary';
import { loadDay, loadSettings, saveDay, saveSettings } from '../../src/background/storage';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { emptyDayUsage } from '../../src/core/stats';
import { surfaceKey } from '../../src/core/surfaceKeys';
import type {
  BlockContext,
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
      {
        id: 'shorts',
        mode: 'DELAY',
        delaySeconds: 20,
        limitReached: false,
        // v0.3: the count axis rides alongside the minute one on every resolved gate.
        // Nothing was viewed and no count budget is set, so both read their "nothing
        // spent, nothing configured" defaults.
        countReached: false,
        itemsToday: 0,
        countLimit: null,
      },
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
      {
        id: 'shorts',
        mode: 'HARD_BLOCK',
        delaySeconds: 0,
        limitReached: true,
        // The MINUTE budget is what is spent here, not the count one — the two axes
        // are independent, and this rule never configured a count limit at all.
        countReached: false,
        itemsToday: 0,
        countLimit: null,
      },
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
      {
        id: 'shorts',
        mode: 'HARD_BLOCK',
        delaySeconds: 15,
        limitReached: false,
        countReached: false,
        itemsToday: 0,
        countLimit: null,
      },
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

  it('reports countReached and itemsToday for a gate whose COUNT budget is spent', async () => {
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'ALLOW',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'OFF', dailyLimitCount: 20 } },
            }),
          }),
        ],
      }),
    );
    await saveDay(localDayKey(NOW), {
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), items: 20 },
    });

    const config = await ask<SiteConfig>({
      type: 'GET_SITE_CONFIG',
      url: 'https://www.youtube.com/shorts/abc',
    });
    // Delay 0 for the same reason a spent minute budget reads 0: an exhausted count has
    // nothing left to wait out today either.
    expect(config.gates).toEqual([
      {
        id: 'shorts',
        mode: 'HARD_BLOCK',
        delaySeconds: 0,
        limitReached: false,
        countReached: true,
        itemsToday: 20,
        countLimit: 20,
      },
    ]);
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

  it('names the COUNT budget when that is what closed the gate, distinct from a minute limit', async () => {
    // "Daily Shorts limit reached" is the wrong sentence for someone who set a count of 20
    // and no time budget at all — they would go looking in the editor for a minutes limit
    // they never set. `gateLimitKind` is what lets the block page tell the two apart.
    await seed(
      settings({
        rules: [
          siteRule({
            domain: 'youtube.com',
            mode: 'ALLOW',
            features: featuresWith('youtube', {
              gates: { shorts: { mode: 'OFF', dailyLimitCount: 20 } },
            }),
          }),
        ],
      }),
    );
    await saveDay(localDayKey(NOW), {
      [surfaceKey('youtube.com', 'shorts')]: { ...emptyDayUsage(), items: 20 },
    });

    const context = await ask<BlockContext>({
      type: 'GET_BLOCK_CONTEXT',
      target: 'https://www.youtube.com/shorts/abc',
    });

    expect(context.gateId).toBe('shorts');
    expect(context.gateLimitKind).toBe('count');
    expect(context.gateItemsToday).toBe(20);
    expect(context.gateCountLimit).toBe(20);
    expect(context.gateItemNoun).toBe('Shorts');
    expect(context.decision.type).toBe('BLOCK');
    if (context.decision.type === 'BLOCK') {
      // The engine itself knows only the minute axis (teaching it a second one risks the
      // ENGINE INVARIANT), so it answers a plain Hard Block; `buildBlockContext` sets
      // `limitReached` afterwards from the verdict that actually decided.
      expect(context.decision.mode).toBe('HARD_BLOCK');
      expect(context.decision.limitReached).toBe(true);
    }
  });
});

describe('ITEM_VIEWED', () => {
  it('reaches the counter and answers ok/counted', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com' })] }));

    const result = await ask<{ ok: boolean; counted: boolean }>({
      type: 'ITEM_VIEWED',
      url: 'https://www.youtube.com/shorts/abc',
    });

    expect(result).toEqual({ ok: true, counted: true });
    const day = await loadDay(localDayKey(NOW));
    expect(day[surfaceKey('youtube.com', 'shorts')]?.items).toBe(1);
  });

  it('still answers on an unknown or garbage URL, rather than hanging the caller', async () => {
    // A handler that never responds hangs the content script's sendMessage promise
    // forever — the same lesson `registerMessageRouter`'s own docstring calls out for the
    // router as a whole, and it applies just as much to one request type as to all of them.
    const unknown = await ask<{ ok: boolean; counted: boolean }>({
      type: 'ITEM_VIEWED',
      url: 'https://example.com/nothing-here',
    });
    const garbage = await ask<{ ok: boolean; counted: boolean }>({
      type: 'ITEM_VIEWED',
      url: 'not a url at all',
    });

    expect(unknown).toEqual({ ok: true, counted: false });
    expect(garbage).toEqual({ ok: true, counted: false });
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
