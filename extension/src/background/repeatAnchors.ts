/**
 * The repeating pause's clock for one video, where the site's grant is no use.
 *
 * WHY A SEPARATE CLOCK. Under a whitelist `/watch` is carved out of the site redirect wholesale,
 * and each video is judged in the page by the channel gate. Completing that gate grants nothing
 * at the network layer, on purpose: a site grant would open the home feed and search for N
 * minutes, and completing a pause on one video must not buy the next. So a video that passed the
 * gate runs on its own clock, kept here, and renewing it changes nothing DNR can see.
 *
 * WHAT AN ANCHOR PROVES. It exists only because the channel gate blocked this video and the user
 * sat through the pause, so it needs no report of whose video it is. Whether it still applies is
 * decided from the current settings on every read (`repeatInForce`): adding the channel to the
 * list mid-watch ends it at once.
 *
 * NOT PRUNED BY AGE. An anchor past its `until` is not stale, it is the pause being due. Dropping
 * it would turn a tab left open on the video into one that plays free. So the map is bounded the
 * way `videoChannels.ts` is, by evicting the oldest, and the rest goes with the browser session.
 */

export const REPEAT_ANCHORS_KEY = 'repeatAnchors';

/** The most videos on a clock at once. Past it the earliest clock goes, never the one just set. */
export const MAX_REPEAT_ANCHORS = 300;

/** Video id to the epoch ms its next pause is due. */
export type RepeatAnchorMap = Record<string, number>;

/** `map` with one video's clock set, PURE. */
export function withRepeatAnchor(
  map: RepeatAnchorMap,
  videoId: string,
  until: number,
  cap: number = MAX_REPEAT_ANCHORS,
): RepeatAnchorMap {
  const next: RepeatAnchorMap = { ...map, [videoId]: until };
  const excess = Object.keys(next).length - cap;
  if (excess <= 0) return next;
  const others = Object.entries(next).filter(([id]) => id !== videoId);
  others.sort(([, a], [, b]) => a - b);
  for (const [id] of others.slice(0, excess)) delete next[id];
  return next;
}

async function loadRepeatAnchors(): Promise<RepeatAnchorMap> {
  const stored = await chrome.storage.session.get(REPEAT_ANCHORS_KEY);
  const map = stored[REPEAT_ANCHORS_KEY];
  return map !== null && typeof map === 'object' ? (map as RepeatAnchorMap) : {};
}

/** When this video's next pause is due, or null if it has no clock. */
export async function repeatAnchorFor(videoId: string): Promise<number | null> {
  const map = await loadRepeatAnchors();
  return Object.prototype.hasOwnProperty.call(map, videoId) ? (map[videoId] ?? null) : null;
}

/** Start or renew a video's clock. Returns when its next pause is due. */
export async function renewRepeatAnchor(
  videoId: string,
  minutes: number,
  now: number,
): Promise<number> {
  const until = now + minutes * 60_000;
  const next = withRepeatAnchor(await loadRepeatAnchors(), videoId, until);
  await chrome.storage.session.set({ [REPEAT_ANCHORS_KEY]: next });
  return until;
}
