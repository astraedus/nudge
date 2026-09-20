import { useState } from 'react';
import { gateDefinition, platformForDomain } from '../../core/platforms';
import type { GateId, HideId, Platform } from '../../core/platforms';
import type { GateSetting, ScheduleOverride, SiteRule } from '../../core/settingsSchema';
import {
  DAILY_LIMIT_MAX_MINUTES,
  DAILY_LIMIT_MIN_MINUTES,
  DAILY_LIMIT_PRESETS,
  DELAY_MAX_SECONDS,
  DELAY_MIN_SECONDS,
  DELAY_PRESETS,
} from '../../core/settingsSchema';
import type { BlockMode, SiteMode } from '../../core/types';
import { isBlockMode, MODE_LABELS, SITE_MODE_LABELS } from '../../core/types';
import { formatMinuteOfDay } from '../../ui/format';
import { Button, Card, Chip, Toggle } from '../../ui/components';
import { ChannelListEditor } from './ChannelListEditor';

const SITE_MODES: SiteMode[] = ['ALLOW', 'HARD_BLOCK', 'DELAY', 'BREATHING'];
const GATE_BLOCK_MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];
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

function sitePicker(value: SiteMode, onChange: (mode: SiteMode) => void, idPrefix: string) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value as SiteMode)}
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
      {SITE_MODES.map((m) => (
        <option key={m} value={m}>
          {SITE_MODE_LABELS[m]}
        </option>
      ))}
    </select>
  );
}

