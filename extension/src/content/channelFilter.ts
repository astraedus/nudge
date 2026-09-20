/**
 * Channel lists applied to the page: feed composition, the watch-page verdict, and the
 * gray-screen colour flip.
 *
 * Split from youtube.ts so all three are testable in jsdom without the SPA controller:
 * every function takes its root and config as arguments and touches nothing global.
 *
 * The DECISION lives in core/channels.ts (pure, exhaustively tested); this module only
 * finds the channel (content/channelDetection.ts) and applies the answer to the DOM. Keeping
 * "what does this mean" and "what does the page look like" apart is what let the decision
 * matrix — including the fail-open unknown-channel case — be tested without any DOM at all.
 */

import {
  decideChannel,
  decideWatchGate,
  shouldShowInColor,
  type ChannelProbe,
  type WatchGateVerdict,
} from '../core/channels';
import {
  channelFreshness,
  channelKey,
  SETTLE_COLOR_MS,
  type Freshness,
} from '../core/channelFreshness';
import type { SiteConfig } from '../core/protocol';
import { DEFAULT_DELAY_SECONDS } from '../core/settingsSchema';
import {
  detectCardChannel,
  detectWatchChannel,
  type DetectedChannel,
  feedCards,
  inlineVideoId,
  videoIdFromUrl,
} from './channelDetection';
import {
  CHANNEL_HIDDEN_CLASS,
  COLOR_CLASS,
  isChannellessCard,
  NUDGE_OVERLAY_ID,
  pageTypeFor,
} from './selectors';

/**
 * The slice of the worker's resolved answer this module needs.
 *
 * It carries the SITE's state (`siteMode`, `siteApplies`, `siteDelaySeconds`) as well as
 * the channel lists, because since v0.2 the channel list is not only a feature of its own —
 * when the youtube.com rule is in force it becomes that rule's exception list, and the
 * mode a disallowed video is held behind comes from the SITE. See `decideWatchGate`.
 */
export type ChannelConfig = Pick<
  SiteConfig,
  'enabled' | 'siteMode' | 'siteApplies' | 'siteDelaySeconds' | 'grayscale' | 'youtube'
>;

/** Channel lists switched fully off — what a page with no YouTube rule behaves like. */
const NO_CHANNEL_LISTS: NonNullable<SiteConfig['youtube']> = {
  channelMode: 'OFF',
  channels: [],
  channelBlockMode: 'DELAY',
  channelDelaySeconds: DEFAULT_DELAY_SECONDS,
  disableAutoplay: false,
};

/**
 * `SiteConfig.youtube` is null on every platform but YouTube (and on a YouTube page no rule
 * covers). Normalising it once here keeps every call site below free of the same null check,
 * and makes "no rule" behave identically to "rule with the lists switched off" rather than
 * being a second, subtly different code path.
 */
function channelSettings(config: ChannelConfig): NonNullable<SiteConfig['youtube']> {
  return config.youtube ?? NO_CHANNEL_LISTS;
}

export interface ChannelFilterResult {
  /** Cards newly hidden this pass. */
  hidden: number;
  /** Cards newly revealed this pass. */
  revealed: number;
  /** Cards whose channel could not be identified at all. */
  unidentified: number;
}

function probeOf(detected: { channelId: string | null; handle: string | null } | null): ChannelProbe {
  return { channelId: detected?.channelId ?? null, handle: detected?.handle ?? null };
}

/**
 * Hide feed cards whose channel the list disallows.
 *
 * Reconcile-and-diff, like the other appliers: compute the set that SHOULD be hidden right
 * now, then reveal anything currently hidden that is not in it. That is what makes flipping
 * the feature off restore the feed without a reload.
 *
 * Not page-type scoped: feed cards appear on the home feed, subscriptions, search results
 * and the watch sidebar alike, and a card is a card wherever it shows up.
 *
 * A card whose channel cannot be identified is LEFT VISIBLE, matching `decideChannel`'s
 * documented fail-open: a detection failure must degrade to "YouTube looks normal", never to
 * "the feed is empty and the extension looks broken".
 */
