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
import type { ChannelEntry } from '../../core/settingsSchema';
import { Button, NudgeMark, RuleFooter } from '../../ui/components';
import { send } from '../../ui/rpc';
import { BreathingView } from './BreathingView';
import { DelayView } from './DelayView';
import { EscapeHatch } from './EscapeHatch';
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

/**
 * The public page for one allowed channel, or null when the entry cannot name one safely.
 *
 * The identifier is percent-encoded before it becomes a path segment. These entries are
 * user data — typed into the dashboard, or restored from an imported settings file — so a
 * handle containing `/` or `?` would otherwise silently build a URL pointing somewhere
 * else entirely. The result is then run through `isNavigableTarget` like every other
 * navigation on this page, so a scheme that is not http(s) can never reach an `href`.
 *
 * A handle is preferred over an id purely because `youtube.com/@name` is the address the
 * user recognises; both resolve to the same channel.
 */
export function channelHomeUrl(entry: ChannelEntry): string | null {
  const handle = entry.handle?.trim() ?? '';
  const id = entry.channelId?.trim() ?? '';

  const path =
    handle !== ''
      ? `@${encodeURIComponent(handle.replace(/^@/, ''))}`
      : id !== ''
        ? `channel/${encodeURIComponent(id)}`
        : null;
  if (path === null) return null;

  const url = `https://www.youtube.com/${path}`;
  return isNavigableTarget(url) ? url : null;
}

/**
 * "Your allowed channels" — the way back IN.
 *
 * Without this, "block YouTube except these channels" is technically correct and
 * practically useless: every route to an allowed channel (the home feed, search, the
 * subscriptions page) is blocked too, so the only way to reach what the user explicitly
 * said they wanted is to type a URL from memory. A rule that punishes you for obeying it
 * is a rule you turn off. The block page has to BE the door.
 */
function AllowedChannels({ channels }: { channels: ChannelEntry[] }) {
  // `?? []` is not defensive typing theatre: this page's whole job is to appear when a
  // site is blocked, and a worker that answers without this field (mid-update, or an old
  // service worker still alive after a reload) would otherwise throw here and leave the
  // user staring at a blank tab instead of a block page — failing open in the worst way.
  const links = (channels ?? [])
    .map((entry) => ({ entry, href: channelHomeUrl(entry) }))
    .filter((row): row is { entry: ChannelEntry; href: string } => row.href !== null);

  if (links.length === 0) return null;

  return (
    <section
      style={{
        marginTop: 28,
        width: '100%',
        maxWidth: 440,
        textAlign: 'left',
        borderTop: '1px solid var(--nudge-surface-variant)',
        paddingTop: 20,
      }}
    >
      <h2
        style={{
          margin: '0 0 10px',
          fontSize: 13,
          fontWeight: 600,
          letterSpacing: 0.3,
          textTransform: 'uppercase',
          color: 'var(--nudge-on-surface-variant)',
        }}
      >
        Your allowed channels
      </h2>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 6 }}>
        {links.map(({ entry, href }) => (
          <li key={href}>
            <a
              href={href}
              style={{
                display: 'block',
                padding: '8px 12px',
                borderRadius: 8,
                background: 'var(--nudge-surface-variant)',
                color: 'var(--nudge-on-surface)',
                fontSize: 14,
                textDecoration: 'none',
              }}
            >
              {entry.displayName}
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
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
 *
 * `enabled` exists so callers can invoke this UNCONDITIONALLY (React requires stable hook
 * order) without arming it. That matters: a disarmed view has a zero-length countdown, and
 * "remaining === 0" would otherwise complete the pause instantly and grant real access.
 */
export function useCompleteOnZero(remainingMs: number, target: string, enabled = true) {
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
    if (enabled && remainingMs === 0 && !firedRef.current) {
      firedRef.current = true;
      attempt();
    }
  }, [enabled, remainingMs, attempt]);

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

/**
 * What this page is about, in the user's words.
 *
 * A gate surface names itself ("Shorts") rather than the domain it lives on: someone who
 * gated Shorts but left the rest of YouTube open is looking at a page that says "YouTube"
 * and reasonably concludes the wrong rule fired. The bare domain is the right answer only
 * when the whole site IS the subject.
 */
export function surfaceLabelFor(context: BlockContext): string {
  return context.gateLabel ?? context.domain;
}

function PageShell({
  ruleName,
  surfaceLabel = null,
  children,
}: {
  ruleName: string | null;
  surfaceLabel?: string | null;
  children: ReactNode;
}) {
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
        {surfaceLabel !== null && (
          <p
            style={{
              margin: 0,
              padding: '3px 10px',
              borderRadius: 999,
              background: 'var(--nudge-surface-variant)',
              color: 'var(--nudge-on-surface)',
              fontSize: 12,
              fontWeight: 600,
            }}
          >
            {surfaceLabel}
          </p>
        )}
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

  /**
   * ORDER IS THE POINT HERE, which is why the Escape Hatch lives on this page rather than
   * inside each of the three views (where it used to be, rendered identically three times).
   *
   * The allowed-channels list is the FREE, intended way through: the user already said
   * these channels are fine. The Escape Hatch is the costly one — a single two-minute pass
   * a day that opens the whole site. Offering the expensive door first, on the very page
   * where the cheap one exists, trains people to spend a pass on something they were never
   * blocked from. Cheap route first, always.
   */
  return (
    <PageShell ruleName={ruleName} surfaceLabel={surfaceLabelFor(context)}>
      {context.decision.mode === 'HARD_BLOCK' && <HardBlockView context={context} />}
      {context.decision.mode === 'DELAY' && <DelayView context={context} target={target} />}
      {context.decision.mode === 'BREATHING' && <BreathingView context={context} target={target} />}
      <AllowedChannels channels={context.allowedChannels} />
      <EscapeHatch context={context} target={target} />
    </PageShell>
  );
}
