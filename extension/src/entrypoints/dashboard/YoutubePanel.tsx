import { useState } from 'react';
import { addChannel, parseChannelInput, removeChannel } from '../../core/channels';
import {
  DELAY_MAX_SECONDS,
  DELAY_MIN_SECONDS,
  DELAY_PRESETS,
} from '../../core/settingsSchema';
import type { ChannelEntry, ChannelListMode, YoutubeSettings } from '../../core/settingsSchema';
import type { BlockMode } from '../../core/types';
import { MODE_LABELS } from '../../core/types';
import { Button, Card, Toggle } from '../../ui/components';

const SHORTS_MODES: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];
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

/** House chip pattern (mirrors SettingsPanel's local `Chip`) — no shared export exists yet. */
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

/** Preset chips + a custom field, mirroring RuleEditor's `delayPicker` visual pattern. */
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
 * The whole Phase 4 YouTube settings surface: Shorts (moved from SettingsPanel), the
 * channel whitelist/blacklist (the differentiator), gray-screen mode, and the
 * Unhook-parity hide toggles. Same propagation pattern as the rest of the dashboard —
 * every change goes straight to `onChange`, which SettingsPanel patches into `NudgeSettings`
 * and sends to the background (Strict Mode gates it there, never in the UI).
 */
export function YoutubePanel({
  settings,
  onChange,
}: {
  settings: YoutubeSettings;
  onChange: (next: YoutubeSettings) => void;
}) {
  const [channelInput, setChannelInput] = useState('');
  const [channelInputError, setChannelInputError] = useState<string | null>(null);

  function patch(changes: Partial<YoutubeSettings>) {
    onChange({ ...settings, ...changes });
  }

  function handleAddChannel() {
    const entry = parseChannelInput(channelInput);
    if (entry === null) {
      setChannelInputError("That doesn't look like a channel handle, URL, or channel ID.");
      return;
    }
    setChannelInputError(null);
    setChannelInput('');
    patch({ channels: addChannel(settings.channels, entry) });
  }

  function handleRemoveChannel(entry: ChannelEntry) {
    patch({ channels: removeChannel(settings.channels, entry) });
  }

  const whitelistIsEmpty = settings.channelMode === 'WHITELIST' && settings.channels.length === 0;

  return (
    <>
      <Card title="YouTube Shorts">
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
          <Chip
            label="Inherit"
            active={settings.shortsMode === 'INHERIT'}
            onClick={() => patch({ shortsMode: 'INHERIT' })}
          />
          {SHORTS_MODES.map((m) => (
            <Chip
              key={m}
              label={MODE_LABELS[m]}
              active={settings.shortsMode === m}
              onClick={() => patch({ shortsMode: m })}
            />
          ))}
        </div>
        <p style={{ margin: '0 0 14px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
          "Inherit" follows whatever rule you set for youtube.com.
        </p>

        {settings.shortsMode !== 'INHERIT' && settings.shortsMode !== 'HARD_BLOCK' && (
          <div style={{ marginBottom: 14 }}>
            <DelayPicker
              value={settings.shortsDelaySeconds}
              onChange={(shortsDelaySeconds) => patch({ shortsDelaySeconds })}
              idPrefix="Shorts"
            />
          </div>
        )}

        <Toggle
          checked={settings.hideShortsShelf}
          onChange={(hideShortsShelf) => patch({ hideShortsShelf })}
          label="Hide Shorts shelf"
        />
      </Card>

      <Card title="Channels">
        <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          Filter YouTube by channel — block specific channels, or lock things down to only the
          ones you choose.
        </p>

        <div style={{ marginBottom: 4 }}>
          {CHANNEL_MODE_OPTIONS.map(({ mode, label, description }) => (
            <ChannelModeOption
              key={mode}
              label={label}
              description={description}
              active={settings.channelMode === mode}
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

        {settings.channelMode !== 'OFF' && (
          <>
            <div style={{ margin: '16px 0' }}>
              <p style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 600 }}>
                What happens to a {settings.channelMode === 'BLACKLIST' ? 'blocked' : 'disallowed'}{' '}
                channel
              </p>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
                {CHANNEL_BLOCK_MODES.map((m) => (
                  <Chip
                    key={m}
                    label={MODE_LABELS[m]}
                    active={settings.channelBlockMode === m}
                    onClick={() => patch({ channelBlockMode: m })}
                  />
                ))}
              </div>
              {settings.channelBlockMode !== 'HARD_BLOCK' && (
                <DelayPicker
                  value={settings.channelDelaySeconds}
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

            {settings.channels.length === 0 ? (
              !whitelistIsEmpty && (
                <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
                  No channels added yet — add one above.
                </p>
              )
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {settings.channels.map((entry) => (
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
      </Card>

      <Card title="Gray-screen mode">
        <Toggle
          checked={settings.grayScreen}
          onChange={(grayScreen) => patch({ grayScreen })}
          label="Turn YouTube grayscale"
        />
        <p style={{ margin: '10px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
          All of YouTube turns grayscale; channels you've allowed through the channel list above
          come back in full colour. Honest caveat: this depends on the channel list above being
          set up — with channel filtering off (or no channels added), everything just stays gray.
        </p>
      </Card>

      <Card title="Hide on YouTube">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Toggle
            checked={settings.hideHomeFeed}
            onChange={(hideHomeFeed) => patch({ hideHomeFeed })}
            label="Hide home feed"
          />
          <Toggle
            checked={settings.hideSidebarRecs}
            onChange={(hideSidebarRecs) => patch({ hideSidebarRecs })}
            label="Hide sidebar recommendations"
          />
          <Toggle
            checked={settings.hideEndScreen}
            onChange={(hideEndScreen) => patch({ hideEndScreen })}
            label="Hide end-screen suggestions"
          />
          <Toggle
            checked={settings.hideComments}
            onChange={(hideComments) => patch({ hideComments })}
            label="Hide comments"
          />
          <div>
            <Toggle
              checked={settings.disableAutoplay}
              onChange={(disableAutoplay) => patch({ disableAutoplay })}
              label="Disable autoplay"
            />
            <p
              style={{
                margin: '6px 0 0 30px',
                fontSize: 12,
                color: 'var(--nudge-on-surface-variant)',
              }}
            >
              Best-effort: YouTube can restore its own player state, so autoplay may still turn
              back on from time to time.
            </p>
          </div>
        </div>
      </Card>
    </>
  );
}
