import { useCallback, useEffect, useState } from 'react';
import type { PopupState } from '../../core/protocol';
import type { SiteRule } from '../../core/settingsSchema';
import { DEFAULT_DELAY_SECONDS } from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS } from '../../core/types';
import { send } from '../../ui/rpc';
import { formatDuration } from '../../ui/format';
import { Button, NudgeMark } from '../../ui/components';

const MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

type SiteStatus = 'blocked' | 'limited' | 'not-blocked';

function computeSiteStatus(rule: SiteRule | null, remainingMs: number | null): SiteStatus {
  if (!rule || !rule.enabled) return 'not-blocked';
  const limitExhausted =
    rule.dailyLimitMinutes !== null && remainingMs !== null && remainingMs <= 0;
  if (rule.mode === 'HARD_BLOCK' || limitExhausted) return 'blocked';
  if (rule.dailyLimitMinutes !== null) return 'limited';
  return 'blocked';
}

function statusColor(status: SiteStatus): string {
  if (status === 'not-blocked') return 'var(--nudge-on-surface-variant)';
  if (status === 'limited') return 'var(--nudge-warn)';
  return 'var(--nudge-danger)';
}

function openDashboard() {
  chrome.tabs.create({ url: chrome.runtime.getURL('dashboard.html') });
  window.close();
}

export function Popup() {
  const [state, setState] = useState<PopupState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<BlockMode>('DELAY');
  const [adding, setAdding] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    send({ type: 'GET_POPUP_STATE' })
      .then((s) => {
        setState(s);
        setLoading(false);
      })
      .catch((e) => {
        setError(e instanceof Error ? e.message : 'Could not reach the extension.');
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleAddSite = useCallback(() => {
    if (!state?.currentDomain) return;
    setAdding(true);
    setAddError(null);
    send({
      type: 'ADD_SITE',
      domain: state.currentDomain,
      mode,
      delaySeconds: DEFAULT_DELAY_SECONDS,
    })
      .then((result) => {
        setAdding(false);
        if (!result.ok) {
          setAddError(result.reason ?? 'Could not add this site.');
          return;
        }
        load();
      })
      .catch((e) => {
        setAdding(false);
        setAddError(e instanceof Error ? e.message : 'Could not reach the extension.');
      });
  }, [state?.currentDomain, mode, load]);

  return (
    <div
      style={{
        width: 320,
        fontFamily: 'var(--nudge-font)',
        background: 'var(--nudge-background)',
        color: 'var(--nudge-on-surface)',
      }}
    >
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '16px 16px 12px',
        }}
      >
        <NudgeMark size={24} />
        <span style={{ fontSize: 16, fontWeight: 700 }}>Nudge</span>
      </header>

      {loading && (
        <div style={{ padding: '24px 16px', fontSize: 14, color: 'var(--nudge-on-surface-variant)' }}>
          Loading…
        </div>
      )}

      {!loading && error && (
        <div style={{ padding: '0 16px 16px' }}>
          <p style={{ fontSize: 13, color: 'var(--nudge-danger)', margin: '0 0 10px' }}>
            {error}
          </p>
          <Button variant="secondary" onClick={load}>
            Retry
          </Button>
        </div>
      )}

      {!loading && !error && state && (
        <div style={{ padding: '0 16px 16px', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <section>
            <p
              style={{
                margin: 0,
                fontSize: 12,
                color: 'var(--nudge-on-surface-variant)',
                textTransform: 'uppercase',
                letterSpacing: 0.4,
              }}
            >
              Today
            </p>
            <p style={{ margin: '2px 0 0', fontSize: 28, fontWeight: 700 }}>
              {formatDuration(state.todayTotalSeconds)}
            </p>
            {!state.globalEnabled && (
              <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--nudge-warn)' }}>
                Nudge is currently off
              </p>
            )}
          </section>

          <section
            style={{
              background: 'var(--nudge-surface)',
              border: '1px solid var(--nudge-surface-variant)',
              borderRadius: 'var(--nudge-radius-sm)',
              padding: 14,
            }}
          >
            {state.currentDomain === null ? (
              <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
                This isn't a site Nudge can block.
              </p>
            ) : (
              <>
                <p
                  style={{
                    margin: 0,
                    fontSize: 14,
                    fontWeight: 600,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                  title={state.currentDomain}
                >
                  {state.currentDomain}
                </p>

                {(() => {
                  const status = computeSiteStatus(state.currentRule, state.currentRemainingMs);
                  const rule = state.currentRule;
                  return (
                    <div style={{ marginTop: 6 }}>
                      <p style={{ margin: 0, fontSize: 13, fontWeight: 600, color: statusColor(status) }}>
                        {status === 'not-blocked' ? 'Not blocked' : MODE_LABELS[rule!.mode]}
                      </p>
                      {rule?.dailyLimitMinutes !== null &&
                        rule !== null &&
                        rule !== undefined &&
                        state.currentRemainingMs !== null && (
                          <p style={{ margin: '2px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                            {state.currentRemainingMs > 0
                              ? `${formatDuration(Math.floor(state.currentRemainingMs / 1000))} left today`
                              : 'Daily limit reached'}
                          </p>
                        )}
                    </div>
                  );
                })()}

                {state.currentRule === null && (
                  <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
                    <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                      Mode
                      <select
                        value={mode}
                        onChange={(e) => setMode(e.target.value as BlockMode)}
                        style={{
                          display: 'block',
                          width: '100%',
                          marginTop: 4,
                          padding: '8px 10px',
                          borderRadius: 8,
                          border: '1px solid var(--nudge-outline)',
                          background: 'var(--nudge-background)',
                          color: 'var(--nudge-on-surface)',
                          fontSize: 13,
                        }}
                      >
                        {MODES.map((m) => (
                          <option key={m} value={m}>
                            {MODE_LABELS[m]}
                          </option>
                        ))}
                      </select>
                    </label>
                    <Button onClick={handleAddSite} disabled={adding} style={{ width: '100%' }}>
                      {adding ? 'Adding…' : 'Block this site'}
                    </Button>
                    {addError && (
                      <p style={{ margin: 0, fontSize: 12, color: 'var(--nudge-danger)' }}>{addError}</p>
                    )}
                  </div>
                )}
              </>
            )}
          </section>

          <Button variant="muted" onClick={openDashboard} style={{ padding: '8px 0', textAlign: 'left' }}>
            Open dashboard →
          </Button>
        </div>
      )}
    </div>
  );
}
