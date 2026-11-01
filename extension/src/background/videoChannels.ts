/**
 * Which channel uploaded which video, as reported by the YouTube content script.
 *
 * WHY THE WORKER NEEDS THIS. A whitelisted channel's videos stay watchable after the YouTube
 * daily limit, and the time spent on them is not counted against it (see
 * `core/youtubeAccess.isWhitelistedVideoUrl`). The tracker only has the tab's URL, and a
 * `/watch?v=<id>` URL does not say whose video it is; only the page does, and only once the
 * content script's channel detection is CONFIRMED (`core/channelFreshness.ts`: for a few
 * seconds after a watch -> watch hop the byline still names the previous video's channel).
 *
 * WHAT IS STORED IS A FACT, NOT A VERDICT. The page reports "video V is by channel C"; it
 * never reports "V is allowed". Whether C is on the list is decided by the worker from the
 * CURRENT settings every time it asks, so removing a channel from the whitelist takes effect
 * on the next accounting step instead of when some cached verdict happens to expire. The
 * page reports, the worker decides, the same split as `ITEM_VIEWED`.
 *
 * Keyed by video id rather than by tab, so a tab that hops to a new video automatically has
 * no record for it, which is the fail-toward-counting direction.
 *
 * `storage.session`: in memory, gone with the browser, never synced. A video's uploader does
 * not change, but there is no reason to keep a browsing history around either.
 */

import type { ChannelForVideo } from '../core/youtubeAccess';

export const VIDEO_CHANNELS_KEY = 'videoChannels';

/** The most videos remembered at once. Past it the OLDEST report is dropped. */
export const MAX_REMEMBERED_VIDEOS = 300;

/** Longest identifier kept. Real handles and ids are far shorter; this only bounds junk. */
const MAX_IDENTIFIER_LENGTH = 128;

export interface VideoChannelRecord {
  channelId: string | null;
  handle: string | null;
  /** Epoch ms of the latest report, for oldest-first eviction. */
  at: number;
}

export type VideoChannelMap = Record<string, VideoChannelRecord>;

function cleanIdentifier(value: string | null): string | null {
  if (value === null) return null;
  const trimmed = value.trim();
  if (trimmed === '' || trimmed.length > MAX_IDENTIFIER_LENGTH) return null;
  return trimmed;
}

/**
 * `map` with one report folded in, PURE. Returns `map` itself when the report carries no
 * usable identifier, so a caller can skip the write.
 */
export function rememberVideoChannel(
  map: VideoChannelMap,
  videoId: string,
  report: { channelId: string | null; handle: string | null },
  now: number,
  cap: number = MAX_REMEMBERED_VIDEOS,
): VideoChannelMap {
  const channelId = cleanIdentifier(report.channelId);
  const handle = cleanIdentifier(report.handle);
  if (channelId === null && handle === null) return map;

  const next: VideoChannelMap = { ...map, [videoId]: { channelId, handle, at: now } };
  const entries = Object.entries(next);
  if (entries.length <= cap) return next;
  entries.sort(([, a], [, b]) => a.at - b.at);
  for (const [id] of entries.slice(0, entries.length - cap)) delete next[id];
  return next;
}

export async function loadVideoChannels(): Promise<VideoChannelMap> {
  const stored = await chrome.storage.session.get(VIDEO_CHANNELS_KEY);
  const map = stored[VIDEO_CHANNELS_KEY];
  return map !== null && typeof map === 'object' ? (map as VideoChannelMap) : {};
}

/** Record one `VIDEO_CHANNEL` report. Returns whether anything was stored. */
export async function recordVideoChannel(
  videoId: string,
  report: { channelId: string | null; handle: string | null },
  now: number,
): Promise<boolean> {
  const current = await loadVideoChannels();
  const next = rememberVideoChannel(current, videoId, report, now);
  if (next === current) return false;
  await chrome.storage.session.set({ [VIDEO_CHANNELS_KEY]: next });
  return true;
}

/** A synchronous lookup over what is stored right now, for the pure predicates. */
export async function videoChannelLookup(): Promise<ChannelForVideo> {
  const map = await loadVideoChannels();
  return (videoId) => {
    const record = Object.prototype.hasOwnProperty.call(map, videoId) ? map[videoId] : undefined;
    return record === undefined ? null : { channelId: record.channelId, handle: record.handle };
  };
}
