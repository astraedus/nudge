/**
 * Strict Mode "commitment lock" challenge. PURE.
 *
 * Direct port of Android's `domain/lock/StrictModeChallenge.kt`. Strict Mode makes weakening
 * protection (turning off the global toggle, disabling/deleting a rule, softening a config, or
 * turning Strict Mode itself off) require typing a random unlock string by hand. The friction is
 * deliberate: it gives the conscious self a moment to reconsider before undoing a block.
 * Strengthening protection is never gated.
 *
 * `isWeakening` is the extension-settings analog of Android's separate `RuleWeakening.kt` —
 * folded in here (rather than a 4th file) because both objects exist to decide what the Strict
 * Mode challenge gates, and the extension's single `NudgeSettings` blob (vs. Android's
 * per-BlockRule entity + separate global prefs) makes one combined comparison the natural shape.
 *
 * No chrome.* imports — fully unit-testable under plain Node/vitest.
 */

import { MINUTES_PER_DAY } from './settingsSchema';
import type {
  LightsOffProfile,
  LightsOffSettings,
  LightsOffStrictness,
  NudgeSettings,
  SiteRule,
} from './settingsSchema';
import type { BlockMode } from './types';

/**
 * Unambiguous charset: excludes visually-confusable glyphs (0/O, 1/l/I) so a user copying the
 * string by eye never mis-reads it. Both letter cases are included so the challenge is genuinely
 * case-sensitive. Identical to the Android charset (parity matters — same commitment device).
 */
export const CHARSET = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** Characters per display group (e.g. "k7Qm2-vX9pL"). */
const GROUP_SIZE = 5;

/** Default / suggested difficulty presets (number of raw characters to type). */
export const LENGTH_EASY = 12;
export const LENGTH_MEDIUM = 24;
export const LENGTH_HARD = 48;
export const DEFAULT_LENGTH = LENGTH_MEDIUM;

/**
 * Produces `count` uniformly random integers in `[0, maxExclusive)`. Injectable so tests can
 * supply a deterministic sequence instead of real randomness.
 */
export type RandomInts = (count: number, maxExclusive: number) => number[];

const UINT32_RANGE = 0x1_0000_0000; // 2^32

function drawUint32(): number {
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  const value = buf[0];
  if (value === undefined) {
    // Unreachable — a length-1 Uint32Array always has index 0 after getRandomValues fills it.
    // Guarding this way (rather than a non-null assertion) keeps noUncheckedIndexedAccess honest.
    throw new Error('crypto.getRandomValues did not fill the buffer');
  }
  return value;
}

/**
 * Crypto-backed uniform integers in `[0, maxExclusive)` via rejection sampling.
 *
 * This is a commitment device — its unlock string MUST come from a real CSPRNG, never
 * `Math.random()` (which is not cryptographically secure and is a documented house standard to
 * avoid in security-relevant contexts, unlike the Kotlin original which used `kotlin.random.Random`
 * on a platform where that concern doesn't apply the same way).
 *
 * Naively computing `drawUint32() % maxExclusive` is BIASED whenever `maxExclusive` doesn't evenly
 * divide 2^32 — which is every value except powers of two, and our 57-char CHARSET is no
 * exception. The low remainder classes (`0 .. (2^32 % maxExclusive) - 1`) would get one extra
 * draw each out of every 2^32 draws, subtly skewing which characters appear more often. Rejecting
 * any draw at or above `rejectionThreshold` (the largest multiple of `maxExclusive` that fits in a
 * uint32) discards exactly the draws that would cause that skew — every accepted draw then falls
 * into one of `maxExclusive` equally-sized buckets, so `draw % maxExclusive` is exactly uniform.
 */
function defaultRandomInts(count: number, maxExclusive: number): number[] {
  if (maxExclusive <= 0) throw new RangeError('maxExclusive must be positive');
  const rejectionThreshold = UINT32_RANGE - (UINT32_RANGE % maxExclusive);
  const results: number[] = [];
  while (results.length < count) {
    const draw = drawUint32();
    if (draw < rejectionThreshold) {
      results.push(draw % maxExclusive);
    }
  }
  return results;
}

/**
 * Generates a fresh random challenge string of `length` raw characters drawn from `CHARSET`.
 * Returns the RAW string (no dashes); use `forDisplay` to render it grouped.
 *
 * @param randomInts injectable for deterministic tests; defaults to a `crypto.getRandomValues`-
 *   backed, rejection-sampled generator (see `defaultRandomInts`). NEVER defaults to
 *   `Math.random()` — this string is a commitment device, not display-only randomness.
 */
export function generate(
  length: number = DEFAULT_LENGTH,
  randomInts: RandomInts = defaultRandomInts,
): string {
  const safeLength = Math.max(1, Math.trunc(length));
  const indices = randomInts(safeLength, CHARSET.length);
  let result = '';
  for (const index of indices) {
    result += CHARSET.charAt(index);
  }
  return result;
}

/**
 * Renders a raw challenge string grouped into dash-separated chunks for readability, e.g.
 * "k7Qm2vX9pLtR4wZ" -> "k7Qm2-vX9pL-tR4wZ". Display-only; never stored or compared.
 */
