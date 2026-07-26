import { useCallback, useEffect, useState } from 'react';
import { normalizeUserInput } from '../../core/domainMatcher';
import { DEFAULT_DELAY_SECONDS } from '../../core/settingsSchema';
import type { NudgeSettings, SiteRule } from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS } from '../../core/types';
import { send } from '../../ui/rpc';
import { Button, Card, NudgeMark } from '../../ui/components';

const MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

/** The sites people actually ask a blocker for first — one tap instead of typing. */
const SUGGESTED_SITES = [
  'youtube.com',
  'instagram.com',
  'tiktok.com',
  'reddit.com',
  'x.com',
  'facebook.com',
];

const MODE_BLURBS: Record<BlockMode, string> = {
  HARD_BLOCK: "The site doesn't open at all.",
  DELAY: 'A countdown runs before the site opens — enough time to change your mind.',
  BREATHING: 'A guided breathing pause runs before the site opens.',
};

/** Copyable, non-clickable instruction row. Extensions cannot navigate to chrome:// URLs. */
function CopyableStep({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      <code
        style={{
          padding: '6px 10px',
          borderRadius: 6,
          border: '1px solid var(--nudge-outline)',
          background: 'var(--nudge-surface-variant)',
          color: 'var(--nudge-on-surface)',
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          fontSize: 13,
          userSelect: 'all',
        }}
      >
        {text}
      </code>
      <Button
        variant="muted"
        style={{ padding: '4px 10px', fontSize: 12 }}
        onClick={() => {
          navigator.clipboard
            ?.writeText(text)
            .then(() => setCopied(true))
            .catch(() => setCopied(false));
        }}
      >
        {copied ? 'Copied' : 'Copy'}
      </Button>
    </div>
  );
}

