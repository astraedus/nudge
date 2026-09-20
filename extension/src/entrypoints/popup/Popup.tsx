import { useCallback, useEffect, useState } from 'react';
import type { PopupState } from '../../core/protocol';
import { DEFAULT_DELAY_SECONDS } from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS, SITE_MODE_LABELS } from '../../core/types';
import { send } from '../../ui/rpc';
import { formatDuration } from '../../ui/format';
import { Button, NudgeMark, Toggle } from '../../ui/components';
import { ChallengeDialog } from '../dashboard/ChallengeDialog';

const MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

/** A grayscale save the Commitment Lock intercepted — same pattern as the dashboard's
 * `PendingChallenge`, held locally because the popup has its own short-lived RPC flow. */
interface PendingGrayscale {
  challenge: string;
  domain: string;
  grayscale: boolean;
  incorrect: boolean;
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : 'Could not reach the extension.';
}

/**
 * The current site's status line, using the WORKER's already-resolved mode/applies
 * fields (`core/applies.ts`, schedule and budget already accounted for) rather than
 * re-deriving them from the raw rule — the popup is the untrusted side and must never
 * disagree with what the network layer is actually doing.
 */
function statusLine(state: PopupState): { text: string; color: string } {
  if (state.currentRule === null || !state.currentRule.enabled || state.currentMode === null) {
    return { text: 'Not blocked', color: 'var(--nudge-on-surface-variant)' };
  }

  if (!state.currentApplies) {
    if (state.currentRemainingMs !== null) {
      return {
        text: `Allowed · ${formatDuration(Math.floor(state.currentRemainingMs / 1000))} left`,
        color: 'var(--nudge-ok)',
      };
    }
    return { text: 'Allowed', color: 'var(--nudge-ok)' };
  }

  // Applies now: the mode may still read 'ALLOW' when it is only in force because the
  // daily budget ran out (the resolved default behaviour never changed) — that reads to
  // the user as "Blocked · limit reached", not "Allow".
  const label =
    state.currentMode === 'ALLOW' ? 'Blocked · limit reached' : SITE_MODE_LABELS[state.currentMode];
  return { text: label, color: 'var(--nudge-danger)' };
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
  const [pending, setPending] = useState<PendingGrayscale | null>(null);
  const [grayscaleSubmitting, setGrayscaleSubmitting] = useState(false);
  const [grayscaleError, setGrayscaleError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    send({ type: 'GET_POPUP_STATE' })
      .then((s) => {
        setState(s);
        setLoading(false);
      })
      .catch((e) => {
        setError(errorText(e));
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
        setAddError(errorText(e));
      });
  }, [state?.currentDomain, mode, load]);

  /**
   * The popup's grayscale quick toggle. It is an ordinary settings mutation, so it goes
   * through the worker exactly like SAVE_SETTINGS — the worker decides whether it needs a
   * Strict Mode challenge (turning grayscale OFF is a weakening; turning it ON never is),
   * this component just renders whatever comes back.
   */
  const setGrayscale = useCallback(
    (domain: string, grayscale: boolean, challengeResponse?: string) => {
      setGrayscaleError(null);
      setGrayscaleSubmitting(challengeResponse !== undefined);
      send({ type: 'SET_GRAYSCALE', domain, grayscale, challengeResponse })
        .then((result) => {
          setGrayscaleSubmitting(false);
          if (result.ok) {
            setPending(null);
            load();
            return;
          }
          if (result.challenge !== undefined) {
            setPending({
              challenge: result.challenge,
              domain,
              grayscale,
              incorrect: result.reason === 'challenge-incorrect',
            });
            return;
          }
          setPending(null);
          setGrayscaleError(result.reason ?? 'Could not update grayscale.');
        })
        .catch((e: unknown) => {
          setGrayscaleSubmitting(false);
          setGrayscaleError(errorText(e));
        });
    },
    [load],
  );

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
                  const status = statusLine(state);
                  return (
                    <p style={{ margin: '6px 0 0', fontSize: 13, fontWeight: 600, color: status.color }}>
                      {status.text}
                    </p>
                  );
                })()}

                {state.currentFeatureSummary !== null && (
                  <p style={{ margin: '2px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                    {state.currentFeatureSummary}
                  </p>
                )}

                <div
                  style={{
                    marginTop: 10,
                    paddingTop: 10,
                    borderTop: '1px solid var(--nudge-surface-variant)',
                  }}
                >
                  <Toggle
                    checked={state.currentGrayscale}
                    onChange={(next) => setGrayscale(state.currentDomain!, next)}
                    label="Grayscale this site"
                    disabled={grayscaleSubmitting}
                  />
                  {grayscaleError && (
                    <p style={{ margin: '6px 0 0', fontSize: 12, color: 'var(--nudge-danger)' }}>
                      {grayscaleError}
                    </p>
                  )}
                </div>

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

      {pending && (
        <ChallengeDialog
          challenge={pending.challenge}
          submitting={grayscaleSubmitting}
          error={pending.incorrect ? "That code doesn't match. Try again." : null}
          onCancel={() => {
            setPending(null);
            setGrayscaleError(null);
          }}
          onSubmit={(typed) => setGrayscale(pending.domain, pending.grayscale, typed)}
        />
      )}
    </div>
  );
}
