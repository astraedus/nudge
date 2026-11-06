import { beforeEach, describe, expect, it, vi } from 'vitest';

import { handleRequest } from '../../src/background/messagesRouter';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { emptyDayUsage } from '../../src/core/stats';
import {
  loadDay,
  loadSettings,
  loadTempAllow,
  saveDay,
  saveSettings,
  saveTempAllow,
} from '../../src/background/storage';
import type { BlockContext, PopupState, RepeatPauseState } from '../../src/core/protocol';
import type { NudgeSettings } from '../../src/core/settingsSchema';
import { featuresWith, scheduleOverride, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser, type AttentionState } from './fakeApis';

/**
 * The router is where the repeat's three readers meet: the in-page timer, the DNR grant length
 * and the popup. Everything here is the worker's answer, because the page never decides.
 */

const NOW = new Date(2026, 8, 20, 12, 0, 0);
const URL_ON_SITE = 'https://news.test/article';
const ALLOWED_CHANNEL = { channelId: null, handle: 'allowed', displayName: 'Allowed', addedAt: 0 };

let attention: AttentionState;

async function seed(config: NudgeSettings): Promise<void> {
  await saveSettings(config);
}

async function ask<T>(request: unknown, sender?: chrome.runtime.MessageSender): Promise<T> {
  return (await handleRequest(request as never, NOW, sender)) as T;
}

function repeatRule(overrides: Parameters<typeof siteRule>[0] = {}) {
  return siteRule({
    id: 'r',
    domain: 'news.test',
    mode: 'DELAY',
    delaySeconds: 15,
    repeatPauseMinutes: 20,
    ...overrides,
  });
}

function state(url = URL_ON_SITE): Promise<RepeatPauseState> {
  return ask<RepeatPauseState>({ type: 'GET_REPEAT_PAUSE', url });
}

beforeEach(() => {
  resetBrowser();
  installDnr();
  attention = installAttention();
});

