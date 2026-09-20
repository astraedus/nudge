/**
 * The structural backstop for a broken ENGINE INVARIANT. PURE.
 *
 * The invariant (extension/CLAUDE.md) is that if any rule applies, the verdict is a BLOCK —
 * because DNR has already redirected by the time the block page runs, so an ALLOW here
 * sends the user back to the site and straight into the redirect again. That is not a
 * harmless no-op: live QA measured 217 main-frame navigations in 8 seconds and a crashed
 * renderer when one consumer of the rule resolver was keyed differently from the rest.
 *
 * The cause of that particular break is fixed. This module exists because the NEXT one
 * should cost a visible error message instead of a pegged CPU: however the invariant gets
 * broken, the page bounces at most once per target and then stops and says so.
 *
 * Counted per TARGET and per short window on purpose. A legitimate ALLOW-and-bounce does
 * happen — a pause completes, the grant lands, the user is sent on — and that must keep
 * working; what can never be legitimate is the SAME target bouncing again moments later,
 * because nothing about the page changed in between.
 */

/** How long two bounces for one target must be apart to be treated as unrelated. */
export const LOOP_WINDOW_MS = 5_000;

/** Bounces allowed for one target inside the window before we stop and show an error. */
export const LOOP_BOUNCE_LIMIT = 2;

export interface BounceRecord {
  target: string;
  count: number;
  firstAt: number;
}

/** Storage this guard needs — `sessionStorage` in the page, a plain object in tests. */
export interface BounceStore {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
}

const STORAGE_KEY = 'nudge:redirect-bounces';

function readRecord(store: BounceStore): BounceRecord | null {
  try {
    const raw = store.getItem(STORAGE_KEY);
    if (raw === null) return null;
    const parsed = JSON.parse(raw) as Partial<BounceRecord>;
    if (
      typeof parsed.target !== 'string' ||
      typeof parsed.count !== 'number' ||
      typeof parsed.firstAt !== 'number'
    ) {
      return null;
    }
    return { target: parsed.target, count: parsed.count, firstAt: parsed.firstAt };
  } catch {
    // Storage can be unavailable or hold junk. A guard that throws would take down the very
    // page it is protecting, so an unreadable record simply means "no history".
    return null;
  }
}

function writeRecord(store: BounceStore, record: BounceRecord): void {
  try {
    store.setItem(STORAGE_KEY, JSON.stringify(record));
  } catch {
    // Full or blocked storage degrades the guard to "always allow the bounce", which is the
    // v0.2.0 behaviour — never worse than before.
  }
}

/**
 * Record an about-to-happen bounce and say whether it may proceed.
 *
 * `false` means this target has already bounced inside the window and the page must stop
 * and render its error instead.
 */
export function registerBounce(
  store: BounceStore,
  target: string,
  now: number,
): { allowed: boolean; count: number } {
  const previous = readRecord(store);
  const continuing =
    previous !== null && previous.target === target && now - previous.firstAt < LOOP_WINDOW_MS;

  const record: BounceRecord = continuing
    ? { target, count: previous.count + 1, firstAt: previous.firstAt }
    : { target, count: 1, firstAt: now };

  writeRecord(store, record);
  return { allowed: record.count < LOOP_BOUNCE_LIMIT, count: record.count };
}

/** Forget this target's history — called once a real BLOCK renders, which ends any loop. */
export function clearBounces(store: BounceStore): void {
  try {
    store.setItem(STORAGE_KEY, '');
  } catch {
    // See writeRecord.
  }
}
