/**
 * Tell the worker whose video this watch page is playing, so time on a whitelisted channel
 * is not counted against the YouTube daily limit (Anti, 2026-11-01).
 *
 * The tracker only sees the tab's URL, and `/watch?v=<id>` does not say who uploaded it.
 * The page does, once the channel detection has CONFIRMED (`core/channelFreshness.ts`).
 *
 * THE RULES, each assertable in jsdom (the reason this is a module, as for channelObserver):
 *
 *  1. WHITELIST ONLY. The exemption exists only for a stored WHITELIST
 *     (`core/youtubeAccess.limitSparesWhitelist`), so nothing is sent otherwise. The check is
 *     on the STORED mode, not the enforced one: creator mode's implicit whitelist does not
 *     earn the exemption, its own Studio exemption covers that.
 *  2. CONFIRMED ONLY. `confirmedWatchChannel` withholds the channel during the settle
 *     window, when the byline may still name the PREVIOUS video's channel. Reporting that
 *     would exempt the wrong video.
 *  3. A FACT, NOT A VERDICT. The report says "video V is by channel C"; the worker decides
 *     whether C is listed, from its own settings. A page cannot talk itself out of counting.
 *  4. ONCE PER VIDEO AND CHANNEL PER PAGE LOAD. `refresh()` runs on every mutation burst.
 *     Keyed by the video, unlike channelObserver, because the worker stores per video.
 */

import { watchPageVideoId } from '../core/youtubeAccess';
import { send } from '../ui/rpc';
import { confirmedWatchChannel, type ChannelConfig, type WatchPageOptions } from './channelFilter';

export interface VideoChannelReport {
  url: string;
  channelId: string | null;
  handle: string | null;
}

export type ReportVideoChannel = (report: VideoChannelReport) => void;

const defaultReport: ReportVideoChannel = (report) => {
  void send({ type: 'VIDEO_CHANNEL', ...report }).catch(() => {
    // The worker was asleep or the extension reloaded. A lost report means this video is
    // counted, the safe direction; the next refresh pass of a new page load re-sends it.
  });
};

export interface VideoChannelReporter {
  /** Report the current watch page's channel if confirmed and not yet reported. */
  observe(doc: Document, config: ChannelConfig, options?: WatchPageOptions): void;
}

export function createVideoChannelReporter(
  report: ReportVideoChannel = defaultReport,
): VideoChannelReporter {
  const reported = new Set<string>();

  return {
    observe(doc: Document, config: ChannelConfig, options: WatchPageOptions = {}): void {
      if (!config.enabled || config.youtube?.channelMode !== 'WHITELIST') return;
      const url = options.url ?? doc.location?.href ?? '';
      const videoId = watchPageVideoId(url);
      if (videoId === null) return;

      const detected = confirmedWatchChannel(doc, config, { ...options, url });
      if (detected === null) return;
      const channelId = detected.channelId ?? null;
      const handle = detected.handle ?? null;
      if (channelId === null && handle === null) return;

      const key = [videoId, channelId ?? '', (handle ?? '').toLowerCase()].join('|');
      if (reported.has(key)) return;
      reported.add(key);
      report({ url, channelId, handle });
    },
  };
}
