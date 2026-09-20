/**
 * The platform registry — the single source of truth for which feature surfaces exist on
 * which known platform. PURE (no chrome.*, no DOM).
 *
 * Two kinds of surface, deliberately different shapes because they are different problems:
 *
 *  - A **GATE** is a URL-addressable sub-page the user can land on (`/shorts/`, `/reels/`,
 *    `/home`). It is reachable by typing the URL, so it MUST be enforceable at the network
 *    layer, which means its identity has to be a URL pattern rather than a DOM selector. It
 *    behaves like a mini site rule: its own mode, delay and daily budget.
 *  - A **HIDE** is a cosmetic element on a page the user is allowed to be on (comments, the
 *    stories tray, "Who to follow"). It has no URL, so it is a boolean applied by CSS class
 *    in the content script.
 *
 * The path patterns live HERE, not in the content scripts, because both DNR (full page
 * loads, typed URLs) and the content script (in-SPA navigation) have to agree on what
 * counts as "the Reels surface". Two copies of that answer is a bug generator — a gate the
 * network layer redirects but the SPA overlay ignores (or vice versa) is exactly the kind
 * of half-enforced feature users report as "it works sometimes".
 *
 * DOM selectors deliberately stay OUT of here, in `content/<platform>/selectors.ts`: they
 * rot on a completely different schedule from the URL structure and are the content
 * layer's problem alone.
 */

import type { BlockMode } from './types';

export type Platform =
  | 'youtube'
  | 'instagram'
  | 'tiktok'
  | 'x'
  | 'facebook'
  | 'reddit'
  | 'linkedin';

/**
 * Gate ids. Deliberately NOT namespaced per platform — `explore` means the same thing to a
 * user on Instagram and on TikTok, and the platform always disambiguates (a rule's gates
 * only ever contain ids its own platform defines). Namespacing them would double the union
 * for no reader benefit.
 */
export type GateId =
  | 'shorts'
  | 'reels'
  | 'explore'
  | 'home'
  | 'foryou'
  | 'live'
  | 'notifications'
  | 'feed'
  | 'watch'
  | 'marketplace';

export type HideId =
  // youtube
  | 'shortsShelf'
  | 'homeFeed'
  | 'sidebarRecs'
  | 'endScreen'
  | 'comments'
  // instagram
  | 'stories'
  | 'suggested'
  | 'reelsNav'
  | 'exploreNav'
  // tiktok
  | 'search'
  | 'liveNav'
  | 'suggestedAccounts'
  // x
  | 'trends'
  | 'whoToFollow'
  | 'promoted'
  | 'grok'
  // facebook
  | 'peopleYouMayKnow'
  // linkedin
  | 'suggestedFollows';

/**
 * One gate surface.
 *
 * `paths` are anchored PATHNAME patterns in the regex subset shared by RE2 (which DNR's
 * `regexFilter` uses) and JavaScript's RegExp: literals, character classes, `(?:…)`,
 * `.`, `*`, `?`, `+`. No lookaround and no backreferences — RE2 supports neither, and a
 * pattern that works in the content script but silently fails to compile in DNR would
 * leave the surface enforced in-SPA and wide open on a full page load.
 */
export interface GateDefinition {
  id: GateId;
  label: string;
  /** One line shown under the gate row in the rule editor. */
  description: string;
  paths: readonly string[];
}

export interface HideDefinition {
  id: HideId;
  label: string;
  description: string;
}

export interface PlatformDefinition {
  id: Platform;
  label: string;
  /**
   * Base domains this platform answers on, canonical first. X is the reason this is a
   * list: twitter.com still resolves and users still have rules on it.
   */
  domains: readonly string[];
  gates: readonly GateDefinition[];
  hides: readonly HideDefinition[];
  /**
   * An honest limitation to print in the dashboard, or null. Where ext-12 found that no
   * OSS implementation anywhere filters individual cards out of a mixed feed, we ship
   * whole-surface control only and SAY so rather than letting the user infer a guarantee
   * the code does not make.
   */
  note: string | null;
}

/** Matches exactly "/" — a home feed, which is a real gate surface on several platforms. */
const ROOT = '/';

