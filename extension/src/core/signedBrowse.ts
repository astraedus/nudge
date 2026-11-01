/**
 * Asking youtube.com for the next page of the user's subscriptions THE WAY YOUTUBE'S OWN
 * PAGE DOES: a `browse` POST signed with a hash of the user's YouTube sign-in cookie.
 * PURE: no chrome.*, no network, no DOM. The two halves that touch the world are
 * `content/signedBrowse.ts` (computes the header and sends the request, inside a
 * youtube.com tab) and `background/youtubeTabBrowse.ts` (asks that tab to, by message).
 *
 * WHY A SIGNATURE AT ALL. YouTube refuses an unsigned continuation: from the worker it
 * answers 403, and from a youtube.com page it answers 200 but signed OUT, with no channels
 * (live signed-in QA, 2026-11-01 and 2026-11-02). The same request carrying the header
 * below comes back signed in with the channels (2026-11-02, see extension/CLAUDE.md 1c).
 *
 * THE ONE PRIVACY RULE. The cookie value exists only inside the content script, for the
 * length of one request: read from `document.cookie`, hashed, dropped. It is never in a
 * message (the request below has no field that could carry it, and the reply is YouTube's
 * JSON), never in storage, never logged. The hash is sent to youtube.com and nowhere else.
 * No `cookies` permission: a youtube.com content script can already read these cookies,
 * because YouTube's own page script reads them to do exactly this.
 */

/** The only origin a signed request is ever made from and to. */
export const YOUTUBE_ORIGIN = 'https://www.youtube.com';

/** Worker -> content script: "sign and send this one browse request". */
export const SIGNED_BROWSE_MESSAGE = 'NUDGE_YT_SIGNED_BROWSE';

/**
 * Which signed-in session to ask as, read from the page's own ytcfg by the worker
 * (`extractInnertubeConfig`). None of these is a credential: they pick the account and
 * channel the way YouTube's own requests do, so the next pages belong to the same list the
 * first page came from.
 */
export interface BrowseSession {
  /** ytcfg `USER_SESSION_ID`: folded into the hash, marked by the `_u` suffix. */
  userSessionId: string | null;
  /** ytcfg `SESSION_INDEX`: which signed-in Google account (`X-Goog-AuthUser`). */
  sessionIndex: string | null;
  /** ytcfg `DELEGATED_SESSION_ID`: a brand channel picked as "you" (`X-Goog-PageId`). */
  delegatedSessionId: string | null;
}

export interface SignedBrowseRequest {
  type: typeof SIGNED_BROWSE_MESSAGE;
  /** YouTube's opaque continuation token, from the previous page. */
  continuation: string;
  /** The page's own `INNERTUBE_CONTEXT` (or the minimal fallback). */
  context: Record<string, unknown>;
  session: BrowseSession;
}

/** Content script -> worker. YouTube's JSON, or why there is none. Never a cookie. */
export type SignedBrowseReply =
  | { ok: true; json: unknown }
  | {
      ok: false;
      /**
       * `not-youtube`: this tab is not www.youtube.com (ask another tab).
       * `signed-out`: the tab holds no sign-in cookie to sign with.
       * `http`: YouTube answered, but not with a usable page (`status` says how).
       * `network`: the request did not complete.
       * `bad-request`: the message failed validation.
       */
      reason: 'not-youtube' | 'signed-out' | 'http' | 'network' | 'bad-request';
      status?: number;
    };

// Every field here ends up in a request header, a hash input or a request body, so each is
// held to a charset that cannot smuggle a header break or anything unexpected.
const TOKEN = /^[A-Za-z0-9_%=+/-]{1,4096}$/;
const SESSION_FIELD = /^[A-Za-z0-9_-]{1,128}$/;
const SESSION_INDEX = /^\d{1,2}$/;

function optionalField(value: unknown, pattern: RegExp): boolean {
  return value === null || (typeof value === 'string' && pattern.test(value));
}

/**
 * A session as parsed from the page, each field nulled unless it passes the same check the
 * content script applies, so one odd value costs that header rather than the whole request.
 */
export function browseSession(raw: {
  userSessionId: string | null;
  sessionIndex: string | null;
  delegatedSessionId: string | null;
}): BrowseSession {
  const keep = (value: string | null, pattern: RegExp) => (value !== null && pattern.test(value) ? value : null);
  return {
    userSessionId: keep(raw.userSessionId, SESSION_FIELD),
    sessionIndex: keep(raw.sessionIndex, SESSION_INDEX),
    delegatedSessionId: keep(raw.delegatedSessionId, SESSION_FIELD),
  };
}

