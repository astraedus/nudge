/**
 * The per-day "which items have I already counted" set. PURE.
 *
 * A count budget says "20 Shorts a day". Re-watching the SAME Short, or bouncing back to
 * the reel you were just on, must not spend two of those twenty: the user's mental model
 * is twenty pieces of content, and a counter that charges for a back button reads as
 * broken and is impossible to reason about. So an item is counted the first time it is
 * seen today and never again.
 *
 * ## Why one day-stamped blob rather than a key per day
 *
 * Usage rollups are stored per day and kept as history, because a chart of last week is
 * worth something. A seen-set is not: it is scaffolding for TODAY's dedupe and has no
 * value the moment the day rolls over. Keeping it under one key that carries its own day
 * stamp makes the midnight reset fall out of reading it (`coerceSeenItems` throws away a
 * blob stamped with any other day) instead of needing a sweep, and it cannot accumulate a
 * month of dead id lists in `storage.local` the way per-day keys would.
 *
 * ## Why a cap
 *
 * The list is unbounded in principle: a determined scroller can pass thousands of Shorts
 * in a day, and `storage.sync` is not involved but `storage.local` quota still is not
 * infinite. Past the cap the OLDEST ids are dropped, which is the right direction: an id
 * that falls off the end can be counted a second time if the user scrolls back that far,
 * and over-counting by a handful after 2,000 items is a strictly safer failure than an
 * unbounded write.
 */

/** Maximum item ids remembered per surface for one day. */
export const MAX_SEEN_ITEMS_PER_SURFACE = 2_000;

/** Today's seen ids, keyed by `domain#gate` surface key. */
export interface SeenItems {
  /** The local day key (`yyyy-mm-dd`) these ids belong to. */
  day: string;
  items: Record<string, string[]>;
}

export function emptySeenItems(day: string): SeenItems {
  return { day, items: {} };
}

/**
 * Normalize a stored blob for `today`.
 *
 * A blob stamped with any other day (or unreadable, or absent) reads as empty: that IS the
 * midnight reset for this structure, and it is the fail-safe direction. A stale set kept
 * across midnight would suppress a genuine first view of a Short the user re-opened today,
 * so their first item of the day would not be counted.
 */
export function coerceSeenItems(raw: unknown, today: string): SeenItems {
  if (!raw || typeof raw !== 'object') return emptySeenItems(today);
  const blob = raw as Partial<SeenItems>;
  if (blob.day !== today || !blob.items || typeof blob.items !== 'object') {
    return emptySeenItems(today);
  }
  const items: Record<string, string[]> = {};
  for (const [key, ids] of Object.entries(blob.items)) {
    if (!Array.isArray(ids)) continue;
    items[key] = ids.filter((id): id is string => typeof id === 'string');
  }
  return { day: today, items };
}

/**
 * Record `itemId` against `key` (a `domain#gate` surface key) for `today`.
 *
 * Returns a NEW state plus whether this was the first sighting. `isNew` is what the caller
 * gates the increment on, so "have I seen this" and "count it" can never disagree: one
 * function answers both, in one read-modify-write, rather than a caller checking
 * membership and then separately deciding to add.
 */
export function recordSeenItem(
  state: SeenItems,
  today: string,
  key: string,
  itemId: string,
): { state: SeenItems; isNew: boolean } {
  const current = state.day === today ? state : emptySeenItems(today);
  const seen = current.items[key] ?? [];
  if (seen.includes(itemId)) {
    return { state: current, isNew: false };
  }
  const appended = [...seen, itemId];
  const capped =
    appended.length > MAX_SEEN_ITEMS_PER_SURFACE
      ? appended.slice(appended.length - MAX_SEEN_ITEMS_PER_SURFACE)
      : appended;
  return {
    state: { day: today, items: { ...current.items, [key]: capped } },
    isNew: true,
  };
}
