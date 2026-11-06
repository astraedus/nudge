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
import {
  installAttention,
  installDnr,
  resetBrowser,
  type AttentionState,
  type DnrState,
} from './fakeApis';

/**
 * The router is where the repeat's three readers meet: the in-page timer, the DNR grant length
 * and the popup. Everything here is the worker's answer, because the page never decides.
 */

const NOW = new Date(2026, 8, 20, 12, 0, 0);
const URL_ON_SITE = 'https://news.test/article';
const ALLOWED_CHANNEL = { channelId: null, handle: 'allowed', displayName: 'Allowed', addedAt: 0 };

let attention: AttentionState;
let dnr: DnrState;

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
  dnr = installDnr();
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

    it('a watch page with no clock gets no repeat: the channel check answers there, not the site pause', async () => {
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

describe('a video from a channel the user did not allow', () => {
  const WATCH = 'https://www.youtube.com/watch?v=abc';
  const EVERY = 10;
  const AT_ANCHOR = NOW.getTime() + EVERY * 60_000;

  function youtubeRule(overrides: Parameters<typeof siteRule>[0] = {}, channels = [ALLOWED_CHANNEL]) {
    return siteRule({
      id: 'yt',
      domain: 'youtube.com',
      mode: 'DELAY',
      repeatPauseMinutes: EVERY,
      features: featuresWith('youtube', { youtube: { channelMode: 'WHITELIST', channels } }),
      ...overrides,
    });
  }

  function gatePassed(url = WATCH): Promise<{ ok: boolean; anchored: boolean }> {
    return ask({ type: 'CHANNEL_PAUSE_DONE', url });
  }

  function askAt<T>(at: number, request: unknown): Promise<T> {
    return handleRequest(request as never, new Date(at)) as Promise<T>;
  }

  it('has its own clock once the gate has been served, with no pause straight after it', async () => {
    await seed(settings({ rules: [youtubeRule()] }));
    expect(await gatePassed()).toEqual({ ok: true, anchored: true });
    expect(await state(WATCH)).toEqual({
      active: true,
      mode: 'DELAY',
      delaySeconds: 15,
      everyMinutes: EVERY,
      domain: 'youtube.com',
      until: AT_ANCHOR,
    });
  });

  it('is due once the clock has run out', async () => {
    await seed(settings({ rules: [youtubeRule()] }));
    await gatePassed();
    const later = await askAt<RepeatPauseState>(AT_ANCHOR + 1, {
      type: 'GET_REPEAT_PAUSE',
      url: WATCH,
    });
    expect(later).toMatchObject({ active: true, until: null });
  });

  it('applies to a live stream the same way', async () => {
    await seed(settings({ rules: [youtubeRule()] }));
    const live = 'https://www.youtube.com/live/abc';
    expect(await gatePassed(live)).toMatchObject({ anchored: true });
    expect(await state(live)).toMatchObject({ active: true, until: AT_ANCHOR });
  });

  it('does not buy the next video: passing one gate leaves another with no repeat', async () => {
    await seed(settings({ rules: [youtubeRule()] }));
    await gatePassed();
    expect(await state('https://www.youtube.com/watch?v=other')).toEqual({ active: false });
  });

  it('stays out of a video from a channel on the list', async () => {
    await seed(settings({ rules: [youtubeRule()] }));
    await gatePassed();
    await ask({ type: 'VIDEO_CHANNEL', url: WATCH, channelId: null, handle: 'allowed' });
    expect(await state(WATCH)).toEqual({ active: false });
  });

  it('ends at once when the channel is added to the list mid-watch', async () => {
    const someone = { channelId: null, handle: 'someone', displayName: 'Someone', addedAt: 0 };
    await seed(settings({ rules: [youtubeRule()] }));
    await ask({ type: 'VIDEO_CHANNEL', url: WATCH, channelId: null, handle: 'someone' });
    await gatePassed();
    expect(await state(WATCH)).toMatchObject({ active: true });

    await seed(settings({ rules: [youtubeRule({}, [ALLOWED_CHANNEL, someone])] }));
    expect(await state(WATCH)).toEqual({ active: false });
  });

  it('ends when the budget is spent, because the engine has made that a Hard Block', async () => {
    await seed(settings({ rules: [youtubeRule({ dailyLimitMinutes: 10 })] }));
    await gatePassed();
    await saveDay(localDayKey(NOW), { 'youtube.com': { ...emptyDayUsage(), activeSec: 11 * 60 } });
    expect(await state(WATCH)).toEqual({ active: false });
  });

  it('runs on the clock, not on a live grant from elsewhere on the site', async () => {
    await seed(settings({ rules: [youtubeRule()] }));
    await gatePassed();
    await saveTempAllow({ 'youtube.com': NOW.getTime() + 3 * 60_000 });
    expect(await state(WATCH)).toMatchObject({ active: true, until: AT_ANCHOR });
  });

  describe('completing the pause', () => {
    const LATER = AT_ANCHOR + 5_000;

    async function pauseServed(): Promise<{ ok: boolean; until: number }> {
      await seed(settings({ rules: [youtubeRule()] }));
      await gatePassed();
      return askAt(LATER, { type: 'COMPLETE_PAUSE', target: WATCH });
    }

    it('renews the clock N minutes on from now', async () => {
      expect(await pauseServed()).toEqual({ ok: true, until: LATER + EVERY * 60_000 });
      expect(
        await askAt(LATER, { type: 'GET_REPEAT_PAUSE', url: WATCH }),
      ).toMatchObject({ active: true, until: LATER + EVERY * 60_000 });
    });

    it('grants nothing: not in storage, and no rule at the network layer', async () => {
      await pauseServed();
      expect(await loadTempAllow()).toEqual({});
      expect(dnr.session).toEqual([]);
    });

    it('leaves the home feed exactly as blocked as it was', async () => {
      await pauseServed();
      const home = await askAt<RepeatPauseState>(LATER, {
        type: 'GET_REPEAT_PAUSE',
        url: 'https://www.youtube.com/',
      });
      expect(home).toMatchObject({ active: true, until: null });
      const context = await askAt<BlockContext>(LATER, {
        type: 'GET_BLOCK_CONTEXT',
        target: 'https://www.youtube.com/',
      });
      expect(context.decision.type).toBe('BLOCK');
    });

    it('a video with no clock keeps today\'s path, and takes the ordinary grant', async () => {
      await seed(settings({ tempAllowMinutes: 7, rules: [youtubeRule()] }));
      const grant = await ask<{ until: number }>({ type: 'COMPLETE_PAUSE', target: WATCH });
      expect(grant.until).toBe(NOW.getTime() + 7 * 60_000);
    });
  });

  describe('the popup', () => {
    it('reads the same clock as the pill', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      await gatePassed();
      attention.tabs = [{ id: 1, url: WATCH }];
      const popup = await ask<PopupState>({ type: 'GET_POPUP_STATE' });
      expect(popup.currentRepeatEveryMinutes).toBe(EVERY);
      expect(popup.currentNextPauseAt).toBe(AT_ANCHOR);
    });

    it('agrees that a run-out clock is due, not a time in the past', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      await gatePassed();
      attention.tabs = [{ id: 1, url: WATCH }];
      const popup = await askAt<PopupState>(AT_ANCHOR + 1, { type: 'GET_POPUP_STATE' });
      expect(popup.currentRepeatEveryMinutes).toBe(EVERY);
      expect(popup.currentNextPauseAt).toBeNull();
    });

    it('shows nothing on a video with no clock', async () => {
      await seed(settings({ rules: [youtubeRule()] }));
      attention.tabs = [{ id: 1, url: WATCH }];
      const popup = await ask<PopupState>({ type: 'GET_POPUP_STATE' });
      expect(popup.currentRepeatEveryMinutes).toBeNull();
      expect(popup.currentNextPauseAt).toBeNull();
    });
  });

  describe('the report starts no clock, and no repeat follows', () => {
    it.each<[string, () => NudgeSettings]>([
      ['Nudge is off', () => settings({ globalEnabled: false, rules: [youtubeRule()] })],
      ['the rule has no repeat', () => settings({ rules: [youtubeRule({ repeatPauseMinutes: null })] })],
      ['the site is Allowed', () => settings({ rules: [youtubeRule({ mode: 'ALLOW' })] })],
      ['the site is Hard Blocked', () => settings({ rules: [youtubeRule({ mode: 'HARD_BLOCK' })] })],
    ])('when %s', async (_name, config) => {
      await seed(config());
      expect(await gatePassed()).toEqual({ ok: true, anchored: false });
      expect(await state(WATCH)).toEqual({ active: false });
    });

    it('when the budget is spent', async () => {
      await seed(settings({ rules: [youtubeRule({ dailyLimitMinutes: 10 })] }));
      await saveDay(localDayKey(NOW), { 'youtube.com': { ...emptyDayUsage(), activeSec: 11 * 60 } });
      expect(await gatePassed()).toEqual({ ok: true, anchored: false });
    });

    it.each([
      ['a listed channel page', 'https://www.youtube.com/@allowed'],
      ['a playlist', 'https://www.youtube.com/playlist?list=abc'],
      ['a legacy channel page', 'https://www.youtube.com/c/allowed'],
      ['a watch page with no video', 'https://www.youtube.com/watch'],
      ['a site with no rule', 'https://news.test/watch?v=abc'],
    ])('on %s', async (_name, url) => {
      await seed(settings({ rules: [youtubeRule()] }));
      expect(await gatePassed(url)).toEqual({ ok: true, anchored: false });
      expect(await state(url)).toEqual({ active: false });
    });

    it('under a BLACKLIST, where the watch page is not carved and the site grant is the clock', async () => {
      const features = featuresWith('youtube', {
        youtube: { channelMode: 'BLACKLIST', channels: [ALLOWED_CHANNEL] },
      });
      await seed(settings({ rules: [youtubeRule({ features })] }));
      expect(await gatePassed()).toEqual({ ok: true, anchored: false });
      expect(await state(WATCH)).toMatchObject({ active: true, until: null });
    });
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
