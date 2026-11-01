import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchSubscriptions,
  maybeAutoSync,
  resetSubscriptionSyncForTests,
  subscriptionStatus,
  syncSubscriptions,
  type Fetcher,
} from '../../src/background/youtubeSubscriptions';
import { handleRequest } from '../../src/background/messagesRouter';
import {
  YT_SUBSCRIPTIONS_KEY,
  loadSettings,
  loadSubscriptionState,
  saveSettings,
  saveSubscriptionState,
} from '../../src/background/storage';
import { compileRules } from '../../src/background/dnr';
import { effectiveChannelLists } from '../../src/core/youtubeAccess';
import {
  AUTO_SYNC_INTERVAL_MS,
  MAX_SUBSCRIBED_CHANNELS,
  SUBSCRIPTIONS_PAGE_URL,
  applySyncOutcome,
  emptySyncState,
} from '../../src/core/youtubeSubscriptions';
import type { SaveResult } from '../../src/core/protocol';
import type { ChannelEntry, NudgeSettings } from '../../src/core/settingsSchema';
import {
  API_KEY,
  CONTINUATION_TOKEN,
  KURZGESAGT_ID,
  VERITASIUM_ID,
  continuationResponse,
  defaultChannels,
  initialData,
  manyChannels,
  subscriptionsPageHtml,
} from '../core/fixtures/youtubeSubscriptionsPage';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser } from './fakeApis';

/**
 * The worker side of "Also allow channels I'm subscribed to": the fetch, the folding into
 * storage, and the boundary that keeps the list the WORKER's. The network is a fake
 * `Fetcher` throughout; `fetch` itself is stubbed so a test that reaches the real default
 * fails loudly instead of touching youtube.com.
 */

const NOW = Date.UTC(2026, 10, 1, 12, 0, 0);
const NOW_DATE = new Date(NOW);

interface Call {
  url: string;
  init: RequestInit;
}

function response(options: { url?: string; ok?: boolean; html?: string; json?: unknown }): Response {
  return {
    ok: options.ok ?? true,
    url: options.url ?? SUBSCRIPTIONS_PAGE_URL,
    text: async () => options.html ?? '',
    json: async () => options.json ?? {},
  } as unknown as Response;
}

/** A fake network answering the page first, then each continuation in turn. */
function fakeYouTube(page: Response | Error, continuations: (Response | Error)[] = []) {
  const calls: Call[] = [];
  const queue = [...continuations];
  const fetcher: Fetcher = async (url, init) => {
    calls.push({ url, init });
    const next = calls.length === 1 ? page : (queue.shift() ?? new Error('unexpected request'));
    if (next instanceof Error) throw next;
    return next;
  };
  return { fetcher, calls };
}

function youtubeSettings(
  youtube: { allowSubscriptions?: boolean; channels?: ChannelEntry[]; subscribedChannels?: ChannelEntry[] } = {},
  strict = false,
): NudgeSettings {
  const base = settings();
  return settings({
    strictMode: { ...base.strictMode, enabled: strict },
    rules: [
      siteRule({
        id: 'yt',
        domain: 'youtube.com',
        mode: 'HARD_BLOCK',
        features: featuresWith('youtube', {
          youtube: {
            channelMode: 'WHITELIST',
            channels: youtube.channels ?? [],
            allowSubscriptions: youtube.allowSubscriptions ?? true,
            subscribedChannels: youtube.subscribedChannels ?? [],
          },
        }),
      }),
    ],
  });
}

const STORED: ChannelEntry = { channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'Veritasium', addedAt: 1 };
const INJECTED: ChannelEntry = { channelId: null, handle: 'mrbeast', displayName: '@mrbeast', addedAt: 2 };

async function storeList(channels: ChannelEntry[]): Promise<void> {
  await saveSubscriptionState(applySyncOutcome(emptySyncState(), { ok: true, channels, truncated: null }, NOW - 1000));
}

let networkFetch: ReturnType<typeof vi.fn>;

beforeEach(() => {
  resetBrowser();
  installDnr();
  installAttention();
  resetSubscriptionSyncForTests();
  networkFetch = vi.fn(async () => {
    throw new Error('a test reached the real network');
  });
  globalThis.fetch = networkFetch as unknown as typeof fetch;
});