export function applyChannelFilter(
  root: Document | Element,
  config: ChannelConfig,
  options: { warn?: (message: string) => void } = {},
): ChannelFilterResult {
  const result: ChannelFilterResult = { hidden: 0, revealed: 0, unidentified: 0 };
  const channels = channelSettings(config);
  const active = config.enabled && channels.channelMode !== 'OFF';

  const shouldHide = new Set<Element>();
  if (active) {
    for (const card of feedCards(root)) {
      if (card.id === NUDGE_OVERLAY_ID || card.closest(`#${NUDGE_OVERLAY_ID}`)) continue;
      const detected = detectCardChannel(card);
      if (detected === null) {
        // Only count cards that SHOULD have had a channel. Ads and Shorts lockups never do,
        // so counting them made the degraded-detection canary fire on every normal feed.
        if (!isChannellessCard(card)) result.unidentified += 1;
        continue;
      }
      // Feed composition keeps the ORIGINAL fail-open decision (`decideChannel`), not the
      // watch gate's site-default version. Hiding a card is not the same act as refusing to
      // play a video: the card is a link, the site rule still stands behind it, and a feed
      // that empties itself because a selector rotted looks like a broken extension rather
      // than an enforced rule.
      const verdict = decideChannel({
        mode: channels.channelMode,
        channels: channels.channels,
        probe: probeOf(detected),
        blockMode: channels.channelBlockMode,
      });
      if (verdict.action === 'BLOCK') shouldHide.add(card);
    }
  }

  for (const card of shouldHide) {
    if (card.classList.contains(CHANNEL_HIDDEN_CLASS)) continue;
    card.classList.add(CHANNEL_HIDDEN_CLASS);
    result.hidden += 1;
  }

  for (const hiddenCard of Array.from(root.querySelectorAll(`.${CHANNEL_HIDDEN_CLASS}`))) {
    if (shouldHide.has(hiddenCard)) continue;
    hiddenCard.classList.remove(CHANNEL_HIDDEN_CLASS);
    result.revealed += 1;
  }

  if (active) warnIfDetectionDegraded(result, options.warn ?? defaultDetectionWarn);

  return result;
}

/** One warning per page load; the observer re-runs this constantly. */
let warnedAboutDetection = false;

/** Test/reset seam for the dedupe flag. */
export function resetDetectionWarning(): void {
  warnedAboutDetection = false;
}

const defaultDetectionWarn: (message: string) => void = (message) => {
  if (warnedAboutDetection) return;
  warnedAboutDetection = true;
  console.warn(message);
};

/**
 * Make the fail-open OBSERVABLE.
 *
 * An unidentifiable channel is deliberately allowed through (see `decideChannel`), which is
 * the right call, but with no signal at all, YouTube changing its DOM would degrade the
 * channel filter into a no-op that looks exactly like "the user has nothing on their list".
 * A cheap console canary means selector rot shows up the first time anyone opens devtools,
 * rather than only when a user notices their whitelist quietly stopped working.
 *
 * Only fires when SOME cards resolved and others did not: a page where nothing resolved is
 * usually just a page with no feed cards on it, and a canary that cries wolf gets ignored.
 */
function warnIfDetectionDegraded(
  result: ChannelFilterResult,
  warn: (message: string) => void,
): void {
  const identified = result.hidden + result.revealed;
  if (result.unidentified === 0) return;
  if (identified === 0) return;

  warn(
    `[nudge] Channel filtering is degraded: ${result.unidentified} video card(s) on this ` +
      `page had no identifiable channel, so they were left visible rather than filtered. ` +
      `This usually means YouTube changed its DOM. Please report it at ` +
      `https://github.com/astraedus/nudge/issues so the selectors can be updated.`,
  );
}

/** How a caller identifies which video the page is showing, for the settle window. */
export interface WatchPageOptions {
  url?: string;
  previousKey?: string | null;
  msSinceNav?: number;
}

/**
 * The channel on this watch page, together with whether the settle window trusts it yet.
 *
 * ONE implementation for both consumers on purpose. The gate uses it to decide whether to
 * show an interstitial; the channel observer uses it to decide whether the observation is
 * safe to PERSIST. A second copy of "has this settled yet" is exactly how a stale id would
 * end up merged into a stored entry — the same P0 the settle window exists to prevent,
 * except written to storage instead of shown on screen for three seconds.
 *
 * `detected` is null when nothing identifiable was found, which is NOT the same as settling:
 * a CONFIRMED miss is the documented unknown-channel case and the gate must still answer it.
 */
function detectWithFreshness(
  doc: Document,
  url: string,
  options: WatchPageOptions,
): { detected: DetectedChannel | null; freshness: Freshness } {
  const detected = detectWatchChannel(doc, { url });
  const freshness = channelFreshness({
    videoId: videoIdFromUrl(url),
    inlineVideoId: inlineVideoId(doc),
    detectedKey: channelKey(detected),
    previousKey: options.previousKey ?? null,
    msSinceNav: options.msSinceNav ?? Number.POSITIVE_INFINITY,
  });
  return { detected, freshness };
}

/**
 * The channel this watch page is CONFIRMED to be showing, for callers that want the channel
 * itself rather than a verdict about it (see `content/channelObserver.ts`).
 *
 * Returns null on every page that is not a settled watch page, and while the channel lists
 * are off: with no list there is nothing an observation could ever teach.
 */
