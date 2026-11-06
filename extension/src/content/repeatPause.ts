/**
 * The repeating pause in the page: "Next pause in 9:32", then the pause itself, over the SAME
 * document.
 *
 * In place rather than a redirect, because that is the point of it. The block page would throw
 * away the half-typed comment and the video position, so a repeat would punish the user for
 * having kept the site open, and people turn off what punishes them.
 *
 * The clock is the WALL clock against the grant's `until`, not a countdown of time spent here. A
 * grant is per domain and runs out whether or not this tab is looking at it, so the pill reads
 * the same as DNR does, and a pause that came due while the user was away is due the moment they
 * return.
 *
 * The worker decides whether a repeat applies (`GET_REPEAT_PAUSE`); this file only keeps the page
 * in step with the answer. It holds no opinion on rules, so it cannot disagree with the engine.
 *
 * PURE DOM plus an injected `send`, so all of it runs under jsdom with fake timers.
 */

import type { GrantResult, Request, RepeatPauseState, ResponseFor } from '../core/protocol';
import { nextPauseLabel } from '../core/repeatPause';
import { createGateOverlay, holdMediaPaused, type MediaHold, type OverlayHandle } from './overlay';
import { createRepeatPauseHost, removeStaleHosts, type RepeatPauseHost } from './repeatPauseHost';

/**
 * The overlay's element id. Has a backdrop rule in `platformOverlay.css`, pinned by
 * `tests/content/overlayStyling.test.ts`.
 */
export const REPEAT_PAUSE_OVERLAY_ID = 'nudge-repeat-pause';

const TICK_MS = 1000;
const REFETCH_MS = 30_000;
/** While due, or after a failed fetch: another tab's renewal should end this one's pause soon. */
const REFETCH_DUE_MS = 5000;
const GRANT_RETRY_MS = 1000;

type Send = <T extends Request>(request: T) => Promise<ResponseFor<T['type']>>;

export interface RepeatPauseOptions {
  doc: Document;
  send: Send;
  /** The shadow root's stylesheet. */
  styles: string;
  now?: () => number;
  navigate?: (url: string) => void;
}

export interface RepeatPauseController {
  /** Ask the worker again now, because something it reads changed. */
  refresh: () => void;
  stop: () => void;
}

