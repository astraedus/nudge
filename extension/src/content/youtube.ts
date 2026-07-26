/**
 * The YouTube content script's logic. Importable and unit-testable WITHOUT a browser:
 * every DOM-touching export takes its root/config as an argument, and only
 * `initYoutubeContentScript()` reaches for `chrome.*` or global listeners.
 *
 * Why a content script exists at all: declarativeNetRequest cannot see YouTube's
 * client-side navigation. Swiping from one Short to the next never fires a network
 * request DNR can match, so a rule-only blocker just... stops blocking (ext-03 §2,
 * confirmed by LeechBlockNG#17). The SPA detection below is that gap, closed.
 *
 * Two jobs:
 *  1. HIDE Shorts surfaces (`hideShortsShelf`) by toggling a CSS class — never by
 *     removing nodes, so flipping the setting off restores the page instantly and we
 *     never fight YouTube's virtual DOM over ownership of an element we deleted.
 *  2. GATE the `/shorts/*` player (`shortsMode` != 'ALLOW') with an in-page interstitial
 *     overlay. We do NOT navigate away: a redirect races YouTube's router, and the whole
 *     product thesis is friction-with-a-choice, not a wall.
 *
 * `shortsMode` arrives already resolved by the service worker (the 'INHERIT' case is
 * decided there by running the block engine against the youtube.com site rule) — this
 * module must never re-implement inheritance.
 */

import type { YoutubeConfig } from '../core/protocol';
import { MODE_LABELS } from '../core/types';
import { send } from '../ui/rpc';
import { applyChannelFilter, applyGrayColor, watchChannelVerdict } from './channelFilter';
import { channelKey, SETTLE_RECHECK_MS } from '../core/channelFreshness';
import { detectWatchChannel } from './channelDetection';
import { applyAutoplayOff, applyHideToggles } from './unhook';
import {
  HIDDEN_CLASS,
  NUDGE_OVERLAY_ID,
  SHORTS_FALLBACK_SURFACE,
  SHORTS_PLAYER_CONTAINERS,
  SHORTS_SURFACES,
  defaultWarn,
  pageTypeFor,
  queryWithFallback,
  type WarnFn,
  type YoutubePageType,
} from './selectors';

/** Debounce for DOM re-checks. Long enough to batch YouTube's mutation storms. */
export const DOM_DEBOUNCE_MS = 250;
/** Safety-net poll for the case where every event AND the observer miss (ext-03 §2). */
export const SAFETY_NET_MS = 1000;
/** Breathing cycle: 4s in, 4s out (Android parity). */
export const BREATH_IN_MS = 4000;
export const BREATH_OUT_MS = 4000;

/** Exact Android wording. Do not "improve" this string. */
export const BAIL_LABEL = 'I changed my mind';
/** Where the bail button sends the user. */
export const BAIL_URL = 'https://www.youtube.com/';

/**
 * `chrome` is absent in unit tests and in any non-extension context. Reading it through
 * `globalThis` keeps a missing global a soft `undefined` instead of a ReferenceError.
 */
const chromeApi: typeof chrome | undefined = (globalThis as { chrome?: typeof chrome })
  .chrome;

/** The URL of whatever document `root` belongs to. */
function documentUrl(root: Document | Element): string {
  const doc = root.ownerDocument ?? (root as Document);
  return doc.location?.href ?? '';
}

export interface HidingResult {
  /** Elements newly given the hidden class this pass. */
  hidden: number;
  /** Elements whose hidden class was removed this pass. */
  revealed: number;
  /** Surfaces that only matched via their generic fallback selector. */
  degradedSurfaces: string[];
}

/**
 * Add/remove the hidden class across every Shorts surface for this page type.
 *
 * Reversible by construction: when hiding is off (or Nudge is globally off) we sweep
 * `.nudge-hidden` and clear it, so a toggle flip restores the page without a reload.
 */