// Restored by hand: `vi.unstubAllGlobals()` would also remove the `chrome` global that the
// WXT test plugin installs.
const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

describe('fetchSubscriptions', () => {
  it("reads the user's own page with their cookies, then follows the continuation", async () => {
    const { fetcher, calls } = fakeYouTube(response({ html: subscriptionsPageHtml() }), [
      response({ json: continuationResponse(manyChannels(2)) }),
    ]);
    const outcome = await fetchSubscriptions(fetcher, NOW);
    expect(outcome).toMatchObject({ ok: true, truncated: null });
    if (!outcome.ok) throw new Error('expected ok');
    expect(outcome.channels.map((c) => c.channelId)).toContain(KURZGESAGT_ID);
    expect(outcome.channels).toHaveLength(5);

    expect(calls[0]).toMatchObject({ url: SUBSCRIPTIONS_PAGE_URL, init: { credentials: 'include' } });
    expect(calls[1]?.url).toContain('/youtubei/v1/browse');
    expect(calls[1]?.url).toContain(`key=${API_KEY}`);
    expect(calls[1]?.init.method).toBe('POST');
    expect(JSON.parse(calls[1]?.init.body as string)).toMatchObject({ continuation: CONTINUATION_TOKEN });
  });

  it('sends nothing of ours: no extension data in any request', async () => {
    const { fetcher, calls } = fakeYouTube(response({ html: subscriptionsPageHtml() }), [
      response({ json: continuationResponse(manyChannels(1)) }),
    ]);
    await fetchSubscriptions(fetcher, NOW);
    expect(calls[0]?.init.body).toBeUndefined();
    const body = JSON.parse(calls[1]?.init.body as string) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['context', 'continuation']);
    // No headers beyond Content-Type: in particular no SAPISIDHASH Authorization.
    expect(calls[1]?.init.headers).toEqual({ 'Content-Type': 'application/json' });
  });

  it('is signed-out when YouTube redirects to the Google sign-in page (status 200 after the redirect)', async () => {
    const { fetcher } = fakeYouTube(
      response({ url: 'https://accounts.google.com/ServiceLogin?service=youtube', html: '<html>Sign in</html>' }),
    );
    expect(await fetchSubscriptions(fetcher, NOW)).toEqual({ ok: false, error: 'signed-out' });
  });

  it('is a network error on a non-ok status or a thrown fetch', async () => {
    expect(await fetchSubscriptions(fakeYouTube(response({ ok: false })).fetcher, NOW)).toEqual({
      ok: false,
      error: 'network',
    });
    expect(await fetchSubscriptions(fakeYouTube(new Error('offline')).fetcher, NOW)).toEqual({
      ok: false,
      error: 'network',
    });
  });

  it('is unparseable for a page with no subscription data', async () => {
    const { fetcher } = fakeYouTube(response({ html: '<html>Before you continue</html>' }));
    expect(await fetchSubscriptions(fetcher, NOW)).toEqual({ ok: false, error: 'unparseable' });
  });

  it.each([
    ['a refused continuation', response({ ok: false })],
    ['a continuation with no channels', response({ json: { responseContext: {} } })],
    ['a thrown continuation', new Error('offline')],
  ])('keeps the first page and says "partial" after %s', async (_name, continuation) => {
    const { fetcher } = fakeYouTube(response({ html: subscriptionsPageHtml() }), [continuation]);
    const outcome = await fetchSubscriptions(fetcher, NOW);
    expect(outcome).toMatchObject({ ok: true, truncated: 'partial' });
    if (outcome.ok) expect(outcome.channels).toHaveLength(3);
  });

  it(`stops at ${MAX_SUBSCRIBED_CHANNELS} channels and says "cap"`, async () => {
    const page = subscriptionsPageHtml(initialData(manyChannels(300), { continuation: 't1' }));
    const { fetcher, calls } = fakeYouTube(response({ html: page }), [
      response({ json: continuationResponse(manyChannels(300, 300), 't2') }),
    ]);
    const outcome = await fetchSubscriptions(fetcher, NOW);
    expect(outcome).toMatchObject({ ok: true, truncated: 'cap' });
    if (outcome.ok) expect(outcome.channels).toHaveLength(MAX_SUBSCRIBED_CHANNELS);
    // Never asks for a page past the cap.
    expect(calls).toHaveLength(2);
  });

  it('an end-of-list page (no token) is whole, not partial', async () => {
    const { fetcher, calls } = fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) }));
    expect(await fetchSubscriptions(fetcher, NOW)).toMatchObject({ ok: true, truncated: null });
    expect(calls).toHaveLength(1);
  });
});

