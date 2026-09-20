/**
 * YouTube channel whitelist/blacklist logic. PURE — no chrome.* imports, no DOM access.
 *
 * Three jobs live here: turning whatever a user typed/pasted into a storable
 * `ChannelEntry` (`parseChannelInput`), matching a channel the content script observed
 * against the stored list (`findChannel`/`isChannelListed`/`sameChannel`), and the actual
 * ALLOW/BLOCK decision for a channel-mode rule (`decideChannel`) plus its gray-screen
 * cousin (`shouldShowInColor`) and the watch-page gate that layers the SITE rule behind
 * them (`decideWatchGate`). Spec: ext-03-youtube-techniques.md §3 (channel detection) and
 * §4 (whitelist/blacklist modes); ext-13 §3 (channel allowlist as a site default).
 */

import { isBlockMode, type BlockMode, type SiteMode } from './types';
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
 * The two identifiers, from anywhere: a stored entry, a probe, an observation. Every
 * question this module asks about channel identity is asked of this shape.
 */
interface ChannelIdentity {
  channelId: string | null;
  handle: string | null;
}

/**
 * THE identity rule: two things describe the same channel when they share EITHER identifier.
 * Sharing one is enough because an entry frequently only HAS one (captured from wherever the
 * user added it). Ids compare case-SENSITIVELY (YouTube ids are case-sensitive); handles
 * compare case-INSENSITIVELY however they happen to be cased on either side.
 *
 * One function, because four things ask it — `sameChannel`, `findChannel`, and both halves
 * of enrichment — and a copy that drifted would mean a channel the whitelist recognises and
 * the enricher does not, or the reverse.
 */
function matchesIdentity(a: ChannelIdentity, b: ChannelIdentity): boolean {
  const idMatch = a.channelId !== null && b.channelId !== null && a.channelId === b.channelId;
  const handleMatch =
    a.handle !== null && b.handle !== null && a.handle.toLowerCase() === b.handle.toLowerCase();
  return idMatch || handleMatch;
}

/**
 * THE contradiction rule, the exact complement of `matchesIdentity`: only a SHARED axis can
 * prove two things are DIFFERENT channels. An id here and a handle there overlap on nothing
 * and prove nothing either way — combining exactly that pair is what enrichment is for. Same
 * rule `assembleChannel` applies in the detection layer, one level up.
 */
function contradictsIdentity(a: ChannelIdentity, b: ChannelIdentity): boolean {
  if (a.channelId !== null && b.channelId !== null && a.channelId !== b.channelId) return true;
  return (
    a.handle !== null && b.handle !== null && a.handle.toLowerCase() !== b.handle.toLowerCase()
  );
}

