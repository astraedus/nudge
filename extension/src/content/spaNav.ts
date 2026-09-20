/**
 * The generic SPA-navigation layer, shared by EVERY platform content script: Instagram,
 * TikTok, X, Facebook, Reddit, LinkedIn and, since the YouTube migration, `youtube.ts`
 * too. It is the generalization of YouTube's original hand-built 3-layer detection
 * (`yt-navigate-finish` + `popstate` + debounced observer + safety-net poll), so seven
 * platforms share one mechanism instead of seven chances to drift.
 *
 * Why this exists at all (ext-12 §G, confirmed independently on YouTube, TikTok, X,
 * Instagram and Facebook — 5-for-5): an isolated-world content script CANNOT observe the
 * page's own `history.pushState`/`replaceState` calls. A React/SPA route change that
 * calls `pushState` fires no event we can listen for, so a naive
 * `window.addEventListener('popstate', ...)`-only design silently stops noticing
 * navigation the moment the user clicks an in-app link instead of using the browser's
 * back/forward buttons. `control-panel-for-twitter` patches `pushState` itself from a
 * MAIN-world script to work around this — explicitly out of scope for Nudge (heavier
 * technique, higher CSP/review risk than pure DOM/CSS work, ext-12 §C). Every other
 * mature repo (`Sayukoo/tiktok_unhook`, `prestoj/protect-my-brain`) instead layers:
 *
 *   1. An optional site-own navigation event (YouTube's `yt-navigate-finish`; most sites
 *      have no analog, hence this parameter is optional here).
 *   2. `popstate` + `hashchange` — real back/forward and hash-only routing.
 *   3. A href-diff POLL — the only thing that reliably catches a same-document
 *      `pushState` call with zero cooperation from the page.
 *   4. A debounced `MutationObserver` as a re-apply safety net for when the site's own
 *      re-render strips injected classes (NOT a navigation detector — see below).
 *
 * A NAVIGATION IS HANDLED IMMEDIATELY, NEVER DEBOUNCED (repo-wide lesson, extension/
 * CLAUDE.md "A NAVIGATION MUST NOT BE DEBOUNCED"): the first pass after a URL change is
 * what NOTICES the change and (for a caller like `platformGate.ts`) decides whether to
 * show an interstitial. Routing that through a debounce lets a mutation storm delay the
 * whole chain, so the page silently holds the PREVIOUS route's verdict for seconds. Only
 * the mutation-storm re-apply pass (`onMutate`) is debounced here; `onNavigate` never is.
 *
 * PURE DOM — no `chrome.*` — so it is unit-testable with fake timers and no browser.
 */

/** How often to compare `location.href` against the last-seen value. */
const DEFAULT_POLL_MS = 500;

/** How long to wait after the last DOM mutation before running the re-apply pass. */
const DEFAULT_MUTATION_DEBOUNCE_MS = 250;

export interface ObserveNavigationOptions {
  /**
   * Called synchronously, on the same tick a navigation is detected — by a nav event, by
   * `popstate`/`hashchange`, or by the poll noticing `location.href` changed. NEVER routed
   * through a debounce.
   */
  onNavigate: () => void;
  /**
   * Called after `mutationDebounceMs` of DOM quiet following a mutation-observer callback.
   * This is the re-apply safety net for a site re-render stripping injected classes — it is
   * NOT how navigation is detected, and it never substitutes for `onNavigate`.
   */
  onMutate?: () => void;
  /**
   * A site's own post-navigation event name, dispatched on `document` (e.g. YouTube's
   * `yt-navigate-finish`). Optional — most platforms have no analog, and the
   * poll+popstate+hashchange trio alone is the documented-sufficient baseline for them
   * (ext-12 §B/§C: TikTok and X have no first-party route event and every mature repo
   * still catches their navigations this way).
   */
  navEvent?: string;
  /** href-diff poll interval, ms. Default 500. */
  pollMs?: number;
  /** Debounce window for the mutation-storm re-apply pass, ms. Default 250. */
  mutationDebounceMs?: number;
  /**
   * Also schedule the debounced `onMutate` re-apply pass on every poll tick that saw NO
   * navigation. Off by default; YouTube is the one caller that turns it on.
   *
   * Why it exists: a page whose DOM has gone quiet stops firing the mutation observer, so
   * the re-apply pass stops running — and on YouTube that pass is not merely cosmetic
   * reconciliation, it is what re-evaluates the channel verdict, the colour flip and the
   * hide toggles as the page finishes settling. `youtube.ts`'s original controller had
   * exactly this: a 1s safety-net interval that refreshed immediately on an href change
   * and otherwise scheduled the debounced pass (`else scheduleRefresh()`). This flag is
   * that second half, kept so the migration onto this module changes nothing about when
   * YouTube re-checks a page.
   *
   * It is deliberately routed through the SAME debounce as the observer rather than
   * calling `onMutate` directly, so a mutation storm and a poll tick landing together
   * still collapse into one pass — which is what the old shared `debounceTimer` did.
   */
  mutateOnIdlePoll?: boolean;
  /** Injectable for tests; defaults to the global `document`. */
  doc?: Document;
  /**
   * Injectable for tests; defaults to `doc.defaultView ?? window`. Typed as the
   * intersection `defaultView` actually returns (not plain `Window`) so it carries
   * `MutationObserver` and every other global constructor.
   */
  win?: Window & typeof globalThis;
}