describe('syncSubscriptions: storage', () => {
  it('stores the synced list under its own local key and hydrates it into loaded settings', async () => {
    await saveSettings(youtubeSettings());
    await syncSubscriptions(fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) })).fetcher, () => NOW);

    const raw = await chrome.storage.local.get(YT_SUBSCRIPTIONS_KEY);
    expect((raw[YT_SUBSCRIPTIONS_KEY] as { channels: unknown[] }).channels).toHaveLength(3);
    const loaded = await loadSettings();
    expect(loaded.rules[0]?.features?.youtube?.subscribedChannels.map((c) => c.handle)).toEqual([
      'veritasium',
      'kurzgesagt',
      null,
    ]);
  });

  it('never writes the list into the synced settings blob (8 KB per-item quota)', async () => {
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const hydrated = await loadSettings();
    expect(hydrated.rules[0]?.features?.youtube?.subscribedChannels).toEqual([STORED]);
    await saveSettings(hydrated);
    const synced = await chrome.storage.sync.get('nudge:settings');
    const yt = (synced['nudge:settings'] as NudgeSettings).rules[0]?.features?.youtube;
    expect(yt?.subscribedChannels).toEqual([]);
  });

  it('a signed-out sync keeps the last list and records why', async () => {
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const signedOut = fakeYouTube(response({ url: 'https://accounts.google.com/ServiceLogin' }));
    const state = await syncSubscriptions(signedOut.fetcher, () => NOW);
    expect(state.channels).toEqual([STORED]);
    expect(state.error).toBe('signed-out');
    expect((await subscriptionStatus()).error).toBe('signed-out');
  });

  it('does nothing at all while the option is off', async () => {
    await saveSettings(youtubeSettings({ allowSubscriptions: false }));
    const { fetcher, calls } = fakeYouTube(response({ html: subscriptionsPageHtml() }));
    await syncSubscriptions(fetcher, () => NOW);
    expect(calls).toHaveLength(0);
    expect(await loadSubscriptionState()).toEqual(emptySyncState());
  });

  it('concurrent callers share one fetch', async () => {
    await saveSettings(youtubeSettings());
    const { fetcher, calls } = fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) }));
    await Promise.all([syncSubscriptions(fetcher, () => NOW), syncSubscriptions(fetcher, () => NOW)]);
    expect(calls).toHaveLength(1);
  });

  it('a synced channel becomes a DNR allow-rule', async () => {
    await saveSettings(youtubeSettings());
    await syncSubscriptions(fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) })).fetcher, () => NOW);
    const rules = compileRules(await loadSettings(), { ms: {}, counts: {} }, NOW_DATE);
    const allows = rules.filter((r) => r.action.type === 'allow').map((r) => new RegExp(r.condition.regexFilter ?? '$^', 'i'));
    expect(allows.some((re) => re.test('https://www.youtube.com/@kurzgesagt/videos'))).toBe(true);
    expect(allows.some((re) => re.test('https://www.youtube.com/@mrbeast'))).toBe(false);
  });
});

describe('maybeAutoSync: at most daily, on a youtube.com visit', () => {
  it('syncs when due and not again within the day', async () => {
    await saveSettings(youtubeSettings());
    const page = () => response({ html: subscriptionsPageHtml(initialData(defaultChannels())) });
    const first = fakeYouTube(page());
    await maybeAutoSync(first.fetcher, () => NOW);
    expect(first.calls).toHaveLength(1);

    const second = fakeYouTube(page());
    await maybeAutoSync(second.fetcher, () => NOW + 60 * 60 * 1000 * 2);
    expect(second.calls).toHaveLength(0);

    const third = fakeYouTube(page());
    await maybeAutoSync(third.fetcher, () => NOW + AUTO_SYNC_INTERVAL_MS + 1);
    expect(third.calls).toHaveLength(1);
  });

  it('never throws into the page, whatever the network does', async () => {
    await saveSettings(youtubeSettings());
    await expect(maybeAutoSync(fakeYouTube(new Error('boom')).fetcher, () => NOW)).resolves.toBeUndefined();
  });
});

