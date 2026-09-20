/**
 * LinkedIn hide-surface selectors.
 *
 * Source: `ops/routes/nudge/research/ext-12-reels-and-feeds-web-techniques.md` §E, from
 * `tobiasdalhof/sanersocialmedia`'s `linkedin.ts` — stable BEM/testid-anchored hooks, not
 * hashed build classes, unusually durable for a social platform. The `feed` GATE (the
 * whole-page `div[data-testid="mainFeed"]` surface) is fully owned by `core/platforms.ts`
 * + `content/platformGate.ts` via URL matching against `/feed(?:/.*)?`; nothing here
 * duplicates it. This file covers only the one cosmetic HIDE surface the registry defines
 * for `linkedin` (`suggestedFollows`).
 *
 * PURE + ZERO NETWORK, same discipline as `content/selectors.ts`.
 */

import type { HideSurfaceDef } from '../platformGate';

export const HIDE_SURFACES: HideSurfaceDef[] = [
  {
    id: 'suggestedFollows',
    chain: [
      {
        selector: '.scaffold-layout__aside .feed-follows-module',
        note: '"Add to your feed" suggested-follows rail (sanersocialmedia linkedin.ts).',
      },
    ],
  },
];
