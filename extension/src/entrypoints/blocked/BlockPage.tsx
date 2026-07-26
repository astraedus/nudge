/**
 * The block-page orchestrator. Reached via a DNR redirect to `blocked.html?target=<encoded url>`.
 * Fetches a `BlockContext` from the service worker and renders the right interstitial —
 * never flashing the wrong mode, never navigating anywhere unsafe.
 *
 * PRD: ops/routes/nudge/research/ext-07-prd.md MVP items 1, 7, 8, 9.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { BlockContext } from '../../core/protocol';
import { Button, NudgeMark, RuleFooter } from '../../ui/components';
import { send } from '../../ui/rpc';
import { BreathingView } from './BreathingView';
import { DelayView } from './DelayView';
import { HardBlockView } from './HardBlockView';

/**
 * Open-redirect / XSS guard. Every navigation this feature performs — Delay/Breathing
 * completion, the Escape Hatch grant, and the raw `target` query param itself — MUST be
 * gated through this before it is ever assigned to `window.location`. `javascript:`,
 * `data:`, and `chrome-extension:` (or any other non-http(s) scheme) are rejected.
 */
export function isNavigableTarget(raw: string | null | undefined): raw is string {
  if (!raw) return false;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  return url.protocol === 'http:' || url.protocol === 'https:';
}

/** Where "I changed my mind" sends the user. Falls back to about:blank outside an extension context. */
export function resolveDashboardUrl(): string {
  try {
    return chrome.runtime.getURL('dashboard.html');
  } catch {
    return 'about:blank';
  }
}

const TICK_MS = 100;

/** Ticks down from `totalMs` to 0 in fixed 100ms steps. Clamps at 0 and self-clears its interval. */
export function useCountdownMs(totalMs: number): number {
  const clamped = Math.max(0, totalMs);
  const [remainingMs, setRemainingMs] = useState(clamped);

  useEffect(() => {
    setRemainingMs(clamped);
    if (clamped <= 0) return;
    const id = setInterval(() => {
      setRemainingMs((prev) => {
        const next = Math.max(0, prev - TICK_MS);
        if (next === 0) clearInterval(id);
        return next;
      });
    }, TICK_MS);
    return () => clearInterval(id);
  }, [clamped]);

  return remainingMs;
}

/**
 * Fires COMPLETE_PAUSE exactly once when the countdown reaches zero, then navigates to
 * `target` on success. Exposes `status`/`retry` so the view can show a retry affordance
 * instead of hanging if the service worker is asleep/restarting.
 */
export function useCompleteOnZero(remainingMs: number, target: string) {
  const [status, setStatus] = useState<'idle' | 'pending' | 'error'>('idle');
  const firedRef = useRef(false);

  const attempt = useCallback(() => {
    setStatus('pending');
    send({ type: 'COMPLETE_PAUSE', target })
      .then((result) => {
        if (result.ok && isNavigableTarget(target)) {
          window.location.replace(target);
          return;
        }
        setStatus('error');
      })
      .catch(() => setStatus('error'));
  }, [target]);

  useEffect(() => {
    if (remainingMs === 0 && !firedRef.current) {
      firedRef.current = true;
      attempt();
    }
  }, [remainingMs, attempt]);

  return { status, retry: attempt };
}

type LoadState =
  | { status: 'invalid' }
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; context: BlockContext; target: string };

/** The marker the DNR redirect appends the original URL after. Must stay last in the URL. */
const TARGET_PARAM = '?target=';

/**
 * Read the blocked URL out of our own address bar.
 *
 * Deliberately NOT `URLSearchParams`: the DNR rule appends the original URL verbatim via
 * `regexSubstitution` (which cannot percent-encode), so the target routinely contains its
 * own `?` and `&` — e.g. `youtube.com/watch?v=abc&t=30`. Parsing it as query parameters
 * would silently truncate at the first `&` and turn `+` into a space. `target` is always
 * the LAST thing in the URL, so everything after the first marker is the target.
 *
 * Read from `href`, not `search`, so a target carrying its own `#fragment` survives too.
 * The target is never percent-decoded here: both producers (the DNR rule and
 * `redirectOpenTabs`) append it verbatim, so decoding would corrupt any URL that legitimately
 * contains a `%XX` sequence.
 */
