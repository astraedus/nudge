/**
 * Breathing — a circle scaling 0.6x-1.0x on a fixed 4000ms inhale / 4000ms exhale cycle
 * (8s per cycle — exact Android timing), alternating "Breathe in..." / "Breathe out...",
 * repeating until `decision.delaySeconds` elapses. Linear progress bar + "Ns remaining".
 * Same completion behaviour as Delay.
 */

import type { BlockContext } from '../../core/protocol';
import { Button } from '../../ui/components';
import { send } from '../../ui/rpc';
import { resolveDashboardUrl, useCompleteOnZero, useCountdownMs } from './BlockPage';
import { EscapeHatch } from './EscapeHatch';

const HALF_CYCLE_MS = 4000; // fixed 4s inhale / 4s exhale — exact Android timing
const MIN_SCALE = 0.6;
const MAX_SCALE = 1.0;

export function BreathingView({ context, target }: { context: BlockContext; target: string }) {
  const decision = context.decision;
  // Hooks run unconditionally so their order is stable across renders; the view bails out
  // AFTER them. `isBlocked` disarms the completion hook so a non-block render — which has a
  // zero-length countdown — cannot instantly "complete" a pause and grant access.
  const isBlocked = decision.type === 'BLOCK';
  const totalMs = isBlocked ? Math.max(0, decision.delaySeconds * 1000) : 0;
  const remainingMs = useCountdownMs(totalMs);
  const { status, retry } = useCompleteOnZero(remainingMs, target, isBlocked);

  if (!isBlocked) return null;

  const elapsedMs = totalMs - remainingMs;
  const cyclePos = elapsedMs % (HALF_CYCLE_MS * 2);
  const isInhale = cyclePos < HALF_CYCLE_MS;
  const withinHalf = isInhale ? cyclePos : cyclePos - HALF_CYCLE_MS;
  const halfFraction = withinHalf / HALF_CYCLE_MS;
  const scale = isInhale
    ? MIN_SCALE + (MAX_SCALE - MIN_SCALE) * halfFraction
    : MAX_SCALE - (MAX_SCALE - MIN_SCALE) * halfFraction;

  const secondsLeft = Math.ceil(remainingMs / 1000);
  const progressPct = totalMs > 0 ? Math.min(100, (elapsedMs / totalMs) * 100) : 100;

  const handleWalkAway = async () => {
    try {
      await send({ type: 'WALKED_AWAY', target });
    } catch {
      // Best-effort stat log — still leave even if the service worker missed it.
    }
    window.location.replace(resolveDashboardUrl());
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 20, width: '100%' }}>
      <div
        aria-hidden="true"
        style={{
          width: 120,
          height: 120,
          borderRadius: '50%',
          background: 'var(--nudge-primary-container)',
          border: '2px solid var(--nudge-primary)',
          transform: `scale(${scale})`,
          transition: 'transform 100ms linear',
        }}
      />
      <p style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>
        {isInhale ? 'Breathe in...' : 'Breathe out...'}
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
          style={{
            width: `${progressPct}%`,
            height: '100%',
            background: 'var(--nudge-primary)',
            transition: 'width 100ms linear',
          }}
        />
      </div>
      <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
        {secondsLeft}s remaining
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

      <EscapeHatch context={context} target={target} />
    </div>
  );
}