export function forDisplay(raw: string): string {
  const groups: string[] = [];
  for (let i = 0; i < raw.length; i += GROUP_SIZE) {
    groups.push(raw.slice(i, i + GROUP_SIZE));
  }
  return groups.join('-');
}

/**
 * Strips display dashes and surrounding whitespace, returning the raw comparable content.
 * Internal whitespace is NOT stripped — it would make a typo (an accidental space mid-string)
 * silently pass, which weakens the commitment device.
 *
 * Public so the UI can derive the live progress counter from the SAME rule `verify` compares
 * against (single source of truth: the dashes the user may type are ignored identically in the
 * counter and the match).
 */
export function normalize(value: string): string {
  return value.trim().replace(/-/g, '');
}

/**
 * Count of raw, dash-stripped characters in `value` — the unit the unlock counter shows and the
 * unit `verify` compares. Typing the code WITH or WITHOUT dashes yields the same count.
 */
export function rawLength(value: string): number {
  return normalize(value).length;
}

/**
 * Case-SENSITIVE exact match of `input` against `target`.
 *
 * Both sides are normalized first: surrounding whitespace trimmed and display dashes removed, so
 * the user may type the string with or without the dashes shown on screen. Everything else (case,
 * every character) must match exactly.
 */
export function verify(input: string, target: string): boolean {
  const normalizedTarget = normalize(target);
  if (normalizedTarget.length === 0) return false;
  return normalize(input) === normalizedTarget;
}

// ── isWeakening — extension-settings analog of Android's RuleWeakening.kt ──

/**
 * Block-mode strength ordering (higher = stronger protection), mirroring
 * RuleWeakening.kt's `modeStrength`. Anything unrecognized sorts below the known modes.
 */
function modeStrength(mode: string | null | undefined): number {
  switch (mode) {
    case 'HARD_BLOCK':
      return 3;
    case 'DELAY':
      return 2;
    case 'BREATHING':
      return 1;
    default:
      return 0;
  }
}

/** Same ordering as `modeStrength`, plus YouTube's 'INHERIT' as the weakest rung of all. */
function youtubeModeStrength(mode: 'INHERIT' | BlockMode): number {
  return mode === 'INHERIT' ? 0 : modeStrength(mode);
}

/**
 * A daily limit is weakened when an existing cap is removed (null) or raised. Adding a cap where
 * none existed, or lowering an existing cap, is strengthening. Mirrors
 * RuleWeakening.kt's `isDailyLimitWeakened`.
 */
function isDailyLimitWeakened(oldLimit: number | null, newLimit: number | null): boolean {
  if (oldLimit === null) return false; // no cap before -> any new cap (or still none) is not weaker
  if (newLimit === null) return true; // had a cap, now removed -> weaker
  return newLimit > oldLimit; // cap raised -> weaker
}

/** Per-rule weakening check — the SiteRule analog of RuleWeakening.kt's `isWeakening(old, new)`. */
function isRuleWeakened(oldRule: SiteRule, newRule: SiteRule): boolean {
  // Disabling an active rule.
  if (oldRule.enabled && !newRule.enabled) return true;
  // Softening the block mode.
  if (modeStrength(newRule.mode) < modeStrength(oldRule.mode)) return true;
  // Shortening the delay = less friction before the site opens.
  if (newRule.delaySeconds < oldRule.delaySeconds) return true;
  // Daily limit: removing it, or raising it, grants more usage.
  if (isDailyLimitWeakened(oldRule.dailyLimitMinutes, newRule.dailyLimitMinutes)) return true;
  return false;
}

// ── Lights Off axes ──

/**
 * Minutes a window covers, overnight spans included. `start === end` is an EMPTY window
 * (never active) and correctly measures 0 — the maximal narrowing.
 */
