/**
 * Tell the worker about a channel we positively identified, so the stored entry can learn
 * the identifier the user never typed.
 *
 * WHY A MODULE AND NOT THREE LINES IN youtube.ts. Everything here is a POLICY decision with
 * a way to get it wrong — when an observation is trustworthy, when it is worth sending, and
 * how often — and youtube.ts is the one file in this layer that cannot be unit-tested
 * without the SPA controller. Keeping the policy out here makes every rule below assertable
 * in jsdom, and leaves youtube.ts with a single call.
 *
 * THE THREE RULES:
 *
 *  1. CONFIRMED ONLY. `confirmedWatchChannel` withholds the channel while the settle window
 *     is open, because for ~1.5-5s after a watch -> watch hop the owner byline still names
 *     the PREVIOUS video's channel (core/channelFreshness.ts). Acting on that is already a
 *     documented P0 when it only shows an interstitial for three seconds; writing it into
 *     the user's channel list would be the same mistake made PERMANENT. This module never
 *     decides freshness itself — it asks the one function that owns that question.
 *
 *  2. WORTH SENDING. An observation that carries a single identifier and no name teaches
 *     nothing an entry could not already know: matching found the entry BY that identifier,
 *     so the entry already has it. Only both identifiers together (the missing-axis case) or
 *     an identifier plus a real author name (the display-name case) can enrich anything, so
 *     everything else is dropped before it costs a message.
 *
 *  3. ONCE PER DISTINCT OBSERVATION PER PAGE LOAD. `refresh()` runs on every mutation burst
 *     and on five settle timers per navigation, so an un-deduped send would fire dozens of
 *     times for one video. The key is the observation itself, not the video: watching ten
 *     videos by one channel is one message, and a genuinely new channel still gets through.
 */

import type { ChannelObservation } from '../core/channels';
import { send } from '../ui/rpc';
import { confirmedWatchChannel, type ChannelConfig, type WatchPageOptions } from './channelFilter';

export interface ChannelObserver {
  /**
   * Report the current page's channel if it is confirmed, informative, and not already
   * reported. Safe to call on every refresh pass; it is a no-op the vast majority of the time.
   */
  observe(doc: Document, config: ChannelConfig, options?: WatchPageOptions): void;
}

/** What the worker is told. Fire-and-forget: nothing on the page depends on the answer. */
export type ReportObservation = (observation: ChannelObservation) => void;

const defaultReport: ReportObservation = (observation) => {
  void send({
    type: 'CHANNEL_OBSERVED',
    channelId: observation.channelId ?? null,
    handle: observation.handle ?? null,
    displayName: observation.displayName ?? null,
  }).catch(() => {
    // The worker was asleep or the extension reloaded mid-navigation. Enrichment is pure
    // upside; losing one observation costs nothing, and the next video re-offers it.
  });
};

/** Identity of an observation, so the same one is never reported twice per page load. */
function observationKey(observation: ChannelObservation): string {
  const handle = observation.handle ?? '';
  return [
    observation.channelId ?? '',
    handle.replace(/^@/, '').toLowerCase(),
    observation.displayName ?? '',
  ].join('|');
}

/**
 * Can this observation teach the stored list anything at all?
 *
 * Both identifiers fills a missing axis; one identifier plus a real name can still upgrade a
 * `@handle`/id placeholder. One identifier alone cannot: whatever entry it matches was
 * matched BY that identifier and therefore already holds it.
 */
function isInformative(observation: ChannelObservation): boolean {
  const hasId = (observation.channelId ?? '') !== '';
  const hasHandle = (observation.handle ?? '') !== '';
  const hasName = (observation.displayName ?? '').trim() !== '';
  if (hasId && hasHandle) return true;
  return (hasId || hasHandle) && hasName;
}

/**
 * Build an observer with its own per-page-load memory.
 *
 * `report` is injectable so the rules above can be asserted without a chrome runtime; the
 * production default sends `CHANNEL_OBSERVED`.
 */
export function createChannelObserver(report: ReportObservation = defaultReport): ChannelObserver {
  // One short string per distinct channel watched in this page load. A document that
  // outlives hundreds of SPA hops holds hundreds of them, which is nothing.
  const reported = new Set<string>();

  return {
    observe(doc: Document, config: ChannelConfig, options: WatchPageOptions = {}): void {
      const detected = confirmedWatchChannel(doc, config, options);
      if (detected === null) return;

      const observation: ChannelObservation = {
        channelId: detected.channelId,
        // Sent as detected, leading '@' and all. `enrichEntries` owns the normalisation, so
        // there is exactly one place that decides what a stored handle looks like.
        handle: detected.handle,
        displayName: detected.displayName,
      };
      if (!isInformative(observation)) return;

      const key = observationKey(observation);
      if (reported.has(key)) return;
      reported.add(key);
      report(observation);
    },
  };
}
