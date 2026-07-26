/**
 * Rotating message pools shown on the block overlays. PURE. Port of Android's
 * `ui/overlay/NudgeMessages.kt` (defaults + `resolvePool`).
 *
 * Ported VERBATIM except three strings swap the word "app" for "site" where it would read wrong
 * in a browser context — each swap is called out against the Android original in a comment below.
 *
 * No chrome.* imports — fully unit-testable under plain Node/vitest.
 */

export const DEFAULT_DELAY_TITLES: string[] = [
  'Take a moment to think...',
  'Pause and reflect',
  'Is this intentional?',
  'Before you scroll...',
  'A moment of awareness',
];

export const DEFAULT_DELAY_SUBTITLES: string[] = [
  // Android: "Do you really need to open this app right now?"
  'Do you really need to open this site right now?',
  'What were you about to do instead?',
  'Will this bring you closer to your goals?',
  'You chose to add friction here for a reason.',
  'This is your future self thanking you.',
];

export const DEFAULT_HARD_BLOCK_MESSAGES: string[] = [
  // Android: "You've blocked access to this app"
  "You've blocked access to this site",
  // Android: "This app is off-limits right now"
  'This site is off-limits right now',
  'Your future self will thank you',
  'Time to do something else',
  'You set this boundary for a reason',
];

/**
 * Resolves the pool of messages to display, given the user's custom text and the built-in
 * `defaults`. Port of `NudgeMessages.resolvePool`.
 *
 * `customRaw` is one message per line — either a raw multiline string (as the Settings textarea
 * produces) or an already-split array of lines (as persisted `NudgeSettings.messages` stores it).
 * Lines are trimmed and blanks dropped. If the result is empty, falls back to `defaults`.
 */
export function resolvePool(customRaw: string | string[], defaults: string[]): string[] {
  const lines = Array.isArray(customRaw) ? customRaw : customRaw.split('\n');
  const cleaned = lines.map((line) => line.trim()).filter((line) => line.length > 0);
  return cleaned.length > 0 ? cleaned : defaults;
}

/**
 * Picks a random element from `pool`. `rng` is injectable for deterministic tests; defaults to
 * `Math.random()` — this is display-only randomness (which message rotates in), never a
 * commitment device, so `Math.random()` is an appropriate default here (unlike `strictMode.ts`'s
 * `generate`, which never uses it).
 */
export function pickRandom<T>(pool: readonly T[], rng: () => number = Math.random): T {
  if (pool.length === 0) {
    throw new RangeError('pickRandom: pool must not be empty');
  }
  const index = Math.floor(rng() * pool.length);
  const safeIndex = Math.min(pool.length - 1, Math.max(0, index));
  // Safe: safeIndex is clamped into [0, pool.length) above, and pool.length > 0 was just checked.
  return pool[safeIndex] as T;
}