function delayPicker(value: number, onChange: (seconds: number) => void, idPrefix: string) {
  const isPreset = (DELAY_PRESETS as readonly number[]).includes(value);
  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
      {DELAY_PRESETS.map((preset) => (
        <Chip key={preset} label={`${preset}s`} active={value === preset} onClick={() => onChange(preset)} />
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

/**
 * A daily-minutes budget: "No limit" + presets + a custom field. Shared by the site's own
 * Daily Time Limit and every feature gate's budget — one visual/behavioural pattern
 * instead of duplicating it once per gate.
 */
function LimitPicker({
  value,
  onChange,
  idPrefix,
}: {
  value: number | null;
  onChange: (minutes: number | null) => void;
  idPrefix: string;
}) {
  const isPreset = (DAILY_LIMIT_PRESETS as readonly number[]).includes(value ?? -1);
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <Chip label="No limit" active={value === null} onClick={() => onChange(null)} />
      {DAILY_LIMIT_PRESETS.map((preset) => (
        <Chip
          key={preset}
          label={preset < 60 ? `${preset}m` : `${preset / 60}h`}
          active={value === preset}
          onClick={() => onChange(preset)}
        />
      ))}
      <label style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
        Custom
        <input
          type="number"
          aria-label={`${idPrefix} custom daily limit minutes`}
          min={DAILY_LIMIT_MIN_MINUTES}
          max={DAILY_LIMIT_MAX_MINUTES}
          value={isPreset || value === null ? '' : value}
          placeholder={value === null ? '—' : String(value)}
          onChange={(e) => {
            const parsed = Number(e.target.value);
            if (e.target.value !== '' && Number.isFinite(parsed)) {
              onChange(clamp(parsed, DAILY_LIMIT_MIN_MINUTES, DAILY_LIMIT_MAX_MINUTES));
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

/**
 * One feature gate row: name + description from the registry, a mode select (incl. Off),
 * a delay picker shown only for Delay/Breathing, and a daily budget — everything a gate
 * carries per the ext-13 §2 `GateSetting` shape. Rendered generically off
 * `platformById(platform).gates` so a new platform/gate needs no new UI code.
 */
function GateRow({
  platform,
  gateId,
  gate,
  onChange,
}: {
  platform: Platform;
  gateId: GateId;
  gate: GateSetting;
  onChange: (next: GateSetting) => void;
}) {
  const definition = gateDefinition(platform, gateId);
  if (definition === null) return null;

  const showDelay = gate.mode === 'DELAY' || gate.mode === 'BREATHING';
  const showBudget = gate.mode !== 'HARD_BLOCK';

  return (
    <div
      style={{
        padding: '12px 0',
        borderTop: '1px solid var(--nudge-surface-variant)',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
    >
      <div>
        <p style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>{definition.label}</p>
        <p style={{ margin: '2px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          {definition.description}
        </p>
      </div>

      <select
        value={gate.mode}
        aria-label={`${definition.label} mode`}
        onChange={(e) =>
          onChange({ ...gate, mode: e.target.value as GateSetting['mode'] })
        }
        style={{
          alignSelf: 'flex-start',
          padding: '8px 10px',
          borderRadius: 8,
          border: '1px solid var(--nudge-outline)',
          background: 'var(--nudge-background)',
          color: 'var(--nudge-on-surface)',
          fontSize: 13,
        }}
      >
        <option value="OFF">Off</option>
        {GATE_BLOCK_MODES.map((m) => (
          <option key={m} value={m}>
            {MODE_LABELS[m]}
          </option>
        ))}
      </select>

      {showDelay && delayPicker(gate.delaySeconds, (delaySeconds) => onChange({ ...gate, delaySeconds }), definition.id)}

      {showBudget ? (
        <LimitPicker
          value={gate.dailyLimitMinutes}
          onChange={(dailyLimitMinutes) => onChange({ ...gate, dailyLimitMinutes })}
          idPrefix={definition.id}
        />
      ) : (
        <p style={{ margin: 0, fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          Not used with Hard Block — always gated, so there is no time to budget.
        </p>
      )}
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

  const schedule: ScheduleOverride | null = draft.schedule;
  const platform = platformForDomain(draft.domain);

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

  function setGate(gateId: GateId, next: GateSetting) {
    setDraft((d) =>
      d.features === null
        ? d
        : { ...d, features: { ...d.features, gates: { ...d.features.gates, [gateId]: next } } },
    );
  }

  function setHide(hideId: HideId, hidden: boolean) {
    setDraft((d) =>
      d.features === null
        ? d
        : { ...d, features: { ...d.features, hides: { ...d.features.hides, [hideId]: hidden } } },
    );
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

        <Card title="Default behaviour">
          {sitePicker(draft.mode, (mode) => setDraft((d) => ({ ...d, mode })), 'default')}
          {draft.mode === 'ALLOW' && (
            <p style={{ margin: '10px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
              The site opens normally. Use a daily limit, grayscale or site features to shape it.
            </p>
          )}
        </Card>

        <Card title="Pause length">
          {isBlockMode(draft.mode) && draft.mode !== 'HARD_BLOCK' ? (
            delayPicker(draft.delaySeconds, (delaySeconds) => setDraft((d) => ({ ...d, delaySeconds })), 'default')
          ) : (
            <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
              {draft.mode === 'HARD_BLOCK'
                ? 'Not used with Hard Block — the site never opens, so there is nothing to pause before.'
                : 'Not used with Allow — the site opens normally. Switch to Delay or Breathing to set a pause.'}
            </p>
          )}
        </Card>

        <Card title="Daily Time Limit">
          {draft.mode === 'HARD_BLOCK' ? (
            // A budget only means something for a mode that lets you through. Hard Block bars
            // the site outright, so there is no browsing time to limit — offering the control
            // here would invite a combination that reads as "blocked, but only after 30
            // minutes", which is not what it does.
            <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
              Not used with Hard Block — the site is always blocked, so there is no time to
              budget. Switch to Allow, Delay or Breathing to set a daily limit.
            </p>
          ) : (
            <>
              <LimitPicker
                value={draft.dailyLimitMinutes}
                onChange={(dailyLimitMinutes) => setDraft((d) => ({ ...d, dailyLimitMinutes }))}
                idPrefix="default"
              />
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
                  {sitePicker(schedule.mode, (mode) => setSchedule({ mode }), 'scheduled')}
                  {isBlockMode(schedule.mode) &&
                    schedule.mode !== 'HARD_BLOCK' &&
                    delayPicker(schedule.delaySeconds, (delaySeconds) => setSchedule({ delaySeconds }), 'scheduled')}
                </div>
              </div>
            </div>
          )}
        </Card>

        <Card title="Grayscale this site">
          <Toggle
            checked={draft.grayscale}
            onChange={(grayscale) => setDraft((d) => ({ ...d, grayscale }))}
            label="Turn on grayscale"
          />
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            Flash-free, all pages of this site.
          </p>
        </Card>

        {draft.features !== null && platform !== null && (
          <Card title="Site features">
            {platform.note !== null && (
              <p style={{ margin: '0 0 14px', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
                {platform.note}
              </p>
            )}

            {platform.gates.length > 0 && (
              <div style={{ marginBottom: platform.hides.length > 0 ? 18 : 0 }}>
                {platform.gates.map((gateDef) => {
                  const gate = draft.features!.gates[gateDef.id];
                  if (gate === undefined) return null;
                  return (
                    <GateRow
                      key={gateDef.id}
                      platform={platform.id}
                      gateId={gateDef.id}
                      gate={gate}
                      onChange={(next) => setGate(gateDef.id, next)}
                    />
                  );
                })}
              </div>
            )}

            {platform.hides.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {platform.hides.map((hideDef) => {
                  const hidden = draft.features!.hides[hideDef.id] ?? false;
                  return (
                    <div key={hideDef.id}>
                      <Toggle
                        checked={hidden}
                        onChange={(next) => setHide(hideDef.id, next)}
                        label={hideDef.label}
                      />
                      <p
                        style={{
                          margin: '4px 0 0 30px',
                          fontSize: 12,
                          color: 'var(--nudge-on-surface-variant)',
                        }}
                      >
                        {hideDef.description}
                      </p>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        )}

        {draft.features?.youtube !== undefined && (
          <ChannelListEditor
            youtube={draft.features.youtube}
            siteMode={draft.mode}
            onChange={(youtube) =>
              setDraft((d) => (d.features === null ? d : { ...d, features: { ...d.features, youtube } }))
            }
          />
        )}

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
