import { useState } from 'react';
import { addChannel, parseChannelInput, removeChannel } from '../../core/channels';
import { DELAY_MAX_SECONDS, DELAY_MIN_SECONDS, DELAY_PRESETS } from '../../core/settingsSchema';
import type { ChannelEntry, ChannelListMode, YoutubeFeatureSettings } from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS } from '../../core/types';
import { Button, Card, Chip, Toggle } from '../../ui/components';

const CHANNEL_BLOCK_MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

const CHANNEL_MODE_OPTIONS: { mode: ChannelListMode; label: string; description: string }[] = [
  {
    mode: 'OFF',
    label: 'Off',
    description: "Every channel plays as normal — Nudge doesn't filter by channel.",
  },
  {
    mode: 'BLACKLIST',
    label: 'Block these channels',
    description: 'Videos from the channels you list below are blocked. Everything else plays as normal.',
  },
  {
    mode: 'WHITELIST',
    label: 'Only allow these channels',
    description:
      'Only videos from the channels you list below are allowed. Everything else on YouTube gets blocked.',
  },
];

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.round(value)));
}

/** Preset chips + a custom field, mirroring RuleEditor's delay-picker visual pattern. */
function DelayPicker({
  value,
  onChange,
  idPrefix,
}: {
  value: number;
  onChange: (seconds: number) => void;
  idPrefix: string;
}) {
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

/** One selectable "radio card" for the channel-list mode — plain-language label + a
 * one-line explanation, because "blacklist"/"whitelist" mean nothing to most users. */
function ChannelModeOption({
  label,
  description,
  active,
  onClick,
}: {
  label: string;
  description: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      style={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        padding: '12px 14px',
        marginBottom: 8,
        borderRadius: 'var(--nudge-radius-sm)',
        border: `1px solid ${active ? 'var(--nudge-primary)' : 'var(--nudge-outline)'}`,
        background: active ? 'var(--nudge-primary-container)' : 'transparent',
        cursor: 'pointer',
      }}
    >
      <span
        style={{
          display: 'block',
          fontSize: 14,
          fontWeight: 600,
          color: active ? 'var(--nudge-on-primary-container)' : 'var(--nudge-on-surface)',
        }}
      >
        {label}
      </span>
      <span
        style={{
          display: 'block',
          marginTop: 2,
          fontSize: 12,
          lineHeight: 1.45,
          color: active ? 'var(--nudge-on-primary-container)' : 'var(--nudge-on-surface-variant)',
        }}
      >
        {description}
      </span>
    </button>
  );
}

/** A stable React key for a channel entry — it has no id field, but at least one of
 * `channelId`/`handle` is always present (coerceChannel drops entries with neither). */
function channelKey(entry: ChannelEntry): string {
  return entry.channelId ?? entry.handle ?? entry.displayName;
}

/**
 * The YouTube channel allowlist/blocklist block of the youtube.com rule's features —
 * moved out of the old standalone YoutubePanel tab (ext-13 §5) into a component owned by
 * `RuleEditor`, since a channel list is a per-site-rule setting like everything else now.
 *
 * `isBlocked` says whether the SITE's own default mode is currently a block mode — it
 * changes what a disallowed channel falls back to (ext-13 §3: "unknown channel resolves
 * to the site's default") and is purely a copy concern here; the actual fallback decision
 * lives in `core/channels.ts`/the worker, never in this component.
 */