export interface ObserveNavigationHandle {
  /** Removes every listener and clears every timer this call created. */
  dispose: () => void;
}

/**
 * Wire up navigation + mutation detection on a real (or fixture) page.
 *
 * Returns immediately; `onNavigate` is never called synchronously from within this
 * function itself — only in response to a later event, poll tick or href change, exactly
 * mirroring how a real navigation would be observed.
 */
export function observeNavigation(options: ObserveNavigationOptions): ObserveNavigationHandle {
  const doc = options.doc ?? document;
  const win = options.win ?? doc.defaultView ?? window;
  const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
  const mutationDebounceMs = options.mutationDebounceMs ?? DEFAULT_MUTATION_DEBOUNCE_MS;

  let lastHref = doc.location?.href ?? '';
  let stopped = false;
  let mutationTimer: ReturnType<Window['setTimeout']> | undefined;

  /**
   * The single source of truth for "did the URL change". Every listener funnels through
   * this so `onNavigate` fires at most once per real change, however many signals noticed
   * it (a site that fires BOTH its own event and a popstate for the same hop must not
   * double-fire).
   */
  function checkForNavigation(): boolean {
    if (stopped) return false;
    const href = doc.location?.href ?? '';
    if (href === lastHref) return false;
    lastHref = href;
    options.onNavigate();
    return true;
  }

  const onNavSignal = (): void => {
    checkForNavigation();
  };

  win.addEventListener('popstate', onNavSignal);
  win.addEventListener('hashchange', onNavSignal);
  if (options.navEvent) doc.addEventListener(options.navEvent, onNavSignal);

  // The href-diff poll: the ONLY reliable way to notice a same-document `pushState`/
  // `replaceState` call an isolated-world content script cannot observe directly.
  const pollTimer = win.setInterval(() => {
    // A tick that found no navigation is the "this page has gone quiet" signal, and for a
    // caller that asked for it that is exactly when the debounced re-apply pass must still
    // run: a quiet DOM fires no mutations to schedule it. See `mutateOnIdlePoll`.
    if (!checkForNavigation() && options.mutateOnIdlePoll === true) scheduleMutate();
  }, pollMs);

  function scheduleMutate(): void {
    if (stopped || !options.onMutate) return;
    if (mutationTimer !== undefined) win.clearTimeout(mutationTimer);
    mutationTimer = win.setTimeout(() => {
      mutationTimer = undefined;
      options.onMutate?.();
    }, mutationDebounceMs);
  }

  // Deliberately does NOT also call checkForNavigation(): folding navigation detection
  // into the debounced path would mean a navigation is only ever noticed after a mutation
  // *and* its debounce elapses — exactly the "debounced signal" failure mode this module
  // exists to avoid. The poll above is the navigation detector; this is purely the
  // re-apply safety net for DOM churn.
  const observer = new win.MutationObserver(scheduleMutate);
  observer.observe(doc.documentElement, { childList: true, subtree: true });

  return {
    dispose: () => {
      stopped = true;
      win.removeEventListener('popstate', onNavSignal);
      win.removeEventListener('hashchange', onNavSignal);
      if (options.navEvent) doc.removeEventListener(options.navEvent, onNavSignal);
      win.clearInterval(pollTimer);
      if (mutationTimer !== undefined) win.clearTimeout(mutationTimer);
      observer.disconnect();
    },
  };
}
