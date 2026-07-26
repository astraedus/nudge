/**
 * EVERY YouTube DOM selector Nudge uses. Nothing is hardcoded in youtube.ts.
 *
 * Source of the taxonomy: ops/routes/nudge/research/ext-03-youtube-techniques.md §1 and §6
 * (read from `Vulpelo/hide-youtube-shorts`, `malekwael229/FocusTube`,
 * `tobiasdalhof/sanersocialmedia` — real shipped extensions, not invention).
 *
 * Three rules this file exists to enforce (ext-03 §6):
 *
 *  1. MULTI-SELECTOR CHAINS, tried in order. YouTube renames wrappers periodically; a
 *     chain survives one rename, a single selector does not.
 *  2. THE GENERIC `[href^="/shorts/"]` MEMBER IS A **FALLBACK**. It survives almost any
 *     wrapper rename but only hides the anchor, not the card around it — degraded, not
 *     correct. Whenever it is the only thing that matched we emit a loud console.warn:
 *     silent degradation is how a blocker quietly stops blocking. It lives in its own
 *     terminal surface (`SHORTS_FALLBACK_SURFACE`) rather than at the end of every chain,
 *     because a chain-tail fallback fires on any page that simply lacks that surface —
 *     and a warning that cries wolf on a healthy page is a warning nobody reads.
 *  3. ATTRIBUTE-ANCHORED over class-anchored. `ytd-*` tag names and `[is-shorts]` /
 *     `[href^=]` attributes are comparatively stable; build-hashed classes are not.
 *
 * PURE + ZERO NETWORK. Every selector is bundled at build time. We never fetch a remote
 * selector list — even though CWS policy would permit remote *data*, "zero network
 * requests" is a listed trust claim for this extension (ext-07 non-negotiables).
 *
 * Fixture tests live in tests/content/selectors.test.ts so that upstream YouTube churn
 * fails CI rather than users (ext-03 §6 pattern 4).
 */

/** YouTube surfaces that get their own selector map — one breaking doesn't break the rest. */
export type YoutubePageType =
  | 'home'
  | 'subscriptions'
  | 'search'
  | 'channel'
  | 'watch'
  | 'shorts'
  | 'other';

/** One rung of a chain. */
export interface SelectorRule {
  selector: string;
  /**
   * A generic catch-all kept only so a wrapper rename degrades instead of failing shut.
   * Matching ONLY on a fallback is a signal that YouTube's DOM moved — it warns.
   */
  fallback?: boolean;
  /** Why this rung exists, for the next person reading a broken selector. */
  note?: string;
}

/** A named Shorts surface plus the ordered selector chain that finds it. */
export interface ShortsSurface {
  /** Stable id — used in warnings and tests, safe to grep. */
  id: string;
  description: string;
  chain: SelectorRule[];
}

/** The class we toggle to hide things. Also defined in youtube.css. */
export const HIDDEN_CLASS = 'nudge-hidden';

/** The generic catch-all (ext-03 §1). Always the LAST rung, and always `fallback`. */
export const SHORTS_HREF_FALLBACK: SelectorRule = {
  selector: '[href^="/shorts/"]',
  fallback: true,
  note: 'Generic catch-all (ext-03 §1). Survives wrapper renames but only hides the anchor.',
};

/**
 * The terminal surface, tried after every specific surface on the page.
 *
 * It hides anything that links to a Short and that no specific selector already covered —
 * which is exactly the "YouTube renamed its wrappers" state, and exactly when the loud
 * warning is warranted. `applyShortsHiding` owns that "already covered" test, which is
 * why the warn sink is injectable: the caller decides whether a fallback match is news.
 */
export const SHORTS_FALLBACK_SURFACE: ShortsSurface = {
  id: 'shorts-fallback',
  description: 'Generic catch-all for Shorts links no specific selector covered',
  chain: [SHORTS_HREF_FALLBACK],
};