export function applyShortsHiding(
  root: Document | Element,
  config: Pick<YoutubeConfig, 'enabled' | 'hideShortsShelf'>,
  options: { pageType?: YoutubePageType; url?: string; warn?: WarnFn } = {},
): HidingResult {
  const { warn = defaultWarn } = options;
  const result: HidingResult = { hidden: 0, revealed: 0, degradedSurfaces: [] };

  if (!config.enabled || !config.hideShortsShelf) {
    for (const element of Array.from(root.querySelectorAll(`.${HIDDEN_CLASS}`))) {
      element.classList.remove(HIDDEN_CLASS);
      result.revealed += 1;
    }
    return result;
  }

  const pageType = options.pageType ?? pageTypeFor(options.url ?? documentUrl(root));
  const covered: Element[] = [];

  function hide(elements: readonly Element[]): number {
    let count = 0;
    for (const element of elements) {
      // Never hide our own overlay, whatever a selector claims.
      if (element.id === NUDGE_OVERLAY_ID || element.closest(`#${NUDGE_OVERLAY_ID}`)) continue;
      covered.push(element);
      if (element.classList.contains(HIDDEN_CLASS)) continue;
      element.classList.add(HIDDEN_CLASS);
      count += 1;
    }
    return count;
  }

  for (const surface of SHORTS_SURFACES[pageType]) {
    const { elements, usedFallback } = queryWithFallback(root, surface.chain, {
      surfaceId: surface.id,
      warn,
    });
    if (usedFallback) result.degradedSurfaces.push(surface.id);
    result.hidden += hide(elements);
  }

  // The catch-all, last. It matches every anchor pointing at a Short — including ones the
  // specific surfaces already swallowed — so we only treat the leftovers as evidence, and
  // only warn if there ARE leftovers. That is the difference between "YouTube renamed a
  // wrapper" (real news) and "this page has no Shorts shelf" (Tuesday).
  let deferredWarning: string | null = null;
  const catchAll = queryWithFallback(root, SHORTS_FALLBACK_SURFACE.chain, {
    surfaceId: SHORTS_FALLBACK_SURFACE.id,
    warn: (message) => {
      deferredWarning = message;
    },
  });
  const uncovered = catchAll.elements.filter(
    (element) => !covered.some((seen) => seen === element || seen.contains(element)),
  );
  if (uncovered.length > 0) {
    result.hidden += hide(uncovered);
    result.degradedSurfaces.push(SHORTS_FALLBACK_SURFACE.id);
    if (deferredWarning !== null) warn(deferredWarning);
  }

  return result;
}

/** True when this URL is a Shorts surface AND the resolved mode is not 'ALLOW'. */
export function shouldGateShorts(
  url: string,
  config: Pick<YoutubeConfig, 'enabled' | 'shortsMode'>,
): boolean {
  if (!config.enabled) return false;
  if (config.shortsMode === 'ALLOW') return false;
  return pageTypeFor(url) === 'shorts';
}

/* ------------------------------------------------------------------ overlay */

interface OverlayHandle {
  element: HTMLElement;
  /** Clears every timer this overlay owns. */
  dispose: () => void;
}

/** Copy for the interstitial, mode by mode. Android microcopy parity. */
function gateCopy(mode: Exclude<YoutubeConfig['shortsMode'], 'ALLOW'>): {
  title: string;
  subtitle: string;
} {
  switch (mode) {
    case 'HARD_BLOCK':
      return {
        title: 'Shorts is blocked',
        subtitle: 'You asked Nudge to keep you out of here. Still true?',
      };
    case 'BREATHING':
      return { title: 'Take a breath', subtitle: 'Follow the circle. Shorts will wait.' };
    case 'DELAY':
    default:
      return { title: 'Hold on a second', subtitle: 'Still want to watch Shorts?' };
  }
}

/**
 * Build the interstitial. Markup is deliberately tiny and self-contained: styles live in
 * youtube.css (injected via the manifest, so it is on the page before YouTube paints).
 *
 * `onComplete` fires when a Delay/Breathing pause elapses. HARD_BLOCK never calls it —
 * its only exit is the bail button.
 */
