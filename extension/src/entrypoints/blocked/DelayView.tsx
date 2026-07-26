/**
 * Delay — a circular countdown ring over `decision.delaySeconds`, showing
 * `context.delayTitle`/`context.delaySubtitle`. On completion, COMPLETE_PAUSE then
 * navigate to `target`. "I changed my mind" logs WALKED_AWAY and leaves for the
 * dashboard.
 */

import type { BlockContext } from '../../core/protocol';
import { Button } from '../../ui/components';
import { send } from '../../ui/rpc';
import { resolveDashboardUrl, useCompleteOnZero, useCountdownMs } from './BlockPage';
import { EscapeHatch } from './EscapeHatch';

const RING_SIZE = 160;
const RING_STROKE = 10;
const RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

export function DelayView({ context, target }: { context: BlockContext; target: string }) {
  const decision = context.decision;
  // Hooks run unconditionally so their order is stable across renders; the view bails out
  // AFTER them. `isBlocked` disarms the completion hook so a non-block render — which has a
  // zero-length countdown — cannot instantly "complete" a pause and grant access.
  const isBlocked = decision.type === 'BLOCK';
  const totalMs = isBlocked ? Math.max(0, decision.delaySeconds * 1000) : 0;
  const remainingMs = useCountdownMs(totalMs);
  const { status, retry } = useCompleteOnZero(remainingMs, target, isBlocked);

  if (!isBlocked) return null;

  const fraction = totalMs > 0 ? remainingMs / totalMs : 0;
  const dashOffset = RING_CIRCUMFERENCE * (1 - fraction);
  const secondsLeft = Math.ceil(remainingMs / 1000);

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
      <p style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>{context.delayTitle}</p>
      <p style={{ margin: 0, fontSize: 14, color: 'var(--nudge-on-surface-variant)' }}>
        {context.delaySubtitle}
      </p>

      <svg width={RING_SIZE} height={RING_SIZE} viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}>
        <circle
          cx={RING_SIZE / 2}
          cy={RING_SIZE / 2}
          r={RING_RADIUS}
          fill="none"
          stroke="var(--nudge-surface-variant)"
          strokeWidth={RING_STROKE}
        />
        <circle
          cx={RING_SIZE / 2}
          cy={RING_SIZE / 2}
          r={RING_RADIUS}
          fill="none"
          stroke="var(--nudge-primary)"
          strokeWidth={RING_STROKE}
          strokeLinecap="round"
          strokeDasharray={RING_CIRCUMFERENCE}
          strokeDashoffset={dashOffset}
          transform={`rotate(-90 ${RING_SIZE / 2} ${RING_SIZE / 2})`}
          style={{ transition: 'stroke-dashoffset 100ms linear' }}
        />
        <text
          x="50%"
          y="50%"
          dominantBaseline="middle"
          textAnchor="middle"
          fontSize="28"
          fontWeight="700"
          fill="var(--nudge-on-surface)"
        >
          {secondsLeft}
        </text>
      </svg>

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