describe('GET_REPEAT_PAUSE', () => {
  it('answers active with what the overlay needs, and null until a grant exists', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    expect(await state()).toEqual({
      active: true,
      mode: 'DELAY',
      delaySeconds: 15,
      everyMinutes: 20,
      until: null,
      domain: 'news.test',
    });
  });

  it('reports the live grant as until', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    const { until } = await ask<{ until: number }>({
      type: 'COMPLETE_PAUSE',
      target: URL_ON_SITE,
    });
    expect(await state()).toMatchObject({ active: true, until });
  });

  it('reads a subdomain through the rule, the same resolution as everything else', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    expect(await state('https://m.news.test/x')).toMatchObject({
      active: true,
      domain: 'news.test',
    });
  });

  it('carries the Breathing mode', async () => {
    await seed(settings({ rules: [repeatRule({ mode: 'BREATHING', delaySeconds: 30 })] }));
    expect(await state()).toMatchObject({ active: true, mode: 'BREATHING', delaySeconds: 30 });
  });

  it('uses the mode a schedule resolves to right now', async () => {
    await seed(
      settings({
        rules: [
          repeatRule({
            mode: 'ALLOW',
            schedule: scheduleOverride({ mode: 'BREATHING', delaySeconds: 20 }),
          }),
        ],
      }),
    );
    expect(await state()).toMatchObject({ active: true, mode: 'BREATHING', delaySeconds: 20 });
  });

  it.each<[string, () => NudgeSettings]>([
    ['Nudge is off', () => settings({ globalEnabled: false, rules: [repeatRule()] })],
    ['no rule covers the site', () => settings({ rules: [] })],
    ['the repeat is off', () => settings({ rules: [repeatRule({ repeatPauseMinutes: null })] })],
    ['the rule is switched off', () => settings({ rules: [repeatRule({ enabled: false })] })],
    ['the mode is Allow', () => settings({ rules: [repeatRule({ mode: 'ALLOW' })] })],
    ['the mode is Hard Block', () => settings({ rules: [repeatRule({ mode: 'HARD_BLOCK' })] })],
    [
      'a schedule resolves it to Hard Block',
      () =>
        settings({
          rules: [repeatRule({ schedule: scheduleOverride({ mode: 'HARD_BLOCK' }) })],
        }),
    ],
  ])('is inactive when %s', async (_name, config) => {
    await seed(config());
    expect(await state()).toEqual({ active: false });
  });

  it('is inactive once a Delay rule’s limit is spent: the engine has made it a Hard Block', async () => {
    await seed(settings({ rules: [repeatRule({ dailyLimitMinutes: 10 })] }));
    expect(await state()).toMatchObject({ active: true });
    await saveDay(localDayKey(NOW), {
      'news.test': { ...emptyDayUsage(), activeSec: 11 * 60 },
    });
    expect(await state()).toEqual({ active: false });
    // And the reason it must be: asking for the grant from here would be refused, and an
    // in-page pause that can never complete would hold the page for good.
    expect(
      await ask<{ ok: boolean; reason?: string }>({ type: 'COMPLETE_PAUSE', target: URL_ON_SITE }),
    ).toMatchObject({ ok: false, reason: 'hard-block' });
  });

  describe('YouTube carve-outs stay inactive', () => {
    // Punishing someone for watching what they allowed is the product's worst failure mode.
    function youtubeRule(creatorMode = false) {
      const features = featuresWith('youtube', {
        youtube: {
          channelMode: 'WHITELIST',
          channels: [ALLOWED_CHANNEL],
          creatorMode,
          myChannel: creatorMode ? ALLOWED_CHANNEL : null,
        },
      });
      return siteRule({
        id: 'yt',
        domain: 'youtube.com',
        mode: 'DELAY',
        repeatPauseMinutes: 10,
        features,
      });
    }

    it('an allowed channel page never gets a repeat', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      expect(await state('https://www.youtube.com/@allowed')).toEqual({ active: false });
    });

    it('a watch page never gets a repeat: the channel check answers there, not the site pause', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      expect(await state('https://www.youtube.com/watch?v=abc')).toEqual({ active: false });
    });

    it('Studio never gets a repeat under creator mode', async () => {
      await seed(settings({ rules: [youtubeRule(true)] }));
      expect(await state('https://studio.youtube.com/channel/UC1')).toEqual({ active: false });
    });

    it('the home feed, which DNR would redirect without a grant, keeps the repeat', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      expect(await state('https://www.youtube.com/')).toMatchObject({ active: true });
    });

    it('asks as if the grant had run out, so a live grant does not make the feed look carved', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      await ask({ type: 'COMPLETE_PAUSE', target: 'https://www.youtube.com/' });
      expect(await state('https://www.youtube.com/')).toMatchObject({ active: true });
    });
  });

  describe('a gate in force on the page', () => {
    function gatedRule(gateMode: 'DELAY' | 'HARD_BLOCK' | 'OFF') {
      const features = featuresWith('youtube', { gates: { shorts: { mode: gateMode } } });
      return siteRule({
        id: 'yt',
        domain: 'youtube.com',
        mode: 'DELAY',
        repeatPauseMinutes: 10,
        features,
      });
    }

    it('leaves the surface to the gate', async () => {
      await seed(settings({ rules: [gatedRule('HARD_BLOCK')] }));
      expect(await state('https://www.youtube.com/shorts/abc')).toEqual({ active: false });
    });

    it('has no say once the gate is off', async () => {
      await seed(settings({ rules: [gatedRule('OFF')] }));
      expect(await state('https://www.youtube.com/shorts/abc')).toMatchObject({ active: true });
    });

    it('does not reach the rest of the site', async () => {
      await seed(settings({ rules: [gatedRule('HARD_BLOCK')] }));
      expect(await state('https://www.youtube.com/')).toMatchObject({ active: true });
    });
  });
});

