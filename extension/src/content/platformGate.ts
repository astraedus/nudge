/**
 * Shared machinery for every non-YouTube platform content script (Instagram, TikTok, X,
 * Facebook, Reddit, LinkedIn). YouTube keeps its own hand-built hide code (`unhook.ts`)
 * because its Shorts/channel logic has YouTube-specific shape; the other six platforms
 * share the EXACT same shape — a set of boolean cosmetic hides plus zero or more
 * URL-addressable gates, resolved by the worker into a `SiteConfig` — so this module
 * builds that once instead of six times with six chances to drift (repo rule:
 * anti-duplication).
 *
 * The overlay itself (mount/teardown, mode copy, "I changed my mind", per-platform
 * element identity) lives in `content/overlay.ts`, a separate module so it can eventually
 * be shared with `youtube.ts` too (see that file's doc comment for why that migration is
 * a deliberate follow-up, not part of this change).
 *
 * This file's own two responsibilities:
 *  1. `applyPlatformHides` — the same reversible, class-toggle, "recompute the full
 *     desired set every pass" pattern as `unhook.ts`'s `applyHideToggles`, generalized
 *     over an arbitrary list of named hide surfaces instead of YouTube's four hardcoded
 *     ones. Per ext-13 §4 ("hide via a per-feature CSS class, never a shared one"), EVERY
 *     hide id gets its OWN class (`hideClassFor`) — sharing one class across features is
 *     the documented clobber bug (extension/CLAUDE.md "Each hiding feature owns its own
 *     CSS class").
 *  2. `initPlatformContentScript` — the generic controller: fetch `SiteConfig` from the
 *     worker once (+ on `storage.onChanged`), apply hides every pass, and gate the
 *     URL-addressable surface the current page landed on (via `core/platforms.ts`'s
 *     `gateForUrl`) with an in-page interstitial, using `spaNav.ts` for navigation
 *     detection. A gate's "satisfied" state is per matched `GateId`, not per exact URL —
 *     mirroring YouTube's Shorts grant (`youtube.ts`: "swiping within Shorts is one
 *     continuous session, not a fresh decision each time") — so paging through many
 *     Reels/For You items after completing one pause does not re-gate every single item.
 *
 * **Correctness property, stated explicitly because a wrong-route verdict is the single
 * worst failure mode a blocker can have (a false "this is blocked" on content the user
 * allowed)**: the gate shown on any given pass is ALWAYS resolved against the URL read at
 * the START of that same `refresh()` call, never against a URL captured earlier or a
 * config fetch that may still be in flight. `refresh()` never awaits anything — it reads
 * `config` (whatever was last successfully fetched) and `currentUrl()` (always live)
 * synchronously and mounts/dismisses the overlay in the same tick `spaNav.ts` calls it. A
 * `GET_SITE_CONFIG` round trip only happens in `reload()` (once at start-up, and again on
 * `storage.onChanged`), never per navigation — so there is no window where a navigation
 * is waiting on a config fetch it triggered. Before the FIRST fetch resolves, `config` is
 * `IDLE_SITE_CONFIG` (`gates: []`), which resolves no gate at all — the fail-safe
 * direction (withhold, don't block) rather than gating on stale defaults.
 *
 * PURE DOM + `chrome.*` reads only through the same soft-optional pattern `youtube.ts`
 * uses, so every DOM-touching export is unit-testable with jsdom fixtures and no browser.
 */

import { gateForUrl, type GateId, type Platform } from '../core/platforms';
import type { ResolvedGate, SiteConfig } from '../core/protocol';
import { send } from '../ui/rpc';
import {
  assignSafeLocation,
  createGateOverlay,
  isOwnOverlay,
  overlayIdFor,
  holdMediaPaused,
  type GateCopy,
  type MediaHold,
  type OverlayHandle,
} from './overlay';
import { createItemReporter, type SendItemView } from './itemCounter';
import { observeNavigation } from './spaNav';
import { defaultWarn, queryWithFallback, type SelectorRule, type WarnFn } from './selectors';

// Re-exported for convenience/back-compat with call sites and tests that import overlay
// concerns from here — the source of truth for all of them is now `overlay.ts`.
export { overlayIdFor, type GateCopy, type OverlayHandle };

/**
 * `chrome` is absent in unit tests and in any non-extension context. Reading it through
 * `globalThis` keeps a missing global a soft `undefined` instead of a ReferenceError.
 */
export const chromeApi: typeof chrome | undefined = (globalThis as { chrome?: typeof chrome })
  .chrome;

/* ==================================================================== hides */

