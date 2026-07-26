import { useCallback, useEffect, useState } from 'react';
import type { DashboardState } from '../../core/protocol';
import type { NudgeSettings } from '../../core/settingsSchema';
import { send } from '../../ui/rpc';
import { Button, NudgeMark } from '../../ui/components';
import { ChallengeDialog } from './ChallengeDialog';
import { SettingsPanel } from './SettingsPanel';
import { StatsPanel } from './StatsPanel';

type Tab = 'stats' | 'settings';

/** A save the Commitment Lock intercepted, held until the user types the code (or gives up). */
interface PendingChallenge {
  challenge: string;
  settings: NudgeSettings;
  /** Set after a wrong answer so the dialog can say so; the background reissues the SAME code. */
  incorrect: boolean;
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : 'Could not reach the extension.';
}

export function Dashboard() {
  const [data, setData] = useState<DashboardState | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('stats');
  const [saveError, setSaveError] = useState<string | null>(null);
  const [pending, setPending] = useState<PendingChallenge | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    send({ type: 'GET_DASHBOARD_STATE' })
      .then((next) => {
        setData(next);
        setLoading(false);
      })
      .catch((e: unknown) => {
        setLoadError(errorText(e));
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * Re-fetch when the dashboard comes back to the foreground.
   *
   * This is a normal tab people leave open, and usage accrues in the service worker while
   * they browse elsewhere — without this it keeps showing whatever was true when it was
   * opened, which reads as "the stats are broken". Refreshing on focus/visibility rather than
   * polling costs nothing while the tab sits in the background.
   */
  useEffect(() => {
    const refresh = () => {
      if (document.visibilityState === 'visible') load();
    };
    document.addEventListener('visibilitychange', refresh);
    window.addEventListener('focus', refresh);
    return () => {
      document.removeEventListener('visibilitychange', refresh);
      window.removeEventListener('focus', refresh);
    };
  }, [load]);

  /**
   * Persist settings through the background, which owns the Commitment Lock gate.
   *
   * A rejected save comes back as `{ ok: false, challenge }` — we render the challenge and retry
   * the SAME settings with `challengeResponse`. Optimistic local state is applied only after the
   * background confirms, so a gated change never appears to have taken effect when it hasn't.
   */
  const saveSettings = useCallback(
    (next: NudgeSettings, challengeResponse?: string) => {
      setSaveError(null);
      setSubmitting(challengeResponse !== undefined);
      send({ type: 'SAVE_SETTINGS', settings: next, challengeResponse })
        .then((result) => {
          setSubmitting(false);
          if (result.ok) {
            setPending(null);
            setData((current) => (current ? { ...current, settings: next } : current));
            return;
          }
          if (result.challenge !== undefined) {
            setPending({
              challenge: result.challenge,
              settings: next,
              incorrect: result.reason === 'challenge-incorrect',
            });
            return;
          }
          setPending(null);
          setSaveError(result.reason ?? 'That change could not be saved.');
        })
        .catch((e: unknown) => {
          setSubmitting(false);
          setSaveError(errorText(e));
        });
    },
    [],
  );

  return (
    <div
      style={{
        minHeight: '100vh',
        background: 'var(--nudge-background)',
        color: 'var(--nudge-on-surface)',
        fontFamily: 'var(--nudge-font)',
      }}
    >
      <div style={{ maxWidth: 880, margin: '0 auto', padding: '32px 24px 64px' }}>
        <header style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          <NudgeMark size={32} />
          <h1 style={{ margin: 0, fontSize: 24, fontWeight: 700 }}>Nudge</h1>
        </header>
        <p style={{ margin: '0 0 28px', fontSize: 14, color: 'var(--nudge-on-surface-variant)' }}>
          Break the scroll. Take back your time.
        </p>

        <nav
          role="tablist"
          style={{
            display: 'flex',
            gap: 4,
            marginBottom: 28,
            borderBottom: '1px solid var(--nudge-surface-variant)',
          }}
        >
          {(['stats', 'settings'] as Tab[]).map((t) => (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={tab === t}
              onClick={() => setTab(t)}
              style={{
                padding: '10px 18px',
                border: 'none',
                background: 'transparent',
                color: tab === t ? 'var(--nudge-primary)' : 'var(--nudge-on-surface-variant)',
                fontSize: 15,
                fontWeight: 600,
                borderBottom:
                  tab === t ? '2px solid var(--nudge-primary)' : '2px solid transparent',
                marginBottom: -1,
                cursor: 'pointer',
              }}
            >
              {t === 'stats' ? 'Stats' : 'Settings'}
            </button>
          ))}
        </nav>

        {loading && (
          <p style={{ fontSize: 14, color: 'var(--nudge-on-surface-variant)' }}>Loading…</p>
        )}

        {!loading && loadError && (
          <div>
            <p style={{ fontSize: 14, color: 'var(--nudge-danger)', marginTop: 0 }}>{loadError}</p>
            <Button variant="secondary" onClick={load}>
              Retry
            </Button>
          </div>
        )}

        {!loading && !loadError && data && (
          <>
            {tab === 'stats' ? (
              <StatsPanel data={data} />
            ) : (
              <SettingsPanel
                settings={data.settings}
                onChange={(next) => saveSettings(next)}
                saveError={saveError}
              />
            )}
          </>
        )}
      </div>

      {pending && (
        <ChallengeDialog
          challenge={pending.challenge}
          submitting={submitting}
          error={pending.incorrect ? "That code doesn't match. Try again." : null}
          onCancel={() => {
            setPending(null);
            setSaveError(null);
          }}
          onSubmit={(typed) => saveSettings(pending.settings, typed)}
        />
      )}
    </div>
  );
}
