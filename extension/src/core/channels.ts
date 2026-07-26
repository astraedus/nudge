/**
 * YouTube channel whitelist/blacklist logic. PURE — no chrome.* imports, no DOM access.
 *
 * Three jobs live here: turning whatever a user typed/pasted into a storable
 * `ChannelEntry` (`parseChannelInput`), matching a channel the content script observed
 * against the stored list (`findChannel`/`isChannelListed`/`sameChannel`), and the actual
 * ALLOW/BLOCK decision for a channel-mode rule (`decideChannel`) plus its gray-screen
 * cousin (`shouldShowInColor`). Spec: ext-03-youtube-techniques.md §3 (channel detection)
 * and §4 (whitelist/blacklist modes).
 */

import type { BlockMode } from './types';
import type { ChannelEntry, ChannelListMode } from './settingsSchema';

// ---------------------------------------------------------------------------------------
// parseChannelInput
// ---------------------------------------------------------------------------------------

/** Exact channel-id shape: "UC" + 22 more characters, always this length. */
const CHANNEL_ID_RE = /^UC[A-Za-z0-9_-]{22}$/;

/**
 * Loose "this was clearly an attempt at a channel id" detector — deliberately wider than
 * `CHANNEL_ID_RE` so a typo'd or truncated id (one char short, one char extra, a bad
 * character) is REJECTED outright instead of silently being filed as a handle literally
 * called "UCxxxx…". Nobody's real handle starts with "UC" followed by 15+ more characters.
 * A wrong id that fails loudly is recoverable; one that quietly becomes an unrelated
 * "handle" is not.
 */
const CHANNEL_ID_NEAR_MISS_RE = /^UC[A-Za-z0-9_-]{15,35}$/;

/** Permissive handle/vanity-name charset. Real YouTube handles are narrower, but this
 * module only needs to reject obvious garbage (spaces, punctuation, empty). */
const HANDLE_CHARS_RE = /^[A-Za-z0-9._-]{1,30}$/;

function isValidChannelId(value: string): boolean {
  return CHANNEL_ID_RE.test(value);
}

function pathSegments(path: string): string[] {
  return path.split('/').filter((segment) => segment.length > 0);
}

/**
 * Locate "youtube.com" as an actual HOST inside `lower`, not merely as a substring —
 * guards the same superstring trap `domainMatcher.ts` guards against ("notyoutube.com"
 * contains "youtube.com" starting at index 3, but is not YouTube). A match must be
 * preceded by a host/scheme boundary (start, '.', '/', ':') and followed by a path/port
 * boundary (end, '/', ':', '?', '#').
 */
function findYoutubeHostIndex(lower: string): number {
  const marker = 'youtube.com';
  let from = 0;
  for (;;) {
    const idx = lower.indexOf(marker, from);
    if (idx === -1) return -1;
    const before = idx === 0 ? '' : lower[idx - 1]!;
    const afterIdx = idx + marker.length;
    const after = afterIdx >= lower.length ? '' : lower[afterIdx]!;
    const boundaryBefore = before === '' || before === '.' || before === '/' || before === ':';
    const boundaryAfter =
      after === '' || after === '/' || after === ':' || after === '?' || after === '#';
    if (boundaryBefore && boundaryAfter) return idx;
    from = idx + 1;
  }
}

/** Returns the path after "youtube.com" (query/fragment stripped), or null when `trimmed`
 * is not a YouTube URL at all — in which case the caller treats it as a bare token. */
function extractYoutubePath(trimmed: string): string | null {
  const lower = trimmed.toLowerCase();
  const idx = findYoutubeHostIndex(lower);
  if (idx === -1) return null;

  let rest = trimmed.slice(idx + 'youtube.com'.length);
  if (rest.startsWith(':')) {
    // A port, e.g. "youtube.com:443/@x" — skip to the next path separator.
    const slash = rest.indexOf('/');
    rest = slash === -1 ? '' : rest.slice(slash);
  }
  return rest.split('?')[0]!.split('#')[0]!;
}

function buildHandleEntry(handleRaw: string, now: number): ChannelEntry {
  const handle = handleRaw.toLowerCase();
  return { channelId: null, handle, displayName: `@${handle}`, addedAt: now };
}