export function lightsOffWindowMinutes(startMinute: number, endMinute: number): number {
  return (((endMinute - startMinute) % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
}

/** null/empty days means "every day", so it expands to the full week for comparison. */
function daySet(days: number[] | null): Set<number> {
  return days === null || days.length === 0
    ? new Set([1, 2, 3, 4, 5, 6, 7])
    : new Set(days);
}

function strictnessStrength(strictness: LightsOffStrictness): number {
  return strictness === 'STRICT' ? 2 : 1;
}

/**
 * Per-profile weakening. Compared regardless of `enabled` on both sides, matching
 * `isRuleWeakened` — editing a currently-off window still commits you to that window later,
 * so loosening it is the same promise broken either way.
 */
function isLightsOffProfileWeakened(
  oldProfile: LightsOffProfile,
  newProfile: LightsOffProfile,
): boolean {
  // Turning the lockdown off entirely.
  if (oldProfile.enabled && !newProfile.enabled) return true;

  // A shorter window is less time protected.
  if (
    lightsOffWindowMinutes(newProfile.startMinute, newProfile.endMinute) <
    lightsOffWindowMinutes(oldProfile.startMinute, oldProfile.endMinute)
  ) {
    return true;
  }

  // Dropping a covered day. Adding days is strengthening.
  const newDays = daySet(newProfile.days);
  for (const day of daySet(oldProfile.days)) {
    if (!newDays.has(day)) return true;
  }

  // ADDING a domain to the allow-list makes more of the internet reachable during the
  // lockdown — the mirror image of the per-site rules, where adding a rule is strengthening.
  // Removing one is strengthening and stays free.
  for (const domain of newProfile.allowedDomains) {
    if (!oldProfile.allowedDomains.includes(domain)) return true;
  }

  if (strictnessStrength(newProfile.strictness) < strictnessStrength(oldProfile.strictness)) {
    return true;
  }

  return false;
}

/**
 * Lights Off weakening across the whole block: a profile removed, any profile loosened, or a
 * running manual lockdown cut short.
 *
 * Without this the Commitment Lock would be theater — a user mid-lockdown could simply add
 * the site they wanted to the allow-list and walk straight through (design §3c).
 */
function isLightsOffWeakened(
  oldLights: LightsOffSettings,
  newLights: LightsOffSettings,
): boolean {
  const newById = new Map(newLights.profiles.map((profile) => [profile.id, profile] as const));
  for (const oldProfile of oldLights.profiles) {
    const newProfile = newById.get(oldProfile.id);
    if (!newProfile) return true; // profile removed
    if (isLightsOffProfileWeakened(oldProfile, newProfile)) return true;
  }

  // Ending a manual lockdown early (or cancelling it) is weakening; extending it is not.
  // Compared without a clock, so a LAPSED manualUntil being cleared also reads as weakening —
  // the UI therefore only offers "Cancel" while the lockdown is genuinely still running, which
  // keeps that false positive off the user's path.
  if (
    oldLights.manualUntil !== null &&
    (newLights.manualUntil === null || newLights.manualUntil < oldLights.manualUntil)
  ) {
    return true;
  }

  return false;
}

/**
 * Returns true if `newSettings` is weaker protection than `oldSettings` in ANY dimension, each
 * dimension evaluated independently — softening one axis is weakening even if another is
 * strengthened at the same time (the user must justify the part that reduces protection).
 * Unchanged on every dimension -> false.
 *
 * Weakening dimensions (each documented at its check below):
 *  - globalEnabled true -> false
 *  - strictMode.enabled true -> false
 *  - a rule removed (matched by `id`)
 *  - a rule disabled, mode softened, delay shortened, or daily limit raised/removed
 *  - emergencyPass.enabled false -> true (adding an escape hatch IS weakening)
 *  - tempAllowMinutes increased
 *  - youtube.shortsMode softened (HARD_BLOCK > DELAY > BREATHING > INHERIT)
 *  - Lights Off: a profile removed, disabled, its window shortened, a covered day dropped, a
 *    domain ADDED to the allow-list, strictness lowered, or a running manual lockdown cut short
 *
 * Adding a brand-new rule (present in `new`, absent in `old`) is strengthening, never weakening.
 */
export function isWeakening(oldSettings: NudgeSettings, newSettings: NudgeSettings): boolean {
  // Axis: the master toggle. Off suppresses all enforcement — the ultimate weakening.
  if (oldSettings.globalEnabled && !newSettings.globalEnabled) return true;

  // Axis: turning Strict Mode itself off removes the commitment lock that gates every other
  // weakening action — so turning it off must itself be gated while it's still on.
  if (oldSettings.strictMode.enabled && !newSettings.strictMode.enabled) return true;

  // Axis: per-site rules, matched by id. A rule present in `old` but missing from `new` was
  // deleted — weakening. Rules present in both are compared field-by-field via isRuleWeakened.
  // A rule present only in `new` is a brand-new rule -> strengthening, not checked here.
  const newRulesById = new Map(newSettings.rules.map((r) => [r.id, r] as const));
  for (const oldRule of oldSettings.rules) {
    const newRule = newRulesById.get(oldRule.id);
    if (!newRule) return true; // rule removed
    if (isRuleWeakened(oldRule, newRule)) return true;
  }

  // Axis: the emergency pass is an escape hatch — turning it ON (false -> true) is weakening
  // even though "enabled" reads as a positive word. Turning it off is strengthening.
  if (!oldSettings.emergencyPass.enabled && newSettings.emergencyPass.enabled) return true;

  // Axis: a longer temporary-allow window grants more free access per Delay/Breathing pass.
  if (newSettings.tempAllowMinutes > oldSettings.tempAllowMinutes) return true;

  // Axis: YouTube Shorts mode softened, including softening all the way to 'INHERIT' (deferring
  // to the site rule, or to nothing if there is none, is the weakest possible stance).
  if (
    youtubeModeStrength(newSettings.youtube.shortsMode) <
    youtubeModeStrength(oldSettings.youtube.shortsMode)
  ) {
    return true;
  }

  // Axis: Lights Off — the global lockdown. Its allow-list inverts the usual polarity, so it
  // gets its own comparison rather than being folded into the per-site rules loop.
  if (isLightsOffWeakened(oldSettings.lightsOff, newSettings.lightsOff)) return true;

  return false;
}
