import { beforeEach, describe, expect, it } from 'vitest';

import {
  clearBounces,
  registerBounce,
  LOOP_WINDOW_MS,
  type BounceStore,
} from '../../src/core/redirectLoopGuard';

/**
 * The backstop for a broken ENGINE INVARIANT. Live QA measured 217 main-frame navigations
 * in 8 seconds and a crashed renderer when the block page was told ALLOW for a URL DNR had
 * just redirected. That specific cause is fixed; this exists so the NEXT one costs a visible
 * error message instead of a pegged CPU.
 *
 * The tension the cases pin: a legitimate ALLOW-and-bounce DOES happen (a pause completes,
 * the grant lands, the user is sent on) and must keep working. Only the SAME target bouncing
 * again moments later is impossible, because nothing about the page changed in between.
 */

function memoryStore(): BounceStore & { raw: Map<string, string> } {
  const raw = new Map<string, string>();
  return {
    raw,
    getItem: (key) => raw.get(key) ?? null,
    setItem: (key, value) => void raw.set(key, value),
  };
}

let store: ReturnType<typeof memoryStore>;

beforeEach(() => {
  store = memoryStore();
});

describe('bouncing off the block page', () => {
  it('lets the first bounce through, because that is the normal case', () => {
    expect(registerBounce(store, 'https://site.test/a', 1_000).allowed).toBe(true);
  });

  it('stops the second bounce for the same target inside the window', () => {
    registerBounce(store, 'https://site.test/a', 1_000);
    const second = registerBounce(store, 'https://site.test/a', 1_200);

    expect(second.allowed).toBe(false);
    expect(second.count).toBe(2);
  });

  it('keeps refusing while the loop keeps trying', () => {
    registerBounce(store, 'https://site.test/a', 1_000);
    registerBounce(store, 'https://site.test/a', 1_100);
    expect(registerBounce(store, 'https://site.test/a', 1_200).allowed).toBe(false);
  });

  it('allows a DIFFERENT target straight away', () => {
    registerBounce(store, 'https://site.test/a', 1_000);
    expect(registerBounce(store, 'https://site.test/b', 1_050).allowed).toBe(true);
  });

  it('allows the same target again once the window has passed', () => {
    // Visiting a site, being blocked, completing a pause and coming back an hour later is
    // an ordinary day, not a loop.
    registerBounce(store, 'https://site.test/a', 1_000);
    const later = registerBounce(store, 'https://site.test/a', 1_000 + LOOP_WINDOW_MS + 1);

    expect(later.allowed).toBe(true);
    expect(later.count).toBe(1);
  });

  it('forgets the history once a real block renders', () => {
    registerBounce(store, 'https://site.test/a', 1_000);
    clearBounces(store);

    expect(registerBounce(store, 'https://site.test/a', 1_200).allowed).toBe(true);
  });
});

describe('when storage is unusable', () => {
  /**
   * A private window, cleared site data, or a full quota. The guard protects the block page,
   * so it must never be the thing that breaks it: degrading to v0.2.0 behaviour (always
   * bounce) is acceptable; throwing is not.
   */
  const throwing: BounceStore = {
    getItem: () => {
      throw new Error('blocked');
    },
    setItem: () => {
      throw new Error('blocked');
    },
  };

  it('allows the bounce rather than throwing into the page', () => {
    expect(() => registerBounce(throwing, 'https://site.test/a', 1_000)).not.toThrow();
    expect(registerBounce(throwing, 'https://site.test/a', 1_000).allowed).toBe(true);
  });

  it('survives a clear', () => {
    expect(() => clearBounces(throwing)).not.toThrow();
  });

  it('treats junk in storage as no history', () => {
    const junk = memoryStore();
    junk.raw.set('nudge:redirect-bounces', 'not json at all');

    expect(registerBounce(junk, 'https://site.test/a', 1_000).allowed).toBe(true);
  });
});