function buildIdEntry(id: string, now: number): ChannelEntry {
  // Ids are case-SENSITIVE and kept exactly as given — no displayName candidate better
  // than the id itself is available from a URL/id alone.
  return { channelId: id, handle: null, displayName: id, addedAt: now };
}

function buildLegacyEntry(nameRaw: string, now: number): ChannelEntry {
  // Legacy "/c/Name" and "/user/name" URLs. The original-case segment IS the nicest human
  // form we have — it's the "given name" the displayName rule prefers — while the stored
  // `handle` is lowercased for matching, same as a real handle.
  return { channelId: null, handle: nameRaw.toLowerCase(), displayName: nameRaw, addedAt: now };
}

function parseHandleToken(handleRaw: string, now: number): ChannelEntry | null {
  if (!HANDLE_CHARS_RE.test(handleRaw)) return null;
  return buildHandleEntry(handleRaw, now);
}

function parseYoutubePath(path: string, now: number): ChannelEntry | null {
  const segments = pathSegments(path);
  if (segments.length === 0) return null; // bare "youtube.com" or "youtube.com/" — no channel
  const first = segments[0]!;
  const second = segments[1];

  if (first.startsWith('@')) {
    return parseHandleToken(first.slice(1), now);
  }

  if (first === 'channel') {
    // Near-misses are rejected the same as a strict-id mismatch anywhere else — there is
    // no ambiguity to fall back to here, the URL explicitly says "channel".
    return second !== undefined && isValidChannelId(second) ? buildIdEntry(second, now) : null;
  }

  if (first === 'c' || first === 'user') {
    // Legacy custom/vanity URLs ("/c/Veritasium", "/user/1veritasium"). These are NOT
    // ids and NOT real handles — the vanity-name -> handle mapping only exists
    // server-side on YouTube, and this module is pure (no network), so it cannot be
    // resolved here. Best effort: store the path segment itself as the `handle`, since
    // it is the only identifier the URL gives us and it lets a later probe carrying the
    // channel's REAL handle or id still reconcile with this entry via `sameChannel`.
    // This is a documented approximation, not a guarantee: if the vanity name and the
    // real handle diverge, matching by handle alone will miss until an id is learned.
    return second !== undefined && HANDLE_CHARS_RE.test(second)
      ? buildLegacyEntry(second, now)
      : null;
  }

  // Any other first segment ("watch", "shorts", "results", "feed", "playlist", "embed",
  // ...) names something other than a channel.
  return null;
}

function parseBareToken(trimmed: string, now: number): ChannelEntry | null {
  if (trimmed.startsWith('@')) {
    return parseHandleToken(trimmed.slice(1), now);
  }
  if (CHANNEL_ID_RE.test(trimmed)) {
    return buildIdEntry(trimmed, now);
  }
  if (CHANNEL_ID_NEAR_MISS_RE.test(trimmed)) {
    return null; // rejected near-miss id — see CHANNEL_ID_NEAR_MISS_RE
  }
  return parseHandleToken(trimmed, now);
}

/**
 * Turn whatever a user typed or pasted into a `ChannelEntry`, or null when it doesn't
 * name a channel at all (a watch URL, garbage text, blank input).
 *
 * Accepts: a bare handle ("@veritasium", "veritasium"), a handle URL
 * ("https://www.youtube.com/@veritasium", "youtube.com/@veritasium/videos"), a canonical
 * id URL ("https://www.youtube.com/channel/UCxxxx…"), a bare id ("UCxxxx…"), and legacy
 * custom URLs ("youtube.com/c/Veritasium", "youtube.com/user/1veritasium" — see the
 * comment in `parseYoutubePath` for how those are handled).
 *
 * `now` is injectable (defaults to `Date.now()`) so callers/tests get a deterministic
 * `addedAt`.
 */
export function parseChannelInput(raw: string, now: number = Date.now()): ChannelEntry | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;

  const youtubePath = extractYoutubePath(trimmed);
  if (youtubePath !== null) {
    return parseYoutubePath(youtubePath, now);
  }
  return parseBareToken(trimmed, now);
}

