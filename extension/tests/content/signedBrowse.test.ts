import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeBrowser } from 'wxt/testing';

import {
  SIGNED_BROWSE_MESSAGE,
  YOUTUBE_ORIGIN,
  isSignedBrowseRequest,
  readSignInCookies,
  sapisidAuthorization,
  sha1Hex,
  signedBrowseHeaders,
  type SignedBrowseRequest,
} from '../../src/core/signedBrowse';
import {
  handleSignedBrowse,
  registerSignedBrowseListener,
  type SignedBrowseDeps,
} from '../../src/content/signedBrowse';
import { BROWSE_ENDPOINT_URL } from '../../src/core/youtubeSubscriptions';
import { INNERTUBE_CONTEXT, USER_SESSION_ID, continuationResponse, manyChannels } from '../core/fixtures/youtubeSubscriptionsPage';

/**
 * The content-script half of the signed subscriptions read. The sign-in cookie is a
 * distinctive made-up value, so "it never leaves" is a plain substring search over
 * everything that does leave: the reply, every console line, every storage write, every
 * message, and every part of the request except the one header that carries its HASH.
 */

const SECRET = 'SAPISIDtestVALUE/abc';
const SECRET_1P = 'ONEPtestVALUE';
const COOKIE = `PREF=f6=40000000; SAPISID=${SECRET}; __Secure-1PAPISID=${SECRET_1P}; __Secure-3PAPISID=${SECRET}; YSC=xyz`;
const TS = 1_700_000_000;
// Reference values from Python's hashlib (sha1 of "[<user> ]<ts> <cookie> <origin>").
const PLAIN_HASH = 'b8272bf547d5a7e43e91193af3c1cde7ef176bd2';
const USER_HASH = '666283c7776e055bf4be30512e234dc1ed50000b';
const USER_HASH_1P = 'eb8040b4df901f103500fa728d32702f392773a0';

const SESSION = { userSessionId: USER_SESSION_ID, sessionIndex: '0', delegatedSessionId: null };

function request(overrides: Partial<SignedBrowseRequest> = {}): SignedBrowseRequest {
  return {
    type: SIGNED_BROWSE_MESSAGE,
    continuation: '4qmFsgKrCBIYRkVjaGFubmVsc19mZWVkX2xvYWRfbW9yZQ%3D%3D',
    context: INNERTUBE_CONTEXT,
    session: SESSION,
    ...overrides,
  };
}

interface Sent {
  url: string;
  init: RequestInit;
}

function deps(
  options: { origin?: string; cookie?: string; answer?: Partial<Response> | Error } = {},
): SignedBrowseDeps & { sent: Sent[] } {
  const sent: Sent[] = [];
  return {
    sent,
    origin: () => options.origin ?? YOUTUBE_ORIGIN,
    cookie: () => options.cookie ?? COOKIE,
    nowSeconds: () => TS + 0.7,
    send: async (url, init) => {
      sent.push({ url, init });
      const answer = options.answer ?? { ok: true, status: 200, json: async () => continuationResponse(manyChannels(2)) };
      if (answer instanceof Error) throw answer;
      return answer as Response;
    },
  };
}

