import { useEffect, useState } from 'react';
import { Button } from '../../ui/components';

/** Strip whitespace/dashes so display formatting never affects the length/match check. */
function normalizeChallengeInput(value: string): string {
  return value.replace(/[\s-]/g, '');
}

/** Dash-group in chunks of 5 for display, matching the Android Strict Mode challenge UI. */
function formatChallengeDisplay(code: string): string {
  const chunks: string[] = [];
  for (let i = 0; i < code.length; i += 5) {
    chunks.push(code.slice(i, i + 5));
  }
  return chunks.join('-');
}

/**
 * "Commitment Lock" unlock UI. A save the background rejects for weakening protection while
 * Strict Mode is on comes back as `SaveResult { ok: false, challenge }` — this dialog makes the
 * user TYPE that code back (never auto-filled, paste blocked) before retrying the save. The
 * friction is the point: it's the only thing standing between "I'll just turn it off" and
 * actually following through.
 */
export function ChallengeDialog({
  challenge,
  onSubmit,
  onCancel,
  submitting = false,
  error = null,
}: {
  challenge: string;
  onSubmit: (typed: string) => void;
  onCancel: () => void;
  submitting?: boolean;
  error?: string | null;
}) {
  const [typed, setTyped] = useState('');

  // A fresh (or re-issued, after a wrong attempt) challenge always starts from a blank input.
  useEffect(() => {
    setTyped('');
  }, [challenge]);

  const normalized = normalizeChallengeInput(typed);
  const targetLength = normalizeChallengeInput(challenge).length;
  const canSubmit = normalized.length === targetLength && !submitting;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Commitment Lock unlock"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      <div
        style={{
          width: 360,
          maxWidth: '90vw',
          background: 'var(--nudge-surface)',
          borderRadius: 'var(--nudge-radius)',
          padding: 24,
          boxShadow: '0 12px 40px rgba(0,0,0,0.35)',
        }}
      >
        <h2 style={{ margin: '0 0 8px', fontSize: 18, fontWeight: 700 }}>Commitment Lock</h2>
        <p style={{ margin: '0 0 16px', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          This change weakens protection. Type the code below exactly — pasting is disabled on
          purpose, that friction is the whole point.
        </p>

        <p
          data-testid="challenge-code"
          style={{
            margin: '0 0 12px',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: 20,
            letterSpacing: 2,
            fontWeight: 700,
            wordBreak: 'break-all',
          }}
        >
          {formatChallengeDisplay(challenge)}
        </p>

        <input
          type="text"
          autoFocus
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          aria-label="Unlock code"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          onPaste={(e) => e.preventDefault()}
          placeholder="Type the code above"
          style={{
            width: '100%',
            padding: '10px 12px',
            fontSize: 15,
            borderRadius: 8,
            border: '1px solid var(--nudge-outline)',
            background: 'var(--nudge-background)',
            color: 'var(--nudge-on-surface)',
            marginBottom: 8,
          }}
        />

        <p
          data-testid="challenge-progress"
          style={{ margin: '0 0 12px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}
        >
          {normalized.length}/{targetLength}
        </p>

        {error && (
          <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-danger)' }}>{error}</p>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button variant="muted" onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={() => onSubmit(typed)} disabled={!canSubmit}>
            {submitting ? 'Checking…' : 'Unlock'}
          </Button>
        </div>
      </div>
    </div>
  );
}
