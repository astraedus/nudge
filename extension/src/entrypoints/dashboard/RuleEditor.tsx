import { useState } from 'react';
import type { ScheduleOverride, SiteRule } from '../../core/settingsSchema';
import {
  DAILY_LIMIT_MAX_MINUTES,
  DAILY_LIMIT_MIN_MINUTES,
  DAILY_LIMIT_PRESETS,
  DELAY_MAX_SECONDS,
  DELAY_MIN_SECONDS,
  DELAY_PRESETS,
} from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS } from '../../core/types';
import { formatMinuteOfDay } from '../../ui/format';
import { Button, Card, Toggle } from '../../ui/components';

const MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];
const DAY_LABELS: { iso: number; label: string }[] = [
  { iso: 1, label: 'Mon' },
  { iso: 2, label: 'Tue' },
  { iso: 3, label: 'Wed' },
  { iso: 4, label: 'Thu' },
  { iso: 5, label: 'Fri' },
  { iso: 6, label: 'Sat' },
  { iso: 7, label: 'Sun' },
];

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.round(value)));
}

/** `<input type="time">` uses `HH:MM`, which is exactly `formatMinuteOfDay`'s output. */
function minutesToTimeValue(minutes: number | null): string {
  return formatMinuteOfDay(minutes ?? 0);
}

function timeValueToMinutes(value: string): number | null {
  const match = /^(\d{2}):(\d{2})$/.exec(value);
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  return clamp(h * 60 + m, 0, 1439);
}

function modePicker(value: BlockMode, onChange: (mode: BlockMode) => void, idPrefix: string) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value as BlockMode)}
      aria-label={`${idPrefix} mode`}
      style={{
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
  );
}

