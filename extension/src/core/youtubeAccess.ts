/**
 * Which YouTube URLs stay open while the youtube.com rule is in force. PURE.
 *
 * THE ONE PATTERN LIST. The network layer (`background/dnr.ts`) compiles these strings into
 * DNR allow-rules for full page loads, and the YouTube content script tests the SAME strings
 * for in-SPA navigation, which DNR cannot see. Before this module existed only the network
 * half existed: a typed URL to the home feed went to the block page, while clicking the
 * YouTube logo from an allowed video opened the home feed, search, any channel page and
 * Shorts with nothing in the way (live QA, 2026-10-23). Two copies of "what is allowed" is
 * how that happens, so there is one.
 *
 * Three kinds of carve-out, in the order a page is checked:
 *
 *  - CREATOR (`creatorMode`): YouTube Studio and the youtube.com routes that lead to it.
 *    Survives an exhausted daily limit, because it is work, not watching, and its time is
 *    not counted (see `background/tracker.ts`).
 *  - CHANNEL (a non-empty effective whitelist): `/watch` and friends wholesale (each video
 *    is then gated per channel in the page, see `core/channels.decideWatchGate`), each
 *    listed channel's own pages, and `/playlist` (its rows are channel-filtered in the page).
 *  - VERIFY: legacy `/c/<name>` and `/user/<name>` channel URLs. Their name is NOT the
 *    channel's handle (`/c/kurzgesagt` is a 404 while `/user/Kurzgesagt` is the channel), so
 *    the URL alone cannot say whose page it is. They are opened at the network layer and the
 *    content script checks the page's own canonical channel id before letting it stay.
 */

import { extractDomain, hostMatchesRuleDomain, normalizeToBaseDomain } from './domainMatcher';
import type { ChannelEntry, ChannelListMode, SiteRule, YoutubeFeatureSettings } from './settingsSchema';
import { exactHostRegexFilter, escapeForRegex, gateRegexFilter, urlMatchesFilter } from './urlFilters';

/**
 * Characters a channel handle or id may contain to be interpolated into a regex.
 *
 * The list is stored data: it arrives from the YouTube DOM, from a user typing, or from a
 * hand-edited / imported settings blob. An identifier of `.*` interpolated into an allow
 * pattern would open ALL of YouTube while the UI still showed a whitelist, a silent, total
 * defeat of the feature. Escaping alone is not enough reassurance here, so anything outside
 * this set is SKIPPED (the channel simply gets no carve-out and falls to the content
 * script's own per-video check) rather than sanitized into something unrecognisable.
 */
export const SAFE_CHANNEL_IDENTIFIER = /^[A-Za-z0-9_.-]+$/;

/**
 * The pages that play ONE video, allowed wholesale and gated per video in the page.
 *
 * `/live/<id>` is a watch page under another name (live QA: it answers 200 with a canonical
 * of `/watch?v=<id>`), so leaving it out redirected a whitelisted channel's live stream.
 */
export const LIVE_VIDEO_PATH = '/live/[A-Za-z0-9_-]+';
export const WATCH_PATHS: readonly string[] = ['/watch', LIVE_VIDEO_PATH];

/**
 * A playlist page lists videos from any channel, so it is opened wholesale like `/watch`:
 * its rows go through the feed filter, and the video a row opens is gated at `/watch`.
 * Without it, a whitelisted channel's own Playlists tab led straight to the block page.
 */
export const PLAYLIST_PATH = '/playlist';

/** Legacy channel URLs, opened at the network layer and verified in the page. */
export const LEGACY_CHANNEL_PATHS: readonly string[] = [
  '/c/[^/?#]+(?:/.*)?',
  '/user/[^/?#]+(?:/.*)?',
];

/**
 * youtube.com routes that exist only to reach YouTube Studio or a creator tool.
 *
 * Each answers with a redirect to studio.youtube.com (or to Google sign-in), and the
 * REDIRECT TARGET is a fresh main-frame request that DNR checks again, so opening one of
 * these cannot leak anything but the page it redirects to. `/signin` is the post-login hop
 * (`/signin?action_handle_signin=true&next=<studio url>`), without which signing in to
 * Studio bounced off the block page.
 */
export const CREATOR_PATHS: readonly string[] = [
  '/upload',
  '/live_dashboard',
  '/my_live_events',
  '/livestreaming',
  '/dashboard',
  '/analytics',
  '/my_videos',
  '/view_all_playlists',
  '/features',
  '/audiolibrary',
  '/copyright_complaint_form',
  '/signin',
  '/channel_switcher',
].map((path) => `${path}(?:/.*)?`);

