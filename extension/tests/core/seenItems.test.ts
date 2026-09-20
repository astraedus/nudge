import { describe, expect, it } from 'vitest';

import {
  MAX_SEEN_ITEMS_PER_SURFACE,
  coerceSeenItems,
  emptySeenItems,
  recordSeenItem,
} from '../../src/core/seenItems';

/**
 * The per-day "which items have I already counted" set (v0.3). It exists so re-watching
 * the SAME Short, or bouncing back to the reel you were just on, does not spend two of the
 * day's twenty — the user's mental model is twenty PIECES of content, and a counter that
 * charges twice for a back button reads as broken.
 */

const TODAY = '2026-09-20';
const YESTERDAY = '2026-09-19';
const SHORTS_KEY = 'youtube.com#shorts';

describe('emptySeenItems', () => {
  it('is an empty items map stamped with the given day', () => {
    expect(emptySeenItems(TODAY)).toEqual({ day: TODAY, items: {} });
  });
});

describe('recordSeenItem', () => {
  it('the first sighting of an item today is new', () => {
    const { state, isNew } = recordSeenItem(emptySeenItems(TODAY), TODAY, SHORTS_KEY, 'abc');
    expect(isNew).toBe(true);
    expect(state.items[SHORTS_KEY]).toEqual(['abc']);
  });

  it('a second sighting of the SAME item is not new — re-watching must not spend a second item', () => {
    const first = recordSeenItem(emptySeenItems(TODAY), TODAY, SHORTS_KEY, 'abc');
    const second = recordSeenItem(first.state, TODAY, SHORTS_KEY, 'abc');
    expect(second.isNew).toBe(false);
    // The list is not appended to a second time either.
    expect(second.state.items[SHORTS_KEY]).toEqual(['abc']);
  });

  it('is pure — returns a new object and never mutates the state it was given', () => {
    const before = emptySeenItems(TODAY);
    const snapshot = JSON.parse(JSON.stringify(before));
    recordSeenItem(before, TODAY, SHORTS_KEY, 'abc');
    expect(before).toEqual(snapshot);
  });

  it('keeps different surfaces independent — the same id on two surfaces is two new sightings', () => {
    const first = recordSeenItem(emptySeenItems(TODAY), TODAY, SHORTS_KEY, 'abc');
    const second = recordSeenItem(first.state, TODAY, 'instagram.com#reels', 'abc');
    expect(second.isNew).toBe(true);
    expect(second.state.items[SHORTS_KEY]).toEqual(['abc']);
    expect(second.state.items['instagram.com#reels']).toEqual(['abc']);
  });

  it('drops the OLDEST id once the per-surface cap is reached, rather than refusing new ids', () => {
    let state = emptySeenItems(TODAY);
    for (let i = 0; i < MAX_SEEN_ITEMS_PER_SURFACE; i++) {
      state = recordSeenItem(state, TODAY, 'k', `id-${i}`).state;
    }
    expect(state.items.k).toHaveLength(MAX_SEEN_ITEMS_PER_SURFACE);
    expect(state.items.k?.[0]).toBe('id-0');

    const overflowed = recordSeenItem(state, TODAY, 'k', 'id-overflow');
    expect(overflowed.isNew).toBe(true);
    expect(overflowed.state.items.k).toHaveLength(MAX_SEEN_ITEMS_PER_SURFACE);
    // The single oldest id (id-0) fell off the front; everything else shifts, the new id
    // lands at the end. Over-counting a handful of items past 2,000/day is the accepted,
    // safer failure direction over an unbounded write.
    expect(overflowed.state.items.k?.[0]).toBe('id-1');
    expect(overflowed.state.items.k).toContain('id-overflow');
    expect(overflowed.state.items.k).not.toContain('id-0');
  });

  it('resets to empty when recording against a state stamped for a DIFFERENT day', () => {
    const stale = { day: YESTERDAY, items: { k: ['old'] } };
    const { state, isNew } = recordSeenItem(stale, TODAY, 'k', 'new');
    expect(state.day).toBe(TODAY);
    expect(isNew).toBe(true);
    expect(state.items.k).toEqual(['new']);
    // Yesterday's list for this surface is gone entirely, not merged with today's.
    expect(Object.keys(state.items)).toEqual(['k']);
  });
});

describe('coerceSeenItems', () => {
  it('reads a blob stamped with TODAY as-is', () => {
    const raw = { day: TODAY, items: { k: ['a', 'b'] } };
    expect(coerceSeenItems(raw, TODAY)).toEqual({ day: TODAY, items: { k: ['a', 'b'] } });
  });

  it('reads a blob stamped with any OTHER day as empty — this IS the midnight reset', () => {
    // A stale set kept across midnight would suppress a genuine first view of a Short the
    // user re-opens today, so their first item of the new day would silently not count.
    const raw = { day: YESTERDAY, items: { k: ['a', 'b'] } };
    expect(coerceSeenItems(raw, TODAY)).toEqual(emptySeenItems(TODAY));
  });

  it('reads garbage (null, undefined, a string, a number, an array) as empty', () => {
    for (const garbage of [null, undefined, 'nope', 42, [], true]) {
      expect(coerceSeenItems(garbage, TODAY)).toEqual(emptySeenItems(TODAY));
    }
  });

  it('reads a same-day blob with a missing or malformed items map as empty', () => {
    expect(coerceSeenItems({ day: TODAY }, TODAY)).toEqual(emptySeenItems(TODAY));
    expect(coerceSeenItems({ day: TODAY, items: 'not an object' }, TODAY)).toEqual(
      emptySeenItems(TODAY),
    );
    expect(coerceSeenItems({ day: TODAY, items: null }, TODAY)).toEqual(emptySeenItems(TODAY));
  });

  it('drops a non-array value under an item key rather than keeping or coercing it', () => {
    const raw = { day: TODAY, items: { k: ['a'], junk: 'not an array' } };
    const result = coerceSeenItems(raw, TODAY);
    expect(result.items.k).toEqual(['a']);
    expect(result.items.junk).toBeUndefined();
  });

  it('filters non-string entries out of an id array, keeping the valid ones', () => {
    const raw = { day: TODAY, items: { k: ['a', 42, null, undefined, 'b'] } };
    expect(coerceSeenItems(raw, TODAY).items.k).toEqual(['a', 'b']);
  });

  it('does not mutate the input value', () => {
    const raw = { day: TODAY, items: { k: ['a', 'b'] } };
    const snapshot = JSON.parse(JSON.stringify(raw));
    coerceSeenItems(raw, TODAY);
    expect(raw).toEqual(snapshot);
  });
});
