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
/**
 * A plain right-pointing arrow. Inline SVG, never an emoji glyph (repo rule): it has to
 * inherit the link colour and it must not be announced, so it carries `aria-hidden`.
 */
function ChannelArrow() {
  return (
    <svg
      className="nudge-channel-link-arrow"
      width={16}
      height={16}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      style={{ flexShrink: 0 }}
    >
      <path
        d="M5 12h12M12 6l6 6-6 6"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function AllowedChannels({ channels }: { channels: ChannelEntry[] }) {
  // `?? []` is not defensive typing theatre: this page's whole job is to appear when a
  // site is blocked, and a worker that answers without this field (mid-update, or an old
  // service worker still alive after a reload) would otherwise throw here and leave the
  // user staring at a blank tab instead of a block page, failing open in the worst way.
  const links = (channels ?? [])
    .map((entry) => ({ entry, href: channelHomeUrl(entry) }))
    .filter((row): row is { entry: ChannelEntry; href: string } => row.href !== null);

  if (links.length === 0) return null;

  return (
    <section
      style={{
        width: '100%',
        textAlign: 'left',
        borderTop: '1px solid var(--nudge-surface-variant)',
        paddingTop: 20,
      }}
    >
      <h2
        style={{
          margin: '0 0 2px',
          fontSize: 13,
          fontWeight: 700,
          letterSpacing: 0.3,
          textTransform: 'uppercase',
          color: 'var(--nudge-on-surface-variant)',
        }}
      >
        Your allowed channels
      </h2>
      <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
        These are still open. Pick one to carry on.
      </p>
      {/*
        THESE ARE THE PRIMARY ACTION ON THIS PAGE, not a footnote.
        When YouTube is Hard Blocked this list is the ONLY way into a channel the user
        explicitly allowed, so it has to look like something you click: link-coloured,
        weighted, with an affordance arrow and a real hover/focus state. Rendered as flat
        grey pills it read as disabled metadata, which turns "block YouTube except these
        channels" back into the dead end the list exists to prevent.
      */}
      <ul
        style={{
          listStyle: 'none',
          margin: 0,
          padding: 0,
          display: 'grid',
          // `minmax(0, 1fr)`, not the implicit `1fr`. A grid track's default floor is its
          // MIN-CONTENT width, so one long channel name stretched the row, the card and the
          // page: 106px of sideways scroll on a 420px window. The column has to be allowed
          // to go narrower than its text before the ellipsis below can ever apply.
          gridTemplateColumns: 'minmax(0, 1fr)',
          gap: 8,
        }}
      >
        {links.map(({ entry, href }) => (
          <li key={href}>
            <a
              className="nudge-channel-link"
              href={href}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                padding: '11px 14px',
                borderRadius: 'var(--nudge-radius-sm)',
                background: 'var(--nudge-link-bg)',
                border: '1px solid var(--nudge-link-border)',
                color: 'var(--nudge-link)',
                fontSize: 15,
                fontWeight: 600,
                textDecoration: 'none',
                transition: 'background 140ms ease, border-color 140ms ease',
              }}
            >
              <span
                style={{
                  // `minWidth: 0` is what makes the ellipsis work at all. A flex item's
                  // default `min-width: auto` is its CONTENT width, so a nowrap span
                  // refuses to shrink and pushes the whole card wider than the window
                  // instead of truncating. One long channel name was enough to scroll the
                  // block page sideways at 420px.
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {entry.displayName}
              </span>
              <ChannelArrow />
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
    <div style={{ minHeight: '100vh', display: 'flex', padding: '40px 24px' }}>
      {/*
        `margin: auto` on the card, NOT `justify-content`/`align-items: center` on the
        flex parent. Both centre a card in a viewport taller than it; only auto margins
        keep the card's TOP reachable when it is taller (a long allowed-channel list, an
        error state under a pause). A centred flex item overflows in both directions and
        the part above the scroll origin cannot be scrolled back to.
      */}
      <main
        data-testid="block-card"
        style={{
          margin: 'auto',
          width: '100%',
          maxWidth: 480,
          minWidth: 0,
          background: 'var(--nudge-surface-raised)',
          border: '1px solid var(--nudge-surface-variant)',
          borderRadius: 'var(--nudge-radius-lg)',
          boxShadow: 'var(--nudge-shadow-card)',
          padding: '40px 32px 32px',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 28,
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
            gap: 28,
            width: '100%',
          }}
        >
          {children}
        </div>
        <RuleFooter ruleName={ruleName} />
      </main>
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
