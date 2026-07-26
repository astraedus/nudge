/**
 * Channel detection for YouTube watch pages and feed cards (ext-03 §3).
 *
 * WHY three tiers: YouTube ships the uploader's identity in a different shape depending on
 * the surface, and reshuffles those shapes on its own schedule. A watch page embeds two
 * separate inline JSON blobs (`ytInitialPlayerResponse` and `ytInitialData`) that both
 * carry the channel, in different places, with different reliability:
 *
 *   1. `channelFromPlayerResponse` — PRIMARY. `ytInitialPlayerResponse.videoDetails` is the
 *      flattest, most stable shape: `channelId` and `author` sit directly on one object.
 *   2. `channelFromInitialData` — FALLBACK. `ytInitialData` carries the same information
 *      nested inside the page's render tree (`videoSecondaryInfoRenderer.owner
 *      .videoOwnerRenderer`), several renderer wrappers deep. Those wrapper names are
 *      exactly what YouTube's A/B tests and redesigns rename, so this path is read with a
 *      generic tree search rather than a fixed property path (`findRenderer` below) — it
 *      only needs the ONE key name `videoSecondaryInfoRenderer` to still exist somewhere in
 *      the tree, not for its ancestors to be unchanged.
 *   3. `channelFromDom` — LAST RESORT. If neither script is present or parseable, fall back
 *      to `CHANNEL_ELEMENTS` (selectors.ts), a chain of DOM selectors ordered specific to
 *      generic so a wrapper rename degrades to the bare `a[href^="/@"]` rung instead of
 *      finding nothing.
 *
 * `detectWatchChannel` runs all three in order and returns the first one that actually
 * identifies the channel (a channel id or a handle — a bare display name is not enough to
 * call it "found"). `detectCardChannel` runs the same DOM chain (tier 3 only — feed cards
 * don't carry their own inline JSON) SCOPED to one feed card, which is what makes per-card
 * channel filtering possible at all: an unscoped query would return the first channel
 * anywhere on the page for every card.
 *
 * THE BRACE-COUNTING SCANNER (`scanBalancedJson`): a naive extraction — split the script's
 * text on a fixed delimiter like `'};</script>'` and glue a `}` back on — breaks the moment
 * YouTube changes what follows the object literal (ext-03 §3). This module never does that;
 * it locates the `=` that starts the assignment, then walks forward character by character
 * counting `{`/`}` depth, correctly stepping over string literals (so a `{` or `}` INSIDE a
 * quoted string, or an escaped quote/backslash, never perturbs the count) until depth
 * returns to zero. No `eval`/`new Function` anywhere — MV3's CSP forbids both outright, and
 * a hand-rolled scanner plus `JSON.parse` on the extracted slice is the only compliant way
 * to pull structured data out of inline script text.
 *
 * Every exported function takes its document/element as an argument, so all of this is
 * testable in jsdom with zero browser and zero network. Every function is also built to
 * NEVER THROW: a missing script, truncated JSON, an unexpected shape, or an empty DOM all
 * resolve to `null` rather than an exception — a page mid-render (YouTube's SPA constantly
 * is) must not turn a channel-detection miss into a crash.
 *
 * `DetectedChannel` is deliberately NOT imported from `../core/channels.ts` (a sibling
 * module under concurrent development) even though the shapes overlap — this file has no
 * dependency on it, by design, so the two can be built and tested independently and bridged
 * later by whoever wires channel detection into the block engine.
 */

import {
  CHANNEL_ELEMENTS,
  CHANNEL_NAME_NOISE,
  VIDEO_RENDERER_SELECTOR,
} from './selectors';

/** What this module knows about a channel. Any field may be unavailable on a given page. */
export interface DetectedChannel {
  /** The canonical `UCxxxxxxxxxxxxxxxxxxxxxx` id, or null if not found. */
  channelId: string | null;
  /** The `@handle` form, INCLUDING the leading `@`, never a URL path. Null if not found. */
  handle: string | null;
  /** Human-readable channel name, noise-stripped. Purely cosmetic — never an identifier. */
  displayName: string | null;
}