export function createShortsOverlay(
  doc: Document,
  config: Pick<YoutubeConfig, 'shortsMode' | 'shortsDelaySeconds'>,
  handlers: { onComplete: () => void; onBail: () => void },
  /**
   * Overrides for a gate that is not about Shorts. The channel gate is the same
   * interstitial with different words, so it reuses this rather than growing a second
   * near-identical overlay implementation that could drift.
   */
  override: { title?: string; subtitle?: string; ruleLabel?: string } = {},
): OverlayHandle {
  const mode = config.shortsMode === 'ALLOW' ? 'HARD_BLOCK' : config.shortsMode;
  const base = gateCopy(mode);
  const copy = {
    title: override.title ?? base.title,
    subtitle: override.subtitle ?? base.subtitle,
  };
  const timers: number[] = [];

  const overlay = doc.createElement('div');
  overlay.id = NUDGE_OVERLAY_ID;
  overlay.className = 'nudge-overlay';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', `Nudge — ${MODE_LABELS[mode]}`);

  const card = doc.createElement('div');
  card.className = 'nudge-overlay__card';

  const title = doc.createElement('h1');
  title.className = 'nudge-overlay__title';
  title.textContent = copy.title;

  const subtitle = doc.createElement('p');
  subtitle.className = 'nudge-overlay__subtitle';
  subtitle.textContent = copy.subtitle;

  card.append(title, subtitle);

  if (mode === 'DELAY') {
    const seconds = Math.max(1, Math.round(config.shortsDelaySeconds));
    const counter = doc.createElement('div');
    counter.className = 'nudge-overlay__count';
    counter.textContent = String(seconds);
    card.append(counter);

    let remaining = seconds;
    const tick = doc.defaultView?.setInterval(() => {
      remaining -= 1;
      counter.textContent = String(Math.max(0, remaining));
      if (remaining <= 0) {
        clearAll();
        handlers.onComplete();
      }
    }, 1000);
    if (tick !== undefined) timers.push(tick);
  } else if (mode === 'BREATHING') {
    const circle = doc.createElement('div');
    circle.className = 'nudge-overlay__breath';
    const phase = doc.createElement('div');
    phase.className = 'nudge-overlay__phase';
    phase.textContent = 'Breathe in';
    const remainingLabel = doc.createElement('div');
    remainingLabel.className = 'nudge-overlay__remaining';

    const totalMs = Math.max(1, Math.round(config.shortsDelaySeconds)) * 1000;
    const startedAt = Date.now();
    remainingLabel.textContent = `${Math.ceil(totalMs / 1000)}s remaining`;

    const progress = doc.createElement('div');
    progress.className = 'nudge-overlay__progress';
    const bar = doc.createElement('div');
    bar.className = 'nudge-overlay__bar';
    progress.append(bar);

    card.append(circle, phase, progress, remainingLabel);

    const cycle = BREATH_IN_MS + BREATH_OUT_MS;
    const tick = doc.defaultView?.setInterval(() => {
      const elapsed = Date.now() - startedAt;
      const left = Math.max(0, totalMs - elapsed);
      remainingLabel.textContent = `${Math.ceil(left / 1000)}s remaining`;
      bar.style.width = `${Math.min(100, (elapsed / totalMs) * 100)}%`;
      const inhaling = elapsed % cycle < BREATH_IN_MS;
      phase.textContent = inhaling ? 'Breathe in' : 'Breathe out';
      circle.classList.toggle('nudge-overlay__breath--in', inhaling);
      if (left <= 0) {
        clearAll();
        handlers.onComplete();
      }
    }, 200);
    if (tick !== undefined) timers.push(tick);
  }

  const bail = doc.createElement('button');
  bail.type = 'button';
  bail.className = 'nudge-overlay__bail';
  bail.textContent = BAIL_LABEL;
  bail.addEventListener('click', () => {
    clearAll();
    handlers.onBail();
  });
  card.append(bail);

  const footer = doc.createElement('p');
  footer.className = 'nudge-overlay__footer';
  footer.textContent = `Rule: ${override.ruleLabel ?? 'YouTube Shorts'} · ${MODE_LABELS[mode]}`;
  card.append(footer);

  overlay.append(card);

  function clearAll(): void {
    for (const id of timers.splice(0)) doc.defaultView?.clearInterval(id);
  }

  return {
    element: overlay,
    dispose: () => {
      clearAll();
      overlay.remove();
    },
  };
}

/** Anchor the overlay over the player (falls back to <body>, which still covers the view). */
function overlayHost(doc: Document): Element {
  const { elements } = queryWithFallback(doc, SHORTS_PLAYER_CONTAINERS, {
    surfaceId: 'shorts-player',
    // The `body` rung is a legitimate anchor, not DOM churn — don't cry wolf.
    warn: () => {},
  });
  return elements[0] ?? doc.body;
}

/** Pause any playing media so the interstitial isn't just a lid over a running video. */
function pauseMedia(doc: Document): void {
  for (const video of Array.from(doc.querySelectorAll('video'))) {
    try {
      (video as HTMLVideoElement).pause();
    } catch {
      // jsdom and some embeds throw on pause(); the overlay still stands.
    }
  }
}