export function Onboarding() {
  const [step, setStep] = useState(0);
  const [settings, setSettings] = useState<NudgeSettings | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [customDomain, setCustomDomain] = useState('');
  const [customError, setCustomError] = useState<string | null>(null);
  const [mode, setMode] = useState<BlockMode>('DELAY');
  const [finishing, setFinishing] = useState(false);
  const [finishError, setFinishError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoadError(null);
    send({ type: 'GET_SETTINGS' })
      .then(setSettings)
      .catch((e: unknown) =>
        setLoadError(e instanceof Error ? e.message : 'Could not reach the extension.'),
      );
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function toggleSite(domain: string) {
    setSelected((current) =>
      current.includes(domain) ? current.filter((d) => d !== domain) : [...current, domain],
    );
  }

  function addCustom() {
    const normalized = normalizeUserInput(customDomain);
    if (normalized === null) {
      setCustomError("That doesn't look like a website address.");
      return;
    }
    setCustomError(null);
    setCustomDomain('');
    setSelected((current) => (current.includes(normalized) ? current : [...current, normalized]));
  }

  /** Write the chosen rules + `onboardingComplete: true` in ONE save, then close the tab. */
  const finish = useCallback(() => {
    if (!settings) return;
    setFinishing(true);
    setFinishError(null);

    const existingDomains = new Set(settings.rules.map((r) => r.domain));
    const now = Date.now();
    const newRules: SiteRule[] = selected
      .filter((domain) => !existingDomains.has(domain))
      .map((domain, index) => ({
        id: `rule-${domain}-${now + index}`,
        domain,
        mode,
        delaySeconds: DEFAULT_DELAY_SECONDS,
        dailyLimitMinutes: null,
        enabled: true,
        createdAt: now,
        showTimeRemaining: false,
        schedule: null,
      }));

    send({
      type: 'SAVE_SETTINGS',
      settings: {
        ...settings,
        onboardingComplete: true,
        rules: [...settings.rules, ...newRules],
      },
    })
      .then((result) => {
        setFinishing(false);
        if (!result.ok) {
          setFinishError(result.reason ?? 'Could not save your choices.');
          return;
        }
        window.close();
      })
      .catch((e: unknown) => {
        setFinishing(false);
        setFinishError(e instanceof Error ? e.message : 'Could not reach the extension.');
      });
  }, [settings, selected, mode]);

  return (
    <div
      style={{
        minHeight: '100vh',
        background: 'var(--nudge-background)',
        color: 'var(--nudge-on-surface)',
        fontFamily: 'var(--nudge-font)',
        display: 'flex',
        justifyContent: 'center',
      }}
    >
      <div style={{ width: '100%', maxWidth: 620, padding: '56px 24px 64px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 32 }}>
          <NudgeMark size={32} />
          <span style={{ fontSize: 18, fontWeight: 700 }}>Nudge</span>
          <span
            style={{
              marginLeft: 'auto',
              fontSize: 13,
              color: 'var(--nudge-on-surface-variant)',
            }}
          >
            Step {step + 1} of 3
          </span>
        </div>

        {loadError && (
          <Card style={{ marginBottom: 20, borderColor: 'var(--nudge-danger)' }}>
            <p style={{ margin: '0 0 10px', fontSize: 13, color: 'var(--nudge-danger)' }}>
              {loadError}
            </p>
            <Button variant="secondary" onClick={load}>
              Retry
            </Button>
          </Card>
        )}

        {step === 0 && (
          <div>
            <h1 style={{ margin: '0 0 12px', fontSize: 34, fontWeight: 700, lineHeight: 1.2 }}>
              Break the scroll. Take back your time.
            </h1>
            <p
              style={{
                margin: '0 0 28px',
                fontSize: 16,
                lineHeight: 1.6,
                color: 'var(--nudge-on-surface-variant)',
              }}
            >
              Nudge works with friction instead of walls. Instead of only slamming a door, it puts a
              countdown or a breathing pause between you and the site — long enough to notice
              whether you actually meant to go there.
            </p>
            <Card style={{ marginBottom: 28 }}>
              <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6 }}>
                Everything stays on this device. No account, no sign-in, no telemetry — Nudge makes
                zero network requests, and your browsing stats never leave your computer.
              </p>
            </Card>
            <Button onClick={() => setStep(1)}>Get started</Button>
          </div>
        )}

        {step === 1 && (
          <div>
            <h1 style={{ margin: '0 0 12px', fontSize: 28, fontWeight: 700 }}>
              Pick your first sites
            </h1>
            <p style={{ margin: '0 0 20px', fontSize: 15, color: 'var(--nudge-on-surface-variant)' }}>
              Start with the one or two that pull you in most. You can change all of this later.
            </p>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 18 }}>
              {SUGGESTED_SITES.map((domain) => {
                const active = selected.includes(domain);
                return (
                  <button
                    key={domain}
                    type="button"
                    onClick={() => toggleSite(domain)}
                    aria-pressed={active}
                    style={{
                      padding: '8px 16px',
                      borderRadius: 999,
                      border: '1px solid var(--nudge-outline)',
                      background: active ? 'var(--nudge-primary)' : 'transparent',
                      color: active ? 'var(--nudge-on-primary)' : 'var(--nudge-on-surface)',
                      fontSize: 14,
                      cursor: 'pointer',
                    }}
                  >
                    {domain}
                  </button>
                );
              })}
            </div>

            <div style={{ display: 'flex', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
              <input
                type="text"
                aria-label="Another site"
                value={customDomain}
                placeholder="Another site…"
                onChange={(e) => {
                  setCustomDomain(e.target.value);
                  setCustomError(null);
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') addCustom();
                }}
                style={{
                  flex: '1 1 200px',
                  padding: '10px 12px',
                  fontSize: 14,
                  borderRadius: 8,
                  border: '1px solid var(--nudge-outline)',
                  background: 'var(--nudge-background)',
                  color: 'var(--nudge-on-surface)',
                }}
              />
              <Button variant="secondary" onClick={addCustom}>
                Add
              </Button>
            </div>
            {customError && (
              <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-danger)' }}>
                {customError}
              </p>
            )}

            {selected.filter((d) => !SUGGESTED_SITES.includes(d)).length > 0 && (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', margin: '12px 0 0' }}>
                {selected
                  .filter((d) => !SUGGESTED_SITES.includes(d))
                  .map((domain) => (
                    <button
                      key={domain}
                      type="button"
                      onClick={() => toggleSite(domain)}
                      style={{
                        padding: '8px 16px',
                        borderRadius: 999,
                        border: '1px solid var(--nudge-outline)',
                        background: 'var(--nudge-primary)',
                        color: 'var(--nudge-on-primary)',
                        fontSize: 14,
                        cursor: 'pointer',
                      }}
                    >
                      {domain}
                    </button>
                  ))}
              </div>
            )}

            <h2 style={{ margin: '28px 0 10px', fontSize: 16, fontWeight: 600 }}>
              How should Nudge handle them?
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 28 }}>
              {MODES.map((m) => (
                <label
                  key={m}
                  style={{
                    display: 'flex',
                    gap: 12,
                    alignItems: 'flex-start',
                    padding: 14,
                    borderRadius: 'var(--nudge-radius-sm)',
                    border: `1px solid ${mode === m ? 'var(--nudge-primary)' : 'var(--nudge-surface-variant)'}`,
                    cursor: 'pointer',
                  }}
                >
                  <input
                    type="radio"
                    name="mode"
                    checked={mode === m}
                    onChange={() => setMode(m)}
                    style={{ marginTop: 3, accentColor: 'var(--nudge-primary)' }}
                  />
                  <span>
                    <span style={{ display: 'block', fontSize: 15, fontWeight: 600 }}>
                      {MODE_LABELS[m]}
                    </span>
                    <span
                      style={{ display: 'block', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}
                    >
                      {MODE_BLURBS[m]}
                    </span>
                  </span>
                </label>
              ))}
            </div>

            <div style={{ display: 'flex', gap: 8 }}>
              <Button variant="muted" onClick={() => setStep(0)}>
                Back
              </Button>
              <Button onClick={() => setStep(2)}>Continue</Button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div>
            <h1 style={{ margin: '0 0 12px', fontSize: 28, fontWeight: 700 }}>Two last things</h1>

            <Card title="Incognito windows" style={{ marginBottom: 16 }}>
              <p style={{ margin: '0 0 14px', fontSize: 14, lineHeight: 1.6 }}>
                Chrome turns every extension OFF in Incognito by default — including Nudge. If you
                skip this, an Incognito window is an open door around your rules. Turning it on
                takes about ten seconds:
              </p>
              <ol style={{ margin: 0, paddingLeft: 20, fontSize: 14, lineHeight: 1.9 }}>
                <li>
                  Copy this address and open it in a new tab:
                  <div style={{ margin: '6px 0 10px' }}>
                    <CopyableStep text="chrome://extensions" />
                  </div>
                </li>
                <li>Find Nudge in the list and click "Details".</li>
                <li>Scroll down and turn on "Allow in Incognito".</li>
              </ol>
              <p style={{ margin: '14px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
                Chrome doesn't let an extension open that page for you, which is why you have to
                paste the address yourself.
              </p>
            </Card>

            <Card title="Pin Nudge to your toolbar" style={{ marginBottom: 16 }}>
              <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6 }}>
                Click the puzzle-piece icon at the right of Chrome's toolbar, find Nudge, and click
                the pin next to it. The popup then shows today's screen time and lets you block the
                site you're on in one click.
              </p>
            </Card>

            <Card style={{ marginBottom: 24 }}>
              <p style={{ margin: 0, fontSize: 13, lineHeight: 1.6, color: 'var(--nudge-on-surface-variant)' }}>
                Honest limit, up front: any Chrome extension can be disabled or removed from{' '}
                <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                  chrome://extensions
                </span>
                . Nudge adds deliberate friction — it can't make a decision for you.
              </p>
            </Card>

            {finishError && (
              <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-danger)' }}>
                {finishError}
              </p>
            )}

            <div style={{ display: 'flex', gap: 8 }}>
              <Button variant="muted" onClick={() => setStep(1)} disabled={finishing}>
                Back
              </Button>
              <Button onClick={finish} disabled={finishing || settings === null}>
                {finishing ? 'Saving…' : "I'm all set"}
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