describe('the hash: what YouTube’s own page sends', () => {
  it('sha1Hex is plain SHA-1', async () => {
    expect(await sha1Hex('abc')).toBe('a9993e364706816aba3e25717850c26c9cd0d89d');
  });

  it('without a session id: SAPISIDHASH <ts>_<sha1(ts cookie origin)>, per cookie present', async () => {
    const header = await sapisidAuthorization(
      { sapisid: SECRET, secure1p: null, secure3p: null },
      { origin: YOUTUBE_ORIGIN, nowSeconds: TS + 0.7, userSessionId: null },
    );
    expect(header).toBe(`SAPISIDHASH ${TS}_${PLAIN_HASH}`);
  });

  it('with USER_SESSION_ID: folded into the hash and marked _u, for SAPISID, 1P and 3P (the captured shape)', async () => {
    const header = await sapisidAuthorization(readSignInCookies(COOKIE), {
      origin: YOUTUBE_ORIGIN,
      nowSeconds: TS,
      userSessionId: USER_SESSION_ID,
    });
    expect(header).toBe(
      `SAPISIDHASH ${TS}_${USER_HASH}_u SAPISID1PHASH ${TS}_${USER_HASH_1P}_u SAPISID3PHASH ${TS}_${USER_HASH}_u`,
    );
  });

  it('falls back to __Secure-3PAPISID for SAPISIDHASH, and is null with no sign-in cookie', async () => {
    const opts = { origin: YOUTUBE_ORIGIN, nowSeconds: TS, userSessionId: null };
    expect(await sapisidAuthorization({ sapisid: null, secure1p: null, secure3p: SECRET }, opts)).toBe(
      `SAPISIDHASH ${TS}_${PLAIN_HASH} SAPISID3PHASH ${TS}_${PLAIN_HASH}`,
    );
    expect(await sapisidAuthorization(readSignInCookies('PREF=1; YSC=2'), opts)).toBeNull();
  });

  it('reads only the three sign-in cookies, and keeps an "=" inside a value', () => {
    expect(readSignInCookies('APISID=nope; SAPISID=a=b; __Secure-3PAPISID=c; SAPISID2=x')).toEqual({
      sapisid: 'a=b',
      secure1p: null,
      secure3p: 'c',
    });
  });

  it('headers: the page’s, with the account index and (only for a brand channel) the page id', () => {
    expect(signedBrowseHeaders('AUTH', SESSION)).toEqual({
      'Content-Type': 'application/json',
      Authorization: 'AUTH',
      'X-Origin': YOUTUBE_ORIGIN,
      'X-Goog-AuthUser': '0',
    });
    expect(signedBrowseHeaders('AUTH', { ...SESSION, sessionIndex: null, delegatedSessionId: '1188' })).toMatchObject({
      'X-Goog-AuthUser': '0',
      'X-Goog-PageId': '1188',
    });
  });
});

describe('handleSignedBrowse', () => {
  it('posts the signed request to the one browse endpoint, same-origin, and answers with the JSON', async () => {
    const d = deps();
    const reply = await handleSignedBrowse(request(), d);
    expect(reply).toEqual({ ok: true, json: continuationResponse(manyChannels(2)) });
    expect(d.sent).toHaveLength(1);
    const [{ url, init }] = d.sent as [Sent];
    expect(url).toBe(BROWSE_ENDPOINT_URL);
    expect(url).toBe('https://www.youtube.com/youtubei/v1/browse?prettyPrint=false');
    expect(init).toMatchObject({ method: 'POST', credentials: 'include', cache: 'no-store' });
    expect((init.headers as Record<string, string>).Authorization).toBe(
      `SAPISIDHASH ${TS}_${USER_HASH}_u SAPISID1PHASH ${TS}_${USER_HASH_1P}_u SAPISID3PHASH ${TS}_${USER_HASH}_u`,
    );
    expect(JSON.parse(init.body as string)).toEqual({ context: INNERTUBE_CONTEXT, continuation: request().continuation });
  });

  it('a message can name a token, never a destination: an extra url field is ignored', async () => {
    const d = deps();
    await handleSignedBrowse({ ...request(), url: 'https://evil.example/steal' }, d);
    expect(d.sent.map((s) => s.url)).toEqual([BROWSE_ENDPOINT_URL]);
  });

  it.each([
    ['m.youtube.com', 'https://m.youtube.com'],
    ['music.youtube.com', 'https://music.youtube.com'],
    ['another site', 'https://example.com'],
  ])('does nothing on %s (not same-origin with the endpoint)', async (_name, origin) => {
    const d = deps({ origin });
    expect(await handleSignedBrowse(request(), d)).toEqual({ ok: false, reason: 'not-youtube' });
    expect(d.sent).toHaveLength(0);
  });

  it('signed out (no sign-in cookie): says so and sends nothing', async () => {
    const d = deps({ cookie: 'PREF=1; YSC=2' });
    expect(await handleSignedBrowse(request(), d)).toEqual({ ok: false, reason: 'signed-out' });
    expect(d.sent).toHaveLength(0);
  });

  it.each([
    ['a non-object', 'hello'],
    ['the wrong type', { ...request(), type: 'OTHER' }],
    ['a token with a header break', request({ continuation: 'abc\r\nX-Evil: 1' })],
    ['an empty token', request({ continuation: '' })],
    ['an array context', { ...request(), context: [] }],
    ['a session id with a space', request({ session: { ...SESSION, userSessionId: 'a b' } })],
    ['a non-numeric account index', request({ session: { ...SESSION, sessionIndex: '0\r\n' } })],
    ['no session', { ...request(), session: undefined }],
  ])('rejects %s without sending anything', async (_name, message) => {
    const d = deps();
    expect(isSignedBrowseRequest(message)).toBe(false);
    expect(await handleSignedBrowse(message, d)).toEqual({ ok: false, reason: 'bad-request' });
    expect(d.sent).toHaveLength(0);
  });

  it('a refused or unreadable answer is http with its status; a failed request is network', async () => {
    expect(await handleSignedBrowse(request(), deps({ answer: { ok: false, status: 403 } }))).toEqual({
      ok: false,
      reason: 'http',
      status: 403,
    });
    const notJson = { ok: true, status: 200, json: async () => JSON.parse('<html>') as unknown };
    expect(await handleSignedBrowse(request(), deps({ answer: notJson }))).toEqual({
      ok: false,
      reason: 'http',
      status: 200,
    });
    expect(await handleSignedBrowse(request(), deps({ answer: new Error('offline') }))).toEqual({
      ok: false,
      reason: 'network',
    });
  });
});

