import { describe, expect, it } from 'vitest';
import {
  BREATH_CYCLE_MS,
  BREATH_IN_MS,
  BREATH_MAX_OPACITY,
  BREATH_MAX_SCALE,
  BREATH_MIN_OPACITY,
  BREATH_MIN_SCALE,
  BREATH_OUT_MS,
  MAX_FRAME_STEP_MS,
  advanceElapsed,
  breathFrame,
  breathVisual,
  easeInOutSine,
} from '../../src/core/breathing';

const LONG = 60_000;

describe('breathFrame: phase and per-phase countdown', () => {
  it('keeps exact Android timing: 4s in, 4s out, no hold', () => {
    expect(BREATH_IN_MS).toBe(4000);
    expect(BREATH_OUT_MS).toBe(4000);
    expect(BREATH_CYCLE_MS).toBe(8000);
  });

  it.each([
    [0, 'in', 4],
    [999, 'in', 4],
    [1000, 'in', 3],
    [2000, 'in', 2],
    [2999, 'in', 2],
    [3000, 'in', 1],
    [3999, 'in', 1],
    [4000, 'out', 4],
    [4999, 'out', 4],
    [5000, 'out', 3],
    [7000, 'out', 1],
    [7999, 'out', 1],
    [8000, 'in', 4],
    [8000 * 3 + 4000, 'out', 4],
  ] as const)('at %ims the phase is %s showing %i', (elapsed, phase, seconds) => {
    const frame = breathFrame(elapsed, LONG);
    expect(frame.phase).toBe(phase);
    expect(frame.phaseSecondsLeft).toBe(seconds);
  });

  it('counts every whole phase 4, 3, 2, 1 in order and lands on 1 as the phase ends', () => {
    const shown: number[] = [];
    for (let t = 0; t < BREATH_IN_MS; t += 10) {
      const n = breathFrame(t, LONG).phaseSecondsLeft;
      if (shown[shown.length - 1] !== n) shown.push(n);
    }
    expect(shown).toEqual([4, 3, 2, 1]);
    // The very last instant of the inhale still says 1; the next instant starts a new 4.
    expect(breathFrame(BREATH_IN_MS - 0.001, LONG).phaseSecondsLeft).toBe(1);
    expect(breathFrame(BREATH_IN_MS, LONG).phaseSecondsLeft).toBe(4);
  });

  it('never shows more phase seconds than the pause has left, so a mid-phase end still lands on 1', () => {
    // A 10s pause ends 2s into the second inhale.
    expect(breathFrame(8000, 10_000).phaseSecondsLeft).toBe(2);
    expect(breathFrame(9000, 10_000).phaseSecondsLeft).toBe(1);
    expect(breathFrame(9999, 10_000).phaseSecondsLeft).toBe(1);
    // A 2s pause (shorter than one phase) counts 2, 1.
    expect(breathFrame(0, 2000).phaseSecondsLeft).toBe(2);
    expect(breathFrame(1500, 2000).phaseSecondsLeft).toBe(1);
  });
});

describe('breathFrame: the whole pause', () => {
  it('is done exactly at totalMs, not before', () => {
    expect(breathFrame(7999, 8000).done).toBe(false);
    expect(breathFrame(8000, 8000).done).toBe(true);
    expect(breathFrame(9000, 8000).done).toBe(true);
  });

  it('reports total seconds left rounded up and zero once done', () => {
    expect(breathFrame(0, 8000).totalSecondsLeft).toBe(8);
    expect(breathFrame(1, 8000).totalSecondsLeft).toBe(8);
    expect(breathFrame(1000, 8000).totalSecondsLeft).toBe(7);
    expect(breathFrame(7999, 8000).totalSecondsLeft).toBe(1);
    const end = breathFrame(8000, 8000);
    expect(end.totalSecondsLeft).toBe(0);
    expect(end.phaseSecondsLeft).toBe(0);
  });

  it('progress runs linearly 0 -> 1 and clamps', () => {
    expect(breathFrame(0, 8000).progress).toBe(0);
    expect(breathFrame(2000, 8000).progress).toBe(0.25);
    expect(breathFrame(8000, 8000).progress).toBe(1);
    expect(breathFrame(-500, 8000).progress).toBe(0);
    expect(breathFrame(20_000, 8000).progress).toBe(1);
  });

  it('a zero-length pause is done immediately (the caller arms completion, not this)', () => {
    const frame = breathFrame(0, 0);
    expect(frame.done).toBe(true);
    expect(frame.progress).toBe(1);
  });
});