/* --------------------------------------------------------------- controller */

export interface YoutubeController {
  /** Force a re-check (also what the SPA listeners call). */
  refresh: () => void;
  /** Re-read config from the service worker, then refresh. */
  reload: () => Promise<void>;
  /** Detach every listener, timer and overlay. */
  stop: () => void;
}

/** Everything off. What the script assumes until the worker answers. */
const IDLE_CONFIG: YoutubeConfig = {
  enabled: false,
  hideShortsShelf: false,
  shortsMode: 'ALLOW',
  shortsDelaySeconds: 15,
  channelMode: 'OFF',
  channels: [],
  channelBlockMode: 'DELAY',
  channelDelaySeconds: 15,
  grayScreen: false,
  hideHomeFeed: false,
  hideSidebarRecs: false,
  hideEndScreen: false,
  hideComments: false,
  disableAutoplay: false,
};

/**
 * Wire everything up on a real page.
 *
 * 3-layer SPA navigation detection (ext-03 §2 — two independent OSS blockers converged
 * on exactly this, and one-layer designs are the documented failure):
 *   1. `yt-navigate-finish` on document — YouTube's own post-route-change event (primary)
 *   2. `popstate` — back/forward, which YouTube does not always announce (secondary)
 *   3. debounced MutationObserver + a slow interval — the safety net for lazy-loaded
 *      feed cards and for the day YouTube renames its event.
 *
 * The observer callback does nothing but schedule: heavy work inside a mutation callback
 * on YouTube is a guaranteed jank complaint (ext-03 §4).
 */
