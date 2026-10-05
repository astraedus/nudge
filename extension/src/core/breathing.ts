/**
 * The Breathing pause, as pure arithmetic: elapsed time in, everything the pacer shows out.
 *
 * ONE clock drives the whole pacer. The circle's size, the phase label ("Breathe in" /
 * "Breathe out"), the per-phase countdown (4, 3, 2, 1), the overall progress bar and the
 * completion all come from the same `elapsedMs`, so they cannot drift apart. Before 0.3.2
 * they did: the block page stepped the circle once per 100ms React tick through a 100ms
 * LINEAR transition (a staircase that slammed into reverse at every phase boundary), and
 * the in-page overlay toggled a class on a 200ms interval under a 4s CSS transition that
 * started ~200ms after its own label and used an asymmetric easing curve. Each surface had
 * two clocks; now each has one, and this file is it.
 *
 * Motion: within a phase the breath level follows ease-in-out SINE, so over a whole cycle
 * the level is one continuous cosine, `(1 - cos(2*pi*t/cycle)) / 2`. Its velocity is zero
 * at both ends of every phase, which is what makes the turn at the top and bottom of a
 * breath feel like a breath rather than a bounce, and there is no step anywhere, including
 * at t = 0 (level 0, the smallest circle).
 *
 * Timing is Android parity and deliberately unchanged: 4s in, 4s out, no hold.
 */

export const BREATH_IN_MS = 4000;
export const BREATH_OUT_MS = 4000;
export const BREATH_CYCLE_MS = BREATH_IN_MS + BREATH_OUT_MS;

/** Circle size at empty and full lungs, as a fraction of its full diameter. */
export const BREATH_MIN_SCALE = 0.6;
export const BREATH_MAX_SCALE = 1;

/** Reduced motion keeps the circle still and breathes its opacity between these. */
export const BREATH_MIN_OPACITY = 0.45;
export const BREATH_MAX_OPACITY = 1;

/**
 * The most one animation frame may advance the clock. A frame gap longer than this is jank
 * or a throttled/frozen page, not time the user spent breathing, so the clock absorbs it
 * instead of leaping the circle forward to catch up.
 */
export const MAX_FRAME_STEP_MS = 250;

export type BreathPhase = 'in' | 'out';

export interface BreathFrame {
  phase: BreathPhase;
  /**
   * The number to show for this phase: 4, 3, 2, 1, landing on 1 for the final second of
   * the phase. Never more than the seconds left in the whole pause, so a pause that ends
   * mid-phase still counts down to 1 as it ends. 0 once done.
   */
  phaseSecondsLeft: number;
  /** 0..1, LINEAR position within the current phase. */
  phaseProgress: number;
  /** 0..1, EASED breath level: 0 = empty lungs (smallest), 1 = full (largest). */
  level: number;
  /** 0..1, linear position within the whole pause. */
  progress: number;
  /** Whole seconds left in the pause, rounded up ("8s remaining"). 0 once done. */
  totalSecondsLeft: number;
  done: boolean;
}

/** Ease-in-out sine on 0..1: zero velocity at both ends, symmetric about 0.5. */
export function easeInOutSine(p: number): number {
  const clamped = Math.min(1, Math.max(0, p));
  return (1 - Math.cos(Math.PI * clamped)) / 2;
}

/** Everything the pacer shows at `elapsedMs` into a pause of `totalMs`. */
export function breathFrame(elapsedMs: number, totalMs: number): BreathFrame {
  const total = Math.max(0, totalMs);
  const elapsed = Math.min(total, Math.max(0, elapsedMs));
  const done = elapsed >= total;

  const cyclePos = elapsed % BREATH_CYCLE_MS;
  const phase: BreathPhase = cyclePos < BREATH_IN_MS ? 'in' : 'out';
  const phaseLen = phase === 'in' ? BREATH_IN_MS : BREATH_OUT_MS;
  const posInPhase = phase === 'in' ? cyclePos : cyclePos - BREATH_IN_MS;
  const phaseProgress = posInPhase / phaseLen;
  const eased = easeInOutSine(phaseProgress);

  const totalSecondsLeft = done ? 0 : Math.ceil((total - elapsed) / 1000);
  const phaseSecondsLeft = done
    ? 0
    : Math.min(Math.ceil((phaseLen - posInPhase) / 1000), totalSecondsLeft);

  return {
    phase,
    phaseSecondsLeft,
    phaseProgress,
    level: phase === 'in' ? eased : 1 - eased,
    progress: total > 0 ? elapsed / total : 1,
    totalSecondsLeft,
    done,
  };
}

/** What the circle looks like at a breath `level`: only transform scale and opacity. */
export function breathVisual(
  level: number,
  reducedMotion: boolean,
): { scale: number; opacity: number } {
  const l = Math.min(1, Math.max(0, level));
  if (reducedMotion) {
    return {
      scale: BREATH_MAX_SCALE,
      opacity: BREATH_MIN_OPACITY + (BREATH_MAX_OPACITY - BREATH_MIN_OPACITY) * l,
    };
  }
  return { scale: BREATH_MIN_SCALE + (BREATH_MAX_SCALE - BREATH_MIN_SCALE) * l, opacity: 1 };
}

/**
 * Advance the pause clock by one frame. A negative delta (clock skew) adds nothing; a delta
 * longer than `MAX_FRAME_STEP_MS` adds only that much, so a stalled page resumes where it
 * left off instead of jumping.
 */
export function advanceElapsed(elapsedMs: number, frameDeltaMs: number): number {
  const delta = Math.min(MAX_FRAME_STEP_MS, Math.max(0, frameDeltaMs));
  return elapsedMs + delta;
}