describe('breathFrame: the motion never snaps', () => {
  it('starts at empty lungs (the smallest circle) on the very first frame', () => {
    expect(breathFrame(0, LONG).level).toBe(0);
  });

  it('peaks at full lungs exactly at the inhale/exhale boundary and empties at the cycle end', () => {
    expect(breathFrame(BREATH_IN_MS, LONG).level).toBeCloseTo(1, 12);
    expect(breathFrame(BREATH_CYCLE_MS - 0.001, LONG).level).toBeCloseTo(0, 6);
    expect(breathFrame(BREATH_CYCLE_MS, LONG).level).toBe(0);
  });

  it('is CONTINUOUS everywhere: no 1ms step anywhere in three cycles moves the circle more than a sine can', () => {
    // The steepest a (1 - cos) / 2 curve over a 4s phase ever gets is pi / (2 * 4000) per ms.
    const maxSlopePerMs = Math.PI / (2 * BREATH_IN_MS);
    let prev = breathFrame(0, LONG).level;
    for (let t = 1; t <= BREATH_CYCLE_MS * 3; t += 1) {
      const level = breathFrame(t, LONG).level;
      expect(Math.abs(level - prev)).toBeLessThanOrEqual(maxSlopePerMs + 1e-9);
      prev = level;
    }
  });

  it('eases: the circle is nearly still at the turn of each breath and fastest mid-phase', () => {
    const step = (t: number) => Math.abs(breathFrame(t + 16, LONG).level - breathFrame(t, LONG).level);
    const atTurn = step(BREATH_IN_MS - 8);
    const midInhale = step(BREATH_IN_MS / 2 - 8);
    expect(atTurn).toBeLessThan(midInhale / 50);
  });

  it('inhale and exhale mirror each other', () => {
    for (const p of [0.1, 0.25, 0.5, 0.75, 0.9]) {
      const inhale = breathFrame(BREATH_IN_MS * p, LONG).level;
      const exhale = breathFrame(BREATH_IN_MS + BREATH_OUT_MS * p, LONG).level;
      expect(inhale + exhale).toBeCloseTo(1, 12);
    }
  });
});

describe('easeInOutSine', () => {
  it('maps 0 -> 0, 0.5 -> 0.5, 1 -> 1 and clamps outside', () => {
    expect(easeInOutSine(0)).toBe(0);
    expect(easeInOutSine(0.5)).toBeCloseTo(0.5, 12);
    expect(easeInOutSine(1)).toBe(1);
    expect(easeInOutSine(-1)).toBe(0);
    expect(easeInOutSine(2)).toBe(1);
  });
});

describe('breathVisual', () => {
  it('scales between min and max with full opacity in normal motion', () => {
    expect(breathVisual(0, false)).toEqual({ scale: BREATH_MIN_SCALE, opacity: 1 });
    expect(breathVisual(1, false)).toEqual({ scale: BREATH_MAX_SCALE, opacity: 1 });
  });

  it('reduced motion never scales: the circle holds still and only its opacity breathes', () => {
    for (const level of [0, 0.3, 0.7, 1]) {
      expect(breathVisual(level, true).scale).toBe(BREATH_MAX_SCALE);
    }
    expect(breathVisual(0, true).opacity).toBe(BREATH_MIN_OPACITY);
    expect(breathVisual(1, true).opacity).toBe(BREATH_MAX_OPACITY);
  });

  it('clamps an out-of-range level', () => {
    expect(breathVisual(-1, false).scale).toBe(BREATH_MIN_SCALE);
    expect(breathVisual(5, false).scale).toBe(BREATH_MAX_SCALE);
  });
});

describe('advanceElapsed', () => {
  it('adds an ordinary frame delta', () => {
    expect(advanceElapsed(1000, 16)).toBe(1016);
  });

  it('absorbs a stall instead of catching up in one jump', () => {
    expect(advanceElapsed(1000, 30_000)).toBe(1000 + MAX_FRAME_STEP_MS);
  });

  it('never runs backwards on clock skew', () => {
    expect(advanceElapsed(1000, -50)).toBe(1000);
  });
});