export function initYoutubeContentScript(
  doc: Document = document,
  fetchConfig: () => Promise<YoutubeConfig> = () => send({ type: 'GET_YOUTUBE_CONFIG' }),
): YoutubeController {
  const view = doc.defaultView;
  let config: YoutubeConfig = IDLE_CONFIG;
  let overlay: OverlayHandle | null = null;
  /** Set once a pause is completed; cleared as soon as we leave the Shorts surface. */
  let gateSatisfied = false;
  /** The watch URL whose channel gate the user has already completed, if any. */
  let channelGateSatisfiedFor: string | null = null;
  /** Identity of the channel confirmed for the video BEFORE the latest navigation. */
  let previousChannelKey: string | null = null;
  /** When the latest navigation happened, for the settle window. */
  let navAt = 0;
  /** Storm-proof re-checks scheduled after a navigation. */
  const settleTimers: number[] = [];
  let lastUrl = doc.location?.href ?? '';
  let debounceTimer: number | undefined;
  let stopped = false;

  function currentHref(): string {
    return doc.location?.href ?? '';
  }

  function teardownOverlay(): void {
    overlay?.dispose();
    overlay = null;
  }

  function refresh(): void {
    if (stopped) return;

    const url = currentHref();
    if (url !== lastUrl) {
      // Remember what we were confident about BEFORE the hop: while the new page's byline
      // still reports that same channel we cannot tell "stale" from "same channel again",
      // so the verdict is withheld (core/channelFreshness.ts).
      previousChannelKey = channelKey(detectWatchChannel(doc, { url: lastUrl }));
      lastUrl = url;
      navAt = Date.now();
      scheduleSettleChecks();
      // Leaving Shorts resets the grant - coming back should cost you the pause again.
      if (pageTypeFor(url) !== 'shorts') gateSatisfied = false;
    }
    const msSinceNav = navAt === 0 ? Number.POSITIVE_INFINITY : Date.now() - navAt;

    // The passive layers run on EVERY pass, before and independently of any gate: hiding,
    // feed filtering and the colour flip must be correct even while an interstitial is up,
    // and must keep reconciling as YouTube lazy-loads more cards in.
    applyShortsHiding(doc, config, { url });
    applyHideToggles(doc, config, { url });
    applyChannelFilter(doc, config);
    applyGrayColor(doc, config, { url, previousKey: previousChannelKey, msSinceNav });
    if (config.disableAutoplay) applyAutoplayOff(doc, config);

    const shortsGated = shouldGateShorts(url, config) && !gateSatisfied;

    // The channel gate is scoped to the exact URL that satisfied it, so completing a pause
    // on one video does not buy access to the next. (Shorts keeps its own coarser grant:
    // swiping within Shorts is one continuous session, not a fresh decision each time.)
    const channelVerdict =
      channelGateSatisfiedFor === url
        ? null
        : watchChannelVerdict(doc, config, {
            url,
            previousKey: previousChannelKey,
            msSinceNav,
          });
    const channelGate = channelVerdict?.action === 'BLOCK' ? channelVerdict : null;

    if (!shortsGated && channelGate === null) {
      teardownOverlay();
      return;
    }
    if (overlay?.element.isConnected) return;

    teardownOverlay();
    pauseMedia(doc);

    if (shortsGated) {
      overlay = createShortsOverlay(doc, config, {
        onComplete: () => {
          gateSatisfied = true;
          teardownOverlay();
        },
        onBail: () => {
          teardownOverlay();
          doc.location.assign(BAIL_URL);
        },
      });
    } else if (channelGate !== null) {
      const gatedUrl = url;
      overlay = createShortsOverlay(
        doc,
        { shortsMode: channelGate.mode, shortsDelaySeconds: config.channelDelaySeconds },
        {
          onComplete: () => {
            channelGateSatisfiedFor = gatedUrl;
            teardownOverlay();
          },
          onBail: () => {
            teardownOverlay();
            doc.location.assign(BAIL_URL);
          },
        },
        {
          title: 'This channel is off your list',
          subtitle:
            config.channelMode === 'WHITELIST'
              ? 'You chose to watch only channels you picked. Still want this one?'
              : 'You asked Nudge to keep you away from this channel.',
          ruleLabel: 'YouTube channels',
        },
      );
    }

    if (overlay !== null) overlayHost(doc).append(overlay.element);
  }

  /**
   * Re-check on our OWN timers after a navigation.
   *
   * The debounced observer alone is not enough: YouTube's post-navigation mutation storm
   * keeps resetting the 250ms debounce, so the corrective pass can be starved for seconds, 
   * that starvation is what stretched the settle window out to ~5s in live QA. These fire
   * regardless of page mutation, so the verdict is always re-evaluated on schedule.
   */
  function scheduleSettleChecks(): void {
    if (!view) return;
    for (const id of settleTimers.splice(0)) view.clearTimeout(id);
    for (const delay of SETTLE_RECHECK_MS) {
      settleTimers.push(view.setTimeout(() => refresh(), delay));
    }
  }

  function scheduleRefresh(): void {
    if (stopped || !view) return;
    if (debounceTimer !== undefined) view.clearTimeout(debounceTimer);
    debounceTimer = view.setTimeout(() => {
      debounceTimer = undefined;
      refresh();
    }, DOM_DEBOUNCE_MS);
  }

  async function reload(): Promise<void> {
    try {
      config = await fetchConfig();
    } catch {
      // The service worker can be asleep or mid-reload. Stay in the last known state
      // rather than failing open on a transient messaging error.
      return;
    }
    refresh();
  }

  /**
   * Navigation is handled IMMEDIATELY, not through the debounce.
   *
   * `scheduleRefresh()` was the cold-hop bug (live QA, 2026-07-26): the very first refresh
   * after a full page load is what NOTICES the url changed and starts the settle machinery,
   * and routing it through the 250ms debounce meant YouTube's post-nav mutation storm kept
   * resetting it, so for ~2s nothing ran at all and the page held the PREVIOUS video's
   * verdict, colour and all. A navigation is a discrete, known-important event; it should
   * never queue behind page churn. The debounced pass still follows for the DOM settling
   * after it.
   */
  const onNavigate = (): void => {
    refresh();
    scheduleRefresh();
  };
  doc.addEventListener('yt-navigate-finish', onNavigate);
  view?.addEventListener('popstate', onNavigate);

  const observer = view ? new view.MutationObserver(() => scheduleRefresh()) : null;
  observer?.observe(doc.documentElement, { childList: true, subtree: true });

  const safetyNet = view?.setInterval(() => {
    if (currentHref() !== lastUrl) refresh();
    else scheduleRefresh();
  }, SAFETY_NET_MS);

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
      doc.removeEventListener('yt-navigate-finish', onNavigate);
      view?.removeEventListener('popstate', onNavigate);
      observer?.disconnect();
      if (safetyNet !== undefined) view?.clearInterval(safetyNet);
      if (debounceTimer !== undefined) view?.clearTimeout(debounceTimer);
      for (const id of settleTimers.splice(0)) view?.clearTimeout(id);
      chromeApi?.storage?.onChanged?.removeListener(onStorageChanged);
      teardownOverlay();
    },
  };
}