/** One hide surface: a named boolean toggle plus the selector chain(s) that find it. */
export interface HideSurfaceDef {
  /** The `HideId` this surface implements — also the CSS-class suffix (`hideClassFor`). */
  id: string;
  chain: readonly SelectorRule[];
  /**
   * Hide EVERY rung that matches instead of stopping at the first (see `unhook.ts`'s
   * `matchAll` for the "the end-screen grid and creator cards coexist" precedent). Default
   * false: rungs are alternative ways to find the SAME element, most-specific first.
   */
  matchAll?: boolean;
}

/** The class toggled for one hide id. Distinct per id so turning one off never un-hides another. */
export function hideClassFor(hideId: string): string {
  return `nudge-hide-${hideId}`;
}

export interface ApplyHidesResult {
  hidden: number;
  revealed: number;
  degradedSurfaces: string[];
}

/**
 * Add/remove each hide surface's own class across `root`.
 *
 * Reversible and independent by construction, same shape as `unhook.ts`'s
 * `applyHideToggles`: each pass computes the full "should be hidden right now" set for
 * EACH surface independently (its own toggle in `hides`, gated additionally by the
 * site-wide `enabled`), applies that surface's class to that set, then sweeps every
 * element wearing that class and reveals anything not in the desired set. A surface that
 * is off (or the extension is globally disabled) never enters its desired set, so
 * anything it previously hid is revealed on the very next pass — no need to remember
 * what was hidden last time.
 *
 * `overlayId` (from `overlay.ts`'s `overlayIdFor(platform)`) is threaded through so a
 * selector chain can never hide the running platform's own interstitial, whatever it
 * happens to match.
 */
export function applyPlatformHides(
  root: Document | Element,
  surfaces: readonly HideSurfaceDef[],
  hides: Partial<Record<string, boolean>>,
  enabled: boolean,
  overlayId: string,
  options: { warn?: WarnFn } = {},
): ApplyHidesResult {
  const { warn = defaultWarn } = options;
  const result: ApplyHidesResult = { hidden: 0, revealed: 0, degradedSurfaces: [] };

  for (const surface of surfaces) {
    const cls = hideClassFor(surface.id);
    const shouldBeOn = enabled && hides[surface.id] === true;
    const shouldHide = new Set<Element>();

    if (shouldBeOn) {
      let elements: Element[] = [];
      let usedFallback = false;
      if (surface.matchAll === true) {
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
        if (isOwnOverlay(element, overlayId)) continue;
        shouldHide.add(element);
      }
    }

    for (const element of shouldHide) {
      if (!element.classList.contains(cls)) {
        element.classList.add(cls);
        result.hidden += 1;
      }
    }
    for (const element of Array.from(root.querySelectorAll(`.${cls}`))) {
      if (!shouldHide.has(element)) {
        element.classList.remove(cls);
        result.revealed += 1;
      }
    }
  }

  return result;
}

/* ===================================================================== gate */

/** Which resolved gate (if any) of `platform` the given URL lands on right now, or null. */
export function resolveActiveGate(
  platform: Platform,
  gates: readonly ResolvedGate[],
  url: string,
): ResolvedGate | null {
  const gateId = gateForUrl(platform, url);
  if (gateId === null) return null;
  return gates.find((gate) => gate.id === gateId) ?? null;
}

/* ============================================================== controller */

export interface PlatformController {
  /** Force a re-check (also what SPA-nav listeners call). */
  refresh: () => void;
  /** Re-read config from the service worker, then refresh. */
  reload: () => Promise<void>;
  /** Detach every listener, timer and overlay. */
  stop: () => void;
}

export interface PlatformScriptOptions {
  platform: Platform;
  hideSurfaces: readonly HideSurfaceDef[];
  /** Copy for the gate this platform's URL currently landed on. */
  gateCopy: (gateId: GateId) => GateCopy;
  /**
   * Where "I changed my mind" sends the user — always a safe, non-gated page on this site.
   *
   * A property of the PLATFORM, not of the copy: it is the same destination whichever gate
   * fired, and it is the caller's business rather than the overlay's (the overlay never
   * navigates; it calls `onBail` and lets whoever mounted it decide). Required, so a new
   * platform cannot ship a bail button that goes nowhere.
   */
  bailUrl: string;
  /** A site-own post-navigation event name, if one exists (see spaNav.ts). Most sites: none. */
  navEvent?: string;
  pollMs?: number;
  mutationDebounceMs?: number;
}

/** Everything off. What every content script assumes until the worker answers. */
export const IDLE_SITE_CONFIG: SiteConfig = {
  enabled: false,
  domain: '',
  platform: null,
  siteMode: 'ALLOW',
  siteDelaySeconds: 15,
  siteApplies: false,
  siteLimitReached: false,
  grayscale: false,
  gates: [],
  hides: {},
  youtube: null,
};

