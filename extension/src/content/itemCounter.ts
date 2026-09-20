/**
 * The page half of COUNT budgets (v0.3): notice that we have landed on one item of a
 * gate's stream, and tell the worker.
 *
 * That is the WHOLE job. The page reports a URL; the service worker resolves it to a gate
 * and an item id, de-duplicates it against the day, increments the surface's rollup and
 * fires the crossing (`background/itemCounter.ts`). Nothing here decides whether anything
 * is blocked, and nothing here keeps a tally: a count kept in the page is a count the user
 * can edit with devtools open on the very surface it limits, and seven platform scripts
 * each keeping their own would be seven chances to disagree with the budget DNR and the
 * block page read.
 *
 * Two entry points, because the platforms arrive here by two routes:
 *
 *  - `createItemReporter` is used from `content/platformGate.ts`, inside the navigation
 *    loop that already exists there, so Instagram, TikTok, X, Facebook, Reddit and
 *    LinkedIn all get counting for free the moment their registry entry grows `itemPaths`.
 *  - `startItemViewCounter` owns its own `spaNav` observer, for YouTube, whose content
 *    script (`content/youtube.ts`) has its own hand-built navigation layer entangled with
 *    the channel-freshness settle window. Wiring into that would mean editing the one file
 *    in this codebase documented as not worth touching without its own isolated change
 *    (see the Known gap in extension/CLAUDE.md), so the counter observes navigation itself
 *    and the YouTube ENTRYPOINT starts it alongside the existing controller.
 *
 * PURE DOM + one message send, so both are unit-testable with fake timers and no browser.
 */

import type { Platform } from '../core/platforms';
import { send } from '../ui/rpc';
import { observeNavigation } from './spaNav';

/** Sends one `ITEM_VIEWED` report. Injectable so tests never need a `chrome` global. */
export type SendItemView = (url: string) => void;

const defaultSend: SendItemView = (url) => {
  // Fire and forget: the report is an observation, never a gate. The worker can be asleep
  // or mid-reload, and an item that fails to report is one item under-counted, whereas an
  // unhandled rejection in a content script is a console error on every navigation.
  void send({ type: 'ITEM_VIEWED', url }).catch(() => undefined);
};

export interface ItemReporter {
  /** Report `url` if it differs from the last URL reported. Never throws, never awaits. */
  report: (url: string) => void;
}

/**
 * A reporter that skips a URL identical to the one it last reported.
 *
 * The local guard exists purely to keep the message channel quiet: the caller in
 * `platformGate.ts` also runs on the debounced mutation pass, which fires many times per
 * second while a feed lazy-loads, and every one of those would otherwise be a round trip
 * for a page that has not moved. It is NOT the dedupe that makes the count correct, which
 * is the worker's day-scoped seen set: going A -> B -> A deliberately re-reports A, and
 * the worker is what knows A was already counted today.
 */
export function createItemReporter(
  platform: Platform,
  sendItemView: SendItemView = defaultSend,
): ItemReporter {
  // `platform` is not sent: the worker re-derives it from the URL, exactly the way it
  // derives the rule and the gate, so a page cannot name a platform it is not on.
  void platform;
  let lastUrl: string | null = null;
  return {
    report(url: string): void {
      if (url === '' || url === lastUrl) return;
      lastUrl = url;
      sendItemView(url);
    },
  };
}

export interface ItemViewCounterOptions {
  platform: Platform;
  /** A site's own post-navigation event name, if it has one (see `spaNav.ts`). */
  navEvent?: string;
  doc?: Document;
  pollMs?: number;
  sendItemView?: SendItemView;
}

export interface ItemViewCounterHandle {
  /** Force a report for the current URL (also what the nav listeners call). */
  check: () => void;
  /** Detach every listener and timer. */
  stop: () => void;
}

/**
 * A standalone counter with its own navigation detection, for a platform whose content
 * script does not route through `platformGate.ts`.
 *
 * Deliberately does NOT subscribe to the mutation pass: a mutation is not a navigation,
 * and the only thing this needs to notice is a URL change. `spaNav`'s poll is what catches
 * the `history.pushState` an isolated-world script cannot observe, which on YouTube is
 * every single swipe between two Shorts.
 */
export function startItemViewCounter(
  options: ItemViewCounterOptions,
): ItemViewCounterHandle {
  const doc = options.doc ?? document;
  const reporter = createItemReporter(options.platform, options.sendItemView);

  const check = (): void => {
    reporter.report(doc.location?.href ?? '');
  };

  const nav = observeNavigation({
    doc,
    navEvent: options.navEvent,
    pollMs: options.pollMs,
    onNavigate: check,
  });

  // The FULL page load onto an item is itself a view, and no navigation event will ever
  // fire for it. Without this, typing a Shorts URL or following a link into one would
  // cost nothing against the day's count until the user swiped to the next one.
  check();

  return { check, stop: () => nav.dispose() };
}
