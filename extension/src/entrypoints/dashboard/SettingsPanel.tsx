import { useRef, useState } from 'react';
import { normalizeUserInput } from '../../core/domainMatcher';
import {
  DEFAULT_DELAY_SUBTITLES,
  DEFAULT_DELAY_TITLES,
  DEFAULT_HARD_BLOCK_MESSAGES,
} from '../../core/messages';
import {
  CHALLENGE_LENGTH_EASY,
  CHALLENGE_LENGTH_HARD,
  CHALLENGE_LENGTH_MEDIUM,
  DAILY_LIMIT_MAX_MINUTES,
  DAILY_LIMIT_MIN_MINUTES,
  DAILY_LIMIT_PRESETS,
  DEFAULT_DELAY_SECONDS,
  DELAY_MAX_SECONDS,
  DELAY_MIN_SECONDS,
  DELAY_PRESETS,
  TEMP_ALLOW_MAX_MINUTES,
  TEMP_ALLOW_MIN_MINUTES,
} from '../../core/settingsSchema';
import type { NudgeSettings, SiteRule } from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS } from '../../core/types';
import { buildExport, dedupeImportedRules, parseImport } from '../../ui/exportImport';
import { formatMinuteOfDay } from '../../ui/format';
import { Button, Card, Toggle } from '../../ui/components';
import { RuleEditor } from './RuleEditor';

const MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

const DIFFICULTIES: { label: string; length: number }[] = [
  { label: 'Easy', length: CHALLENGE_LENGTH_EASY },
  { label: 'Medium', length: CHALLENGE_LENGTH_MEDIUM },
  { label: 'Hard', length: CHALLENGE_LENGTH_HARD },
];

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.round(value)));
}