export function ChannelListEditor({
  youtube,
  isBlocked,
  onChange,
}: {
  youtube: YoutubeFeatureSettings;
  isBlocked: boolean;
  onChange: (next: YoutubeFeatureSettings) => void;
}) {
  const [channelInput, setChannelInput] = useState('');
  const [channelInputError, setChannelInputError] = useState<string | null>(null);

  function patch(changes: Partial<YoutubeFeatureSettings>) {
    onChange({ ...youtube, ...changes });
  }

  function handleAddChannel() {
    const entry = parseChannelInput(channelInput);
    if (entry === null) {
      setChannelInputError("That doesn't look like a channel handle, URL, or channel ID.");
      return;
    }
    setChannelInputError(null);
    setChannelInput('');
    patch({ channels: addChannel(youtube.channels, entry) });
  }

  function handleRemoveChannel(entry: ChannelEntry) {
    patch({ channels: removeChannel(youtube.channels, entry) });
  }

  const whitelistIsEmpty = youtube.channelMode === 'WHITELIST' && youtube.channels.length === 0;

  return (
    <Card title="YouTube channels">
      <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
        {isBlocked
          ? 'YouTube is blocked; videos and pages from these channels are still allowed.'
          : 'Filter YouTube by channel — block specific channels, or lock things down to only the ones you choose.'}
      </p>

      <div style={{ marginBottom: 4 }}>
        {CHANNEL_MODE_OPTIONS.map(({ mode, label, description }) => (
          <ChannelModeOption
            key={mode}
            label={label}
            description={description}
            active={youtube.channelMode === mode}
            onClick={() => patch({ channelMode: mode })}
          />
        ))}
      </div>

      {whitelistIsEmpty && (
        <div
          style={{
            margin: '10px 0 16px',
            padding: '12px 14px',
            borderRadius: 'var(--nudge-radius-sm)',
            border: '1px solid var(--nudge-danger)',
          }}
        >
          <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: 'var(--nudge-danger)' }}>
            Your allow list is empty — this currently blocks ALL of YouTube.
          </p>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
            Add at least one channel below to let anything through.
          </p>
        </div>
      )}

      {youtube.channelMode !== 'OFF' && (
        <>
          <div style={{ margin: '16px 0' }}>
            <p style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 600 }}>
              What happens to a {youtube.channelMode === 'BLACKLIST' ? 'blocked' : 'disallowed'} channel
            </p>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
              {CHANNEL_BLOCK_MODES.map((m) => (
                <Chip
                  key={m}
                  label={MODE_LABELS[m]}
                  active={youtube.channelBlockMode === m}
                  onClick={() => patch({ channelBlockMode: m })}
                />
              ))}
            </div>
            {youtube.channelBlockMode !== 'HARD_BLOCK' && (
              <DelayPicker
                value={youtube.channelDelaySeconds}
                onChange={(channelDelaySeconds) => patch({ channelDelaySeconds })}
                idPrefix="channel"
              />
            )}
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <input
              type="text"
              aria-label="Add YouTube channel"
              value={channelInput}
              placeholder="@handle, channel URL, or channel ID"
              onChange={(e) => {
                setChannelInput(e.target.value);
                setChannelInputError(null);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleAddChannel();
              }}
              style={{
                flex: '1 1 220px',
                padding: '10px 12px',
                fontSize: 14,
                borderRadius: 8,
                border: '1px solid var(--nudge-outline)',
                background: 'var(--nudge-background)',
                color: 'var(--nudge-on-surface)',
              }}
            />
            <Button onClick={handleAddChannel}>Add channel</Button>
          </div>
          {channelInputError && (
            <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-danger)' }}>
              {channelInputError}
            </p>
          )}

          {youtube.channels.length === 0 ? (
            !whitelistIsEmpty && (
              <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
                No channels added yet — add one above.
              </p>
            )
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {youtube.channels.map((entry) => (
                <div
                  key={channelKey(entry)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 12,
                    padding: '8px 12px',
                    borderRadius: 'var(--nudge-radius-sm)',
                    border: '1px solid var(--nudge-surface-variant)',
                  }}
                >
                  <span
                    style={{
                      fontSize: 14,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {entry.displayName}
                  </span>
                  <Button
                    variant="muted"
                    onClick={() => handleRemoveChannel(entry)}
                    aria-label={`Remove ${entry.displayName}`}
                    style={{ padding: '6px 12px', fontSize: 13, flexShrink: 0 }}
                  >
                    Remove
                  </Button>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <div style={{ marginTop: 18, borderTop: '1px solid var(--nudge-surface-variant)', paddingTop: 14 }}>
        <Toggle
          checked={youtube.disableAutoplay}
          onChange={(disableAutoplay) => patch({ disableAutoplay })}
          label="Disable autoplay"
        />
        <p style={{ margin: '6px 0 0 30px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          Best-effort: YouTube can restore its own player state, so autoplay may still turn back on
          from time to time.
        </p>
      </div>
    </Card>
  );
}