describe('COMPLETE_PAUSE grant length', () => {
  it('is the repeat while it is in force, so the pill and DNR agree', async () => {
    await seed(settings({ tempAllowMinutes: 10, rules: [repeatRule({ repeatPauseMinutes: 3 })] }));
    const grant = await ask<{ ok: boolean; until: number }>({
      type: 'COMPLETE_PAUSE',
      target: URL_ON_SITE,
    });
    expect(grant).toEqual({ ok: true, until: NOW.getTime() + 3 * 60_000 });
    expect((await loadTempAllow())['news.test']).toBe(grant.until);
  });

  it('is tempAllowMinutes with the repeat off', async () => {
    await seed(
      settings({ tempAllowMinutes: 10, rules: [repeatRule({ repeatPauseMinutes: null })] }),
    );
    const grant = await ask<{ until: number }>({ type: 'COMPLETE_PAUSE', target: URL_ON_SITE });
    expect(grant.until).toBe(NOW.getTime() + 10 * 60_000);
  });

  it('is tempAllowMinutes on a gate surface, whose pause the repeat does not reach', async () => {
    const features = featuresWith('youtube', {
      gates: { shorts: { mode: 'DELAY', delaySeconds: 5 } },
    });
    await seed(
      settings({
        tempAllowMinutes: 10,
        rules: [
          siteRule({
            id: 'yt',
            domain: 'youtube.com',
            mode: 'DELAY',
            repeatPauseMinutes: 3,
            features,
          }),
        ],
      }),
    );
    const grant = await ask<{ until: number }>({
      type: 'COMPLETE_PAUSE',
      target: 'https://www.youtube.com/shorts/abc',
    });
    expect(grant.until).toBe(NOW.getTime() + 10 * 60_000);
  });

  it('a page whose repeat is carved out keeps the ordinary grant', async () => {
    const features = featuresWith('youtube', {
      youtube: { channelMode: 'WHITELIST', channels: [ALLOWED_CHANNEL] },
    });
    await seed(
      settings({
        tempAllowMinutes: 10,
        rules: [
          siteRule({
            id: 'yt',
            domain: 'youtube.com',
            mode: 'DELAY',
            repeatPauseMinutes: 3,
            features,
          }),
        ],
      }),
    );
    const grant = await ask<{ until: number }>({
      type: 'COMPLETE_PAUSE',
      target: 'https://www.youtube.com/@allowed',
    });
    expect(grant.until).toBe(NOW.getTime() + 10 * 60_000);
  });
});

describe('SET_REPEAT_PAUSE', () => {
  it('saves against the rule the target resolves to', async () => {
    await seed(settings({ rules: [repeatRule({ repeatPauseMinutes: null })] }));
    const result = await ask<{ ok: boolean }>({
      type: 'SET_REPEAT_PAUSE',
      target: 'https://m.news.test/article',
      minutes: 15,
    });
    expect(result).toEqual({ ok: true });
    expect((await loadSettings()).rules[0]?.repeatPauseMinutes).toBe(15);
  });

  it('clamps a value a page made up, and treats junk as off', async () => {
    await seed(settings({ rules: [repeatRule({ repeatPauseMinutes: 10 })] }));
    await ask({ type: 'SET_REPEAT_PAUSE', target: URL_ON_SITE, minutes: 9000 });
    expect((await loadSettings()).rules[0]?.repeatPauseMinutes).toBe(120);
    await ask({ type: 'SET_REPEAT_PAUSE', target: URL_ON_SITE, minutes: 'lots' });
    expect((await loadSettings()).rules[0]?.repeatPauseMinutes).toBeNull();
  });

  it('refuses a site with no rule', async () => {
    await seed(settings({ rules: [] }));
    expect(
      await ask({ type: 'SET_REPEAT_PAUSE', target: URL_ON_SITE, minutes: 10 }),
    ).toEqual({ ok: false, reason: 'no-rule' });
  });

  describe('under the Commitment Lock', () => {
    const locked = (minutes: number | null) =>
      settings({
        tempAllowMinutes: 10,
        strictMode: { enabled: true, challengeLength: 12 },
        rules: [repeatRule({ repeatPauseMinutes: minutes })],
      });

    it('refuses to raise N or switch it off without the challenge, in the worker', async () => {
      await seed(locked(10));
      const raised = await ask<{ ok: boolean }>({
        type: 'SET_REPEAT_PAUSE',
        target: URL_ON_SITE,
        minutes: 30,
      });
      expect(raised.ok).toBe(false);
      const off = await ask<{ ok: boolean }>({
        type: 'SET_REPEAT_PAUSE',
        target: URL_ON_SITE,
        minutes: null,
      });
      expect(off.ok).toBe(false);
      expect((await loadSettings()).rules[0]?.repeatPauseMinutes).toBe(10);
    });

    it('lets N come down and lets a short one be switched on', async () => {
      await seed(locked(30));
      expect(
        await ask({ type: 'SET_REPEAT_PAUSE', target: URL_ON_SITE, minutes: 10 }),
      ).toEqual({ ok: true });
      expect((await loadSettings()).rules[0]?.repeatPauseMinutes).toBe(10);

      await seed(locked(null));
      expect(
        await ask({ type: 'SET_REPEAT_PAUSE', target: URL_ON_SITE, minutes: 5 }),
      ).toEqual({ ok: true });
    });
  });
});