function delayPicker(
  value: number,
  onChange: (seconds: number) => void,
  idPrefix: string,
) {
  const isPreset = (DELAY_PRESETS as readonly number[]).includes(value);
  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
      {DELAY_PRESETS.map((preset) => (
        <button
          key={preset}
          type="button"
          onClick={() => onChange(preset)}
          style={{
            padding: '6px 12px',
            borderRadius: 999,
            border: '1px solid var(--nudge-outline)',
            background: value === preset ? 'var(--nudge-primary)' : 'transparent',
            color: value === preset ? 'var(--nudge-on-primary)' : 'var(--nudge-on-surface)',
            fontSize: 12,
            cursor: 'pointer',
          }}
        >
          {preset}s
        </button>
      ))}
      <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
        Custom
        <input
          type="number"
          aria-label={`${idPrefix} custom delay seconds`}
          min={DELAY_MIN_SECONDS}
          max={DELAY_MAX_SECONDS}
          value={isPreset ? '' : value}
          placeholder={String(value)}
          onChange={(e) => {
            const parsed = Number(e.target.value);
            if (e.target.value !== '' && Number.isFinite(parsed)) {
              onChange(clamp(parsed, DELAY_MIN_SECONDS, DELAY_MAX_SECONDS));
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
  );
}

export function RuleEditor({
  rule,
  onSave,
  onCancel,
  onDelete,
}: {
  rule: SiteRule;
  onSave: (next: SiteRule) => void;
  onCancel: () => void;
  onDelete?: (id: string) => void;
}) {
  const [draft, setDraft] = useState<SiteRule>(rule);

  const limitPreset = (DAILY_LIMIT_PRESETS as readonly number[]).includes(
    draft.dailyLimitMinutes ?? -1,
  );

  const schedule: ScheduleOverride | null = draft.schedule;

  function setSchedule(patch: Partial<ScheduleOverride>) {
    setDraft((d) => ({
      ...d,
      schedule: d.schedule ? { ...d.schedule, ...patch } : null,
    }));
  }

  function toggleScheduleEnabled(enabled: boolean) {
    setDraft((d) => ({
      ...d,
      schedule: enabled
        ? (d.schedule ?? {
            enabled: true,
            days: null,
            startMinute: 540, // 09:00 — a concrete starting window, not "always"
            endMinute: 1020, // 17:00
            mode: d.mode,
            delaySeconds: d.delaySeconds,
          })
        : d.schedule
          ? { ...d.schedule, enabled: false }
          : null,
    }));
  }

  function toggleDay(iso: number) {
    setSchedule({
      days: schedule?.days?.includes(iso)
        ? schedule.days.filter((d) => d !== iso)
        : [...(schedule?.days ?? []), iso],
    });
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Edit rule for ${draft.domain}`}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        overflowY: 'auto',
        padding: '5vh 16px',
        zIndex: 900,
      }}
    >
      <div
        style={{
          width: 480,
          maxWidth: '100%',
          background: 'var(--nudge-surface)',
          borderRadius: 'var(--nudge-radius)',
          padding: 24,
          display: 'flex',
          flexDirection: 'column',
          gap: 18,
        }}
      >
        <div>
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 700 }}>{draft.domain}</h2>
          <Toggle
            checked={draft.enabled}
            onChange={(enabled) => setDraft((d) => ({ ...d, enabled }))}
            label="Rule enabled"
          />
        </div>

        <Card title="Mode">{modePicker(draft.mode, (mode) => setDraft((d) => ({ ...d, mode })), 'default')}</Card>

        <Card title="Delay">{delayPicker(draft.delaySeconds, (delaySeconds) => setDraft((d) => ({ ...d, delaySeconds })), 'default')}</Card>

        <Card title="Daily Time Limit">
          {draft.mode === 'HARD_BLOCK' ? (
            // A budget only means something for a mode that lets you through. Hard Block bars
            // the site outright, so there is no browsing time to limit — offering the control
            // here would invite a combination that reads as "blocked, but only after 30
            // minutes", which is not what it does.
            <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
              Not used with Hard Block — the site is always blocked, so there is no time to
              budget. Switch to Delay or Breathing to set a daily limit.
            </p>
          ) : (
          <>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <button
              type="button"
              onClick={() => setDraft((d) => ({ ...d, dailyLimitMinutes: null }))}
              style={{
                padding: '6px 12px',
                borderRadius: 999,
                border: '1px solid var(--nudge-outline)',
                background: draft.dailyLimitMinutes === null ? 'var(--nudge-primary)' : 'transparent',
                color:
                  draft.dailyLimitMinutes === null
                    ? 'var(--nudge-on-primary)'
                    : 'var(--nudge-on-surface)',
                fontSize: 12,
                cursor: 'pointer',
              }}
            >
              No limit
            </button>
            {DAILY_LIMIT_PRESETS.map((preset) => (
              <button
                key={preset}
                type="button"
                onClick={() => setDraft((d) => ({ ...d, dailyLimitMinutes: preset }))}
                style={{
                  padding: '6px 12px',
                  borderRadius: 999,
                  border: '1px solid var(--nudge-outline)',
                  background: draft.dailyLimitMinutes === preset ? 'var(--nudge-primary)' : 'transparent',
                  color:
                    draft.dailyLimitMinutes === preset
                      ? 'var(--nudge-on-primary)'
                      : 'var(--nudge-on-surface)',
                  fontSize: 12,
                  cursor: 'pointer',
                }}
              >
                {preset < 60 ? `${preset}m` : `${preset / 60}h`}
              </button>
            ))}
            <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
              Custom
              <input
                type="number"
                aria-label="Custom daily limit minutes"
                min={DAILY_LIMIT_MIN_MINUTES}
                max={DAILY_LIMIT_MAX_MINUTES}
                value={limitPreset || draft.dailyLimitMinutes === null ? '' : draft.dailyLimitMinutes}
                placeholder={draft.dailyLimitMinutes === null ? '—' : String(draft.dailyLimitMinutes)}
                onChange={(e) => {
                  const parsed = Number(e.target.value);
                  if (e.target.value !== '' && Number.isFinite(parsed)) {
                    setDraft((d) => ({
                      ...d,
                      dailyLimitMinutes: clamp(parsed, DAILY_LIMIT_MIN_MINUTES, DAILY_LIMIT_MAX_MINUTES),
                    }));
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
          {draft.dailyLimitMinutes !== null && (
            <div style={{ marginTop: 12 }}>
              <Toggle
                checked={draft.showTimeRemaining}
                onChange={(showTimeRemaining) => setDraft((d) => ({ ...d, showTimeRemaining }))}
                label="Show time remaining"
              />
            </div>
          )}
          </>
          )}
        </Card>

        <Card title="Scheduled Override">
          <Toggle
            checked={schedule?.enabled === true}
            onChange={toggleScheduleEnabled}
            label="Enable scheduled override"
          />
          {schedule?.enabled && (
            <div style={{ marginTop: 14, display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <p style={{ margin: '0 0 6px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                  Days (none selected = every day)
                </p>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {DAY_LABELS.map(({ iso, label }) => {
                    const active = schedule.days?.includes(iso) ?? false;
                    return (
                      <button
                        key={iso}
                        type="button"
                        onClick={() => toggleDay(iso)}
                        style={{
                          width: 40,
                          height: 32,
                          borderRadius: 8,
                          border: '1px solid var(--nudge-outline)',
                          background: active ? 'var(--nudge-primary)' : 'transparent',
                          color: active ? 'var(--nudge-on-primary)' : 'var(--nudge-on-surface)',
                          fontSize: 12,
                          cursor: 'pointer',
                        }}
                      >
                        {label}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                  Start
                  <input
                    type="time"
                    step={900}
                    aria-label="Scheduled override start time"
                    value={minutesToTimeValue(schedule.startMinute)}
                    onChange={(e) => setSchedule({ startMinute: timeValueToMinutes(e.target.value) })}
                    style={{
                      display: 'block',
                      marginTop: 4,
                      padding: '6px 8px',
                      borderRadius: 8,
                      border: '1px solid var(--nudge-outline)',
                      background: 'var(--nudge-background)',
                      color: 'var(--nudge-on-surface)',
                    }}
                  />
                </label>
                <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                  End
                  <input
                    type="time"
                    step={900}
                    aria-label="Scheduled override end time"
                    value={minutesToTimeValue(schedule.endMinute)}
                    onChange={(e) => setSchedule({ endMinute: timeValueToMinutes(e.target.value) })}
                    style={{
                      display: 'block',
                      marginTop: 4,
                      padding: '6px 8px',
                      borderRadius: 8,
                      border: '1px solid var(--nudge-outline)',
                      background: 'var(--nudge-background)',
                      color: 'var(--nudge-on-surface)',
                    }}
                  />
                </label>
              </div>
              <p style={{ margin: 0, fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                Overnight spans are supported — e.g. start 23:00, end 06:00 applies from 11pm
                through 6am the next morning.
              </p>

              <div>
                <p style={{ margin: '0 0 6px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
                  Mode + delay inside the window
                </p>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
                  {modePicker(schedule.mode, (mode) => setSchedule({ mode }), 'scheduled')}
                  {delayPicker(schedule.delaySeconds, (delaySeconds) => setSchedule({ delaySeconds }), 'scheduled')}
                </div>
              </div>
            </div>
          )}
        </Card>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          {onDelete ? (
            <Button variant="danger" onClick={() => onDelete(draft.id)}>
              Delete rule
            </Button>
          ) : (
            <span />
          )}
          <div style={{ display: 'flex', gap: 8 }}>
            <Button variant="muted" onClick={onCancel}>
              Cancel
            </Button>
            <Button onClick={() => onSave(draft)}>Save rule</Button>
          </div>
        </div>
      </div>
    </div>
  );
}