describe('the sign-in cookie never leaves the content script', () => {
  const consoleSpies: ReturnType<typeof vi.spyOn>[] = [];
  beforeEach(() => {
    fakeBrowser.reset();
    for (const method of ['log', 'info', 'warn', 'error', 'debug'] as const) {
      consoleSpies.push(vi.spyOn(console, method));
    }
  });
  afterEach(() => {
    for (const spy of consoleSpies.splice(0)) spy.mockRestore();
  });

  it.each([
    ['a success', undefined],
    ['a refusal', { ok: false, status: 403 }],
    ['a network failure', new Error(`offline ${SECRET}?`)],
  ] as const)('after %s: not in the reply, a log line, storage, a message, or the request outside its hash', async (_name, answer) => {
    const sendMessage = vi.spyOn(chrome.runtime, 'sendMessage');
    const d = deps({ answer: answer as Partial<Response> | Error | undefined });
    const reply = await handleSignedBrowse(request(), d);

    expect(JSON.stringify(reply)).not.toContain(SECRET);
    expect(JSON.stringify(reply)).not.toContain(SECRET_1P);
    for (const spy of consoleSpies) expect(JSON.stringify(spy.mock.calls)).not.toContain(SECRET);
    expect(sendMessage).not.toHaveBeenCalled();
    const stored = JSON.stringify([await chrome.storage.local.get(null), await chrome.storage.sync.get(null)]);
    expect(stored).not.toContain(SECRET);

    for (const { url, init } of d.sent) {
      const { Authorization, ...otherHeaders } = init.headers as Record<string, string>;
      expect(Authorization).not.toContain(SECRET);
      expect(Authorization).not.toContain(SECRET_1P);
      expect(JSON.stringify([url, otherHeaders, init.body])).not.toContain(SECRET);
      expect(JSON.stringify([url, otherHeaders, init.body])).not.toContain(USER_HASH);
    }
    sendMessage.mockRestore();
  });
});

describe('registerSignedBrowseListener', () => {
  type Listener = (message: unknown, sender: chrome.runtime.MessageSender, sendResponse: (r: unknown) => void) => boolean;

  function capture(): { listener: Listener; d: ReturnType<typeof deps>; stop: () => void } {
    const add = vi.spyOn(chrome.runtime.onMessage, 'addListener');
    const d = deps();
    const stop = registerSignedBrowseListener(d);
    const listener = add.mock.calls[0]?.[0] as unknown as Listener;
    add.mockRestore();
    return { listener, d, stop };
  }

  it('answers the extension’s own request asynchronously (returns true, then responds)', async () => {
    const { listener, d, stop } = capture();
    const sendResponse = vi.fn();
    expect(listener(request(), { id: chrome.runtime.id }, sendResponse)).toBe(true);
    await vi.waitFor(() => expect(sendResponse).toHaveBeenCalledWith({ ok: true, json: expect.anything() }));
    expect(d.sent).toHaveLength(1);
    stop();
  });

  it('leaves every other message type to other listeners (returns false, never responds)', () => {
    const { listener, d, stop } = capture();
    const sendResponse = vi.fn();
    expect(listener({ type: 'GET_SITE_CONFIG' }, { id: chrome.runtime.id }, sendResponse)).toBe(false);
    expect(sendResponse).not.toHaveBeenCalled();
    expect(d.sent).toHaveLength(0);
    stop();
  });

  it('ignores a request from any other extension', () => {
    const { listener, d, stop } = capture();
    const sendResponse = vi.fn();
    expect(listener(request(), { id: 'some-other-extension' }, sendResponse)).toBe(false);
    expect(d.sent).toHaveLength(0);
    stop();
  });
});