describe('PAUSE_SHOWN', () => {
  it('logs a block against the rule’s bucket, not the subdomain', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    await ask({ type: 'PAUSE_SHOWN', url: 'https://m.news.test/x' });
    const usage = await loadDay(localDayKey(NOW));
    expect(usage['news.test']?.blocked).toBe(1);
    expect(usage['m.news.test']).toBeUndefined();
  });

  it('logs nothing for a URL that is not a site', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    await ask({ type: 'PAUSE_SHOWN', url: 'not a url' });
    expect(Object.keys(await loadDay(localDayKey(NOW)))).toEqual([]);
  });
});

describe('LEAVE_TO_DASHBOARD', () => {
  const sender = (id: number | undefined) =>
    ({ tab: id === undefined ? undefined : { id } }) as chrome.runtime.MessageSender;

  it('moves the sender’s own tab to the dashboard', async () => {
    const result = await ask({ type: 'LEAVE_TO_DASHBOARD' }, sender(42));
    expect(result).toEqual({ ok: true });
    expect(Object.keys(attention.navigatedTo)).toEqual(['42']);
    expect(attention.navigatedTo[42]).toMatch(/dashboard\.html$/);
  });

  it('takes the tab from the SENDER only: an id in the message moves nothing', async () => {
    const result = await ask({ type: 'LEAVE_TO_DASHBOARD', tabId: 7 }, sender(undefined));
    expect(result).toEqual({ ok: false });
    expect(attention.navigatedTo).toEqual({});
  });

  it('answers ok:false when the tab is already gone', async () => {
    vi.spyOn(chrome.tabs, 'update').mockRejectedValueOnce(new Error('No tab with id: 42'));
    expect(await ask({ type: 'LEAVE_TO_DASHBOARD' }, sender(42))).toEqual({ ok: false });
  });
});

describe('BlockContext and PopupState', () => {
  async function context(target = URL_ON_SITE): Promise<BlockContext> {
    return ask<BlockContext>({ type: 'GET_BLOCK_CONTEXT', target });
  }

  it('offers the chooser on a site-level Delay or Breathing pause', async () => {
    await seed(settings({ rules: [repeatRule({ repeatPauseMinutes: 15 })] }));
    expect(await context()).toMatchObject({ repeatPauseMinutes: 15, repeatPauseOffered: true });
  });

  it('does not offer it on a Hard Block', async () => {
    await seed(settings({ rules: [repeatRule({ mode: 'HARD_BLOCK' })] }));
    expect((await context()).repeatPauseOffered).toBe(false);
  });

  it('does not offer it on a gate’s block page', async () => {
    const features = featuresWith('youtube', {
      gates: { shorts: { mode: 'DELAY', delaySeconds: 5 } },
    });
    await seed(
      settings({
        rules: [
          siteRule({
            id: 'yt',
            domain: 'youtube.com',
            mode: 'DELAY',
            repeatPauseMinutes: 10,
            features,
          }),
        ],
      }),
    );
    const onGate = await context('https://www.youtube.com/shorts/abc');
    expect(onGate.gateId).toBe('shorts');
    expect(onGate.repeatPauseOffered).toBe(false);
  });

  it('the popup reads the same answer as the timer', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    attention.tabs = [{ id: 1, url: URL_ON_SITE }];
    const { until } = await ask<{ until: number }>({
      type: 'COMPLETE_PAUSE',
      target: URL_ON_SITE,
    });
    const popup = await ask<PopupState>({ type: 'GET_POPUP_STATE' });
    expect(popup.currentRepeatEveryMinutes).toBe(20);
    expect(popup.currentNextPauseAt).toBe(until);
  });

  it('the popup shows nothing on a carved or inert page', async () => {
    await seed(settings({ rules: [repeatRule({ mode: 'ALLOW' })] }));
    attention.tabs = [{ id: 1, url: URL_ON_SITE }];
    const popup = await ask<PopupState>({ type: 'GET_POPUP_STATE' });
    expect(popup.currentRepeatEveryMinutes).toBeNull();
    expect(popup.currentNextPauseAt).toBeNull();
  });

  it('an expired grant reads as none', async () => {
    await seed(settings({ rules: [repeatRule()] }));
    await saveTempAllow({ 'news.test': NOW.getTime() - 1 });
    expect(await state()).toMatchObject({ active: true, until: null });
  });
});
