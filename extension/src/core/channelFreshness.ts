/**
 * Is the channel we just detected actually describing the video on screen YET? PURE.
 *
 * THE SETTLE WINDOW (live QA, 2026-07-26). Fixing the permanent SPA-staleness bypass left a
 * transient one. On a watch -> watch client-side navigation YouTube fires `yt-navigate-finish`
 * BEFORE it re-renders the owner byline (ext-03 §2 warns metadata lags the event), so for
 * ~1.5-5s the DOM tier still reports the PREVIOUS video's channel. Two bad outcomes:
 *
 *   forward  (allowed -> blocked): a couple of seconds ungated. Mild — it is the fail-open
 *            we already chose deliberately.
 *   reverse  (blocked -> allowed): the WORSE one. A channel the user explicitly allowed
 *            flashes "This channel is off your list" and goes gray for 3-5s. Punishing
 *            someone for watching what they said they wanted is the most damaging thing
 *            this extension can do, and it teaches them to distrust the gate.
 *
 * The discriminator is cheap: staleness only MATTERS when the byline still shows the channel
 * from before the navigation. If what we detect now differs from what we detected then, the
 * byline has demonstrably re-rendered and can be trusted immediately. If it is the same, we
 * genuinely cannot distinguish "stale" from "two videos by the same channel" — so we wait,
 * and while waiting we withhold the interstitial rather than risk a false accusation.
 *
 * Waiting is safe in one direction only, which is why gray-screen is handled separately:
 * staying GRAY during the window costs nothing, so colour is withheld too.
 */

/** A comparable identity for a detected channel. Null when nothing was identified. */
export function channelKey(
  detected: { channelId?: string | null; handle?: string | null } | null,
): string | null {
  const id = detected?.channelId ?? null;
  const handle = detected?.handle ?? null;
  if (id === null && handle === null) return null;
  return `${id ?? ''}|${(handle ?? '').toLowerCase()}`;
}

/**
 * How long to keep waiting before accepting the byline anyway.
 *
 * Generous on purpose. It only bites when the detected channel never changes across the
 * navigation — which is either a genuine same-channel hop (where the verdict is identical
 * and the delay costs nothing) or a byline that lagged unusually long. In the common
 * different-channel case confirmation arrives the moment the byline re-renders, typically
 * well under 2s, so a long backstop buys safety without adding latency.
 */
export const SETTLE_MS = 6_000;

export type Freshness = 'CONFIRMED' | 'SETTLING';

export interface FreshnessInput {
  /** The video id in the address bar, or null when this page names no video. */
  videoId: string | null;
  /** The video id the inline page data declares, if any. */
  inlineVideoId: string | null;
  /** Identity of the channel detected right now. */
  detectedKey: string | null;
  /** Identity confirmed for the PREVIOUS video, or null if there was none. */
  previousKey: string | null;
  /** Time since the navigation to the current video. */
  msSinceNav: number;
  settleMs?: number;
}

/**
 * Whether the current detection can be acted on, or is still settling after a navigation.
 *
 * CONFIRMED as soon as ANY of these hold, cheapest first:
 *  - the inline data declares the video the URL names (authoritative; a full page load)
 *  - there is no previous channel to be stale from (first view of the session)
 *  - what we detect now differs from before the nav (the byline demonstrably re-rendered)
 *  - the backstop elapsed (accept it rather than wait forever)
 */
export function channelFreshness(input: FreshnessInput): Freshness {
  const { videoId, inlineVideoId, detectedKey, previousKey, msSinceNav } = input;
  const settleMs = input.settleMs ?? SETTLE_MS;

  if (videoId !== null && inlineVideoId === videoId) return 'CONFIRMED';
  if (previousKey === null) return 'CONFIRMED';
  if (detectedKey !== previousKey) return 'CONFIRMED';
  if (msSinceNav >= settleMs) return 'CONFIRMED';

  return 'SETTLING';
}

/**
 * Extra re-check times after a navigation, in ms.
 *
 * The debounced observer alone is not enough: YouTube's post-navigation mutation storm keeps
 * resetting the debounce, so the corrective pass can be starved for seconds — that starvation
 * is what stretched the window to 5s. These fire on their own timers and cannot be reset by
 * page mutation, guaranteeing the verdict is re-evaluated whether or not the storm ever calms.
 */
export const SETTLE_RECHECK_MS: readonly number[] = [400, 900, 1_800, 3_200, SETTLE_MS + 100];
