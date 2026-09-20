/**
 * The shared in-page gate overlay: mount/teardown, mode copy (Hard Block / Delay
 * countdown / Breathing pacer), "I changed my mind", per-platform element identity, and
 * the media hold that keeps a gated player silent. Used by ALL SEVEN platform content
 * scripts — the six generic ones via `platformGate.ts`, and YouTube directly from
 * `content/youtube.ts`.
 *
 * The markup and classes originated as `youtube.ts`'s own overlay builder; this module is
 * that builder generalized, and since the YouTube migration it is the ONLY copy. The one
 * thing that varies per platform is the root element's `id`, which is a parameter
 * (`overlayIdFor` for the generic six, `selectors.ts`'s `NUDGE_OVERLAY_ID` for YouTube,
 * because `youtube.css` keys its full-screen backdrop rule off that exact id).
 *
 * Why the migration was safe to do, having been deferred once: the overlay is pure DOM and
 * knows nothing about WHEN it is shown. YouTube's hard part — the channel-freshness
 * settle window (`core/channelFreshness.ts`'s `SETTLE_MS` / `SETTLE_COLOR_MS`,
 * `previousChannelKey`, `msSinceNav`), which exists to stop a documented P0: a channel the
 * user explicitly allowed being accused of being "off your list" for 3-5s after a
 * watch→watch hop — lives entirely in the CALLER, in `content/channelFilter.ts`'s
 * `watchChannelVerdict`, which returns null while SETTLING so no overlay is ever built.
 * Nothing about the interstitial itself was ever entangled with it, and nothing in this
 * module can reintroduce that regression.
 *
 * PURE DOM — no `chrome.*` — so every export is unit-testable with jsdom and no browser.
 */

import type { Platform } from '../core/platforms';
import { MODE_LABELS, type BlockMode } from '../core/types';

/** Exact Android wording. Do not "improve" this string. */
export const BAIL_LABEL = 'I changed my mind';

/** Breathing cycle: 4s in, 4s out (Android + youtube.ts parity). */
export const BREATH_IN_MS = 4000;
export const BREATH_OUT_MS = 4000;

/**
 * A distinct overlay element id PER PLATFORM (rather than one id shared by all six),
 * mirroring `NUDGE_OVERLAY_ID`'s role for YouTube. Every id this returns MUST also carry a
 * matching `#<id>.nudge-overlay` backdrop rule in `platformOverlay.css`, or the
 * interstitial renders as an ordinary in-flow block instead of covering the page;
 * `tests/content/overlayStyling.test.ts` is what keeps that list total.
 * Only one platform's content script
 * ever runs on a given page, so a single shared id would work functionally — but a
 * platform-specific id keeps every hide-selector exclusion check (`isOwnOverlay`) legible
 * in isolation (a selector chain audited for, say, `tiktok/selectors.ts` never has to
 * reason about an id that also means something on Instagram), keeps devtools inspection
 * unambiguous when debugging one platform's fixture, and costs nothing.
 */
export function overlayIdFor(platform: Platform): string {
  return `nudge-gate-${platform}`;
}

/** True when `element` IS this platform's overlay, or is contained inside it. */
export function isOwnOverlay(element: Element, overlayId: string): boolean {
  return element.id === overlayId || element.closest(`#${overlayId}`) !== null;
}

/**
 * What the interstitial RENDERS. Deliberately says nothing about where the bail button
 * goes: the overlay does not navigate, it calls `handlers.onBail` and lets the caller
 * decide. Keeping the destination out of the render copy is what lets YouTube — whose
 * channel gate bails by going BACK rather than to a site root the same rule is still
 * redirecting (`bailAwayFromGate`) — share this exact builder with the six platform
 * scripts that simply assign a hardcoded safe URL.
 */
export interface GateCopy {
  /** What the interstitial calls the thing being gated, e.g. "Reels" or "the For You feed". */
  ruleLabel: string;
  title?: string;
  subtitle?: string;
}

