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
 *  1. HIDE Shorts surfaces (`hides.shortsShelf`) by toggling a CSS class — never by
 *     removing nodes, so flipping the setting off restores the page instantly and we
 *     never fight YouTube's virtual DOM over ownership of an element we deleted.
 *  2. GATE the `/shorts/*` player (the `shorts` gate) and the `/watch` page (the channel
 *     list) with an in-page interstitial overlay. We do NOT navigate away: a redirect
 *     races YouTube's router, and the whole product thesis is friction-with-a-choice,
 *     not a wall.
 *
 * The interstitial itself, the media hold and the SPA-navigation detection are the SHARED
 * modules every platform uses (`content/overlay.ts`, `content/spaNav.ts`); this file is
 * YouTube's own part: which surfaces exist, what the copy says, and the channel-freshness
 * settle machinery below. Those three used to be private copies here and no longer are.
 *
 * EVERYTHING here arrives ALREADY RESOLVED, as a `SiteConfig` from the service worker:
 * the schedule, the budget and the applies-predicate have all been evaluated there, so a
 * gate's mode is the mode right now and nothing in this module re-derives it. That is what
 * keeps the overlay this script shows and the redirect DNR performs from ever disagreeing
 * about the same surface.
 */

import type { ResolvedGate, SiteConfig } from '../core/protocol';
import { gateDefinition } from '../core/platforms';
import type { BlockMode } from '../core/types';
import { send } from '../ui/rpc';
import { applyChannelFilter, applyGrayColor, watchChannelVerdict } from './channelFilter';
import { createChannelObserver } from './channelObserver';
import { channelKey, SETTLE_RECHECK_MS } from '../core/channelFreshness';
import type { WatchGateVerdict } from '../core/channels';
import { detectWatchChannel } from './channelDetection';
import { applyAutoplayOff, applyHideToggles } from './unhook';
import {
  assignSafeLocation,
  bailAwayFromGate,
  createGateOverlay,
  holdMediaPaused,
  type GateCopy,
  type MediaHold,
  type OverlayHandle,
} from './overlay';
import { IDLE_SITE_CONFIG } from './platformGate';
import { observeNavigation } from './spaNav';
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

/**
 * Debounce for DOM re-checks, and the safety-net poll for when every event AND the
 * observer miss (ext-03 §2). Both are handed to the shared `observeNavigation` rather than
 * driving timers this module owns.
 */
export const DOM_DEBOUNCE_MS = 250;
export const SAFETY_NET_MS = 1000;

/**
 * Where a bail lands when there is nowhere better.
 *
 * The SHORTS gate always uses it: that gate only ever fires while the site itself is open
 * (an applying site rule redirects `/shorts/*` at the network layer before this script
 * sees the page), so the root is not being redirected out from under it. The CHANNEL gate
 * does NOT — see `bailFromChannelGate` — and keeps this only as a last resort.
 */
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

/** Shorts hiding reads exactly one registry hide id, plus the master switch. */
export type ShortsHidingConfig = Pick<SiteConfig, 'enabled' | 'hides'>;

/** The slice the Shorts gate reads: the master switch and the resolved gate list. */
export type ShortsGateConfig = Pick<SiteConfig, 'enabled' | 'gates'>;

/** The gate id for YouTube's Shorts surface, as the platform registry defines it. */
const SHORTS_GATE_ID = 'shorts' as const;

/**
 * Add/remove the hidden class across every Shorts surface for this page type.
 *
 * Reversible by construction: when hiding is off (or Nudge is globally off) we sweep
 * `.nudge-hidden` and clear it, so a toggle flip restores the page without a reload.
 */
