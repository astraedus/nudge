/**
 * TikTok hide-surface selectors — the `HideSurfaceDef`s handed to
 * `initPlatformContentScript` (`src/content/platformGate.ts`) by `./index.ts`.
 *
 * Source of every chain: ops/routes/nudge/research/ext-12-reels-and-feeds-web-techniques.md
 * §B, corroborated across `Sayukoo/tiktok_unhook` and two other repos. TikTok ships its own
 * `data-e2e` QA-attribute layer that is far more stable across builds than class names, so
 * (per ext-12) every chain here is `data-e2e`-anchored and none needs a true last-resort
 * `fallback: true` rung — a chain-tail `fallback` rung is reserved for a rung so generic it
 * proves the DOM moved (see `selectors.ts`'s `SHORTS_HREF_FALLBACK` for the shape of that);
 * TikTok's secondary rungs below are simply LESS SPECIFIC `data-e2e`/`href` alternatives,
 * not "the DOM broke" signals, so they are plain rungs, exactly like `search`'s two
 * equally-`data-e2e`-anchored rungs.
 *
 * Explicitly OUT OF SCOPE (ext-12): class-hash selectors (`[class*="DivSideNavContainer"]`
 * etc. — per-build-hashed and rot-prone) and per-video-card filtering inside the For You /
 * Explore feeds (`gateForUrl`'s `foryou`/`explore` gates already block the whole surface;
 * no OSS tool filters individual cards out of a mixed feed). Ad badges
 * (`[data-e2e="ad-badge"]`, `[data-e2e="sponsored-badge"]`) are not in the platform
 * registry's `hides` list for TikTok, so no surface is defined for them here.
 */

import type { HideId } from '../../core/platforms';
import type { SelectorRule } from '../selectors';
import type { HideSurfaceDef } from '../platformGate';

const COMMENTS_CHAIN: SelectorRule[] = [
  {
    selector: '[data-e2e="comment-list"]',
    note: "TikTok's own QA attribute for the comment panel beside/under a video.",
  },
];

const SEARCH_CHAIN: SelectorRule[] = [
  {
    selector: 'form[data-e2e="search-box"]',
    note: 'Primary: the whole search form in the header.',
  },
  {
    selector: 'input[data-e2e="search-user-input"]',
    note:
      'Secondary rung, not a `fallback` — equally `data-e2e`-anchored, just narrower ' +
      '(the input alone) for builds where the form selector misses.',
  },
];

const LIVE_NAV_CHAIN: SelectorRule[] = [
  { selector: '[data-e2e="nav-live"]', note: 'Live entry in the side nav.' },
];

const EXPLORE_NAV_CHAIN: SelectorRule[] = [
  { selector: '[data-e2e="nav-explore"]', note: 'Primary: attribute-anchored Explore tab.' },
  {
    selector: 'a[href="/explore"]',
    note: 'Secondary rung for builds where the data-e2e attribute is absent.',
  },
];

const SUGGESTED_ACCOUNTS_CHAIN: SelectorRule[] = [
  {
    selector: '[data-e2e="suggest-accounts"]',
    note: 'ext-12: TikTok ships BOTH spellings across surfaces/builds.',
  },
  {
    selector: '[data-e2e="suggested-accounts"]',
    note: 'The other spelling ext-12 found live — either can be the current one.',
  },
];

/** One TikTok hide surface per `HideId` the platform registry defines for `tiktok`. */
export const HIDE_SURFACES: readonly HideSurfaceDef[] = [
  { id: 'comments' satisfies HideId, chain: COMMENTS_CHAIN },
  { id: 'search' satisfies HideId, chain: SEARCH_CHAIN },
  { id: 'liveNav' satisfies HideId, chain: LIVE_NAV_CHAIN },
  { id: 'exploreNav' satisfies HideId, chain: EXPLORE_NAV_CHAIN },
  { id: 'suggestedAccounts' satisfies HideId, chain: SUGGESTED_ACCOUNTS_CHAIN },
];
