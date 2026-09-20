/**
 * X (Twitter) hide-surface selectors — the `HideSurfaceDef`s handed to
 * `initPlatformContentScript` (`src/content/platformGate.ts`) by `./index.ts`.
 *
 * Source of every chain: ops/routes/nudge/research/ext-12-reels-and-feeds-web-techniques.md
 * §C, primarily `insin/control-panel-for-twitter`'s 8,233-line `Selectors` enum — the
 * reference `data-testid` catalog for this site.
 *
 * ROT-RISK WARNING (ext-12 §C, load-bearing): `[data-testid="ScrollSnap-List"]` is reused
 * across at least THREE unrelated tab bars (the home Following/For-you switcher, profile
 * tabs, search tabs). Never target it alone for any surface below — none of these chains
 * do, and if a future chain ever needs it, it MUST be scoped with an ancestor selector
 * first (e.g. `[data-testid="primaryColumn"] [data-testid="ScrollSnap-List"]`).
 *
 * Per-tweet / per-timeline-item filtering is explicitly OUT OF SCOPE (ext-12: no OSS tool
 * filters individual items out of a mixed timeline) — the `home`/`explore`/`notifications`
 * gates in `core/platforms.ts` already fully block those whole surfaces via
 * `initPlatformContentScript`'s overlay; no hide surface is defined for individual posts.
 */

import type { HideId } from '../../core/platforms';
import type { SelectorRule } from '../selectors';
import type { HideSurfaceDef } from '../platformGate';

const PROMOTED_CHAIN: SelectorRule[] = [
  {
    selector: '[data-testid="placementTracking"]',
    note: "Twitter's own attribute wrapping every promoted (ad) post in the timeline.",
  },
];

const TRENDS_CHAIN: SelectorRule[] = [
  {
    selector:
      '[data-testid="sidebarColumn"] section:has([aria-label="Timeline: Trending now"])',
    note: 'Primary: the whole "What’s happening" section, chrome and header included.',
  },
  {
    selector: '[data-testid="trend"]',
    fallback: true,
    note:
      'DEGRADED fallback: hides individual trend rows only, leaving the section header/' +
      'chrome on screen. Used only if the section-level selector ever misses — a genuine ' +
      'DOM-rot signal, so it is flagged `fallback: true` to fire the loud selector-rot warn.',
  },
];

const WHO_TO_FOLLOW_CHAIN: SelectorRule[] = [
  {
    selector: '[data-testid="sidebarColumn"] aside:has([data-testid="UserCell"])',
    note: 'The "Who to follow" panel, identified by the UserCell rows it contains.',
  },
];

const EXPLORE_NAV_CHAIN: SelectorRule[] = [
  {
    selector: '[data-testid="AppTabBar_Explore_Link"]',
    note: 'Primary: attribute-anchored Explore tab in the app nav bar.',
  },
  {
    selector: 'nav[role="navigation"] a[href="/explore"]',
    note:
      'Secondary rung, scoped to the nav landmark (never bare `a[href]`) for builds ' +
      'where the data-testid is absent.',
  },
];

const GROK_CHAIN: SelectorRule[] = [
  {
    selector: '[data-testid*="Grok" i]',
    fallback: true,
    note:
      'INFERRED, not independently sourced from a live DOM capture: ext-12 only names the ' +
      'toggle identifiers `GrokDrawer`/`grokImgGen` without exact selector strings. Best-' +
      'effort substring/case-insensitive match, flagged `fallback: true` so a miss (or a ' +
      'match) is loudly observable rather than silently trusted.',
  },
];

/** One X hide surface per `HideId` the platform registry defines for `x`. */
export const HIDE_SURFACES: readonly HideSurfaceDef[] = [
  { id: 'trends' satisfies HideId, chain: TRENDS_CHAIN },
  { id: 'whoToFollow' satisfies HideId, chain: WHO_TO_FOLLOW_CHAIN },
  { id: 'promoted' satisfies HideId, chain: PROMOTED_CHAIN },
  { id: 'grok' satisfies HideId, chain: GROK_CHAIN },
  { id: 'exploreNav' satisfies HideId, chain: EXPLORE_NAV_CHAIN },
];