/* ------------------------------------------------------------ shared JSON extraction */

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Find the index of the `{` that opens the object assigned to `varName` in `text`.
 *
 * Handles the assignment forms YouTube actually ships: `var X = {`, bare `X = {`, and the
 * bracket form `window["X"] = {` / `window['X'] = {`. Deliberately does NOT try to parse
 * full JS grammar — it only needs to find one `=` that plausibly belongs to this variable
 * name and the first `{` after it, then hands off to the brace scanner.
 */
function findObjectStart(text: string, varName: string): number | null {
  let searchFrom = 0;

  for (;;) {
    const nameIndex = text.indexOf(varName, searchFrom);
    if (nameIndex === -1) return null;

    const afterName = nameIndex + varName.length;
    const eqIndex = text.indexOf('=', afterName);
    if (eqIndex === -1) {
      searchFrom = afterName;
      continue;
    }

    // Only whitespace / quotes / a closing bracket may sit between the name and its `=`
    // (covers `var x = `, `x=`, `window["x"] = `, `window['x']=`). Anything else means this
    // occurrence of the name isn't the assignment we're after (e.g. it's inside a longer
    // identifier or a string).
    const between = text.slice(afterName, eqIndex);
    if (!/^["'\]\s]*$/.test(between)) {
      searchFrom = afterName;
      continue;
    }
    // Don't mistake `==`/`===` for an assignment operator.
    if (text.charAt(eqIndex + 1) === '=') {
      searchFrom = eqIndex + 1;
      continue;
    }

    let i = eqIndex + 1;
    while (i < text.length && /\s/.test(text.charAt(i))) i += 1;
    if (text.charAt(i) === '{') return i;

    searchFrom = afterName;
  }
}

/**
 * From `start` (which must point at a `{`), walk forward tracking brace depth while
 * correctly skipping over string contents — including escaped quotes and escaped
 * backslashes — and return the source slice through the matching closing brace. Returns
 * null if the braces never balance (truncated input) or `start` isn't a `{`.
 */
function scanBalancedJson(text: string, start: number): string | null {
  if (text.charAt(start) !== '{') return null;

  let depth = 0;
  let inString = false;
  let quote = '';
  let escaped = false;

  for (let i = start; i < text.length; i += 1) {
    const ch = text.charAt(i);

    if (inString) {
      if (escaped) {
        // Whatever this character is, it was escaped — consume it as literal string
        // content and nothing else, whether it's a quote, a backslash, or anything else.
        escaped = false;
      } else if (ch === '\\') {
        escaped = true;
      } else if (ch === quote) {
        inString = false;
      }
      continue;
    }

    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      continue;
    }

    if (ch === '{') {
      depth += 1;
    } else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }

  return null;
}

/**
 * Scan every `<script>` on the page for an assignment to `varName`, extract the object
 * literal with the brace scanner, and `JSON.parse` it. Tries every script (not just the
 * first that mentions the name) and keeps going past a malformed candidate, so one broken
 * or unrelated script can't hide a good one elsewhere on the page.
 */
function findAssignedObject(doc: Document, varName: string): Record<string, unknown> | null {
  for (const script of Array.from(doc.querySelectorAll('script'))) {
    const text = script.textContent ?? '';
    if (!text.includes(varName)) continue;

    const start = findObjectStart(text, varName);
    if (start === null) continue;

    const slice = scanBalancedJson(text, start);
    if (slice === null) continue;

    try {
      const parsed: unknown = JSON.parse(slice);
      if (isRecord(parsed)) return parsed;
    } catch {
      // Malformed JSON in this script — keep looking rather than giving up on the page.
    }
  }
  return null;
}

/**
 * Breadth-first search for the first object anywhere in `root`'s tree that owns `key` as
 * its own property, returning that property's value. This is how `channelFromInitialData`
 * survives YouTube renaming/reshuffling the renderer wrappers ABOVE
 * `videoSecondaryInfoRenderer` — it never assumes a fixed path down to it.
 */
function findRenderer(root: unknown, key: string): unknown {
  const queue: unknown[] = [root];

  while (queue.length > 0) {
    const current = queue.shift();

    if (isRecord(current)) {
      if (key in current) return current[key];
      for (const value of Object.values(current)) {
        if (isRecord(value) || Array.isArray(value)) queue.push(value);
      }
    } else if (Array.isArray(current)) {
      for (const value of current) {
        if (isRecord(value) || Array.isArray(value)) queue.push(value);
      }
    }
  }

  return undefined;
}

