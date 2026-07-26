/**
 * "Unhook parity" — the independent YouTube hide toggles (ext-03 §5): Home Feed, Sidebar
 * Recommendations, End Screen, Comments, plus best-effort Autoplay-off. Sibling module to
 * `youtube.ts`'s Shorts hiding, same conventions: pure functions over `root`/`config` so
 * this is unit-testable without a browser, class-toggle hiding (never node removal) so a
 * settings flip restores the page instantly, and we never touch our own overlay.
 *
 * Kept as its OWN module rather than folded into `youtube.ts` because these toggles are
 * independent of Shorts hiding and of each other — they can be reasoned about, tested and
 * (eventually) wired into the content script separately from the Shorts gate/hide logic.
 */

import type { YoutubeConfig } from '../core/protocol';
import {
  AUTOPLAY_TOGGLE,
  NUDGE_OVERLAY_ID,
  defaultWarn,
  pageTypeFor,
  queryWithFallback,
  HIDE_SURFACES,
  type WarnFn,
  type YoutubePageType,
} from './selectors';

/**
 * The class `applyHideToggles` toggles. DELIBERATELY DISTINCT from `HIDDEN_CLASS`
 * (selectors.ts), which Shorts hiding owns.
 *
 * If both features shared one class, they would clobber each other's state: sweeping
 * "everything with the shared class" when Shorts hiding turns off would also un-hide
 * comments/end-screen/etc. that this module hid for an unrelated reason (and vice versa).
 * Two features, two classes, two independent on/off lifecycles.
 */
export const UNHOOK_HIDDEN_CLASS = 'nudge-unhook-hidden';

export interface HideResult {
  /** Elements newly given the hidden class this pass. */
  hidden: number;
  /** Elements whose hidden class was removed this pass. */
  revealed: number;
  /** Surfaces that only matched via their generic fallback selector. */
  degradedSurfaces: string[];
}

/** The four independent hide toggles, plus the master switch. */
type HideToggleConfig = Pick<
  YoutubeConfig,
  'enabled' | 'hideHomeFeed' | 'hideSidebarRecs' | 'hideEndScreen' | 'hideComments'
>;

/** The URL of whatever document `root` belongs to. Mirrors youtube.ts's private helper. */
function documentUrl(root: Document | Element): string {
  const doc = root.ownerDocument ?? (root as Document);
  return doc.location?.href ?? '';
}

/**
 * Add/remove `UNHOOK_HIDDEN_CLASS` across every enabled, page-applicable hide surface.
 *
 * Reversible and independent by construction: each pass computes the FULL desired set of
 * "should be hidden right now" elements (only surfaces whose `pages` include the current
 * page type AND whose toggle is on in `config`), applies the class to that set, then sweeps
 * every element currently wearing the class and removes it from anything NOT in the desired
 * set. That sweep is what makes "flip a toggle off" and "enabled: false" both self-correcting
 * without needing to remember what was hidden last time — a surface that is off (or off the
 * current page, or globally disabled) simply never enters the desired set, so anything it
 * previously hid gets revealed on the very next pass.
 */
export function applyHideToggles(
  root: Document | Element,
  config: HideToggleConfig,
  options: { pageType?: YoutubePageType; url?: string; warn?: WarnFn } = {},
): HideResult {
  const { warn = defaultWarn } = options;
  const result: HideResult = { hidden: 0, revealed: 0, degradedSurfaces: [] };
  const pageType = options.pageType ?? pageTypeFor(options.url ?? documentUrl(root));

  const shouldHide = new Set<Element>();

  if (config.enabled) {
    for (const surface of HIDE_SURFACES) {
      // Page-type scoping: don't even query a surface that doesn't live on this page —
      // a watch-only surface (sidebar recs, comments, end screen) must never be considered
      // on home/search/etc., whatever a selector might coincidentally match there.
      if (!surface.pages.includes(pageType)) continue;
      if (!config[surface.toggle]) continue;

      // `matchAll` surfaces are made of DIFFERENT elements that appear together (the
      // end-screen grid AND the creator's end-cards), so every rung has to be collected.
      // The default first-match-wins is for chains whose rungs are alternative ways to find
      // the same element.
      let elements: Element[];
      let usedFallback = false;
      if (surface.matchAll === true) {
        elements = [];
        for (const rule of surface.chain) {
          const rung = queryWithFallback(root, [rule], { surfaceId: surface.id, warn });
          elements.push(...rung.elements);
          usedFallback ||= rung.usedFallback;
        }
      } else {
        const found = queryWithFallback(root, surface.chain, { surfaceId: surface.id, warn });
        elements = found.elements;
        usedFallback = found.usedFallback;
      }
      if (usedFallback) result.degradedSurfaces.push(surface.id);

      for (const element of elements) {
        // Never hide our own overlay, whatever a selector claims — either because a
        // selector matched the overlay itself, or because it matched something the
        // overlay happens to contain.
        if (element.id === NUDGE_OVERLAY_ID || element.closest(`#${NUDGE_OVERLAY_ID}`)) {
          continue;
        }
        shouldHide.add(element);
      }
    }
  }

  for (const element of shouldHide) {
    if (!element.classList.contains(UNHOOK_HIDDEN_CLASS)) {
      element.classList.add(UNHOOK_HIDDEN_CLASS);
      result.hidden += 1;
    }
  }

  for (const element of Array.from(root.querySelectorAll(`.${UNHOOK_HIDDEN_CLASS}`))) {
    if (!shouldHide.has(element)) {
      element.classList.remove(UNHOOK_HIDDEN_CLASS);
      result.revealed += 1;
    }
  }

  return result;
}

/**
 * Click the player's autoplay switch off, if it is currently on.
 *
 * UNLIKE `applyHideToggles`, this is a genuine DOM INTERACTION, not a hide: there is no
 * supported API to disable autoplay, so we simulate a click on the switch — and only when
 * `AUTOPLAY_TOGGLE` finds one with `aria-checked="true"` (see the chain's own doc comment in
 * selectors.ts). That asymmetry is what makes the operation idempotent by construction: we
 * only ever act on a switch that is currently ON, so a call can turn autoplay off but can
 * never turn it back on — there is no code path here that clicks an OFF switch.
 *
 * BEST-EFFORT, NOT A GUARANTEE: YouTube re-renders the player (SPA nav, ad breaks, etc.) and
 * can silently restore its own autoplay state at any time. This function does not track or
 * fight that — callers are expected to re-run it on every SPA navigation (the same
 * `yt-navigate-finish`/`popstate`/observer cadence `youtube.ts` already drives Shorts hiding
 * with), so a re-enabled switch just gets clicked off again on the next pass.
 *
 * Never throws: a missing switch is a no-op (returns `false`), and `click()` itself is
 * guarded — jsdom and YouTube's own custom-element upgrade races can both throw on a click
 * in odd cases, and that must never take down the caller.
 */
export function applyAutoplayOff(
  root: Document | Element,
  config: Pick<YoutubeConfig, 'enabled' | 'disableAutoplay'>,
  options: { warn?: WarnFn } = {},
): boolean {
  if (!config.enabled || !config.disableAutoplay) return false;

  const { warn = defaultWarn } = options;
  const { elements } = queryWithFallback(root, AUTOPLAY_TOGGLE, {
    surfaceId: 'autoplay-toggle',
    warn,
  });

  const toggle = elements[0];
  if (!toggle) return false;

  try {
    (toggle as HTMLElement).click();
    return true;
  } catch {
    return false;
  }
}
