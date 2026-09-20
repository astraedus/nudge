/**
 * Instagram hide-surface selectors.
 *
 * Source of the selector table: `ops/routes/nudge/research/ext-12-reels-and-feeds-web-
 * techniques.md` §A/§D. Gates (Reels, Explore, Home feed) are URL-addressable and fully
 * owned by `core/platforms.ts` + `content/platformGate.ts` — nothing here duplicates them.
 * This file covers only the four cosmetic HIDE surfaces the registry defines for
 * `instagram` (`stories`, `suggested`, `reelsNav`, `exploreNav`).
 *
 * CLIMB-VIA-`:has()` DEVIATION (read before touching the `stories`/`reelsNav` chains):
 * ext-12 describes the stories tray as "find `[aria-label^="Story by"]`, then
 * `.closest('[scrollable="true"]')` to reach the tray container". `queryWithFallback`
 * (selectors.ts) only runs `querySelectorAll` — it has no "match then climb" step, and the
 * prompt that specified this file allowed a bespoke helper function for that case. We did
 * NOT reach for one: Chrome (and this repo's jsdom test environment — see
 * `content/selectors.ts`'s own `:has()` chains, e.g. the Shorts shelf surface) supports
 * CSS `:has()`, so "closest ancestor matching X that contains a descendant matching Y" is
 * expressible directly as `X:has(Y)`. That keeps every hide surface in this file a flat
 * `SelectorRule[]` chain — same shape `platformGate.ts#applyPlatformHides` already knows
 * how to run — instead of adding a second, one-off code path just for two surfaces.
 *
 * PURE + ZERO NETWORK, same discipline as `content/selectors.ts`.
 */

import type { HideSurfaceDef } from '../platformGate';

export const INSTAGRAM_HIDE_SURFACES: HideSurfaceDef[] = [
  {
    id: 'reelsNav',
    chain: [
      {
        selector: 'a[href="/reels/"]',
        note: 'Exact-href Reels nav entry (ext-12 §A).',
      },
      {
        selector: 'a[href$="/reels/"]',
        note: 'Trailing-slash variant for a localized/prefixed href.',
      },
      {
        selector: 'nav a:has(svg[aria-label*="Reel"])',
        fallback: true,
        note:
          'Icon-anchored fallback: climbs from the aria-labelled svg to its containing ' +
          '<a> via :has() (ext-12 §A calls for "closest a" — see file header) so the ' +
          'whole nav row disappears, not just the icon.',
      },
    ],
  },
  {
    id: 'exploreNav',
    chain: [
      {
        selector: 'a[href="/explore/"]',
        note:
          'INFERRED BY ANALOGY to the reelsNav chain above — ext-12 has no independently ' +
          'sourced selector for the Explore nav link itself, only for the Explore GATE ' +
          '(the grid page, already fully handled by the gate surface). Instagram nav ' +
          'links are consistently exact-href-anchored, which is the basis for the ' +
          'inference; flagged here rather than presented as sourced.',
      },
      {
        selector: 'a[href$="/explore/"]',
        note: 'Trailing-slash variant, same inference as above.',
      },
    ],
  },
  {
    id: 'stories',
    chain: [
      {
        selector: '[scrollable="true"]:has([aria-label^="Story by"])',
        note:
          'Story tray container: climbs from a per-story anchor (`[aria-label^="Story ' +
          'by"]`, ext-12 §A) to its closest `[scrollable="true"]` ancestor via :has() — ' +
          'see the CLIMB-VIA-:has() note at the top of this file.',
      },
      {
        selector: '[role="presentation"]:has([aria-label^="Story by"])',
        note: 'Fallback ancestor when the tray is not marked scrollable="true".',
      },
      {
        selector: 'ul._acay',
        fallback: true,
        note: 'Hashed-class last resort (ext-12 §A). Expect this to rot first.',
      },
    ],
  },
  {
    id: 'suggested',
    chain: [
      {
        selector: 'div:has(> div > a[href="/explore/people/"])',
        note: '"Suggested for you" block (ext-12 §A). The Explore-suggestions GRID is a ' +
          'gate surface (`explore`), not this hide — no separate work needed for it.',
      },
    ],
  },
];