/** Shelves: a horizontal row of Shorts embedded in a feed. */
const SHELF_SURFACE: ShortsSurface = {
  id: 'shorts-shelf',
  description: 'Horizontal Shorts shelf embedded in a feed',
  chain: [
    {
      selector: 'ytd-rich-section-renderer:has(>div>ytd-rich-shelf-renderer)',
      note: 'Home/subscriptions shelf wrapper (Vulpelo).',
    },
    {
      selector: 'ytd-rich-shelf-renderer[is-shorts]',
      note: 'Attribute-anchored shelf (FocusTube, sanersocialmedia).',
    },
    { selector: 'ytd-reel-shelf-renderer', note: 'Shelf variant used on search results.' },
    { selector: 'grid-shelf-view-model', note: 'Newer extendable shelf view-model.' },
  ],
};

/** Grid groups: the block that wraps a Shorts row in the rich grid feed. */
const GRID_GROUP_SURFACE: ShortsSurface = {
  id: 'shorts-grid-group',
  description: 'Rich-grid group containing Shorts',
  chain: [
    {
      selector: 'ytd-rich-grid-group:has(a[href^="/shorts/"])',
      note: 'Shorts container in the home/subscriptions feed (Vulpelo).',
    },
    { selector: 'ytd-rich-grid-group[is-shorts]' },
  ],
};

/** Individual Shorts cards scattered through a feed / sidebar / search results. */
function cardSurface(cardSelectors: string[]): ShortsSurface {
  return {
    id: 'shorts-cards',
    description: 'Individual Shorts video cards',
    chain: cardSelectors.map((tag) => ({
      selector: `${tag}:has(a[href^="/shorts/"])`,
      note: `${tag} card whose thumbnail links to a Short.`,
    })),
  };
}

/** The Shorts entry in the left navigation (full guide + collapsed mini guide). */
const NAV_SURFACE: ShortsSurface = {
  id: 'shorts-nav',
  description: 'Shorts entry in the left navigation rail',
  chain: [
    {
      selector: "ytd-mini-guide-entry-renderer>a[href='/shorts/']",
      note: 'Collapsed mini-guide Shorts tab (Vulpelo).',
    },
    {
      selector: 'ytd-guide-entry-renderer:has(>a[href="/shorts/"])',
      note: 'Expanded guide Shorts tab.',
    },
    { selector: 'a[title="Shorts"]', note: 'Title-anchored nav link (FocusTube).' },
  ],
};

/**
 * Per-page-type selector maps (ext-03 §6 pattern 5). A page type that breaks upstream
 * only takes its own surfaces down with it.
 *
 * These are the SPECIFIC surfaces only — `SHORTS_FALLBACK_SURFACE` is applied after them,
 * on every page type.
 *
 * The `/shorts/*` player page has no hiding surfaces of its own beyond the nav — it is
 * *gated* by the interstitial overlay in youtube.ts instead, because there is nothing
 * left to browse once you hide the player.
 */
export const SHORTS_SURFACES: Record<YoutubePageType, ShortsSurface[]> = {
  home: [
    SHELF_SURFACE,
    GRID_GROUP_SURFACE,
    cardSurface(['ytd-rich-item-renderer']),
    NAV_SURFACE,
  ],
  subscriptions: [
    SHELF_SURFACE,
    GRID_GROUP_SURFACE,
    cardSurface(['ytd-rich-item-renderer', 'ytd-grid-video-renderer']),
    NAV_SURFACE,
  ],
  search: [
    SHELF_SURFACE,
    cardSurface(['ytd-video-renderer', 'ytd-rich-item-renderer']),
    NAV_SURFACE,
  ],
  channel: [
    SHELF_SURFACE,
    GRID_GROUP_SURFACE,
    cardSurface(['ytd-rich-item-renderer', 'ytd-grid-video-renderer']),
    NAV_SURFACE,
  ],
  watch: [
    SHELF_SURFACE,
    cardSurface(['ytd-compact-video-renderer', 'ytd-rich-item-renderer']),
    NAV_SURFACE,
  ],
  shorts: [NAV_SURFACE],
  other: [NAV_SURFACE],
};

/** Where the Shorts player mounts — the overlay anchors here, falling back to <body>. */
export const SHORTS_PLAYER_CONTAINERS: SelectorRule[] = [
  { selector: 'ytd-shorts', note: 'The Shorts player host element.' },
  { selector: '#shorts-player' },
  { selector: 'ytd-reel-video-renderer' },
  { selector: 'body', fallback: true, note: 'Always present; overlay still covers the viewport.' },
];