/**
 * Wire everything up on a real (or fixture) page. This is the ONE implementation shared
 * by Instagram/TikTok/X/Facebook/Reddit/LinkedIn — each platform module supplies its own
 * `hideSurfaces` and `gateCopy` and calls this rather than re-deriving the overlay/hide/
 * nav-detection wiring six separate times.
 */
export function initPlatformContentScript(
  options: PlatformScriptOptions,
  doc: Document = document,
  fetchConfig: (url: string) => Promise<SiteConfig> = (url) =>
    send({ type: 'GET_SITE_CONFIG', url }),
  sendItemView?: SendItemView,
): PlatformController {
  const overlayId = overlayIdFor(options.platform);
  // COUNT budgets (v0.3). Wired HERE, in the one shared controller, rather than in each
  // platform module: every platform then gets counting the moment its registry entry
  // grows `itemPaths`, with no per-platform code and no second navigation loop. The
  // reporter only reports; the worker owns the tally (see content/itemCounter.ts).
  const itemReporter = createItemReporter(options.platform, sendItemView);
  let config: SiteConfig = IDLE_SITE_CONFIG;
  let overlay: OverlayHandle | null = null;
  /** Held only while an interstitial is up; see `holdMediaPaused`. */
  let mediaHold: MediaHold | null = null;
  /** The gate id a completed pause was granted for. Reset the moment no gate applies. */
  let satisfiedGateId: GateId | null = null;
  let stopped = false;

  function currentUrl(): string {
    return doc.location?.href ?? '';
  }

  function teardownOverlay(): void {
    overlay?.dispose();
    overlay = null;
    // Release BEFORE the overlay is forgotten: a hold that outlives its interstitial would
    // keep the page's media un-playable with nothing on screen to explain why.
    mediaHold?.release();
    mediaHold = null;
  }

  function refresh(): void {
    if (stopped) return;
    // Read fresh, synchronously, on every call — this is what makes the "never gate on a
    // stale route" property (see module doc comment) hold: there is no cached URL here to
    // go stale, and no await between reading it and using it below.
    const url = currentUrl();

    // Reported BEFORE the gate is resolved, and independently of it: an item view is an
    // observation about where the page is, not a consequence of any verdict. Reporting it
    // only when a gate applied would mean the count stopped advancing the moment the gate
    // started applying, so a spent budget could never be re-measured and the stats line
    // would freeze at exactly the number the user is most interested in.
    itemReporter.report(url);

    // The passive layer runs on every pass, independent of any gate: hiding must be
    // correct even while an interstitial is up, and must keep reconciling as the site
    // lazy-loads more content in (the mutation-debounced re-apply pass calls this too).
    applyPlatformHides(doc, options.hideSurfaces, config.hides, config.enabled, overlayId);

    const gate = config.enabled ? resolveActiveGate(options.platform, config.gates, url) : null;

    if (gate === null) {
      // Left every gated surface (or nothing applies) — the next gate hit is a fresh
      // decision, exactly like Shorts resetting `gateSatisfied` on leaving the player.
      satisfiedGateId = null;
      teardownOverlay();
      return;
    }
    if (gate.mode === 'ALLOW' || gate.id === satisfiedGateId) {
      teardownOverlay();
      return;
    }
    if (overlay?.element.isConnected) return;

    teardownOverlay();
    mediaHold = holdMediaPaused(doc);

    const mode = gate.mode;
    const copy = options.gateCopy(gate.id);
    overlay = createGateOverlay(doc, overlayId, mode, gate.delaySeconds, copy, {
      onComplete: () => {
        satisfiedGateId = gate.id;
        teardownOverlay();
      },
      onBail: () => {
        teardownOverlay();
        assignSafeLocation(doc, options.bailUrl);
      },
    });
    doc.body.append(overlay.element);
  }

  async function reload(): Promise<void> {
    try {
      config = await fetchConfig(currentUrl());
    } catch {
      // The service worker can be asleep or mid-reload. Stay in the last known state
      // rather than failing open on a transient messaging error.
      return;
    }
    refresh();
  }

  const nav = observeNavigation({
    doc,
    navEvent: options.navEvent,
    pollMs: options.pollMs,
    mutationDebounceMs: options.mutationDebounceMs,
    onNavigate: refresh,
    onMutate: refresh,
  });

  const onStorageChanged = (): void => {
    void reload();
  };
  chromeApi?.storage?.onChanged?.addListener(onStorageChanged);

  void reload();

  return {
    refresh,
    reload,
    stop: () => {
      stopped = true;
      nav.dispose();
      chromeApi?.storage?.onChanged?.removeListener(onStorageChanged);
      teardownOverlay();
    },
  };
}