/* ------------------------------------------------------------------------- tier 1 & 2 */

/** Never throws: wraps a tier lookup so a shape surprise resolves to null, not a crash. */
function safeCall<T>(fn: () => T | null): T | null {
  try {
    return fn();
  } catch {
    return null;
  }
}

/**
 * PRIMARY path. Reads `ytInitialPlayerResponse.videoDetails.{channelId,author}` out of an
 * inline `<script>`. This is the flattest, most stable shape YouTube ships (ext-03 §3).
 */
export function channelFromPlayerResponse(doc: Document): DetectedChannel | null {
  return safeCall(() => {
    const data = findAssignedObject(doc, 'ytInitialPlayerResponse');
    if (!data) return null;

    const videoDetails = data.videoDetails;
    if (!isRecord(videoDetails)) return null;

    const channelId = typeof videoDetails.channelId === 'string' ? videoDetails.channelId : null;
    const displayName = typeof videoDetails.author === 'string' ? videoDetails.author : null;
    if (channelId === null && displayName === null) return null;

    return { channelId, handle: null, displayName };
  });
}

/**
 * FALLBACK path. Reads `ytInitialData` and walks to
 * `videoSecondaryInfoRenderer.owner.videoOwnerRenderer` (found anywhere in the tree, see
 * `findRenderer`) for the channel id, handle and display name.
 */
export function channelFromInitialData(doc: Document): DetectedChannel | null {
  return safeCall(() => {
    const data = findAssignedObject(doc, 'ytInitialData');
    if (!data) return null;

    const secondary = findRenderer(data, 'videoSecondaryInfoRenderer');
    if (!isRecord(secondary)) return null;

    const owner = secondary.owner;
    if (!isRecord(owner)) return null;

    const videoOwnerRenderer = owner.videoOwnerRenderer;
    if (!isRecord(videoOwnerRenderer)) return null;

    let channelId: string | null = null;
    let displayName: string | null = null;

    const title = videoOwnerRenderer.title;
    if (isRecord(title) && Array.isArray(title.runs)) {
      const firstRun = title.runs[0];
      if (isRecord(firstRun)) {
        displayName = typeof firstRun.text === 'string' ? firstRun.text : null;

        const runNav = firstRun.navigationEndpoint;
        if (isRecord(runNav)) {
          const runBrowse = runNav.browseEndpoint;
          if (isRecord(runBrowse) && typeof runBrowse.browseId === 'string') {
            channelId = runBrowse.browseId;
          }
        }
      }
    }

    let handle: string | null = null;
    const ownerNav = videoOwnerRenderer.navigationEndpoint;
    if (isRecord(ownerNav)) {
      const ownerBrowse = ownerNav.browseEndpoint;
      if (isRecord(ownerBrowse) && typeof ownerBrowse.canonicalBaseUrl === 'string') {
        handle = handleFromCanonicalPath(ownerBrowse.canonicalBaseUrl);
      }
    }

    if (channelId === null && handle === null && displayName === null) return null;
    return { channelId, handle, displayName };
  });
}

/* ------------------------------------------------------------------------------ tier 3 */