export function initRepeatPause(options: RepeatPauseOptions): RepeatPauseController {
  const { doc, send, styles } = options;
  const now = options.now ?? Date.now;
  const navigate = options.navigate ?? ((url: string) => doc.location.replace(url));
  const view = doc.defaultView;

  let state: RepeatPauseState | null = null;
  let host: RepeatPauseHost | null = null;
  let pill: HTMLElement | null = null;
  let overlay: OverlayHandle | null = null;
  let hold: MediaHold | null = null;

  /** A fresh answer from the worker agrees it is due, so another tab has not renewed the grant. */
  let confirmedDue = false;
  let pauseShownThisCycle = false;
  /** The pause was served and the grant is on its way. Nothing may take the overlay down. */
  let completing = false;
  /** The user bailed and the tab is navigating. Nothing may bring the pause back. */
  let leaving = false;
  let stopped = false;

  let lastHref = doc.location.href;
  let lastFetchAt = 0;
  let lastFetchFailed = false;
  /** Only the newest request's answer counts, and a grant outdates any request made before it. */
  let fetchSeq = 0;
  let fetchesInFlight = 0;

  function isDue(current: RepeatPauseState & { active: true }): boolean {
    return current.until === null || current.until <= now();
  }

  function ensureHost(): RepeatPauseHost {
    host ??= createRepeatPauseHost(doc, styles);
    host.ensureAttached();
    return host;
  }

  function clearPill(): void {
    pill?.remove();
    pill = null;
  }

  function clearOverlay(): void {
    overlay?.dispose();
    overlay = null;
  }

  /** The cycle is over: nothing is due any more, and what the hold stopped may play again. */
  function clearCycle(): void {
    clearOverlay();
    hold?.release({ resume: true });
    hold = null;
    confirmedDue = false;
    pauseShownThisCycle = false;
    completing = false;
  }

  function showPill(until: number): void {
    const root = ensureHost().root;
    if (pill === null) {
      pill = doc.createElement('div');
      pill.className = 'nudge-repeat-pill';
      root.append(pill);
    }
    // Deliberately not an aria-live region: it would announce every second.
    const text = nextPauseLabel(until - now());
    if (pill.textContent !== text) pill.textContent = text;
  }

  function exitFullscreen(): void {
    if (!doc.fullscreenElement) return;
    try {
      void doc.exitFullscreen().catch(() => undefined);
    } catch {
      // The overlay is in the top layer regardless.
    }
  }

  async function requestGrant(): Promise<GrantResult | null> {
    try {
      return await send({ type: 'COMPLETE_PAUSE', target: doc.location.href });
    } catch {
      return null;
    }
  }

  function applyGrant(until: number): void {
    // A request made before this grant may still answer "due", from before it existed.
    fetchSeq += 1;
    if (state?.active) state = { ...state, until };
    clearCycle();
    render();
  }

  /**
   * Never takes the overlay down without a successful grant: a pause that ended in nothing would
   * be a free pass. Refused twice, the URL goes back through the network layer and DNR decides,
   * with the overlay still up until that navigation replaces it.
   */
  async function completePause(): Promise<void> {
    completing = true;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      if (attempt > 0) {
        await new Promise((resolve) => view?.setTimeout(resolve, GRANT_RETRY_MS));
      }
      const grant = await requestGrant();
      if (stopped) return;
      if (grant?.ok) {
        applyGrant(grant.until);
        return;
      }
      // Another tab's renewal ended this pause while the request was out. Nothing left to do.
      if (!completing) return;
    }
    navigate(doc.location.href);
  }

  async function bail(): Promise<void> {
    leaving = true;
    const href = doc.location.href;
    hold?.release({ resume: false });
    hold = null;
    let left = false;
    try {
      await send({ type: 'WALKED_AWAY', target: href });
      left = (await send({ type: 'LEAVE_TO_DASHBOARD' })).ok;
    } catch {
      left = false;
    }
    if (!left && !stopped) navigate(href);
  }

  function mountOverlay(current: RepeatPauseState & { active: true }): void {
    exitFullscreen();
    overlay = createGateOverlay(
      doc,
      REPEAT_PAUSE_OVERLAY_ID,
      current.mode,
      current.delaySeconds,
      { ruleLabel: current.domain },
      { onComplete: () => void completePause(), onBail: () => void bail() },
    );
    ensureHost().root.append(overlay.element);
    if (!pauseShownThisCycle) {
      pauseShownThisCycle = true;
      // The overlay rendering IS the block event, same as GET_BLOCK_CONTEXT on the block page.
      void send({ type: 'PAUSE_SHOWN', url: doc.location.href }).catch(() => undefined);
    }
  }

  function render(): void {
    if (stopped || leaving || state === null) return;

    if (!state.active) {
      clearCycle();
      clearPill();
      host?.remove();
      host = null;
      return;
    }

    if (!isDue(state)) {
      clearCycle();
      showPill(state.until as number);
      return;
    }

    clearPill();
    // Served out in a background tab is not served. It starts again on return.
    if (doc.hidden && !completing) clearOverlay();

    if (!confirmedDue) {
      // Another tab may have renewed the shared grant, which only the worker knows.
      if (fetchesInFlight === 0) void fetchState();
      return;
    }

    if (hold === null) hold = holdMediaPaused(doc);
    else hold.repause();

    if (!doc.hidden && overlay === null && !completing) mountOverlay(state);
  }

  async function fetchState(): Promise<void> {
    fetchSeq += 1;
    const seq = fetchSeq;
    fetchesInFlight += 1;
    lastFetchAt = now();
    let fresh: RepeatPauseState | null = null;
    try {
      const answer = await send({ type: 'GET_REPEAT_PAUSE', url: doc.location.href });
      if (typeof answer?.active === 'boolean') fresh = answer;
    } catch {
      fresh = null;
    }
    fetchesInFlight -= 1;
    if (stopped || seq !== fetchSeq) return;

    lastFetchFailed = fresh === null;
    if (fresh !== null) state = fresh;
    // A failed fetch stays in the last known state. If that state says due, it is due: treating
    // "could not ask" as "not due" would let a sleeping worker switch the pause off.
    confirmedDue = state !== null && state.active && isDue(state);
    render();
  }

  function tick(): void {
    host?.ensureAttached();
    const href = doc.location.href;
    const due = state !== null && state.active && isDue(state);
    const interval = due || lastFetchFailed ? REFETCH_DUE_MS : REFETCH_MS;
    if (href !== lastHref) {
      lastHref = href;
      void fetchState();
    } else if (now() - lastFetchAt >= interval) {
      void fetchState();
    }
    render();
  }

  const onVisibility = (): void => {
    // Coming back: ask before showing anything, or a renewal from another tab flashes a pause.
    if (!doc.hidden) confirmedDue = false;
    void fetchState();
    render();
  };

  const onFullscreen = (): void => {
    host?.raise();
    if (overlay !== null) exitFullscreen();
  };

  /** Back from the dashboard can restore this page from bfcache, frozen mid-bail. */
  const onPageShow = (event: Event): void => {
    if (!(event as PageTransitionEvent).persisted) return;
    leaving = false;
    completing = false;
    pauseShownThisCycle = false;
    confirmedDue = false;
    clearOverlay();
    void fetchState();
  };

  removeStaleHosts(doc);
  doc.addEventListener('visibilitychange', onVisibility);
  doc.addEventListener('fullscreenchange', onFullscreen);
  view?.addEventListener('pageshow', onPageShow);
  const interval = view?.setInterval(tick, TICK_MS);
  void fetchState();

  return {
    refresh: () => void fetchState(),
    stop: () => {
      stopped = true;
      if (interval !== undefined) view?.clearInterval(interval);
      doc.removeEventListener('visibilitychange', onVisibility);
      doc.removeEventListener('fullscreenchange', onFullscreen);
      view?.removeEventListener('pageshow', onPageShow);
      clearOverlay();
      clearPill();
      // Not a cleared page, the script is going away. A replacement holds again if still due.
      hold?.release({ resume: false });
      hold = null;
      host?.remove();
      host = null;
    },
  };
}
