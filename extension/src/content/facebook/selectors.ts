/**
 * Facebook hide-surface selectors.
 *
 * Source of the selector table: `ops/routes/nudge/research/ext-12-reels-and-feeds-web-
 * techniques.md` §A/§D. News Feed, Reels, Watch and Marketplace are URL-addressable and
 * fully owned by `core/platforms.ts` + `content/platformGate.ts` as GATES — nothing here
 * duplicates them. This file covers only the three cosmetic HIDE surfaces the registry
 * defines for `facebook` (`stories`, `reelsNav`, `peopleYouMayKnow`).
 *
 * CLIMB-VIA-`:has()` DEVIATION (read before touching `reelsNav`/`stories`): ext-12
 * describes both as "find the marker element, then `.closest(...)` to the row/tray
 * container". `queryWithFallback` (`content/selectors.ts`) only runs `querySelectorAll` —
 * no "match then climb" step — so, exactly as `content/instagram/selectors.ts` does for
 * the same shape of problem, we express "closest ancestor X containing marker Y" directly
 * as the CSS `X:has(Y)`, which this repo's selector engine (Chrome, and jsdom in tests —
 * see `content/selectors.ts`'s own `:has()` chains) supports. That keeps every surface a
 * flat `SelectorRule[]` chain instead of adding a bespoke non-selector code path for two
 * of the three surfaces.
 *
 * The "People You May/Might Know" source list in ext-12 also included a text-regex rung;
 * `queryWithFallback` only supports CSS selectors, so that rung is skipped and the
 * structural fallback (`div.x1xnnf8n > div:nth-child(3)`) is the last resort instead.
 *
 * PURE + ZERO NETWORK, same discipline as `content/selectors.ts`.
 */

import type { HideSurfaceDef } from '../platformGate';

export const FACEBOOK_HIDE_SURFACES: HideSurfaceDef[] = [
  {
    id: 'reelsNav',
    chain: [
      {
        selector: 'li:has(a[href="/reel/?s=tab"])',
        note:
          'Reels nav entry, climbed via :has() to its containing <li> (ext-12 §A: ' +
          '".closest(\'li\')") so the whole row disappears, not just the link text.',
      },
      {
        selector: 'a[href="/reel/?s=tab"]',
        fallback: true,
        note: 'Anchor-only fallback for when no <li> wraps the link.',
      },
    ],
  },
  {
    id: 'stories',
    chain: [
      {
        selector: '[scrollable="true"]:has([aria-label="Stories"])',
        note:
          'Stories tray container, climbed via :has() from the `[aria-label="Stories"]` ' +
          'marker (ext-12 §A) to its closest `[scrollable="true"]` ancestor.',
      },
      {
        selector: '.xb57i2i',
        fallback: true,
        note: 'Hashed-class last resort (ext-12 §A). Expect this to rot first.',
      },
    ],
  },
  {
    id: 'peopleYouMayKnow',
    chain: [
      { selector: '[data-pagelet*="PeopleYouMayKnow"]', note: 'Pagelet-anchored (ext-12 §A).' },
      { selector: '[data-pagelet*="PeopleYouMightKnow"]', note: 'Alternate pagelet spelling.' },
      { selector: '[aria-label="People You May Know"]', note: 'Aria-anchored variant.' },
      {
        selector: 'div.x1xnnf8n > div:nth-child(3)',
        fallback: true,
        note:
          'Structural last resort (ext-12 §A). The source repo also carried a text-regex ' +
          'rung ("People You May Know" as visible text) — omitted here because ' +
          'queryWithFallback only matches CSS selectors, not text content.',
      },
    ],
  },
];
