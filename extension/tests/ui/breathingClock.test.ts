// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BreathFrame } from '../../src/core/breathing';
import { startBreathingClock } from '../../src/ui/breathingClock';

let visibility: DocumentVisibilityState = 'visible';

function setVisibility(state: DocumentVisibilityState): void {
  visibility = state;
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => {
  vi.useFakeTimers();
  visibility = 'visible';
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => visibility,
  });
});

afterEach(() => {
  vi.useRealTimers();
});

function record(totalMs: number) {
  const frames: BreathFrame[] = [];
  const stop = startBreathingClock(window, totalMs, (frame) => frames.push(frame));
  const last = (): BreathFrame => {
    const frame = frames[frames.length - 1];
    if (frame === undefined) throw new Error('no frame emitted');
    return frame;
  };
  return { frames, stop, last };
}

describe('startBreathingClock', () => {
  it('paints the first frame synchronously, at 0ms, before any timer runs', () => {
    const { frames, stop } = record(8000);
    expect(frames).toHaveLength(1);
    expect(frames[0]?.level).toBe(0);
    expect(frames[0]?.phase).toBe('in');
    expect(frames[0]?.phaseSecondsLeft).toBe(4);
    stop();
  });

  it('runs on animation frames, many per second, not a once-a-second tick', () => {
    const { frames, stop } = record(8000);
    vi.advanceTimersByTime(1000);
    expect(frames.length).toBeGreaterThan(30);
    stop();
  });

  it('keeps the numbers in sync with the motion: the phase turns at 4s', () => {
    const { last, stop } = record(16_000);
    vi.advanceTimersByTime(3900);
    expect(last().phase).toBe('in');
    expect(last().phaseSecondsLeft).toBe(1);
    vi.advanceTimersByTime(200);
    expect(last().phase).toBe('out');
    expect(last().phaseSecondsLeft).toBe(4);
    stop();
  });

  it('finishes with a done frame and then stops scheduling frames', () => {
    const { frames, last, stop } = record(2000);
    vi.advanceTimersByTime(2100);
    expect(last().done).toBe(true);
    expect(frames.filter((f) => f.done)).toHaveLength(1);
    const count = frames.length;
    vi.advanceTimersByTime(5000);
    expect(frames.length).toBe(count);
    stop();
  });

  it('does not advance while the tab is hidden, and resumes with no catch-up jump', () => {
    const { last, stop } = record(30_000);
    vi.advanceTimersByTime(2000);
    const before = last().progress;

    setVisibility('hidden');
    vi.advanceTimersByTime(20_000);
    setVisibility('visible');
    vi.advanceTimersByTime(16);

    const after = last().progress;
    // 20s away moved the pause by at most a frame or two, not by 20s.
    expect((after - before) * 30_000).toBeLessThan(100);
    expect(last().done).toBe(false);
    stop();
  });

  it('stop() cancels the pending frame and removes the visibility listener', () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener');
    const { frames, stop } = record(8000);
    vi.advanceTimersByTime(100);
    stop();
    const count = frames.length;
    vi.advanceTimersByTime(5000);
    expect(frames.length).toBe(count);
    expect(removeSpy).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
    stop(); // idempotent
    removeSpy.mockRestore();
  });

  it('passes reduced-motion through from the media query', () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) =>
      ({ matches: query.includes('reduce'), media: query }) as MediaQueryList) as typeof window.matchMedia;
    const seen: boolean[] = [];
    const stop = startBreathingClock(window, 8000, (_frame, reduced) => seen.push(reduced));
    expect(seen[0]).toBe(true);
    stop();
    window.matchMedia = original;
  });
});