function readTargetParam(): string | null {
  try {
    const { href } = window.location;
    const markerAt = href.indexOf(TARGET_PARAM);
    if (markerAt === -1) return null;
    const raw = href.slice(markerAt + TARGET_PARAM.length);
    return raw === '' ? null : raw;
  } catch {
    return null;
  }
}

function PageShell({ ruleName, children }: { ruleName: string | null; children: ReactNode }) {
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '48px 24px',
        gap: 32,
        textAlign: 'center',
      }}
    >
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
        <NudgeMark size={36} />
        <p
          style={{
            margin: 0,
            fontSize: 13,
            fontWeight: 500,
            color: 'var(--nudge-on-surface-variant)',
            letterSpacing: 0.2,
          }}
        >
          Break the scroll. Take back your time.
        </p>
      </div>
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          maxWidth: 440,
          width: '100%',
        }}
      >
        {children}
      </div>
      <RuleFooter ruleName={ruleName} />
    </div>
  );
}

export function BlockPage() {
  const rawTarget = useMemo(readTargetParam, []);
  const targetIsSafe = isNavigableTarget(rawTarget);
  const [state, setState] = useState<LoadState>(
    targetIsSafe ? { status: 'loading' } : { status: 'invalid' },
  );
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!targetIsSafe) return;
    let cancelled = false;
    setState({ status: 'loading' });
    send({ type: 'GET_BLOCK_CONTEXT', target: rawTarget })
      .then((context) => {
        if (!cancelled) setState({ status: 'ready', context, target: rawTarget });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error' });
      });
    return () => {
      cancelled = true;
    };
    // rawTarget/targetIsSafe are stable for the page's lifetime; `attempt` is the retry trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  // ALLOW is unexpected on the block page (nothing to block) — send the user on rather
  // than trap them behind a dead interstitial.
  useEffect(() => {
    if (
      state.status === 'ready' &&
      state.context.decision.type === 'ALLOW' &&
      isNavigableTarget(state.target)
    ) {
      window.location.replace(state.target);
    }
  }, [state]);

  if (state.status === 'invalid') {
    return (
      <PageShell ruleName={null}>
        <p style={{ margin: 0, fontSize: 16, color: 'var(--nudge-on-surface-variant)' }}>
          Nothing to show here — the page you came from didn&apos;t tell Nudge where you were
          headed.
        </p>
      </PageShell>
    );
  }

  if (state.status === 'loading') {
    return (
      <PageShell ruleName={null}>
        <p style={{ margin: 0, fontSize: 15, color: 'var(--nudge-on-surface-variant)' }}>
          Loading…
        </p>
      </PageShell>
    );
  }

  if (state.status === 'error') {
    return (
      <PageShell ruleName={null}>
        <p
          style={{
            margin: '0 0 16px',
            fontSize: 15,
            color: 'var(--nudge-on-surface-variant)',
          }}
        >
          Nudge couldn&apos;t load this page. It may still be waking up.
        </p>
        <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>
          Retry
        </Button>
      </PageShell>
    );
  }

  const { context, target } = state;

  if (context.decision.type !== 'BLOCK') {
    // ALLOW — the redirect effect above handles it; render a neutral state meanwhile.
    return (
      <PageShell ruleName={null}>
        <p style={{ margin: 0, fontSize: 15, color: 'var(--nudge-on-surface-variant)' }}>
          Redirecting…
        </p>
      </PageShell>
    );
  }

  const ruleName = context.decision.ruleName;

  return (
    <PageShell ruleName={ruleName}>
      {context.decision.mode === 'HARD_BLOCK' && <HardBlockView context={context} target={target} />}
      {context.decision.mode === 'DELAY' && <DelayView context={context} target={target} />}
      {context.decision.mode === 'BREATHING' && <BreathingView context={context} target={target} />}
    </PageShell>
  );
}