export function applyShortsHiding(
  root: Document | Element,
  config: ShortsHidingConfig,
  options: { pageType?: YoutubePageType; url?: string; warn?: WarnFn } = {},
): HidingResult {
  const { warn = defaultWarn } = options;
  const result: HidingResult = { hidden: 0, revealed: 0, degradedSurfaces: [] };

  if (!config.enabled || config.hides.shortsShelf !== true) {
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

/**
 * How the Shorts surface must be treated on this URL, or null when it must not be gated.
 *
 * Two independent reasons a gate is in force, and they need different endings:
 *
 *  - a GATE MODE (Hard Block / Delay / Breathing) — the user's standing rule for the
 *    surface, and a Delay/Breathing pause can be waited out.
 *  - LIMIT REACHED — the surface's own daily budget is spent. There is nothing left to
 *    wait for until midnight, so a countdown would be a lie and the only honest rendering
 *    is a Hard Block that says why. This is the same answer `core/applies.ts` gives an
 *    exhausted site budget, reached independently here so the overlay and the block page
 *    cannot drift apart.
 *
 * A gate carrying `limitReached` outranks its own mode: a Delay whose budget is gone is
 * not a Delay any more.
 */
export interface ShortsGateVerdict {
  mode: BlockMode;
  delaySeconds: number;
  limitReached: boolean;
}

export function resolveShortsGate(
  url: string,
  config: ShortsGateConfig,
): ShortsGateVerdict | null {
  if (!config.enabled) return null;
  if (pageTypeFor(url) !== 'shorts') return null;

  const gate: ResolvedGate | undefined = config.gates.find((g) => g.id === SHORTS_GATE_ID);
  if (gate === undefined) return null;

  if (gate.limitReached) {
    return { mode: 'HARD_BLOCK', delaySeconds: 0, limitReached: true };
  }
  if (gate.mode === 'ALLOW') return null;
  return { mode: gate.mode, delaySeconds: gate.delaySeconds, limitReached: false };
}

/* ------------------------------------------------------------------ overlay */

/** The human name of a gate surface, straight from the platform registry. */
export function shortsGateLabel(): string {
  return gateDefinition('youtube', SHORTS_GATE_ID)?.label ?? 'Shorts';
}

/**
 * YouTube always supplies its OWN title and subtitle for every interstitial
 * (`shortsGateCopy`, `channelGateCopy`), so both are required here even though the shared
 * builder treats them as optional. That is also why `baseGateCopy` below survives instead
 * of deferring to `overlay.ts`'s internal `gateCopyForMode`: the two word the Breathing
 * and Delay cases slightly differently ("Follow the circle. Shorts will wait.") and this
 * is shipped microcopy, not an implementation detail to be unified away.
 */
export type YoutubeGateCopy = GateCopy & { title: string; subtitle: string };

/**
 * Copy for the Shorts interstitial.
 *
 * The exhausted-budget case gets its OWN wording rather than borrowing the Hard Block
 * line. "You asked Nudge to keep you out of here" is false when the truth is "you had ten
 * minutes and you have spent them" — and a user who reads the wrong reason goes looking
 * for a rule they never wrote. It also names WHEN it comes back, because the one thing
 * someone wants to know at that moment is whether this is for today or forever.
 */
export function shortsGateCopy(verdict: ShortsGateVerdict): YoutubeGateCopy {
  const label = shortsGateLabel();
  const ruleLabel = `YouTube ${label}`;

  if (verdict.limitReached) {
    return {
      title: `You're out of ${label} for today`,
      subtitle: `Your daily ${label} limit is used up. It resets at midnight.`,
      ruleLabel,
    };
  }
  return { ...baseGateCopy(verdict.mode, label), ruleLabel };
}

/** Copy for the interstitial, mode by mode. Android microcopy parity. */
function baseGateCopy(
  mode: BlockMode,
  label: string,
): {
  title: string;
  subtitle: string;
} {
  switch (mode) {
    case 'HARD_BLOCK':
      return {
        title: `${label} is blocked`,
        subtitle: 'You asked Nudge to keep you out of here. Still true?',
      };
    case 'BREATHING':
      return { title: 'Take a breath', subtitle: `Follow the circle. ${label} will wait.` };
    case 'DELAY':
    default:
      return { title: 'Hold on a second', subtitle: `Still want to watch ${label}?` };
  }
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

/**
 * Copy for the watch-page channel gate.
 *
 * The words have to say WHICH rule is holding the video, because the user's next move
 * depends on it: a channel that is merely off a list is fixed by editing the list, while
 * "the whole site is blocked and this channel is not one of your exceptions" is fixed by
 * editing the site rule — and "we could not tell whose video this is" is not the user's
 * mistake at all. One generic "this channel is off your list" for all three would send
 * people to the wrong screen, and in the unknown case would accuse a channel that might
 * well be on their list.
 */
export function channelGateCopy(
  verdict: Extract<WatchGateVerdict, { action: 'BLOCK' }>,
): YoutubeGateCopy {
  if (verdict.source === 'site-default') {
    if (verdict.reason === 'unknown-channel') {
      return {
        title: 'YouTube is blocked right now',
        subtitle:
          "Nudge couldn't identify this video's channel, so YouTube's default rule applies.",
        ruleLabel: 'YouTube',
      };
    }
    return {
      title: 'This channel is off your list',
      subtitle: "YouTube is blocked except the channels you picked. This one isn't one of them.",
      ruleLabel: 'YouTube',
    };
  }

  return {
    title: 'This channel is off your list',
    subtitle:
      verdict.reason === 'not-listed'
        ? 'You chose to watch only channels you picked. Still want this one?'
        : 'You asked Nudge to keep you away from this channel.',
    ruleLabel: 'YouTube channels',
  };
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

/**
 * Wire everything up on a real page.
 *
 * SPA navigation detection is the SHARED layer (`content/spaNav.ts`), configured with
 * YouTube's own cadence. It is the same 3 layers this file used to hand-build (ext-03 §2
 * — two independent OSS blockers converged on exactly this, and one-layer designs are
 * the documented failure), mapped one for one:
 *   1. `navEvent: 'yt-navigate-finish'` — YouTube's own post-route-change event (primary)
 *   2. `popstate` (and `hashchange`) — back/forward, which YouTube does not always
 *      announce (secondary)
 *   3. the href-diff poll at `SAFETY_NET_MS` + a `DOM_DEBOUNCE_MS` MutationObserver — the
 *      safety net for lazy-loaded feed cards and for the day YouTube renames its event.
 *      `mutateOnIdlePoll` is what keeps the old controller's "a poll tick that saw no
 *      navigation still schedules the debounced pass" half.
 *
 * `onNavigate` is called on the SAME TICK the URL change is noticed and is never routed
 * through the debounce (spaNav guarantees this) — that is the repo-wide "A NAVIGATION
 * MUST NOT BE DEBOUNCED" rule, and on YouTube specifically it is what starts the settle
 * machinery promptly instead of letting the post-nav mutation storm starve it.
 *
 * What did NOT move into the shared layer, and must not: `previousChannelKey`, `navAt` and
 * `scheduleSettleChecks` below. They exist to stop a documented P0 — a channel the user
 * explicitly allowed being accused of being "off your list" for 3-5s after a
 * watch→watch hop — and they are YouTube-shaped (a byline that re-renders late), not
 * navigation-shaped.
 */
export function initYoutubeContentScript(
  doc: Document = document,
  fetchConfig: (url: string) => Promise<SiteConfig> = (url) =>
    send({ type: 'GET_SITE_CONFIG', url }),
): YoutubeController {
  const view = doc.defaultView;
  let config: SiteConfig = IDLE_SITE_CONFIG;
  let overlay: OverlayHandle | null = null;
  /** Held only while an interstitial is up; see `holdMediaPaused`. */
  let mediaHold: MediaHold | null = null;
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
  /** Reports confirmed channels to the worker; owns its own per-page-load dedupe. */
  const channelObserver = createChannelObserver();
  let lastUrl = doc.location?.href ?? '';
  let stopped = false;

  function currentHref(): string {
    return doc.location?.href ?? '';
  }

  function teardownOverlay(): void {
    overlay?.dispose();
    overlay = null;
    // Release BEFORE the overlay is forgotten, or the page's media stays un-playable with
    // nothing on screen to explain why.
    mediaHold?.release();
    mediaHold = null;
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
    applyAutoplayOff(doc, config);
    // Read-only for the page, but it can WRITE the user's channel list, so it is gated on the
    // same CONFIRMED detection the interstitial is — never on a settling one. See
    // content/channelObserver.ts for why that rule lives there and not here.
    channelObserver.observe(doc, config, { url, previousKey: previousChannelKey, msSinceNav });

    const resolvedShortsGate = resolveShortsGate(url, config);
    // A completed pause buys the rest of the Shorts session — but it cannot buy a budget
    // that has run out since. There is nothing left today to have paid for, so an
    // exhausted gate ignores the grant instead of being waived by it.
    const shortsGate =
      resolvedShortsGate !== null && gateSatisfied && !resolvedShortsGate.limitReached
        ? null
        : resolvedShortsGate;

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

    if (shortsGate === null && channelGate === null) {
      teardownOverlay();
      return;
    }
    if (overlay?.element.isConnected) return;

    teardownOverlay();
    mediaHold = holdMediaPaused(doc);

    if (shortsGate !== null) {
      overlay = createGateOverlay(
        doc,
        NUDGE_OVERLAY_ID,
        shortsGate.mode,
        shortsGate.delaySeconds,
        shortsGateCopy(shortsGate),
        {
          onComplete: () => {
            gateSatisfied = true;
            teardownOverlay();
          },
          onBail: () => {
            teardownOverlay();
            assignSafeLocation(doc, BAIL_URL);
          },
        },
      );
    } else if (channelGate !== null) {
      const gatedUrl = url;
      overlay = createGateOverlay(
        doc,
        NUDGE_OVERLAY_ID,
        channelGate.mode,
        channelGate.delaySeconds,
        channelGateCopy(channelGate),
        {
          onComplete: () => {
            channelGateSatisfiedFor = gatedUrl;
            teardownOverlay();
          },
          onBail: bailFromChannelGate,
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

  /**
   * "I changed my mind" on the CHANNEL gate.
   *
   * It cannot simply go to `BAIL_URL` the way the Shorts gate does. This gate is the one
   * that fires under "block YouTube except these channels", and in that configuration the
   * site root is exactly what DNR redirects — so the old bail put the user on the block
   * page instead of back where they came from (Known gap, v0.2.0). Going BACK returns them
   * to the page they were actually on, and for an in-SPA hop does so without touching the
   * network layer at all.
   *
   * The no-history case (a tab opened straight onto a gated video from another tab or
   * another app) has nowhere to go back to, so the worker closes the tab — which is the
   * browser's version of what "I changed my mind" does on Android. If that fails too (no
   * tab id, a sleeping worker, a tab already gone) we fall back to the old behaviour rather
   * than leaving the user looking at a torn-down overlay: the block page is a worse landing
   * than going back, but it is still an exit.
   */
  function bailFromChannelGate(): void {
    teardownOverlay();
    bailAwayFromGate(doc, {
      onNoHistory: () => {
        void send({ type: 'CLOSE_TAB' })
          .then((result) => {
            if (result.ok) return;
            assignSafeLocation(doc, BAIL_URL);
          })
          .catch(() => {
            assignSafeLocation(doc, BAIL_URL);
          });
      },
    });
  }

  async function reload(): Promise<void> {
    try {
      // The worker resolves the domain, the rule and the gates from the URL exactly the way
      // the network layer does, so it is handed the page's own address rather than a
      // platform name this script would otherwise have to guess at.
      config = await fetchConfig(currentHref());
    } catch {
      // The service worker can be asleep or mid-reload. Stay in the last known state
      // rather than failing open on a transient messaging error.
      return;
    }
    refresh();
  }

  /**
   * Navigation is handled IMMEDIATELY, never through the debounce.
   *
   * That was the cold-hop bug (live QA, 2026-07-26): the very first refresh after a full
   * page load is what NOTICES the url changed and starts the settle machinery, and routing
   * it through the 250ms debounce meant YouTube's post-nav mutation storm kept resetting
   * it, so for ~2s nothing ran at all and the page held the PREVIOUS video's verdict,
   * colour and all. `spaNav.ts` makes that structural: `onNavigate` fires on the same tick
   * the href change is seen, and only `onMutate` is ever debounced.
   */
  const nav = observeNavigation({
    doc,
    navEvent: 'yt-navigate-finish',
    pollMs: SAFETY_NET_MS,
    mutationDebounceMs: DOM_DEBOUNCE_MS,
    // A quiet YouTube page still has to be re-checked: the settle window can outlast the
    // mutation storm, and the hide toggles must keep reconciling. This is the old
    // safety-net interval's `else scheduleRefresh()` branch.
    mutateOnIdlePoll: true,
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
      for (const id of settleTimers.splice(0)) view?.clearTimeout(id);
      chromeApi?.storage?.onChanged?.removeListener(onStorageChanged);
      teardownOverlay();
    },
  };
}
