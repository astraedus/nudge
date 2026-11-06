/**
 * Repeating pause: "every N minutes, the pause comes up again on the page I am on".
 *
 * PURE and deliberately import-free. `settingsSchema.ts` reads the range from here and
 * `strictMode.ts` the weakening rule, and `strictMode` already imports the schema, so the
 * constants cannot live in either without a cycle.
 *
 * Why it is not simply "a shorter temp allow": an expired grant removes the DNR allow rule,
 * which only re-blocks the NEXT navigation. A person reading one article or watching one
 * video never navigates. The repeat is enforced in the page instead, see
 * `content/repeatPause.ts` and the section in `extension/CLAUDE.md`.
 */

export const REPEAT_PAUSE_MIN_MINUTES = 1;
export const REPEAT_PAUSE_MAX_MINUTES = 120;

/** What the block page's chooser offers, besides Off and any custom value already set. */
export const REPEAT_PAUSE_PRESETS = [5, 10, 15, 30, 60] as const;

/**
 * A stored value read back from anywhere: not a number means off, a number is clamped.
 *
 * Off is the only safe reading of junk. The repeat changes the length of the grant, and
 * inventing one for a rule whose owner never asked would surprise them on upgrade.
 */
export function coerceRepeatPauseMinutes(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  return Math.min(
    REPEAT_PAUSE_MAX_MINUTES,
    Math.max(REPEAT_PAUSE_MIN_MINUTES, Math.round(value)),
  );
}

/**
 * True when a repeat setting change reduces protection, for the Commitment Lock.
 *
 *  - on -> off removes the interruption.
 *  - raising N means longer between pauses.
 *  - off -> on is free UNLESS N outlasts the ordinary grant. The repeat REPLACES the grant
 *    length, so "pause again every 60 minutes" on a 10 minute temp allow is 50 minutes more
 *    access per pause, dressed up as a strengthening.
 *  - lowering N, and off -> on with N <= the grant, only add friction.
 */
export function isRepeatPauseWeakening(
  oldMinutes: number | null,
  newMinutes: number | null,
  tempAllowMinutes: number,
): boolean {
  if (newMinutes === null) return oldMinutes !== null;
  if (oldMinutes === null) return newMinutes > tempAllowMinutes;
  return newMinutes > oldMinutes;
}

/**
 * How long a completed pause opens the site for.
 *
 * ONE answer on purpose: the pill's "Next pause in X" and the DNR allow rule read this same
 * grant, so a full navigation inside the window never hits the block page and the timer
 * never lies.
 */
export function pauseGrantMinutes(
  repeatInForceMinutes: number | null,
  tempAllowMinutes: number,
): number {
  return repeatInForceMinutes ?? tempAllowMinutes;
}

/**
 * "9:32". Rounds UP so the pill never reads 0:00 while there is still time on the clock, and
 * the moment it would, the pause is due.
 */
export function formatTimeLeft(ms: number): string {
  const total = Math.ceil(Math.max(0, ms) / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/** The one line both the pill and the popup show. */
export function nextPauseLabel(ms: number): string {
  return `Next pause in ${formatTimeLeft(ms)}`;
}

/** The read-only sentence when Strict Mode leaves nothing to choose. */
export function repeatPauseSentence(minutes: number): string {
  return minutes === 1 ? 'Pauses again every minute' : `Pauses again every ${minutes} minutes`;
}

/**
 * What the block page's chooser lists, current value included, in the order shown. `null` is
 * Off. A custom value is kept in the list so opening the chooser cannot appear to change it.
 *
 * Under Strict Mode only what `isRepeatPauseWeakening` calls free is offered, so the worker's
 * lock never has to refuse the chooser and no challenge is needed mid-pause. One entry left
 * means there is nothing to choose.
 */
export function repeatPauseChoices(
  current: number | null,
  strict: boolean,
  tempAllowMinutes: number,
): (number | null)[] {
  const minutes = new Set<number>(REPEAT_PAUSE_PRESETS);
  if (current !== null) minutes.add(current);
  const all: (number | null)[] = [null, ...[...minutes].sort((a, b) => a - b)];
  if (!strict) return all;
  return all.filter((choice) => !isRepeatPauseWeakening(current, choice, tempAllowMinutes));
}