export const PLATFORMS: readonly PlatformDefinition[] = [
  {
    id: 'youtube',
    label: 'YouTube',
    domains: ['youtube.com'],
    gates: [
      {
        id: 'shorts',
        label: 'Shorts',
        description: 'The vertical short-video player and its URLs.',
        paths: ['/shorts(?:/.*)?'],
      },
    ],
    hides: [
      {
        id: 'shortsShelf',
        label: 'Shorts shelves and tabs',
        description: 'Removes Shorts rows, cards and the sidebar entry across YouTube.',
      },
      {
        id: 'homeFeed',
        label: 'Home feed',
        description: 'Leaves search, subscriptions and your history reachable.',
      },
      {
        id: 'sidebarRecs',
        label: 'Recommended videos',
        description: 'The "up next" rail beside a video.',
      },
      {
        id: 'endScreen',
        label: 'End screen and cards',
        description: 'The suggestion grid and creator cards at the end of a video.',
      },
      {
        id: 'comments',
        label: 'Comments',
        description: 'The comment section under a video.',
      },
    ],
    note: null,
  },
  {
    id: 'instagram',
    label: 'Instagram',
    domains: ['instagram.com'],
    gates: [
      {
        id: 'reels',
        label: 'Reels',
        description: 'The Reels tab and individual reel pages.',
        paths: ['/reels(?:/.*)?', '/reel/.*'],
      },
      {
        id: 'explore',
        label: 'Explore',
        description: 'The Explore grid.',
        paths: ['/explore(?:/.*)?'],
      },
      {
        id: 'home',
        label: 'Home feed',
        description: 'instagram.com itself — the scrolling feed.',
        paths: [ROOT],
      },
    ],
    hides: [
      {
        id: 'stories',
        label: 'Stories tray',
        description: 'The row of story circles above the feed.',
      },
      {
        id: 'suggested',
        label: 'Suggested for you',
        description: 'Suggested accounts and posts blocks.',
      },
      {
        id: 'reelsNav',
        label: 'Reels nav entry',
        description: 'The Reels link in the sidebar.',
      },
      {
        id: 'exploreNav',
        label: 'Explore nav entry',
        description: 'The Explore link in the sidebar.',
      },
    ],
    note:
      'Blocks the Reels tab and reel pages. Reels mixed into the home feed stay — hide or gate the home feed to remove those.',
  },
  {
    id: 'tiktok',
    label: 'TikTok',
    domains: ['tiktok.com'],
    gates: [
      {
        id: 'foryou',
        label: 'For You feed',
        description: 'tiktok.com itself, plus the For You and Following feeds.',
        paths: [ROOT, '/foryou(?:/.*)?', '/following(?:/.*)?'],
      },
      {
        id: 'explore',
        label: 'Explore',
        description: 'The Explore tab.',
        paths: ['/explore(?:/.*)?'],
      },
      {
        id: 'live',
        label: 'Live',
        description: 'The Live tab and live streams.',
        paths: ['/live(?:/.*)?'],
      },
    ],
    hides: [
      { id: 'comments', label: 'Comments', description: 'The comment list beside a video.' },
      { id: 'search', label: 'Search box', description: 'The search field in the header.' },
      { id: 'liveNav', label: 'Live nav entry', description: 'The Live link in the sidebar.' },
      {
        id: 'exploreNav',
        label: 'Explore nav entry',
        description: 'The Explore link in the sidebar.',
      },
      {
        id: 'suggestedAccounts',
        label: 'Suggested accounts',
        description: 'The "Suggested accounts" block in the sidebar.',
      },
    ],
    note:
      'TikTok has no filtered view — gating the For You feed is the only meaningful control. Individual videos mixed into a feed cannot be filtered out.',
  },
  {
    id: 'x',
    label: 'X (Twitter)',
    domains: ['x.com', 'twitter.com'],
    gates: [
      {
        id: 'home',
        label: 'Home timeline',
        description: 'The For You / Following timeline.',
        paths: ['/home(?:/.*)?'],
      },
      {
        id: 'explore',
        label: 'Explore and Trending',
        description: 'The Explore tab and trending pages.',
        paths: ['/explore(?:/.*)?', '/i/trending(?:/.*)?'],
      },
      {
        id: 'notifications',
        label: 'Notifications',
        description: 'The notifications tab.',
        paths: ['/notifications(?:/.*)?'],
      },
    ],
    hides: [
      {
        id: 'trends',
        label: '"What’s happening"',
        description: 'The trending topics panel in the sidebar.',
      },
      {
        id: 'whoToFollow',
        label: '"Who to follow"',
        description: 'Account suggestions in the sidebar.',
      },
      {
        id: 'promoted',
        label: 'Promoted posts',
        description: 'Ads in the timeline.',
      },
      { id: 'grok', label: 'Grok', description: 'The Grok entry point and drawer.' },
      {
        id: 'exploreNav',
        label: 'Explore nav entry',
        description: 'The Explore link in the sidebar.',
      },
    ],
    note: null,
  },
  {
    id: 'facebook',
    label: 'Facebook',
    domains: ['facebook.com'],
    gates: [
      {
        id: 'feed',
        label: 'News Feed',
        description: 'facebook.com itself — the scrolling feed.',
        paths: [ROOT, '/home\\.php'],
      },
      {
        id: 'reels',
        label: 'Reels',
        description: 'The Reels tab and individual reel pages.',
        paths: ['/reel(?:/.*)?', '/reels(?:/.*)?'],
      },
      {
        id: 'watch',
        label: 'Watch',
        description: 'The Watch video tab.',
        paths: ['/watch(?:/.*)?'],
      },
      {
        id: 'marketplace',
        label: 'Marketplace',
        description: 'The Marketplace tab.',
        paths: ['/marketplace(?:/.*)?'],
      },
    ],
    hides: [
      { id: 'stories', label: 'Stories tray', description: 'The story row above the feed.' },
      {
        id: 'reelsNav',
        label: 'Reels nav entry',
        description: 'The Reels link in the left rail.',
      },
      {
        id: 'peopleYouMayKnow',
        label: '"People you may know"',
        description: 'Friend suggestions in the feed.',
      },
    ],
    note: null,
  },
  {
    id: 'reddit',
    label: 'Reddit',
    domains: ['reddit.com'],
    gates: [
      {
        id: 'home',
        label: 'Home, Popular and All',
        description: 'The endless front-page feeds. Individual subreddits stay reachable.',
        paths: [ROOT, '/r/popular(?:/.*)?', '/r/all(?:/.*)?'],
      },
    ],
    // No sourced, durable selector exists for a Reddit sub-element worth hiding on its own
    // (ext-12 §E found only a generic whole-`main` container hide, which is what the home
    // gate already does properly). Shipping a hide toggle we cannot implement reliably
    // would be a promise the code does not keep.
    hides: [],
    note: 'Gates the front-page feeds. Individual subreddits and comment threads stay open.',
  },
  {
    id: 'linkedin',
    label: 'LinkedIn',
    domains: ['linkedin.com'],
    gates: [
      {
        id: 'feed',
        label: 'Feed',
        description: 'The LinkedIn home feed. Messaging and your profile stay reachable.',
        paths: ['/feed(?:/.*)?'],
      },
    ],
    hides: [
      {
        id: 'suggestedFollows',
        label: 'Suggested follows',
        description: 'The "Add to your feed" rail.',
      },
    ],
    note: null,
  },
];

