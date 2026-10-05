/**
 * Breathing: a circle that grows smoothly over a 4s inhale and shrinks over a 4s exhale
 * (exact Android timing), with the phase's own countdown (4, 3, 2, 1) in its centre,
 * repeating until `decision.delaySeconds` elapses. Same completion behaviour as Delay.
 *
 * ONE clock (`ui/breathingClock.ts`) drives everything: the circle, the numbers, the
 * progress bar and completion, so they cannot drift apart. The circle and the bar are
 * written straight to `style.transform` / `style.opacity` from that clock on every frame
 * and are deliberately NOT in the JSX style props, so a React re-render (which happens
 * once a second when a number changes) can never reset them mid-breath. Only the numbers
 * and the phase label go through React state, and only when they change.
 */

import { useLayoutEffect, useRef, useState } from 'react';
import { breathFrame, breathVisual, type BreathFrame } from '../../core/breathing';
import type { BlockContext } from '../../core/protocol';
import { startBreathingClock } from '../../ui/breathingClock';
import { Button } from '../../ui/components';
import { send } from '../../ui/rpc';
import { resolveDashboardUrl, useCompleteOnZero } from './BlockPage';

const STAGE_SIZE = 168;

interface BreathDisplay {
  phase: BreathFrame['phase'];
  phaseSecondsLeft: number;
  totalSecondsLeft: number;
  done: boolean;
}

function displayOf(frame: BreathFrame): BreathDisplay {
  return {
    phase: frame.phase,
    phaseSecondsLeft: frame.phaseSecondsLeft,
    totalSecondsLeft: frame.totalSecondsLeft,
    done: frame.done,
  };
}

function sameDisplay(a: BreathDisplay, b: BreathDisplay): boolean {
  return (
    a.phase === b.phase &&
    a.phaseSecondsLeft === b.phaseSecondsLeft &&
    a.totalSecondsLeft === b.totalSecondsLeft &&
    a.done === b.done
  );
}

export function BreathingView({ context, target }: { context: BlockContext; target: string }) {
  const decision = context.decision;
  // Hooks run unconditionally so their order is stable across renders; the view bails out
  // AFTER them. `isBlocked` disarms the completion hook so a non-block render, which has a
  // zero-length pause, cannot instantly "complete" a pause and grant access.
  const isBlocked = decision.type === 'BLOCK';
  const totalMs = isBlocked ? Math.max(0, decision.delaySeconds * 1000) : 0;

  const [display, setDisplay] = useState<BreathDisplay>(() => displayOf(breathFrame(0, totalMs)));
  const discRef = useRef<HTMLDivElement>(null);
  const barRef = useRef<HTMLDivElement>(null);

  // A layout effect, so the clock's synchronous first frame paints the circle at its
  // starting size BEFORE the browser's first paint: no frame of a wrong-sized circle.
  useLayoutEffect(() => {
    if (!isBlocked) return;
    return startBreathingClock(window, totalMs, (frame, reducedMotion) => {
      const { scale, opacity } = breathVisual(frame.level, reducedMotion);
      if (discRef.current) {
        discRef.current.style.transform = `scale(${scale})`;
        discRef.current.style.opacity = String(opacity);
      }
      if (barRef.current) barRef.current.style.transform = `scaleX(${frame.progress})`;
      const next = displayOf(frame);
      setDisplay((prev) => (sameDisplay(prev, next) ? prev : next));
    });
  }, [isBlocked, totalMs]);

  const remainingMs = display.done ? 0 : Math.max(1, display.totalSecondsLeft * 1000);
  const { status, retry } = useCompleteOnZero(remainingMs, target, isBlocked);

  if (!isBlocked) return null;

  const handleWalkAway = async () => {
    try {
      await send({ type: 'WALKED_AWAY', target });
    } catch {
      // Best-effort stat log — still leave even if the service worker missed it.
    }
    window.location.replace(resolveDashboardUrl());
  };

  const phaseLabel = display.phase === 'in' ? 'Breathe in' : 'Breathe out';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 20, width: '100%' }}>
      <div
        aria-hidden="true"
        data-testid="breath-stage"
        style={{ position: 'relative', width: STAGE_SIZE, height: STAGE_SIZE }}
      >
        {/* The full-breath guide: where the circle is heading on every inhale. */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: '50%',
            border: '1.5px solid color-mix(in srgb, var(--nudge-primary) 28%, transparent)',
          }}
        />
        <div
          ref={discRef}
          data-testid="breath-circle"
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: '50%',
            background:
              'radial-gradient(circle at 50% 38%, var(--nudge-primary-container) 0%, color-mix(in srgb, var(--nudge-primary-container) 70%, var(--nudge-primary)) 100%)',
            border: '2px solid var(--nudge-primary)',
            boxShadow:
              '0 0 0 6px color-mix(in srgb, var(--nudge-primary) 10%, transparent), 0 0 36px color-mix(in srgb, var(--nudge-primary) 32%, transparent)',
            willChange: 'transform, opacity',
          }}
        />
        <span
          data-testid="breath-count"
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 40,
            fontWeight: 700,
            fontVariantNumeric: 'tabular-nums',
            color: 'var(--nudge-on-primary-container)',
          }}
        >
          {display.phaseSecondsLeft}
        </span>
      </div>
      <p aria-live="polite" style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>
        {phaseLabel}
      </p>

      <div
        style={{
          width: '100%',
          maxWidth: 260,
          height: 6,
          borderRadius: 999,
          background: 'var(--nudge-surface-variant)',
          overflow: 'hidden',
        }}
      >
        <div
          ref={barRef}
          style={{
            width: '100%',
            height: '100%',
            background: 'var(--nudge-primary)',
            transformOrigin: 'left center',
            willChange: 'transform',
          }}
        />
      </div>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
        {display.totalSecondsLeft}s remaining
      </p>

      {status === 'error' && (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-danger)' }}>
            Couldn&apos;t reach Nudge.
          </p>
          <Button variant="secondary" onClick={retry}>
            Retry
          </Button>
        </div>
      )}

      <Button variant="muted" onClick={handleWalkAway}>
        I changed my mind
      </Button>
    </div>
  );
}