/** The first path segment: stops at `/`, `?` or `#`. `''` if `text` starts with one. */
function firstSegment(text: string): string {
  const match = /^[^/?#]+/.exec(text);
  return match ? match[0] : '';
}

/** `/@handle`, `@handle` or `/@handle/videos` -> `@handle`. Anything else -> null. */
function handleFromCanonicalPath(path: string): string | null {
  const withoutSlash = path.startsWith('/') ? path.slice(1) : path;
  if (!withoutSlash.startsWith('@')) return null;
  const segment = firstSegment(withoutSlash);
  return segment.length > 0 ? segment : null;
}

/**
 * Classify a channel anchor's `href`: `/channel/UC…` -> channelId, `/@name` -> handle,
 * `/c/…` or `/user/…` -> a handle-ish name (the best identifier a legacy custom-URL link
 * gives us — not a real handle, but stored in the same field since it plays the same role).
 */
function classifyHref(href: string): { channelId: string | null; handle: string | null } {
  if (href.startsWith('/channel/')) {
    const id = firstSegment(href.slice('/channel/'.length));
    return { channelId: id.length > 0 ? id : null, handle: null };
  }

  const handle = handleFromCanonicalPath(href);
  if (handle !== null) return { channelId: null, handle };

  if (href.startsWith('/c/')) {
    const name = firstSegment(href.slice('/c/'.length));
    return { channelId: null, handle: name.length > 0 ? name : null };
  }
  if (href.startsWith('/user/')) {
    const name = firstSegment(href.slice('/user/'.length));
    return { channelId: null, handle: name.length > 0 ? name : null };
  }

  return { channelId: null, handle: null };
}

/**
 * Display name from a channel anchor: `aria-label` FIRST (ext-03 §3: more reliable than
 * text content), falling back to `textContent`, then stripping the decorative noise
 * patterns YouTube wraps around the accessible name (`CHANNEL_NAME_NOISE`).
 */
function displayNameFrom(el: Element): string | null {
  const ariaLabel = el.getAttribute('aria-label');
  const source = ariaLabel !== null && ariaLabel.trim().length > 0 ? ariaLabel : (el.textContent ?? '');

  let cleaned = source.trim();
  for (const pattern of CHANNEL_NAME_NOISE) {
    cleaned = cleaned.replace(pattern, '').trim();
  }
  return cleaned.length > 0 ? cleaned : null;
}

/**
 * LAST RESORT. Runs the `CHANNEL_ELEMENTS` chain (selectors.ts) against `root` and reads
 * the channel off the first matching anchor. `root` is whatever scope the caller wants —
 * a whole document for a watch page, or a single feed card for `detectCardChannel` — the
 * scoping is entirely the caller's choice, this function never reaches outside `root`.
 */
export function channelFromDom(root: ParentNode): DetectedChannel | null {
  return safeCall(() => {
    // Try EVERY rung, and every element each rung matches, until one actually yields an
    // identifier.
    //
    // Taking only `elements[0]` of the first matching rung leaked the whitelist (live QA,
    // 2026-07-26): YouTube search results carry a hidden placeholder `ytd-channel-name`
    // anchor with an EMPTY href, which wins the first rung, classifies to nothing, and makes
    // the card look unidentifiable - even though a perfectly good `/@handle` anchor exists
    // further down the same card. An element that yields no identifier is not an answer, it
    // is a miss, so keep looking.
    let fallbackName: string | null = null;

    for (const rule of CHANNEL_ELEMENTS) {
      let matches: Element[];
      try {
        matches = Array.from(root.querySelectorAll(rule.selector));
      } catch {
        continue;
      }

      for (const anchor of matches) {
        const href = anchor.getAttribute('href') ?? '';
        // A hrefless anchor is a placeholder, never a channel link.
        if (href.trim() === '') {
          fallbackName ??= displayNameFrom(anchor);
          continue;
        }
        const { channelId, handle } = classifyHref(href);
        if (channelId === null && handle === null) {
          fallbackName ??= displayNameFrom(anchor);
          continue;
        }
        return { channelId, handle, displayName: displayNameFrom(anchor) ?? fallbackName };
      }
    }

    // Nothing identifying anywhere. A bare name is not an identifier (see `hasIdentifier`),
    // but returning it keeps the display useful for a caller that only wants a label.
    return fallbackName === null
      ? null
      : { channelId: null, handle: null, displayName: fallbackName };
  });
}

/* ------------------------------------------------------------------------- composite */

/** True when a result actually identifies a channel (an id or a handle) — a bare name does not. */
function hasIdentifier(result: DetectedChannel | null): result is DetectedChannel {
  return result !== null && (result.channelId !== null || result.handle !== null);
}

/** The video id a watch/shorts URL refers to, or null when the URL names no video. */
export function videoIdFromUrl(url: string): string | null {
  try {
    const parsed = new URL(url);
    const fromQuery = parsed.searchParams.get('v');
    if (fromQuery !== null && fromQuery !== '') return fromQuery;
    const shorts = /^\/shorts\/([^/?#]+)/.exec(parsed.pathname);
    return shorts?.[1] ?? null;
  } catch {
    return null;
  }
}

/** The video id the inline `ytInitialPlayerResponse` describes, if it says. */
export function playerResponseVideoId(doc: Document): string | null {
  return safeCall(() => {
    const data = findAssignedObject(doc, 'ytInitialPlayerResponse');
    if (!data || !isRecord(data.videoDetails)) return null;
    const id = data.videoDetails.videoId;
    return typeof id === 'string' && id !== '' ? id : null;
  });
}

/** The video id the inline `ytInitialData` describes, if it says. */
export function initialDataVideoId(doc: Document): string | null {
  return safeCall(() => {
    const data = findAssignedObject(doc, 'ytInitialData');
    if (!data) return null;
    const endpoint = findRenderer(data, 'watchEndpoint');
    const id = isRecord(endpoint) ? endpoint.videoId : null;
    return typeof id === 'string' && id !== '' ? id : null;
  });
}

/**
 * The ordered composite for a watch page: player response -> initial data -> DOM.
 *
 * STALENESS IS THE WHOLE PROBLEM HERE (live QA, 2026-07-26). YouTube is a SPA, and on a
 * watch -> watch client-side navigation the inline `ytInitialPlayerResponse` and
 * `ytInitialData` scripts are NOT rewritten, they stay pinned to whatever video was loaded
 * by the last FULL page load. Tier 1 therefore returns a perfectly well-formed channel that
 * simply belongs to the wrong video, short-circuits the chain, and the fresh DOM byline is
 * never consulted. Observed live: a whitelisted channel full-loaded, then an SPA hop to a
 * non-whitelisted video played in full colour with no gate at all, defeating the whitelist,
 * the blacklist and gray-screen during exactly the browsing pattern people actually use.
 *
 * So the inline tiers must PROVE they are describing the current video: when the URL names a
 * video, a script tier is used only if it declares the SAME video id. A tier that declares a
 * different id is stale, and a tier that declares none cannot be verified, both are skipped
 * in favour of the DOM, which YouTube genuinely re-renders on navigation.
 */
export function detectWatchChannel(
  doc: Document,
  options: { url?: string } = {},
): DetectedChannel | null {
  const expectedVideoId = videoIdFromUrl(options.url ?? doc.location?.href ?? '');

  /** Can this inline tier be trusted to describe the video the URL names? */
  function inlineTierIsCurrent(tierVideoId: string | null): boolean {
    // No video in the URL (a channel page, say), there is nothing to contradict.
    if (expectedVideoId === null) return true;
    return tierVideoId === expectedVideoId;
  }

  if (inlineTierIsCurrent(playerResponseVideoId(doc))) {
    const fromPlayerResponse = channelFromPlayerResponse(doc);
    if (hasIdentifier(fromPlayerResponse)) return fromPlayerResponse;
  }

  if (inlineTierIsCurrent(initialDataVideoId(doc))) {
    const fromInitialData = channelFromInitialData(doc);
    if (hasIdentifier(fromInitialData)) return fromInitialData;
  }

  // Always fresh: YouTube re-renders the byline on every navigation.
  const fromDom = channelFromDom(doc);
  if (hasIdentifier(fromDom)) return fromDom;

  return null;
}

/**
 * Identify the channel for ONE feed card, scoped to it. Feed cards carry no inline JSON of
 * their own, so this is the DOM chain only — but run against `card`, never the document, so
 * every card in a feed resolves to its own uploader instead of all collapsing to whichever
 * channel link happens to be first on the page.
 */
export function detectCardChannel(card: Element): DetectedChannel | null {
  return channelFromDom(card);
}

/** Every feed-card wrapper on the page (`VIDEO_RENDERERS`, selectors.ts), in document order. */
export function feedCards(root: ParentNode): Element[] {
  try {
    return Array.from(root.querySelectorAll(VIDEO_RENDERER_SELECTOR));
  } catch {
    return [];
  }
}

/**
 * The video id the page's inline data claims to describe, from whichever tier says.
 *
 * Exposed so the settle-window check can ask "is the inline data authoritative for the video
 * in the address bar?" without re-parsing the page itself.
 */
export function inlineVideoId(doc: Document): string | null {
  return playerResponseVideoId(doc) ?? initialDataVideoId(doc);
}
