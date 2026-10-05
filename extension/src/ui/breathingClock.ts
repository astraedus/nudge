/**
 * The one clock behind a Breathing pause, shared by the block page (`BreathingView`) and
 * the in-page gate overlay (`content/overlay.ts`). DOM only, no `chrome.*` and no React,
 * so a content script can import it without pulling either in.
 *
 * Each animation frame advances the pause's elapsed time and hands the caller one
 * `breathFrame` (core/breathing.ts) to paint. The caller writes the continuous values
 * (scale, opacity, progress) straight to `style.transform` / `style.opacity`, so the motion
 * is composited, and updates text only when a number actually changes.
 *
 * Elapsed time is ACTIVE time: it does not advance while the document is hidden, and one
 * frame can never add more than `MAX_FRAME_STEP_MS`. Switching away mid-breath and coming
 * back resumes the breath exactly where it was, with no burst of catch-up motion. That is
 * also the honest reading of "breathe before you go in": the pause is served by looking at
 * it, as it was before (the old setInterval countdown was throttled to a crawl in a
 * background tab).
 */

import { advanceElapsed, breathFrame, type BreathFrame } from '../core/breathing';

export type BreathFrameListener = (frame: BreathFrame, reducedMotion: boolean) => void;

/** Fallback cadence where `requestAnimationFrame` does not exist. */
const FALLBACK_FRAME_MS = 16;

/**
 * Start the clock. `onFrame` is called synchronously once with the frame at 0ms (so the
 * first paint is already correct), then once per animation frame until the pause is done.
 * Returns `stop`, which cancels the pending frame and removes every listener; it is safe
 * to call more than once.
 */
export function startBreathingClock(
  win: Window,
  totalMs: number,
  onFrame: BreathFrameListener,
): () => void {
  const doc = win.document;
  const reducedMotionQuery =
    typeof win.matchMedia === 'function'
      ? win.matchMedia('(prefers-reduced-motion: reduce)')
      : null;
  const hasRaf = typeof win.requestAnimationFrame === 'function';
  const schedule = (cb: () => void): number =>
    hasRaf ? win.requestAnimationFrame(cb) : win.setTimeout(cb, FALLBACK_FRAME_MS);
  const cancel = (id: number): void => {
    if (hasRaf) win.cancelAnimationFrame(id);
    else win.clearTimeout(id);
  };
  const isHidden = (): boolean => doc.visibilityState === 'hidden';
  const now = (): number => win.performance.now();

  let elapsedMs = 0;
  let lastFrameAt: number | null = isHidden() ? null : now();
  let pending: number | null = null;
  let stopped = false;

  const emit = (): BreathFrame => {
    const frame = breathFrame(elapsedMs, totalMs);
    onFrame(frame, reducedMotionQuery?.matches ?? false);
    return frame;
  };

  const tick = (): void => {
    pending = null;
    if (stopped) return;
    if (isHidden()) {
      lastFrameAt = null;
    } else {
      const t = now();
      if (lastFrameAt !== null) elapsedMs = advanceElapsed(elapsedMs, t - lastFrameAt);
      lastFrameAt = t;
    }
    if (!emit().done) pending = schedule(tick);
  };

  // Hidden -> visible must not count the time away; restarting the frame baseline is
  // enough, because rAF itself simply resumes when the page is visible again.
  const onVisibilityChange = (): void => {
    lastFrameAt = null;
  };

  doc.addEventListener('visibilitychange', onVisibilityChange);
  if (!emit().done) pending = schedule(tick);

  return () => {
    if (stopped) return;
    stopped = true;
    if (pending !== null) cancel(pending);
    pending = null;
    doc.removeEventListener('visibilitychange', onVisibilityChange);
  };
}