/**
 * The Studio host, as a label prefix on the rule's base domain.
 *
 * Handled as ONE EXACT host. The site and gate filters deliberately match every subdomain
 * (a youtube.com rule has to catch m.youtube.com), so a carve-out that reused that wildcard
 * would open every subdomain, and one written as "anything starting studio." could be
 * satisfied by a host YouTube does not own.
 */
export const STUDIO_HOST_PREFIX = 'studio.';

/**
 * The network paths a YouTube whitelist must let through, for these channels.
 *
 * The channel's own pages are allowed outright so the user can actually navigate to what
 * they said they wanted: with the home feed, search and subscriptions all redirected, there
 * would otherwise be no route in at all.
 */
export function youtubeWhitelistPaths(channels: readonly ChannelEntry[]): string[] {
  const paths = new Set<string>([...WATCH_PATHS, PLAYLIST_PATH]);
  for (const channel of channels) {
    if (channel.handle !== null && SAFE_CHANNEL_IDENTIFIER.test(channel.handle)) {
      paths.add(`/@${escapeForRegex(channel.handle)}(?:/.*)?`);
    }
    if (channel.channelId !== null && SAFE_CHANNEL_IDENTIFIER.test(channel.channelId)) {
      paths.add(`/channel/${escapeForRegex(channel.channelId)}(?:/.*)?`);
    }
  }
  return [...paths].sort();
}

/** The channel lists actually enforced, once creator mode's own channel is folded in. */
export interface EffectiveChannelLists {
  channelMode: ChannelListMode;
  channels: ChannelEntry[];
}

/**
 * The user's own channel, while creator mode is on. Empty otherwise.
 */
export function ownChannels(youtube: YoutubeFeatureSettings | null): ChannelEntry[] {
  if (youtube === null || !youtube.creatorMode || youtube.myChannel === null) return [];
  return [youtube.myChannel];
}

/**
 * The lists `decideWatchGate`, the feed filter and the network carve-out all read.
 *
 * Creator mode's channel is an IMPLICIT WHITELIST ENTRY, and it goes through the same path
 * as every typed entry rather than a parallel "is this mine" check, so there is exactly one
 * answer to "may this video play":
 *
 *  - WHITELIST: the own channel joins the list.
 *  - The site is BLOCKED and the list is OFF or a BLACKLIST: the user said "block YouTube",
 *    and creator mode says "except my channel", which is a whitelist of one. A blacklist has
 *    nothing left to say while the whole site is blocked.
 *  - The site is OPEN (Allow, under budget): a blacklist or OFF stays exactly as written. The
 *    own channel is reachable anyway, and turning creator mode on must never START blocking
 *    the rest of YouTube.
 */
export function effectiveChannelLists(
  youtube: YoutubeFeatureSettings | null,
  siteApplies: boolean,
): EffectiveChannelLists {
  if (youtube === null) return { channelMode: 'OFF', channels: [] };
  const own = ownChannels(youtube);
  if (youtube.channelMode === 'WHITELIST') {
    return { channelMode: 'WHITELIST', channels: [...youtube.channels, ...own] };
  }
  if (siteApplies && own.length > 0) return { channelMode: 'WHITELIST', channels: own };
  return { channelMode: youtube.channelMode, channels: [...youtube.channels] };
}

/** What the youtube.com rule's carve-outs are right now, as full-URL filters. */
export interface YoutubeAllowFilters {
  /** Watch pages, playlists and each listed channel's own pages. */
  channel: string[];
  /** Legacy `/c/` and `/user/` pages, which the content script must verify. */
  verify: string[];
  /** Studio and the routes that lead to it. */
  creator: string[];
}

export interface YoutubeRuleState {
  /** Whether the youtube.com rule is in force right now (`core/applies.ts`). */
  siteApplies: boolean;
  /** True when it is in force because its daily budget is spent. */
  limitReached: boolean;
}

const NO_FILTERS: YoutubeAllowFilters = { channel: [], verify: [], creator: [] };

/** Studio plus the creator routes, for the rule on `domain`. */
export function creatorFilters(domain: string): string[] {
  const base = normalizeToBaseDomain(domain);
  return [
    exactHostRegexFilter(`${STUDIO_HOST_PREFIX}${base}`),
    ...CREATOR_PATHS.map((path) => gateRegexFilter(base, path)),
  ];
}