// ---------------------------------------------------------------------------------------
// Matching
// ---------------------------------------------------------------------------------------

/**
 * True when `a` and `b` describe the same channel — sharing EITHER identifier is enough,
 * because a single entry frequently only has one (captured from wherever the user added
 * it). Ids compare case-SENSITIVELY (YouTube ids are case-sensitive); handles compare
 * case-INSENSITIVELY regardless of how they happen to be cased on either side.
 */
export function sameChannel(a: ChannelEntry, b: ChannelEntry): boolean {
  const idMatch = a.channelId !== null && b.channelId !== null && a.channelId === b.channelId;
  const handleMatch =
    a.handle !== null && b.handle !== null && a.handle.toLowerCase() === b.handle.toLowerCase();
  return idMatch || handleMatch;
}

/**
 * What the content script actually has when it wants to check a channel against the
 * list. It may carry only ONE of the two — a feed card usually exposes just a handle,
 * a watch page's player response usually gives just the canonical id — or, when
 * detection fails outright, NEITHER (see `decideChannel`'s unknown-channel handling).
 */
export interface ChannelProbe {
  channelId?: string | null;
  handle?: string | null;
}

function normalizeProbeHandle(handle: string | null | undefined): string | null {
  if (handle === null || handle === undefined) return null;
  return handle.replace(/^@/, '').toLowerCase();
}

/** Find the stored entry matching `probe` by EITHER identifier — the whole point of
 * storing both. Returns null when the probe carries no identifier, or none match. */
export function findChannel(
  list: readonly ChannelEntry[],
  probe: ChannelProbe,
): ChannelEntry | null {
  const probeId = probe.channelId ?? null;
  const probeHandle = normalizeProbeHandle(probe.handle);
  if (probeId === null && probeHandle === null) return null;

  for (const entry of list) {
    const idMatch = probeId !== null && entry.channelId !== null && entry.channelId === probeId;
    const handleMatch =
      probeHandle !== null && entry.handle !== null && entry.handle === probeHandle;
    if (idMatch || handleMatch) return entry;
  }
  return null;
}

export function isChannelListed(list: readonly ChannelEntry[], probe: ChannelProbe): boolean {
  return findChannel(list, probe) !== null;
}

// ---------------------------------------------------------------------------------------
// Decision
// ---------------------------------------------------------------------------------------

export type ChannelVerdict =
  | { action: 'ALLOW'; reason: 'mode-off' | 'not-listed' | 'listed' | 'unknown-channel' }
  | { action: 'BLOCK'; mode: BlockMode };

/** Shape shared by `decideChannel` and `shouldShowInColor` — both are "given the current
 * channel-list mode and a probe, what do we know about this channel". */
export interface ChannelDecisionInput {
  mode: ChannelListMode;
  channels: readonly ChannelEntry[];
  probe: ChannelProbe;
}

function probeHasIdentifier(probe: ChannelProbe): boolean {
  return (probe.channelId ?? null) !== null || (probe.handle ?? null) !== null;
}

/**
 * The core channel-list decision.
 *
 *   - mode 'OFF'        -> always ALLOW (reason 'mode-off'); the feature isn't in use.
 *   - mode 'BLACKLIST'  -> listed channels BLOCK (in `blockMode`); everything else ALLOWs.
 *   - mode 'WHITELIST'  -> listed channels ALLOW; everything else BLOCKs. A HARD whitelist.
 *
 * THE UNKNOWN-CHANNEL CASE is load-bearing. When `probe` carries NEITHER `channelId` nor
 * `handle` — channel detection failed, e.g. YouTube changed its DOM or the page hasn't
 * hydrated yet — what should happen?
 *
 *   - BLACKLIST: an unknown channel cannot be proven to be ON the list, so it must ALLOW.
 *     Blocking here would mean a single detection miss blocks ALL of YouTube, not just the
 *     channel that should have matched — worse than the blacklist doing nothing.
 *   - WHITELIST: an unknown channel cannot be proven to be ALLOWED either, and here both
 *     choices are bad in a different way: BLOCKing turns a detection failure into "all of
 *     YouTube is now broken" (a hard whitelist becomes a hard "block everything" the
 *     moment a selector rots), while ALLOWing silently defeats the whitelist's entire
 *     purpose for exactly the videos it failed to identify.
 *
 * We choose ALLOW for the unknown case in BOTH modes — fail OPEN, never fail shut on a
 * browser-wide surface — but make the choice OBSERVABLE rather than silent: `reason:
 * 'unknown-channel'` is a distinct value from `'not-listed'` / `'listed'` / `'mode-off'`,
 * so a caller (and eventually a "channel detection degraded" UI hint) can tell "we let
 * this through because we checked and it's fine" from "we let this through because we
 * genuinely could not tell". A silent fail-open is a bug; a documented, observable one is
 * a design decision.
 */