/** True when `a` and `b` describe the same channel. See `matchesIdentity`. */
export function sameChannel(a: ChannelEntry, b: ChannelEntry): boolean {
  return matchesIdentity(a, b);
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

/**
 * The canonical comparison form for a handle: no leading '@', lowercased, surrounding
 * whitespace gone, and null rather than an empty string, so a blank handle can never
 * accidentally compare equal to anything.
 */
function normalizeProbeHandle(handle: string | null | undefined): string | null {
  if (handle === null || handle === undefined) return null;
  const value = handle.trim().replace(/^@/, '').trim().toLowerCase();
  return value === '' ? null : value;
}

/** Find the stored entry matching `probe` by EITHER identifier — the whole point of
 * storing both. Returns null when the probe carries no identifier, or none match. */
export function findChannel(
  list: readonly ChannelEntry[],
  probe: ChannelProbe,
): ChannelEntry | null {
  const identity: ChannelIdentity = {
    channelId: probe.channelId ?? null,
    handle: normalizeProbeHandle(probe.handle),
  };
  if (identity.channelId === null && identity.handle === null) return null;

  return list.find((entry) => matchesIdentity(entry, identity)) ?? null;
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

// ---------------------------------------------------------------------------------------
// The watch-page gate: the channel list AS A SITE DEFAULT
// ---------------------------------------------------------------------------------------

/**
 * The block mode a site rule resolves to when it is in force, or null when it is not.
 *
 * Mirrors `applies.appliedBlockMode` for callers that hold the already-resolved pair
 * (`siteMode`, `siteApplies`) rather than a whole `AppliesResult` — the content script is
 * exactly that caller, because the worker resolves the rule and sends it the answer. The
 * ALLOW-but-applicable case can only be an exhausted daily budget, which cannot be waited
 * out before midnight and is therefore a Hard Block, same as everywhere else.
 */
export function siteFallbackMode(siteMode: SiteMode, siteApplies: boolean): BlockMode | null {
  if (!siteApplies) return null;
  return isBlockMode(siteMode) ? siteMode : 'HARD_BLOCK';
}

/** Which rule supplied the mode a gated video is held behind. Drives the copy. */
export type WatchGateSource = 'channel-rule' | 'site-default';

export type WatchGateVerdict =
  | {
      action: 'ALLOW';
      reason: 'mode-off' | 'not-listed' | 'listed' | 'unknown-channel';
    }
  | {
      action: 'BLOCK';
      mode: BlockMode;
      delaySeconds: number;
      source: WatchGateSource;
      /** Why: a channel we identified and disallowed, or one we could not identify at all. */
      reason: 'listed' | 'not-listed' | 'unknown-channel';
    };

export interface WatchGateInput {
  mode: ChannelListMode;
  channels: readonly ChannelEntry[];
  probe: ChannelProbe;
  /** The site rule's mode right now, schedule already applied. */
  siteMode: SiteMode;
  /** Whether the site rule is in force right now (`core/applies.ts`). */
  siteApplies: boolean;
  siteDelaySeconds: number;
  /** The mode a disallowed channel gets when the SITE rule is not in force. */
  channelBlockMode: BlockMode;
  channelDelaySeconds: number;
}

/**
 * The verdict for one watch page — `decideChannel` plus the site rule standing behind it.
 *
 * This is the "block YouTube, except these channels" case, and the only place where the
 * channel list stops being a standalone feature and becomes the site's exception list.
 * Two things change once the youtube.com rule is IN FORCE and the list is a WHITELIST:
 *
 *  1. A disallowed channel is held behind the SITE's mode and delay, not the channel
 *     rule's. The user already answered "what does blocked mean for this site"; a second,
 *     quieter answer to the same question is how a whitelist ends up weaker than the rule
 *     its owner thought they wrote.
 *
 *  2. AN UNIDENTIFIED CHANNEL FAILS **CLOSED**, and only here.
 *     Everywhere else — including this module's `decideChannel` — an unknown channel is
 *     allowed, because a hard whitelist that fails shut turns one rotted selector into
 *     "all of YouTube is blocked". That reasoning does not survive contact with this case:
 *     the user's stated default for this site is ALREADY "blocked", so "all of YouTube is
 *     blocked" is not a regression, it is the rule. Failing OPEN here would instead let a
 *     rotted selector silently defeat the rule with no signal at all — the site someone
 *     asked to be kept out of quietly opens. The pause and the Escape Hatch both still
 *     exist, so being wrong costs seconds, not a locked door, and the console canary is
 *     still emitted either way so detection rot stays observable.
 *
 * When the site rule is NOT in force (an ALLOW site still under budget) nothing changes:
 * the channel rule supplies the mode, and an unknown channel is allowed with its own
 * distinct `unknown-channel` reason.
 */
export function decideWatchGate(input: WatchGateInput): WatchGateVerdict {
  const {
    mode,
    channels,
    probe,
    siteMode,
    siteApplies,
    siteDelaySeconds,
    channelBlockMode,
    channelDelaySeconds,
  } = input;

  if (mode === 'OFF') return { action: 'ALLOW', reason: 'mode-off' };

  const fallbackMode = siteFallbackMode(siteMode, siteApplies);
  // The site default only takes over for a WHITELIST. A blacklist names what to keep OUT,
  // so it has no opinion about a video it does not name and cannot stand in for the site's
  // own rule.
  const siteDefault = mode === 'WHITELIST' && fallbackMode !== null;

  const blockBySite = (reason: 'not-listed' | 'unknown-channel'): WatchGateVerdict => ({
    action: 'BLOCK',
    mode: fallbackMode as BlockMode,
    delaySeconds: siteDelaySeconds,
    source: 'site-default',
    reason,
  });

  const blockByChannelRule = (reason: 'listed' | 'not-listed'): WatchGateVerdict => ({
    action: 'BLOCK',
    mode: channelBlockMode,
    delaySeconds: channelDelaySeconds,
    source: 'channel-rule',
    reason,
  });

  if (!probeHasIdentifier(probe)) {
    return siteDefault
      ? blockBySite('unknown-channel')
      : { action: 'ALLOW', reason: 'unknown-channel' };
  }

  const listed = isChannelListed(channels, probe);

  if (mode === 'BLACKLIST') {
    return listed ? blockByChannelRule('listed') : { action: 'ALLOW', reason: 'not-listed' };
  }

  // mode === 'WHITELIST'
  if (listed) return { action: 'ALLOW', reason: 'listed' };
  return siteDefault ? blockBySite('not-listed') : blockByChannelRule('not-listed');
}

// ---------------------------------------------------------------------------------------
// Enrichment: teaching a stored entry the identifier it is missing
// ---------------------------------------------------------------------------------------

/**
 * What a content script actually SAW on a page, once the freshness state machine confirmed
 * the observation describes the video in the address bar.
 *
 * Distinct from `ChannelProbe` on purpose: a probe is a QUESTION ("does the list know this
 * channel?") and carries only the two identifiers, while an observation is an ASSERTION
 * about a real channel and may also carry the author name YouTube printed beside it.
 */
export interface ChannelObservation {
  channelId?: string | null;
  handle?: string | null;
  displayName?: string | null;
}

/**
 * Why `enrichEntries` did or did not change the list. Every value is worth distinguishing:
 *
 *  - `enriched`         an identifier and/or a real display name was learned.
 *  - `merged`           the observation proved two stored entries are one channel.
 *  - `contradiction`    the observation disagrees with a stored entry on an axis they SHARE.
 *                       Never an enrichment, and worth LOGGING: it means either YouTube put
 *                       two channels on one page or our detection is wrong.
 *  - `no-match`         no stored entry describes this channel. Enrichment never ADDS one.
 *  - `nothing-to-learn` the observation carries no identifier, or the matched entry already
 *                       knows everything it says.
 */
export type EnrichmentReason =
  | 'enriched'
  | 'merged'
  | 'contradiction'
  | 'no-match'
  | 'nothing-to-learn';

export interface EnrichResult {
  /** The list to persist. Same content as the input unless `changed` is true. */
  entries: ChannelEntry[];
  changed: boolean;
  reason: EnrichmentReason;
}

/** An observation with both identifiers in their canonical comparison form. */
interface NormalizedObservation {
  channelId: string | null;
  handle: string | null;
  displayName: string | null;
}

function normalizeObservation(observation: ChannelObservation): NormalizedObservation {
  const rawId = observation.channelId;
  const rawName = observation.displayName;
  return {
    // Ids are case-SENSITIVE and kept verbatim, exactly as `parseChannelInput` stores them.
    channelId: typeof rawId === 'string' && rawId.trim() !== '' ? rawId.trim() : null,
    handle: normalizeProbeHandle(observation.handle),
    displayName: typeof rawName === 'string' && rawName.trim() !== '' ? rawName.trim() : null,
  };
}

/**
 * True when `displayName` is merely the placeholder built out of an identifier, so there is
 * a real name to be gained by replacing it.
 *
 * Deliberately EXACT rather than "looks like an identifier": a legacy `/c/Veritasium` entry
 * stores `handle: 'veritasium'` with `displayName: 'Veritasium'`, which is the nicest human
 * form that URL gave us and must survive. Only the literal `@handle` that `buildHandleEntry`
 * writes and the raw id that `buildIdEntry` writes yield.
 */
function isFallbackDisplayName(entry: ChannelEntry): boolean {
  const name = entry.displayName.trim();
  if (name === '') return true;
  if (entry.channelId !== null && name === entry.channelId) return true;
  return entry.handle !== null && name.toLowerCase() === `@${entry.handle.toLowerCase()}`;
}

/**
 * The display name to keep. Upgrades ONLY a fallback name, and never to another identifier:
 * swapping `@veritasium` for a bare `UCxxxx…` is a downgrade wearing an upgrade's clothes.
 */
function upgradedDisplayName(
  current: string,
  currentIsFallback: boolean,
  observed: NormalizedObservation,
  nextId: string | null,
  nextHandle: string | null,
): string {
  const name = observed.displayName;
  if (name === null || !currentIsFallback) return current;
  if (name === nextId) return current;
  if (nextHandle !== null && name.toLowerCase() === `@${nextHandle}`) return current;
  return name;
}

/**
 * Fold everything known about one channel into a single entry.
 *
 * `matched` is every stored entry the observation matched, in list order. More than one
 * means the user added the same channel twice by different routes (`@x` from a feed card,
 * `UCxxxx…` from a watch URL), and the observation is the first thing able to PROVE they are
 * one channel: until now the two shared no axis at all.
 */
function foldEntries(
  matched: readonly ChannelEntry[],
  observed: NormalizedObservation,
): ChannelEntry {
  const first = matched[0]!;
  const nextId = matched.find((e) => e.channelId !== null)?.channelId ?? observed.channelId;
  const nextHandle = matched.find((e) => e.handle !== null)?.handle ?? observed.handle;

  // The oldest `addedAt` wins, so a merge never makes a channel look newer than the day the
  // user actually added it.
  const addedAt = matched.reduce((oldest, e) => Math.min(oldest, e.addedAt), first.addedAt);

  // A real name already typed or learned beats the observation; only when every copy is
  // still showing a placeholder does the observed author name get to win.
  const named = matched.find((e) => !isFallbackDisplayName(e));

  return {
    channelId: nextId,
    handle: nextHandle,
    displayName: upgradedDisplayName(
      named?.displayName ?? first.displayName,
      named === undefined,
      observed,
      nextId,
      nextHandle,
    ),
    addedAt,
  };
}

function sameEntry(a: ChannelEntry, b: ChannelEntry): boolean {
  return (
    a.channelId === b.channelId &&
    a.handle === b.handle &&
    a.displayName === b.displayName &&
    a.addedAt === b.addedAt
  );
}

/**
 * Learn the identifier a stored entry is missing, from a channel the user just watched. PURE.
 *
 * WHY THIS EXISTS. A `ChannelEntry` records whichever identifier the user happened to supply:
 * typing `@veritasium` stores a handle and no id, pasting a `/channel/UCxxxx…` URL stores an
 * id and no handle. Detection ASSEMBLES both from a watch page, so matching works there, but
 * the stored entry stays half-blind, and three real things break:
 *
 *  1. `background/dnr.ts` compiles a `/channel/UCxxxx…` allow-rule only for an entry that HAS
 *     an id, and an `/@handle` allow-rule only for one that has a handle. A handle-only entry
 *     therefore gets no network allow for its own `/channel/UCxxxx…` page: a full navigation
 *     to that URL shape hits the site redirect even though the channel is allowed. Learning
 *     the other axis closes that hole at the network layer, where the content script cannot.
 *  2. During the settle window, and on surfaces where only ONE tier may speak (a stale byline
 *     is excluded; an inline tier is authoritative but carries only the id), a handle-only
 *     entry cannot match an id-only observation at all.
 *  3. The dashboard shows `@veritasium` or a bare `UCxxxx…` instead of "Veritasium".
 *
 * THE RULES, and why each is load-bearing:
 *
 *  - A non-null identifier is NEVER overwritten. Enrichment only ever fills a null.
 *  - A CONTRADICTION IS NOT AN ENRICHMENT. If the stored id differs from the observed id
 *    while the handles match (or the mirror image), the entry is left completely untouched
 *    and the reason is `contradiction`. Welding two channels into a chimera that matches
 *    neither is strictly worse than learning nothing, and invisible afterwards.
 *  - Ids compare case-SENSITIVELY, handles case-INSENSITIVELY, and a handle is stored
 *    lowercased without its '@' — the storage contract `settingsSchema.ts` already enforces.
 *  - `displayName` is upgraded only when the stored one is the bare `@handle`/id fallback.
 *  - Matching two SEPARATE entries means the user added the same channel twice by different
 *    routes; they merge into one (earliest `addedAt`) and the reason is `merged`.
 *
 * WHAT IT CANNOT DO, which is why the Commitment Lock never has to challenge it (the gate is
 * KEPT, not bypassed — see `handleChannelObserved` — it simply cannot fire):
 * it never ADDS a channel (no match means no change) and never DROPS one (a merge collapses
 * entries that all describe the SAME channel, and the survivor carries every identifier they
 * held between them). The set of channels the list covers is invariant, and that set — read
 * per identifier through `channelMatches` — is exactly what `strictMode.isWeakening`
 * measures, so an enrichment can never register as a weakening in either list mode.
 */
export function enrichEntries(
  entries: readonly ChannelEntry[],
  observation: ChannelObservation,
): EnrichResult {
  const unchanged = (reason: EnrichmentReason): EnrichResult => ({
    entries: [...entries],
    changed: false,
    reason,
  });

  const observed = normalizeObservation(observation);
  if (observed.channelId === null && observed.handle === null) {
    return unchanged('nothing-to-learn');
  }

  // Indices, not the entries themselves: a merge is destructive and must never depend on
  // object identity holding across a list a caller may have built by hand.
  const matchedIndices: number[] = [];
  entries.forEach((entry, index) => {
    if (matchesIdentity(entry, observed)) matchedIndices.push(index);
  });
  if (matchedIndices.length === 0) return unchanged('no-match');

  const matched = matchedIndices.map((index) => entries[index]!);
  if (matched.some((entry) => contradictsIdentity(entry, observed))) {
    return unchanged('contradiction');
  }
  // Two entries that match the same observation but disagree with EACH OTHER on an axis the
  // observation is silent about cannot be merged either. `dedupeChannels` makes that
  // unreachable for a list loaded through the schema, but a merge proves its own
  // precondition rather than trusting the caller.
  const mutuallyConsistent = matched.every((entry, index) =>
    matched.slice(index + 1).every((other) => !contradictsIdentity(entry, other)),
  );
  if (!mutuallyConsistent) return unchanged('contradiction');

  const folded = foldEntries(matched, observed);
  if (matched.length === 1 && sameEntry(matched[0]!, folded)) {
    return unchanged('nothing-to-learn');
  }

  // The survivor keeps the FIRST match's position, so enrichment never reorders the list
  // underneath a user who is looking at it.
  const survivorIndex = matchedIndices[0]!;
  const absorbed = new Set(matchedIndices.slice(1));
  const next = entries
    .map((entry, index) => (index === survivorIndex ? folded : entry))
    .filter((_, index) => !absorbed.has(index));

  return { entries: next, changed: true, reason: matched.length > 1 ? 'merged' : 'enriched' };
}