/**
 * Every carve-out from the youtube.com rule on `domain`, right now.
 *
 * Nothing at all while the rule is not in force: the site is open, there is nothing to carve
 * out of, and an allow-rule there would only outrank a gate redirect it has no business
 * outranking.
 *
 * A spent DAILY LIMIT drops the channel carve-out but keeps the creator one. A limit budgets
 * HOW MUCH of the site, a channel list restricts WHAT, so once the budget is spent there is
 * no allowance left for a whitelist to carve out of (otherwise "1 hour of YouTube a day"
 * would be unlimited for every allowed channel). Studio is different in kind: it is the
 * user's work, its time is not counted against the limit, and locking someone out of
 * managing their own channel because they watched an hour of videos is exactly the outcome
 * they turned creator mode on to prevent.
 */
export function youtubeAllowFilters(
  domain: string,
  youtube: YoutubeFeatureSettings | null,
  state: YoutubeRuleState,
): YoutubeAllowFilters {
  if (youtube === null || !state.siteApplies) return NO_FILTERS;
  const base = normalizeToBaseDomain(domain);

  const creator = youtube.creatorMode ? creatorFilters(base) : [];
  const lists = effectiveChannelLists(youtube, state.siteApplies);
  const whitelistOpen =
    lists.channelMode === 'WHITELIST' && lists.channels.length > 0 && !state.limitReached;

  return {
    channel: whitelistOpen
      ? youtubeWhitelistPaths(lists.channels).map((path) => gateRegexFilter(base, path))
      : [],
    verify: whitelistOpen
      ? LEGACY_CHANNEL_PATHS.map((path) => gateRegexFilter(base, path))
      : [],
    creator,
  };
}

/** Everything DNR must compile as an allow-rule for this rule, de-duplicated and sorted. */
export function allYoutubeAllowFilters(filters: YoutubeAllowFilters): string[] {
  return [...new Set([...filters.creator, ...filters.channel, ...filters.verify])].sort();
}

/**
 * What a YouTube page is, for the in-page gate.
 *
 *  - `open`     the rule is not in force here (or a completed pause opened the site)
 *  - `creator`  a creator-mode carve-out
 *  - `carved`   a channel carve-out; a watch page is still gated per video
 *  - `verify`   a legacy channel URL; the page must prove whose channel it is
 *  - `blocked`  exactly what DNR redirects on a full load
 */
export type YoutubePageAccess = 'open' | 'creator' | 'carved' | 'verify' | 'blocked';

export interface YoutubePageAccessInput extends YoutubeRuleState {
  url: string;
  /** The rule's domain (`SiteConfig.domain`). */
  domain: string;
  youtube: YoutubeFeatureSettings | null;
  /** A completed pause is live for this site. */
  tempAllowed: boolean;
}

/**
 * The page-level verdict, built from the very filter strings DNR compiles.
 *
 * Checked with `urlMatchesFilter`, which matches case-insensitively as DNR does by default.
 */
export function youtubePageAccess(input: YoutubePageAccessInput): YoutubePageAccess {
  if (!input.siteApplies || input.tempAllowed) return 'open';
  const filters = youtubeAllowFilters(input.domain, input.youtube, input);
  const matches = (list: readonly string[]): boolean =>
    list.some((filter) => urlMatchesFilter(input.url, filter));
  if (matches(filters.creator)) return 'creator';
  if (matches(filters.channel)) return 'carved';
  if (matches(filters.verify)) return 'verify';
  return 'blocked';
}

/**
 * True when `url` is a creator-mode page (Studio, or a route to it) under a rule that has
 * creator mode ON. Used by the tracker, so time spent in Studio is never counted against a
 * YouTube limit, and by a limit crossing, so it never yanks a Studio tab to the block page.
 *
 * The user's own channel pages are deliberately NOT exempt: watching your own videos is
 * still watching YouTube, and the limit is about watching.
 */
export function isCreatorExemptUrl(rules: readonly SiteRule[], url: string): boolean {
  const host = extractDomain(url);
  if (host === null) return false;
  for (const rule of rules) {
    const youtube = rule.features?.youtube;
    if (!rule.enabled || youtube === undefined || !youtube.creatorMode) continue;
    if (!hostMatchesRuleDomain(host, normalizeToBaseDomain(rule.domain))) continue;
    if (creatorFilters(rule.domain).some((filter) => urlMatchesFilter(url, filter))) return true;
  }
  return false;
}