const PLATFORM_BY_ID = new Map<Platform, PlatformDefinition>(
  PLATFORMS.map((platform) => [platform.id, platform]),
);

const PLATFORM_BY_DOMAIN = new Map<string, PlatformDefinition>(
  PLATFORMS.flatMap((platform) => platform.domains.map((domain) => [domain, platform] as const)),
);

export function platformById(id: Platform): PlatformDefinition {
  const found = PLATFORM_BY_ID.get(id);
  if (found === undefined) throw new Error(`unknown platform: ${id}`);
  return found;
}

/** The platform a base domain belongs to, or null when it is not a known platform. */
export function platformForDomain(domain: string): PlatformDefinition | null {
  return PLATFORM_BY_DOMAIN.get(domain.trim().toLowerCase()) ?? null;
}

/** Canonical domains for the quick-add chips, one per platform. */
export function quickAddPlatforms(): { platform: Platform; label: string; domain: string }[] {
  return PLATFORMS.map((platform) => ({
    platform: platform.id,
    label: platform.label,
    // domains[0] is canonical by construction; alias entries exist for matching only.
    domain: platform.domains[0]!,
  }));
}

export function gateDefinition(platform: Platform, gateId: GateId): GateDefinition | null {
  return platformById(platform).gates.find((gate) => gate.id === gateId) ?? null;
}

export function isKnownGate(platform: Platform, gateId: string): gateId is GateId {
  return platformById(platform).gates.some((gate) => gate.id === gateId);
}

export function isKnownHide(platform: Platform, hideId: string): hideId is HideId {
  return platformById(platform).hides.some((hide) => hide.id === hideId);
}

/**
 * Compile one path pattern into an anchored JS RegExp for the pathname.
 *
 * The same string is handed to DNR as part of a full-URL `regexFilter` (see
 * `background/dnr.ts`), so the two layers can never disagree about what the surface is.
 */
export function gatePathRegex(pattern: string): RegExp {
  return new RegExp(`^${pattern}$`);
}

/** True when `pathname` is one of this gate's surfaces. */
export function gateMatchesPath(gate: GateDefinition, pathname: string): boolean {
  return gate.paths.some((pattern) => gatePathRegex(pattern).test(pathname));
}

/**
 * Which gate of `platform` the given URL lands on, or null.
 *
 * Takes a full URL (what the content script, the tracker and the block page all actually
 * hold) and does the pathname extraction itself, so no caller has to remember that a query
 * string or hash must not participate in the match.
 *
 * Gates are tried most-specific-first so a surface like `/explore` wins over a root gate
 * ("/"), which would otherwise be order-dependent.
 */
export function gateForUrl(platform: Platform, url: string): GateId | null {
  let pathname: string;
  try {
    pathname = new URL(url).pathname;
  } catch {
    return null;
  }
  const gates = [...platformById(platform).gates].sort(
    (a, b) => longestPatternLength(b) - longestPatternLength(a),
  );
  for (const gate of gates) {
    if (gateMatchesPath(gate, pathname)) return gate.id;
  }
  return null;
}

function longestPatternLength(gate: GateDefinition): number {
  return gate.paths.reduce((max, pattern) => Math.max(max, pattern.length), 0);
}

/** Strength ordering for a gate mode. Higher = more protection. Mirrors strictMode's. */
export function gateModeStrength(mode: 'OFF' | BlockMode): number {
  switch (mode) {
    case 'HARD_BLOCK':
      return 3;
    case 'DELAY':
      return 2;
    case 'BREATHING':
      return 1;
    default:
      return 0;
  }
}
