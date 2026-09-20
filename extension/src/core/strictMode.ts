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

import { gateModeStrength, type GateId, type HideId } from './platforms';
import type {
  ChannelEntry,
  ChannelListMode,
  NudgeSettings,
  SiteFeatures,
  SiteRule,
  YoutubeFeatureSettings,
} from './settingsSchema';
import type { SiteMode } from './types';

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

/**
 * Channel-list strength. WHITELIST is the strongest stance because it is default-DENY:
 * anything not named is blocked. BLACKLIST is default-allow, so it only ever removes what
 * is named. OFF is no stance at all.
 *
 * This ordering is what makes "switch my whitelist to a blacklist" a weakening even though
 * both are "a channel list is configured" — and it is the switch a user reaches for at
 * exactly the moment the whitelist is doing its job.
 */
function channelModeStrength(mode: ChannelListMode): number {
  switch (mode) {
    case 'WHITELIST':
      return 2;
    case 'BLACKLIST':
      return 1;
    default:
      return 0;
  }
}

/** A stable identity for a channel entry — either identifier matches (see ChannelEntry). */
function channelMatches(a: ChannelEntry, b: ChannelEntry): boolean {
  return (
    (a.channelId !== null && a.channelId === b.channelId) ||
    (a.handle !== null && a.handle === b.handle)
  );
}

function hasChannel(list: readonly ChannelEntry[], entry: ChannelEntry): boolean {
  return list.some((candidate) => channelMatches(candidate, entry));
}

/**
 * Whether the YouTube channel configuration got weaker.
 *
 * The list itself weakens in OPPOSITE directions depending on the mode, which is the whole
 * reason this cannot be a generic "did the array shrink" check: on a BLACKLIST, removing a
 * channel un-blocks it; on a WHITELIST, ADDING a channel un-blocks it. A single
 * length-based rule would gate the wrong half of the cases and wave the other half
 * through — and the waved-through half is the one a user reaches for impulsively.
 */
function isYoutubeFeatureWeakened(
  oldYt: YoutubeFeatureSettings,
  newYt: YoutubeFeatureSettings,
): boolean {
  if (channelModeStrength(newYt.channelMode) < channelModeStrength(oldYt.channelMode)) {
    return true;
  }
  if (modeStrength(newYt.channelBlockMode) < modeStrength(oldYt.channelBlockMode)) return true;
  if (newYt.channelDelaySeconds < oldYt.channelDelaySeconds) return true;
  if (oldYt.disableAutoplay && !newYt.disableAutoplay) return true;

  // Only judge the list against the mode that is now in force; a mode CHANGE is already
  // covered above, and re-judging the list under the old mode would double-gate it.
  if (newYt.channelMode === 'BLACKLIST') {
    for (const entry of oldYt.channels) {
      if (!hasChannel(newYt.channels, entry)) return true; // un-blocked a blocked channel
    }
  }
  if (newYt.channelMode === 'WHITELIST') {
    for (const entry of newYt.channels) {
      if (!hasChannel(oldYt.channels, entry)) return true; // allowed something new
    }
  }
  return false;
}

/**
 * Whether a site's feature block got weaker on any axis: a gate softened, a gate's budget
 * raised or removed, a hide switched off, or the YouTube channel config weakened.
 *
 * Losing the features object entirely (non-null -> null) counts as weakening, because
 * every gate and hide it carried stops being enforced.
 */
function areFeaturesWeakened(
  oldFeatures: SiteFeatures | null,
  newFeatures: SiteFeatures | null,
): boolean {
  if (oldFeatures === null) return false;
  if (newFeatures === null) return true;

  for (const [gateId, oldGate] of Object.entries(oldFeatures.gates)) {
    const newGate = newFeatures.gates[gateId as GateId];
    if (newGate === undefined) return true;
    if (gateModeStrength(newGate.mode) < gateModeStrength(oldGate.mode)) return true;
    if (newGate.delaySeconds < oldGate.delaySeconds) return true;
    if (isDailyLimitWeakened(oldGate.dailyLimitMinutes, newGate.dailyLimitMinutes)) return true;
  }

  for (const [hideId, wasHidden] of Object.entries(oldFeatures.hides)) {
    if (wasHidden === true && newFeatures.hides[hideId as HideId] !== true) return true;
  }

  if (oldFeatures.youtube !== undefined) {
    if (newFeatures.youtube === undefined) return true;
    if (isYoutubeFeatureWeakened(oldFeatures.youtube, newFeatures.youtube)) return true;
  }
  return false;
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
  // Grayscale off is a weakening; grayscale on never is. (The popup exposes this as a
  // one-tap toggle, so it is the single easiest weakening in the product to reach for.)
  if (oldRule.grayscale && !newRule.grayscale) return true;
  // The schedule window's own mode is a second, independent mode axis — softening what
  // happens INSIDE the window is a weakening even when the default behaviour is untouched.
  if (isScheduleWeakened(oldRule, newRule)) return true;
  if (areFeaturesWeakened(oldRule.features, newRule.features)) return true;
  return false;
}

/**
 * What actually happens INSIDE the schedule window: the override when one is active,
 * otherwise the rule's own default behaviour.
 *
 * Collapsing "has a schedule" and "has no schedule" into one value is what makes DELETING
 * a schedule comparable to softening one. Since v0.2 a schedule can go either way — an
 * ALLOW rule with a Hard Block window is the "block only during work hours" shape — so
 * removing a window is a weakening when the window was blocking and a STRENGTHENING when
 * it was an allowance. A naive "the schedule disappeared, gate it" rule would burn a
 * challenge on deleting a lunchtime exception, and users who are made to solve challenges
 * for strengthening their own settings stop trusting the gate.
 */
function effectiveInWindow(rule: SiteRule): { mode: SiteMode; delaySeconds: number } {
  const schedule = rule.schedule;
  return schedule !== null && schedule.enabled
    ? { mode: schedule.mode, delaySeconds: schedule.delaySeconds }
    : { mode: rule.mode, delaySeconds: rule.delaySeconds };
}

function isScheduleWeakened(oldRule: SiteRule, newRule: SiteRule): boolean {
  const before = effectiveInWindow(oldRule);
  const after = effectiveInWindow(newRule);
  if (modeStrength(after.mode) < modeStrength(before.mode)) return true;
  if (modeStrength(after.mode) > modeStrength(before.mode)) return false;
  return after.delaySeconds < before.delaySeconds;
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

  return false;
}