/** Whether a message is a well-formed signed-browse request. The content script's gate. */
export function isSignedBrowseRequest(value: unknown): value is SignedBrowseRequest {
  if (value === null || typeof value !== 'object') return false;
  const v = value as Partial<Record<keyof SignedBrowseRequest, unknown>>;
  if (v.type !== SIGNED_BROWSE_MESSAGE) return false;
  if (typeof v.continuation !== 'string' || !TOKEN.test(v.continuation)) return false;
  if (v.context === null || typeof v.context !== 'object' || Array.isArray(v.context)) return false;
  const s = v.session as Partial<Record<keyof BrowseSession, unknown>> | null | undefined;
  if (s === null || typeof s !== 'object') return false;
  return (
    optionalField(s.userSessionId, SESSION_FIELD) &&
    optionalField(s.sessionIndex, SESSION_INDEX) &&
    optionalField(s.delegatedSessionId, SESSION_FIELD)
  );
}

/** The sign-in cookies YouTube's page hashes, as `document.cookie` shows them. */
export interface SignInCookies {
  sapisid: string | null;
  secure1p: string | null;
  secure3p: string | null;
}

/** Pick the three sign-in cookies out of a `document.cookie` string. Nothing else is read. */
export function readSignInCookies(cookieString: string): SignInCookies {
  const found: SignInCookies = { sapisid: null, secure1p: null, secure3p: null };
  for (const part of cookieString.split(';')) {
    const eq = part.indexOf('=');
    if (eq === -1) continue;
    const name = part.slice(0, eq).trim();
    const value = part.slice(eq + 1).trim();
    if (value === '') continue;
    if (name === 'SAPISID') found.sapisid = value;
    else if (name === '__Secure-1PAPISID') found.secure1p = value;
    else if (name === '__Secure-3PAPISID') found.secure3p = value;
  }
  return found;
}

/** Lowercase hex SHA-1, via Web Crypto (available in content scripts, workers and Node). */
export async function sha1Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * The `Authorization` header YouTube's own page sends on `youtubei/v1/browse`, or null
 * when there is no sign-in cookie to sign with.
 *
 * FORMAT, as captured from youtube.com's own request (2026-11-02) and matching yt-dlp's
 * `_make_sid_authorization`: one `<SCHEME> <ts>_<sha1>[_u]` per cookie, space-joined, for
 * SAPISIDHASH (SAPISID), SAPISID1PHASH (__Secure-1PAPISID) and SAPISID3PHASH
 * (__Secure-3PAPISID). The hashed text is `[<USER_SESSION_ID> ]<ts> <cookie> <origin>`;
 * when the session id is folded in, the value ends `_u`. `ts` is Unix seconds. Live, the
 * plain SAPISIDHASH alone is also accepted, but the page's exact shape is what is sent.
 */
export async function sapisidAuthorization(
  cookies: SignInCookies,
  options: { origin: string; nowSeconds: number; userSessionId: string | null },
): Promise<string | null> {
  const primary = cookies.sapisid ?? cookies.secure3p;
  if (primary === null) return null;
  const ts = String(Math.floor(options.nowSeconds));
  const user = options.userSessionId;
  const sign = async (scheme: string, value: string): Promise<string> => {
    const input = `${user !== null ? `${user} ` : ''}${ts} ${value} ${options.origin}`;
    return `${scheme} ${ts}_${await sha1Hex(input)}${user !== null ? '_u' : ''}`;
  };
  const parts = [await sign('SAPISIDHASH', primary)];
  if (cookies.secure1p !== null) parts.push(await sign('SAPISID1PHASH', cookies.secure1p));
  if (cookies.secure3p !== null) parts.push(await sign('SAPISID3PHASH', cookies.secure3p));
  return parts.join(' ');
}

/** Every header of the signed request: what YouTube's page sends that the server reads. */
export function signedBrowseHeaders(authorization: string, session: BrowseSession): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Authorization: authorization,
    'X-Origin': YOUTUBE_ORIGIN,
    'X-Goog-AuthUser': session.sessionIndex ?? '0',
  };
  if (session.delegatedSessionId !== null) headers['X-Goog-PageId'] = session.delegatedSessionId;
  return headers;
}
