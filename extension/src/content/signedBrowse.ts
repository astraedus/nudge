/**
 * The content-script half of the signed subscriptions read (`core/signedBrowse.ts` has the
 * why). The worker asks, by message, for ONE continuation page; this signs and sends it from
 * the youtube.com page, same-origin, with the cookies the page already has, and answers
 * with YouTube's JSON.
 *
 * The sign-in cookie is read here, hashed here, and goes out of scope here. Nothing that is
 * returned, stored or logged is derived from it except the hash, which only ever travels in
 * the request to youtube.com (pinned by tests/content/signedBrowse.test.ts).
 *
 * The request target is a constant (`BROWSE_ENDPOINT_URL`). The message names a token and a
 * client context, never a URL, so no message can point this at another address.
 */

import { BROWSE_ENDPOINT_URL } from '../core/youtubeSubscriptions';
import {
  SIGNED_BROWSE_MESSAGE,
  YOUTUBE_ORIGIN,
  isSignedBrowseRequest,
  readSignInCookies,
  sapisidAuthorization,
  signedBrowseHeaders,
  type SignedBrowseReply,
} from '../core/signedBrowse';

export interface SignedBrowseDeps {
  origin: () => string;
  cookie: () => string;
  nowSeconds: () => number;
  send: (url: string, init: RequestInit) => Promise<Response>;
}

// The second (and last) sanctioned network call in the extension, see extension/CLAUDE.md
// "Non-negotiables": the opt-in subscriptions read, to youtube.com, from youtube.com.
const realDeps: SignedBrowseDeps = {
  origin: () => location.origin,
  cookie: () => document.cookie,
  nowSeconds: () => Date.now() / 1000,
  send: (url, init) =>
    // eslint-disable-next-line no-restricted-globals -- opt-in signed youtube.com browse, see above
    fetch(url, init),
};

/** Handle one request. Never throws; every failure is a reason the worker can act on. */
export async function handleSignedBrowse(
  message: unknown,
  deps: SignedBrowseDeps = realDeps,
): Promise<SignedBrowseReply> {
  if (!isSignedBrowseRequest(message)) return { ok: false, reason: 'bad-request' };
  if (deps.origin() !== YOUTUBE_ORIGIN) return { ok: false, reason: 'not-youtube' };

  const authorization = await sapisidAuthorization(readSignInCookies(deps.cookie()), {
    origin: YOUTUBE_ORIGIN,
    nowSeconds: deps.nowSeconds(),
    userSessionId: message.session.userSessionId,
  });
  if (authorization === null) return { ok: false, reason: 'signed-out' };

  let response: Response;
  try {
    response = await deps.send(BROWSE_ENDPOINT_URL, {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      headers: signedBrowseHeaders(authorization, message.session),
      body: JSON.stringify({ context: message.context, continuation: message.continuation }),
    });
  } catch {
    return { ok: false, reason: 'network' };
  }
  if (!response.ok) return { ok: false, reason: 'http', status: response.status };
  try {
    return { ok: true, json: (await response.json()) as unknown };
  } catch {
    return { ok: false, reason: 'http', status: response.status };
  }
}

/**
 * Listen for the worker's requests. Only this extension can reach a content script's
 * `runtime.onMessage` (no `externally_connectable`), and the sender id is checked anyway.
 * Any other message type is left alone (`false`), so this never answers for someone else.
 */
export function registerSignedBrowseListener(deps: SignedBrowseDeps = realDeps): () => void {
  const listener = (
    message: unknown,
    sender: chrome.runtime.MessageSender,
    sendResponse: (reply: SignedBrowseReply) => void,
  ): boolean => {
    if ((message as { type?: unknown } | null)?.type !== SIGNED_BROWSE_MESSAGE) return false;
    if (sender.id !== chrome.runtime.id) return false;
    void handleSignedBrowse(message, deps).then(sendResponse);
    return true;
  };
  chrome.runtime.onMessage.addListener(listener);
  return () => chrome.runtime.onMessage.removeListener(listener);
}
