/**
 * Reddit hide-surface selectors.
 *
 * Source: `ops/routes/nudge/research/ext-12-reels-and-feeds-web-techniques.md` §E. The
 * `home` GATE (front page, r/popular, r/all — a URL-addressable full-page surface) is
 * fully owned by `core/platforms.ts` + `content/platformGate.ts`; nothing here duplicates
 * it.
 *
 * `HIDE_SURFACES` is deliberately EMPTY, matching the registry's own `hides: []` for
 * `reddit` (`core/platforms.ts`). ext-12 §E found only ONE selector worth citing for
 * Reddit at all — a generic whole-`main` container (`main > :not([data-sanersocialmedia-
 * widget])`) — and that describes a FULL-PAGE gate, exactly what the `home` gate already
 * covers via `initPlatformContentScript`'s URL matching. No durable, sourced selector for
 * a Reddit SUB-element (a comments-hider, a sidebar-hider, ...) turned up in that research
 * pass. Shipping a hide toggle backed by an invented selector would be a promise the code
 * does not keep — the registry's own `note` says so, and this file keeps that promise by
 * not inventing one.
 *
 * PURE + ZERO NETWORK, same discipline as `content/selectors.ts`.
 */

import type { HideSurfaceDef } from '../platformGate';

export const HIDE_SURFACES: HideSurfaceDef[] = [];