describe('the router: the list is the worker\'s, never the caller\'s', () => {
  async function save(next: NudgeSettings, challengeResponse?: string): Promise<SaveResult> {
    return (await handleRequest(
      { type: 'SAVE_SETTINGS', settings: next, challengeResponse } as never,
      NOW_DATE,
    )) as SaveResult;
  }

  it('a save carrying a made-up subscribed list cannot smuggle channels into the whitelist', async () => {
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const result = await save(youtubeSettings({ subscribedChannels: [INJECTED, STORED] }));
    expect(result.ok).toBe(true);
    const loaded = await loadSettings();
    expect(loaded.rules[0]?.features?.youtube?.subscribedChannels).toEqual([STORED]);
    // What the save INSTALLED, not a recompile from storage: the save compiles DNR from the
    // object it judged, so that object must already hold the worker's list.
    const installed = await chrome.declarativeNetRequest.getDynamicRules();
    const allows = installed
      .filter((r) => r.action.type === 'allow')
      .map((r) => new RegExp(r.condition.regexFilter ?? '$^', 'i'));
    expect(allows.some((re) => re.test('https://www.youtube.com/@veritasium'))).toBe(true);
    expect(allows.some((re) => re.test('https://www.youtube.com/@mrbeast'))).toBe(false);
  });

  it('a stale save cannot erase a sync that landed after the screen loaded', async () => {
    await saveSettings(youtubeSettings());
    const staleSnapshot = await loadSettings(); // dashboard opens: no subscriptions yet
    await storeList([STORED]); // a sync lands
    expect((await save(staleSnapshot)).ok).toBe(true);
    expect((await loadSettings()).rules[0]?.features?.youtube?.subscribedChannels).toEqual([STORED]);
  });

  it('turning the option ON under Strict Mode asks for the challenge', async () => {
    await saveSettings(youtubeSettings({ allowSubscriptions: false }, true));
    const result = await save(youtubeSettings({ allowSubscriptions: true }, true));
    expect(result).toMatchObject({ ok: false, reason: 'challenge-required' });
    expect(networkFetch).not.toHaveBeenCalled();
  });

  it('turning it ON syncs straight away (through the real network seam)', async () => {
    await saveSettings(youtubeSettings({ allowSubscriptions: false }));
    networkFetch.mockImplementation(async () =>
      response({ html: subscriptionsPageHtml(initialData(defaultChannels())) }),
    );
    expect((await save(youtubeSettings({ allowSubscriptions: true }))).ok).toBe(true);
    await vi.waitFor(async () => expect((await loadSubscriptionState()).channels).toHaveLength(3));
    expect(networkFetch.mock.calls[0]?.[0]).toBe(SUBSCRIPTIONS_PAGE_URL);
  });

  it("the content script's config carries the synced list, so the in-page gate agrees with DNR", async () => {
    // DNR allows a subscribed channel's pages; if GET_SITE_CONFIG dropped the list, the
    // watch gate in the page would hold the very video the network layer let through.
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const config = (await handleRequest(
      { type: 'GET_SITE_CONFIG', url: 'https://www.youtube.com/watch?v=abc' } as never,
      NOW_DATE,
    )) as { youtube: { subscribedChannels: ChannelEntry[] } | null; siteApplies: boolean };
    expect(config.youtube?.subscribedChannels).toEqual([STORED]);
    const { channels } = effectiveChannelLists(config.youtube as never, config.siteApplies);
    expect(channels.map((c) => c.handle)).toContain('veritasium');
  });

  it('GET_YT_SUBSCRIPTIONS reports the count and status without fetching', async () => {
    await storeList([STORED]);
    const status = await handleRequest({ type: 'GET_YT_SUBSCRIPTIONS' } as never, NOW_DATE);
    expect(status).toMatchObject({ count: 1, error: null, syncing: false });
    expect(networkFetch).not.toHaveBeenCalled();
  });
});