function Chip({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      style={{
        padding: '6px 14px',
        borderRadius: 999,
        border: '1px solid var(--nudge-outline)',
        background: active ? 'var(--nudge-primary)' : 'transparent',
        color: active ? 'var(--nudge-on-primary)' : 'var(--nudge-on-surface)',
        fontSize: 13,
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  );
}

function minuteLabel(minutes: number): string {
  return minutes < 60 ? `${minutes}m` : `${minutes / 60}h`;
}

/** One "Delay title / Delay subtitle / Hard-block message" textarea + its reset action. */
function MessageField({
  label,
  value,
  defaults,
  onChange,
}: {
  label: string;
  value: string[];
  defaults: string[];
  onChange: (lines: string[]) => void;
}) {
  const usingDefaults = value.length === 0;
  return (
    <div style={{ marginBottom: 18 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
          marginBottom: 6,
        }}
      >
        <label htmlFor={`msg-${label}`} style={{ fontSize: 13, fontWeight: 600 }}>
          {label}
        </label>
        <Button
          variant="muted"
          onClick={() => onChange([])}
          disabled={usingDefaults}
          style={{ padding: '2px 8px', fontSize: 12 }}
        >
          Reset to defaults
        </Button>
      </div>
      <textarea
        id={`msg-${label}`}
        rows={5}
        value={(usingDefaults ? defaults : value).join('\n')}
        placeholder="One message per line"
        onChange={(e) => {
          const lines = e.target.value.split('\n').map((l) => l.trimEnd());
          // All-blank means "use the bundled defaults" — the same convention the
          // background's `resolvePool` applies when rendering a block page.
          onChange(lines.every((l) => l.trim() === '') ? [] : lines);
        }}
        style={{
          width: '100%',
          padding: 10,
          fontSize: 13,
          lineHeight: 1.5,
          fontFamily: 'inherit',
          borderRadius: 8,
          border: '1px solid var(--nudge-outline)',
          background: 'var(--nudge-background)',
          color: 'var(--nudge-on-surface)',
          resize: 'vertical',
        }}
      />
      {usingDefaults && (
        <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          Using the built-in messages. Edit above to use your own.
        </p>
      )}
    </div>
  );
}

/** A compact summary line for a rule row: "Delay · 15s · 30m/day · Scheduled 23:00–06:00". */
function ruleSummary(rule: SiteRule): string {
  const parts: string[] = [MODE_LABELS[rule.mode]];
  if (rule.mode !== 'HARD_BLOCK') parts.push(`${rule.delaySeconds}s`);
  if (rule.dailyLimitMinutes !== null) parts.push(`${minuteLabel(rule.dailyLimitMinutes)}/day`);
  if (rule.schedule?.enabled) {
    const start = rule.schedule.startMinute;
    const end = rule.schedule.endMinute;
    parts.push(
      start !== null && end !== null
        ? `Scheduled ${formatMinuteOfDay(start)}–${formatMinuteOfDay(end)}`
        : 'Scheduled',
    );
  }
  if (!rule.enabled) parts.push('disabled');
  return parts.join(' · ');
}

export function SettingsPanel({
  settings,
  onChange,
  saveError,
}: {
  settings: NudgeSettings;
  /** Every mutation goes straight to the background so Strict Mode can gate it there. */
  onChange: (next: NudgeSettings) => void;
  saveError?: string | null;
}) {
  const [editingRuleId, setEditingRuleId] = useState<string | null>(null);
  const [newDomain, setNewDomain] = useState('');
  const [newDomainError, setNewDomainError] = useState<string | null>(null);
  const [importMessage, setImportMessage] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const editingRule = settings.rules.find((r) => r.id === editingRuleId) ?? null;

  function patch(changes: Partial<NudgeSettings>) {
    onChange({ ...settings, ...changes });
  }

  function handleAddDomain() {
    const normalized = normalizeUserInput(newDomain);
    if (normalized === null) {
      setNewDomainError("That doesn't look like a website address.");
      return;
    }
    if (settings.rules.some((r) => r.domain === normalized)) {
      setNewDomainError('There is already a rule for that site.');
      return;
    }
    setNewDomainError(null);
    setNewDomain('');
    patch({
      rules: [
        ...settings.rules,
        {
          id: `rule-${normalized}-${Date.now()}`,
          domain: normalized,
          mode: 'DELAY',
          delaySeconds: DEFAULT_DELAY_SECONDS,
          dailyLimitMinutes: null,
          enabled: true,
          createdAt: Date.now(),
          showTimeRemaining: false,
          schedule: null,
        },
      ],
    });
  }

  function handleExport() {
    const payload = buildExport(settings);
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `nudge-rules-${new Date().toISOString().slice(0, 10)}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  function handleImportFile(file: File) {
    setImportMessage(null);
    file
      .text()
      .then((text) => {
        const result = parseImport(text);
        if (!result.ok || !result.rules) {
          setImportMessage(result.error ?? 'Could not read that file.');
          return;
        }
        const merged = dedupeImportedRules(settings.rules, result.rules);
        const added = merged.length - settings.rules.length;
        patch({ rules: merged });
        setImportMessage(
          added === 0
            ? 'Nothing new to import — every site in that file already has a rule.'
            : `Imported ${added} rule${added === 1 ? '' : 's'}.`,
        );
      })
      .catch(() => setImportMessage('Could not read that file.'));
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {saveError && (
        <Card style={{ borderColor: 'var(--nudge-danger)' }}>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-danger)' }}>{saveError}</p>
        </Card>
      )}

      <Card>
        <Toggle
          checked={settings.globalEnabled}
          onChange={(globalEnabled) => patch({ globalEnabled })}
          label="Nudge is on"
        />
        <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          Turning this off suspends every rule — Nudge behaves as if it weren't installed.
        </p>
      </Card>

      <Card title="Blocked sites">
        <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
          <input
            type="text"
            aria-label="Site to block"
            value={newDomain}
            placeholder="youtube.com"
            onChange={(e) => {
              setNewDomain(e.target.value);
              setNewDomainError(null);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleAddDomain();
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
          <Button onClick={handleAddDomain}>Add site</Button>
        </div>
        {newDomainError && (
          <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-danger)' }}>
            {newDomainError}
          </p>
        )}

        {settings.rules.length === 0 ? (
          <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            No sites yet. Add one above, or use "Block this site" from the toolbar popup.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {settings.rules.map((rule) => (
              <div
                key={rule.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                  padding: '10px 12px',
                  borderRadius: 'var(--nudge-radius-sm)',
                  border: '1px solid var(--nudge-surface-variant)',
                }}
              >
                <div style={{ minWidth: 0 }}>
                  <p
                    style={{
                      margin: 0,
                      fontSize: 14,
                      fontWeight: 600,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {rule.domain}
                  </p>
                  <p style={{ margin: '2px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                    {ruleSummary(rule)}
                  </p>
                </div>
                <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                  <Button
                    variant="secondary"
                    onClick={() => setEditingRuleId(rule.id)}
                    style={{ padding: '6px 14px', fontSize: 13 }}
                  >
                    Edit
                  </Button>
                  <Button
                    variant="muted"
                    onClick={() => patch({ rules: settings.rules.filter((r) => r.id !== rule.id) })}
                    style={{ padding: '6px 14px', fontSize: 13 }}
                  >
                    Delete
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, marginTop: 16, flexWrap: 'wrap' }}>
          <Button variant="muted" onClick={handleExport} style={{ padding: '8px 14px', fontSize: 13 }}>
            Export rules
          </Button>
          <Button
            variant="muted"
            onClick={() => fileInputRef.current?.click()}
            style={{ padding: '8px 14px', fontSize: 13 }}
          >
            Import rules
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept="application/json,.json"
            aria-label="Import rules file"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleImportFile(file);
              e.target.value = '';
            }}
          />
        </div>
        {importMessage && (
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            {importMessage}
          </p>
        )}
      </Card>

      <Card title="Daily Time Limit defaults">
        <p style={{ margin: '0 0 10px', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          Limits are set per site in each rule. Presets: {DAILY_LIMIT_PRESETS.map(minuteLabel).join(' · ')},
          or any value from {DAILY_LIMIT_MIN_MINUTES} to {DAILY_LIMIT_MAX_MINUTES} minutes.
        </p>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          Delay presets: {DELAY_PRESETS.map((d) => `${d}s`).join(' · ')}, or {DELAY_MIN_SECONDS}–
          {DELAY_MAX_SECONDS} seconds.
        </p>
      </Card>

      <Card title="After a pause">
        <label style={{ fontSize: 13 }}>
          Temporary access (minutes)
          <input
            type="number"
            aria-label="Temporary access minutes"
            min={TEMP_ALLOW_MIN_MINUTES}
            max={TEMP_ALLOW_MAX_MINUTES}
            value={settings.tempAllowMinutes}
            onChange={(e) => {
              const parsed = Number(e.target.value);
              if (e.target.value !== '' && Number.isFinite(parsed)) {
                patch({
                  tempAllowMinutes: clamp(parsed, TEMP_ALLOW_MIN_MINUTES, TEMP_ALLOW_MAX_MINUTES),
                });
              }
            }}
            style={{
              display: 'block',
              width: 90,
              marginTop: 6,
              padding: '8px 10px',
              borderRadius: 8,
              border: '1px solid var(--nudge-outline)',
              background: 'var(--nudge-background)',
              color: 'var(--nudge-on-surface)',
            }}
          />
        </label>
        <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          How long a site stays open after you complete a Delay or Breathing pause
          ({TEMP_ALLOW_MIN_MINUTES}–{TEMP_ALLOW_MAX_MINUTES} minutes).
        </p>
      </Card>

      <Card title="Block messages">
        <MessageField
          label="Delay title"
          value={settings.messages.delayTitles}
          defaults={DEFAULT_DELAY_TITLES}
          onChange={(delayTitles) => patch({ messages: { ...settings.messages, delayTitles } })}
        />
        <MessageField
          label="Delay subtitle"
          value={settings.messages.delaySubtitles}
          defaults={DEFAULT_DELAY_SUBTITLES}
          onChange={(delaySubtitles) => patch({ messages: { ...settings.messages, delaySubtitles } })}
        />
        <MessageField
          label="Hard-block message"
          value={settings.messages.hardBlockMessages}
          defaults={DEFAULT_HARD_BLOCK_MESSAGES}
          onChange={(hardBlockMessages) =>
            patch({ messages: { ...settings.messages, hardBlockMessages } })
          }
        />
        <p style={{ margin: 0, fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          One message per line. Nudge picks one at random each time.
        </p>
      </Card>

      <Card title="YouTube Shorts">
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
          <Chip
            label="Inherit"
            active={settings.youtube.shortsMode === 'INHERIT'}
            onClick={() => patch({ youtube: { ...settings.youtube, shortsMode: 'INHERIT' } })}
          />
          {MODES.map((m) => (
            <Chip
              key={m}
              label={MODE_LABELS[m]}
              active={settings.youtube.shortsMode === m}
              onClick={() => patch({ youtube: { ...settings.youtube, shortsMode: m } })}
            />
          ))}
        </div>
        <p style={{ margin: '0 0 14px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          "Inherit" follows whatever rule you set for youtube.com.
        </p>

        {settings.youtube.shortsMode !== 'INHERIT' && settings.youtube.shortsMode !== 'HARD_BLOCK' && (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', marginBottom: 14 }}>
            {DELAY_PRESETS.map((preset) => (
              <Chip
                key={preset}
                label={`${preset}s`}
                active={settings.youtube.shortsDelaySeconds === preset}
                onClick={() => patch({ youtube: { ...settings.youtube, shortsDelaySeconds: preset } })}
              />
            ))}
            <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
              Custom
              <input
                type="number"
                aria-label="Shorts custom delay seconds"
                min={DELAY_MIN_SECONDS}
                max={DELAY_MAX_SECONDS}
                value={settings.youtube.shortsDelaySeconds}
                onChange={(e) => {
                  const parsed = Number(e.target.value);
                  if (e.target.value !== '' && Number.isFinite(parsed)) {
                    patch({
                      youtube: {
                        ...settings.youtube,
                        shortsDelaySeconds: clamp(parsed, DELAY_MIN_SECONDS, DELAY_MAX_SECONDS),
                      },
                    });
                  }
                }}
                style={{
                  width: 64,
                  marginLeft: 6,
                  padding: '6px 8px',
                  borderRadius: 8,
                  border: '1px solid var(--nudge-outline)',
                  background: 'var(--nudge-background)',
                  color: 'var(--nudge-on-surface)',
                }}
              />
            </label>
          </div>
        )}

        <Toggle
          checked={settings.youtube.hideShortsShelf}
          onChange={(hideShortsShelf) => patch({ youtube: { ...settings.youtube, hideShortsShelf } })}
          label="Hide Shorts shelf"
        />
      </Card>

      <Card title="Escape Hatch">
        <Toggle
          checked={settings.emergencyPass.enabled && !settings.strictMode.enabled}
          onChange={(enabled) => patch({ emergencyPass: { enabled } })}
          label="Allow a daily 2-minute pass"
          disabled={settings.strictMode.enabled}
        />
        <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          {settings.strictMode.enabled
            ? 'Unavailable while Commitment Lock is on — a commitment device cannot have a one-tap bypass.'
            : 'One 2-minute window per day, shared across every blocked site.'}
        </p>
      </Card>

      <Card title="Commitment Lock">
        <Toggle
          checked={settings.strictMode.enabled}
          onChange={(enabled) => patch({ strictMode: { ...settings.strictMode, enabled } })}
          label="Lock my settings (Strict Mode)"
        />
        <p style={{ margin: '10px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          While this is on, anything that weakens your protection — turning Nudge off, deleting or
          softening a rule, shortening a delay, raising a limit — requires typing a random unlock
          code by hand. Strengthening protection is never gated.
        </p>

        <div style={{ marginTop: 16 }}>
          <p style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 600 }}>Difficulty</p>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {DIFFICULTIES.map(({ label, length }) => (
              <Chip
                key={label}
                label={`${label} (${length})`}
                active={settings.strictMode.challengeLength === length}
                onClick={() =>
                  patch({ strictMode: { ...settings.strictMode, challengeLength: length } })
                }
              />
            ))}
          </div>
          <p style={{ margin: '8px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
            The number of characters you have to type to unlock a weakening change.
          </p>
        </div>

        <div
          style={{
            marginTop: 18,
            padding: 14,
            borderRadius: 'var(--nudge-radius-sm)',
            border: '1px solid var(--nudge-outline)',
          }}
        >
          <p style={{ margin: '0 0 6px', fontSize: 13, fontWeight: 700 }}>
            What this lock cannot do
          </p>
          <p style={{ margin: 0, fontSize: 13, lineHeight: 1.55, color: 'var(--nudge-on-surface-variant)' }}>
            A Chrome extension cannot prevent its own removal. Anyone can open{' '}
            <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
              chrome://extensions
            </span>{' '}
            and uninstall or disable Nudge in two clicks, and no extension — ours or anyone
            else's — can stop that. Commitment Lock is a commitment device, not an unbreakable
            lock: it makes an impulsive bypass slow and deliberate enough that you notice you're
            doing it. We'd rather tell you that plainly than pretend otherwise.
          </p>
        </div>
      </Card>

      {editingRule && (
        <RuleEditor
          rule={editingRule}
          onCancel={() => setEditingRuleId(null)}
          onSave={(next) => {
            patch({ rules: settings.rules.map((r) => (r.id === next.id ? next : r)) });
            setEditingRuleId(null);
          }}
          onDelete={(id) => {
            patch({ rules: settings.rules.filter((r) => r.id !== id) });
            setEditingRuleId(null);
          }}
        />
      )}
    </div>
  );
}