/** Elements Nudge itself injects, so we never hide our own overlay. */
export const NUDGE_OVERLAY_ID = 'nudge-shorts-gate';

export interface QueryResult {
  /** Everything the first matching rung matched. Empty when nothing in the chain matched. */
  elements: Element[];
  /** The selector string that matched, or null when the whole chain missed. */
  matchedSelector: string | null;
  /** True when the ONLY rung that matched was flagged `fallback`. */
  usedFallback: boolean;
}

/** Injectable so tests can assert the warning fired without polluting test output. */
export type WarnFn = (message: string) => void;

const warnedSelectors = new Set<string>();

/**
 * Default warning sink: one loud console.warn per selector per page load.
 *
 * Deduped because the MutationObserver re-runs the chains constantly — an undeduped
 * warning would be a console flood, and a flooded console is an ignored console.
 */
export const defaultWarn: WarnFn = (message) => {
  if (warnedSelectors.has(message)) return;
  warnedSelectors.add(message);
  console.warn(message);
};

/** Test/reset seam for the dedupe set. */
export function resetFallbackWarnings(): void {
  warnedSelectors.clear();
}

/** The exact warning text, so tests assert on one definition rather than a copy. */
export function fallbackWarningMessage(surfaceId: string, selector: string): string {
  return (
    `[nudge] YouTube DOM changed: the "${surfaceId}" surface only matched the fallback ` +
    `selector \`${selector}\`. Shorts hiding is running in degraded mode (the link is ` +
    `hidden, its card is not). Please report this at ` +
    `https://github.com/astraedus/nudge/issues so the selector chain can be updated.`
  );
}

/**
 * Run one selector chain against `root`, first rung that matches wins.
 *
 * Chains are ordered specific -> generic, so "first match wins" means "most precise
 * available selector". When the winner is a `fallback` rung we warn loudly instead of
 * silently degrading (ext-03 §6 pattern 2).
 */
export function queryWithFallback(
  root: ParentNode,
  chain: readonly SelectorRule[],
  options: { surfaceId?: string; warn?: WarnFn } = {},
): QueryResult {
  const { surfaceId = 'unknown', warn = defaultWarn } = options;

  for (const rule of chain) {
    let matches: Element[];
    try {
      matches = Array.from(root.querySelectorAll(rule.selector));
    } catch {
      // A selector the engine cannot parse (e.g. `:has()` on an ancient browser) must
      // never abort the chain — skip the rung and keep going.
      continue;
    }
    if (matches.length === 0) continue;

    if (rule.fallback) {
      warn(fallbackWarningMessage(surfaceId, rule.selector));
    }
    return {
      elements: matches,
      matchedSelector: rule.selector,
      usedFallback: rule.fallback === true,
    };
  }

  return { elements: [], matchedSelector: null, usedFallback: false };
}

/**
 * `/shorts`, `/shorts/`, `/shorts/<id>` — "shorts" must be a WHOLE first path segment.
 * `/shortsomething` and `/watch` are not Shorts no matter what else the URL says.
 */
const SHORTS_PATH = /^\/shorts(\/|$)/;
const CHANNEL_PATH = /^\/(channel|c|user)\//;

/**
 * Classify a YouTube URL by *pathname only*.
 *
 * Pathname-only is the point: a video merely titled "shorts", or a search for the word,
 * puts "shorts" in the query string or the page title — never in the first path segment.
 * Matching those would gate an ordinary watch page.
 */
export function pageTypeFor(url: string): YoutubePageType {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return 'other';
  }
  if (!/(^|\.)youtube\.com$/.test(parsed.hostname)) return 'other';

  const path = parsed.pathname.replace(/\/+$/, '') || '/';

  if (SHORTS_PATH.test(parsed.pathname)) return 'shorts';
  if (path === '/watch') return 'watch';
  if (path === '/results') return 'search';
  if (path === '/feed/subscriptions') return 'subscriptions';
  if (path === '/') return 'home';
  if (CHANNEL_PATH.test(parsed.pathname) || parsed.pathname.startsWith('/@')) return 'channel';
  return 'other';
}