export function confirmedWatchChannel(
  doc: Document,
  config: ChannelConfig,
  options: WatchPageOptions = {},
): DetectedChannel | null {
  const channels = channelSettings(config);
  if (!config.enabled || channels.channelMode === 'OFF') return null;
  const url = options.url ?? doc.location?.href ?? '';
  if (pageTypeFor(url) !== 'watch') return null;

  const { detected, freshness } = detectWithFreshness(doc, url, options);
  return freshness === 'CONFIRMED' ? detected : null;
}

/**
 * The gate verdict for the CURRENT watch page, or null when channel lists are off, this is
 * not a watch page, or the detection has not settled yet.
 *
 * The mode a BLOCK carries comes from `decideWatchGate`, which is where the site rule and
 * the channel rule are reconciled — this function's only jobs are finding the channel and
 * refusing to answer while the byline may still describe the previous video.
 */
export function watchChannelVerdict(
  doc: Document,
  config: ChannelConfig,
  options: WatchPageOptions = {},
): WatchGateVerdict | null {
  const channels = channelSettings(config);
  if (!config.enabled || channels.channelMode === 'OFF') return null;
  const url = options.url ?? doc.location?.href ?? '';
  if (pageTypeFor(url) !== 'watch') return null;

  // Hold fire while the byline may still describe the PREVIOUS video. Returning null means
  // "no interstitial yet", NOT "allowed" - the caller simply does not gate, which is the same
  // fail-open we already take for an unidentifiable channel, and it is what stops an allowed
  // channel being accused for several seconds after a navigation.
  const { detected, freshness } = detectWithFreshness(doc, url, options);
  if (freshness === 'SETTLING') return null;

  return decideWatchGate({
    mode: channels.channelMode,
    channels: channels.channels,
    probe: probeOf(detected),
    siteMode: config.siteMode,
    siteApplies: config.siteApplies,
    siteDelaySeconds: config.siteDelaySeconds,
    channelBlockMode: channels.channelBlockMode,
    channelDelaySeconds: channels.channelDelaySeconds,
  });
}

/**
 * Flip <html> between grayscale and colour.
 *
 * The grayscale itself comes from grayscale.css, registered by the service worker only while
 * the feature is on (see background/grayscale.ts) — so when gray-screen is off this function
 * only has to make sure it isn't leaving a stale colour class behind.
 *
 * Colour is a REWARD for a positively-identified allowed channel. An unidentified channel
 * stays gray, which is the opposite bias to blocking: failing toward gray is harmless, while
 * failing toward blocked would break YouTube.
 */
export function applyGrayColor(
  doc: Document,
  config: ChannelConfig,
  options: { url?: string; previousKey?: string | null; msSinceNav?: number } = {},
): boolean {
  const root = doc.documentElement;
  if (root === null) return false;

  const channels = channelSettings(config);

  // `grayscale` is a PER-SITE toggle since v0.2 (it was a YouTube-only global before), and
  // the worker registers the stylesheet only for sites that have it on. The colour REWARD
  // below stays YouTube-only, because it is the channel list that earns it — every other
  // grayscaled site is simply gray.
  if (!config.enabled || !config.grayscale) {
    root.classList.remove(COLOR_CLASS);
    return false;
  }

  const url = options.url ?? doc.location?.href ?? '';
  const pageType = pageTypeFor(url);
  // Only a watch or channel page has a single channel whose identity could earn colour.
  // A feed is a mix of many, so it stays gray.
  const detected =
    pageType === 'watch' || pageType === 'channel' ? detectWatchChannel(doc, { url }) : null;

  // Staying gray through the settle window is the safe half of the tradeoff: an extra second
  // of grayscale on an allowed video is unnoticeable, whereas flashing COLOUR onto a channel
  // the user is avoiding hands them exactly the hit the feature exists to remove.
  const settling =
    channelFreshness({
      videoId: videoIdFromUrl(url),
      inlineVideoId: inlineVideoId(doc),
      detectedKey: channelKey(detected),
      previousKey: options.previousKey ?? null,
      msSinceNav: options.msSinceNav ?? Number.POSITIVE_INFINITY,
      // Colour gets the shorter backstop, see SETTLE_COLOR_MS.
      settleMs: SETTLE_COLOR_MS,
    }) === 'SETTLING';

  const inColor =
    !settling &&
    shouldShowInColor({
      mode: channels.channelMode,
      channels: channels.channels,
      probe: probeOf(detected),
    });

  root.classList.toggle(COLOR_CLASS, inColor);
  return inColor;
}