function gateCopyForMode(mode: BlockMode, ruleLabel: string): { title: string; subtitle: string } {
  switch (mode) {
    case 'HARD_BLOCK':
      return {
        title: `${ruleLabel} is blocked`,
        subtitle: 'You asked Nudge to keep you out of here. Still true?',
      };
    case 'BREATHING':
      return { title: 'Take a breath', subtitle: `${ruleLabel} will wait.` };
    case 'DELAY':
    default:
      return { title: 'Hold on a second', subtitle: `Still want ${ruleLabel}?` };
  }
}

export interface OverlayHandle {
  element: HTMLElement;
  dispose: () => void;
}

/**
 * Build the interstitial. Markup and classes match `youtube.ts`'s `createShortsOverlay`
 * exactly (`.nudge-overlay`, `.nudge-overlay__card`, etc.) so `platformOverlay.css` (or,
 * post-migration, `youtube.css`) is the only thing that needs to agree with this file.
 */
export function createGateOverlay(
  doc: Document,
  overlayId: string,
  mode: BlockMode,
  delaySeconds: number,
  copy: GateCopy,
  handlers: { onComplete: () => void; onBail: () => void },
): OverlayHandle {
  const base = gateCopyForMode(mode, copy.ruleLabel);
  const title = copy.title ?? base.title;
  const subtitle = copy.subtitle ?? base.subtitle;
  const timers: number[] = [];

  const overlay = doc.createElement('div');
  overlay.id = overlayId;
  overlay.className = 'nudge-overlay';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', `Nudge — ${MODE_LABELS[mode]}`);

  const card = doc.createElement('div');
  card.className = 'nudge-overlay__card';

  const titleEl = doc.createElement('h1');
  titleEl.className = 'nudge-overlay__title';
  titleEl.textContent = title;

  const subtitleEl = doc.createElement('p');
  subtitleEl.className = 'nudge-overlay__subtitle';
  subtitleEl.textContent = subtitle;

  card.append(titleEl, subtitleEl);

  function clearAll(): void {
    for (const id of timers.splice(0)) doc.defaultView?.clearInterval(id);
  }

  if (mode === 'DELAY') {
    const seconds = Math.max(1, Math.round(delaySeconds));
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

    const totalMs = Math.max(1, Math.round(delaySeconds)) * 1000;
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
  footer.textContent = `Rule: ${copy.ruleLabel} · ${MODE_LABELS[mode]}`;
  card.append(footer);

  overlay.append(card);

  return {
    element: overlay,
    dispose: () => {
      clearAll();
      overlay.remove();
    },
  };
}

/** Pause any playing media so the interstitial isn't just a lid over a running video. */
export function pauseMedia(doc: Document): void {
  for (const media of Array.from(doc.querySelectorAll('video, audio'))) {
    try {
      (media as HTMLMediaElement).pause();
    } catch {
      // jsdom and some embeds throw on pause(); the overlay still stands.
    }
  }
}

/** A standing "nothing may play" hold, released when the overlay comes down. */
export interface MediaHold {
  /** Re-pause right now — for callers that already have a periodic pass. */
  repause: () => void;
  /** Stop holding. MUST be called on overlay teardown or media can never play again. */
  release: () => void;
}

/**
 * Keep media paused for as long as a gate overlay is up.
 *
 * A single `pause()` at mount time is not enough, and live QA caught exactly that (run 51,
 * 2026-09-20): the Hard Block overlay was on screen while the video underneath paused at
 * +2.0s and was playing again by +2.5s, unmuted, for the next twelve seconds. YouTube's own
 * autoplay simply resumes the player after our one-shot pause, so the interstitial became a
 * lid over a running video — the audio the user asked not to be pulled into kept playing.
 * Intermittent (4 of 5 runs stayed paused), which is exactly why it needs a standing hold
 * rather than a better-timed single pause.
 *
 * Listeners are CAPTURING and on the document: `play`/`playing` do not bubble, so a
 * capturing document listener is the only way to hear them for a player that is replaced or
 * re-created while the overlay is up — which YouTube does.
 */
export function holdMediaPaused(doc: Document): MediaHold {
  /**
   * Muting is belt to the pause's braces, and it exists because the pause has a gap.
   *
   * QA R2 measured 0.68s of audible playback on 1 of 5 cold loads: the player is already
   * running when the overlay mounts, so there is a window between "the page starts sound"
   * and "our first pause lands" that no listener can close — the sound has already left.
   * Muting is synchronous and takes effect immediately, so that window is silent even when
   * the pause is a beat late. The previous muted state is restored on release, because
   * silently un-muting a video the user had muted themselves would be its own small bug.
   */
  const previouslyMuted = new WeakMap<HTMLMediaElement, boolean>();

  const muteAll = (): void => {
    for (const element of Array.from(doc.querySelectorAll('video, audio'))) {
      const media = element as HTMLMediaElement;
      try {
        if (!previouslyMuted.has(media)) previouslyMuted.set(media, media.muted === true);
        media.muted = true;
      } catch {
        // Same tolerance as pausing: a player that refuses must not throw into the gate.
      }
    }
  };

  const repause = (): void => {
    muteAll();
    pauseMedia(doc);
  };

  const onPlay = (event: Event): void => {
    const target = event.target as Partial<HTMLMediaElement> | null;
    if (target === null || typeof target.pause !== 'function') return;
    try {
      const media = target as HTMLMediaElement;
      if (!previouslyMuted.has(media)) previouslyMuted.set(media, media.muted === true);
      media.muted = true;
      target.pause();
    } catch {
      // Same tolerance as pauseMedia: a player that refuses to pause must not throw into
      // the gate that is holding the page.
    }
  };

  doc.addEventListener('play', onPlay, true);
  doc.addEventListener('playing', onPlay, true);
  repause();

  return {
    repause,
    release: () => {
      doc.removeEventListener('play', onPlay, true);
      doc.removeEventListener('playing', onPlay, true);
      // Give each element back the muted state it had BEFORE the overlay, not a blanket
      // un-mute: a video the user had muted themselves must stay muted.
      for (const element of Array.from(doc.querySelectorAll('video, audio'))) {
        const media = element as HTMLMediaElement;
        const before = previouslyMuted.get(media);
        if (before === undefined) continue;
        try {
          media.muted = before;
        } catch {
          // Nothing to do; the page is already usable again.
        }
      }
    },
  };
}

/**
 * Assign `location` only to a well-formed http(s) URL.
 *
 * Security posture (chrome-mv3-extension.md): validate a URL parses as http:/https:
 * before ever assigning to `window.location`, at every navigation call site — an
 * open-redirect / `javascript:` / `data:` guard. `bailUrl` is always a hardcoded
 * platform-root constant in practice, but the guard costs nothing and keeps every
 * navigation call site in the codebase honestly defensive rather than "trusted because
 * nothing bad has happened yet".
 */
export function assignSafeLocation(doc: Document, url: string): void {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return;
  doc.location.assign(url);
}

/**
 * Leave a gated page WITHOUT landing somewhere the same rule is still redirecting.
 *
 * "I changed my mind" used to mean `location.assign('https://www.youtube.com/')` for every
 * YouTube gate. That is right when only a FEATURE is gated (the site itself opens fine)
 * and wrong when the SITE rule applies: under "block YouTube except these channels" the
 * site root is precisely what DNR redirects, so the bail button landed the user on the
 * block page rather than back where they came from. Filed as a Known gap for v0.2.0.
 *
 * Going BACK is the honest answer, and better than any fixed URL could be: the previous
 * history entry is by construction a page the user had already reached (and if it was
 * blocked too, the block page is then the correct destination rather than a surprise). It
 * is also same-document for an in-SPA hop, so it never re-enters the network layer.
 *
 * `history.length <= 1` — a tab opened straight onto the gated URL from another tab or
 * another app — has no back to go to, which is what `onNoHistory` is for. The caller
 * supplies that because escaping such a tab needs `chrome.tabs`, and this module is
 * deliberately `chrome`-free.
 *
 * Returns which branch it took, so a caller (and a test) can tell them apart.
 */
export function bailAwayFromGate(
  doc: Document,
  handlers: { onNoHistory: () => void },
): 'back' | 'no-history' {
  const view = doc.defaultView;
  // `history.length` counts the CURRENT entry, so >1 is what means "there is a previous
  // one". A document with no view (detached, or a test fixture) counts as no history.
  if (view !== null && view !== undefined && view.history.length > 1) {
    view.history.back();
    return 'back';
  }
  handlers.onNoHistory();
  return 'no-history';
}