export function decideChannel(
  params: ChannelDecisionInput & { blockMode: BlockMode },
): ChannelVerdict {
  const { mode, channels, probe, blockMode } = params;

  if (mode === 'OFF') {
    return { action: 'ALLOW', reason: 'mode-off' };
  }

  if (!probeHasIdentifier(probe)) {
    return { action: 'ALLOW', reason: 'unknown-channel' };
  }

  const listed = isChannelListed(channels, probe);

  if (mode === 'BLACKLIST') {
    return listed
      ? { action: 'BLOCK', mode: blockMode }
      : { action: 'ALLOW', reason: 'not-listed' };
  }

  // mode === 'WHITELIST'
  return listed ? { action: 'ALLOW', reason: 'listed' } : { action: 'BLOCK', mode: blockMode };
}

/**
 * Gray-screen mode: true ONLY when the channel is positively identified AND either the
 * list is a WHITELIST that includes it, or a BLACKLIST that excludes it — i.e. exactly
 * the channels `decideChannel` would ALLOW *because it checked*, never because it
 * couldn't. An unknown channel is NEVER shown in colour: unlike blocking, staying gray
 * is harmless (the content is still reachable, just desaturated), so there is no
 * fail-open pressure here — gray is simply the safe default whenever we can't identify
 * the channel, and it also stays the default when `mode` is 'OFF' (no list to check a
 * channel against yet).
 */
export function shouldShowInColor(params: ChannelDecisionInput): boolean {
  const { mode, channels, probe } = params;
  if (!probeHasIdentifier(probe)) return false;

  const listed = isChannelListed(channels, probe);
  if (mode === 'WHITELIST') return listed;
  if (mode === 'BLACKLIST') return !listed;
  return false; // mode === 'OFF'
}

// ---------------------------------------------------------------------------------------
// List mutation
// ---------------------------------------------------------------------------------------

/**
 * Add `entry` to `list`, merging into an existing entry that describes the same channel
 * (via `sameChannel`) rather than creating a duplicate row. Mirrors
 * `settingsSchema.ts`'s `dedupeChannels` merge behaviour: a missing identifier on the
 * existing row is filled in from the incoming one, and a bare "@handle" displayName
 * yields to a nicer name once one is known. Pure — always returns a NEW array, never
 * mutates `list` or its entries.
 */
export function addChannel(list: readonly ChannelEntry[], entry: ChannelEntry): ChannelEntry[] {
  const idx = list.findIndex((existing) => sameChannel(existing, entry));
  if (idx === -1) {
    return [...list, entry];
  }

  const existing = list[idx]!;
  const merged: ChannelEntry = {
    channelId: existing.channelId ?? entry.channelId,
    handle: existing.handle ?? entry.handle,
    displayName:
      existing.displayName.startsWith('@') && !entry.displayName.startsWith('@')
        ? entry.displayName
        : existing.displayName,
    addedAt: existing.addedAt,
  };

  const next = [...list];
  next[idx] = merged;
  return next;
}

/** Remove every entry describing the same channel as `entry` (via `sameChannel`). Pure —
 * returns a NEW array, never mutates `list`. */
export function removeChannel(
  list: readonly ChannelEntry[],
  entry: ChannelEntry,
): ChannelEntry[] {
  return list.filter((existing) => !sameChannel(existing, entry));
}
