/**
 * The "2-minute daily pass" emergency escape hatch — ONE global free window per rolling 24h
 * across the whole extension. PURE. Direct port of Android's `domain/emergency/EmergencyPass.kt`.
 *
 * Global (not per-site) semantics: using the pass on ANY blocked site opens a free window for
 * that site AND locks the pass out for every site for `LOCKOUT_MS`. The active free window itself
 * stays scoped to the site it was granted on (owned by the background service that calls this
 * module); this file only decides eligibility (the shared lockout).
 *
 * Ledger shape: `Record<string, number>` — site/domain key -> epoch-millis last-used. A plain
 * Record (not a Map) keeps this directly JSON-serializable for chrome.storage without a
 * translation layer, matching the persisted-string ledger the Android original used
 * (`key=epochMillis;…`) — [globalLastUsed] takes the MAX timestamp across ALL entries, so a
 * pre-existing per-site ledger is interpreted as "last used = the most recent of any site." A
 * fresh global use collapses the ledger to a single [GLOBAL_KEY] entry. The extension's analog of
 * Android's "package" is a domain, but the ledger format and global semantics are unchanged.
 *
 * `parse` is deliberately lenient — any malformed entry is skipped and blank/garbage input yields
 * an empty object, never an exception (persisted settings must never crash the block-decision path).
 *
 * No chrome.* imports — fully unit-testable under plain Node/vitest.
 */

/** Free window granted per use (2 minutes). */
export const PASS_DURATION_MS = 120_000;

/** Rolling global lockout after a use before the pass can be used again (24h). */
export const LOCKOUT_MS = 86_400_000;

/**
 * Reserved ledger key holding the single global last-used timestamp after a fresh use. Not a
 * real domain (domains never contain `*`), so it can never collide with a migrated per-site entry.
 */
export const GLOBAL_KEY = '*';

const ENTRY_SEPARATOR = ';';
const KEY_VALUE_SEPARATOR = '=';

/** Matches an optionally-signed integer string — the numeric grammar `parse` accepts as a value. */
const INTEGER_PATTERN = /^-?\d+$/;

/** Deserialize the ledger. Malformed entries are ignored; blank input -> empty object. Never throws. */
export function parse(raw: string): Record<string, number> {
  const result: Record<string, number> = {};
  if (raw.trim() === '') return result;

  for (const entry of raw.split(ENTRY_SEPARATOR)) {
    if (entry.trim() === '') continue;
    const idx = entry.indexOf(KEY_VALUE_SEPARATOR);
    // Require a non-empty key before '=' and a value after it.
    if (idx <= 0 || idx === entry.length - 1) continue;
    const key = entry.slice(0, idx);
    const valueRaw = entry.slice(idx + 1);
    if (!INTEGER_PATTERN.test(valueRaw)) continue;
    const millis = Number(valueRaw);
    if (!Number.isFinite(millis) || millis < 0) continue;
    result[key] = millis;
  }
  return result;
}

/** Inverse of `parse`. Round-trips (blank keys are dropped defensively). */
export function serialize(usage: Record<string, number>): string {
  return Object.entries(usage)
    .filter(([key]) => key.trim() !== '')
    .map(([key, value]) => `${key}${KEY_VALUE_SEPARATOR}${value}`)
    .join(ENTRY_SEPARATOR);
}

/**
 * The global last-used timestamp: the MAX across ALL ledger entries. This makes the lockout
 * global (any site's use counts) and migrates a legacy per-site ledger transparently. Null if the
 * pass has never been used.
 */
export function globalLastUsed(usage: Record<string, number>): number | null {
  const values = Object.values(usage);
  if (values.length === 0) return null;
  return Math.max(...values);
}

/**
 * True once `cooldownMs` has elapsed since the pass was last used on ANY site (or it never was).
 * Boundary is inclusive: exactly at `last + cooldownMs` this returns true.
 */
export function canUseGlobal(
  usage: Record<string, number>,
  now: number,
  cooldownMs: number,
): boolean {
  const last = globalLastUsed(usage);
  if (last === null) return true;
  return now - last >= cooldownMs;
}

/** Remaining global lockout in ms (0 if available now). For the "Next pass in Xh" UI hint. */
export function nextAvailableGlobalMs(
  usage: Record<string, number>,
  now: number,
  cooldownMs: number,
): number {
  const last = globalLastUsed(usage);
  if (last === null) return 0;
  return Math.max(0, last + cooldownMs - now);
}

/**
 * A fresh ledger recording a global use at `now`. Collapses to the single [GLOBAL_KEY] entry —
 * previous per-site entries are dropped because the global timestamp already dominates them.
 */
export function recordGlobal(now: number): Record<string, number> {
  return { [GLOBAL_KEY]: now };
}
