import { useCallback, useEffect, useState } from 'react';
import { parseChannelInput } from '../../core/channels';
import { DELAY_MAX_SECONDS, DELAY_MIN_SECONDS, DELAY_PRESETS } from '../../core/settingsSchema';
import type { ChannelEntry, ChannelListMode, YoutubeFeatureSettings } from '../../core/settingsSchema';
import type { BlockMode, SiteMode } from '../../core/types';
import { isBlockMode, MODE_LABELS, SITE_MODE_LABELS } from '../../core/types';
import type { SubscriptionSyncStatus } from '../../core/protocol';
import { Button, Card, Chip, Toggle } from '../../ui/components';
import { send } from '../../ui/rpc';
import {
  CHANNEL_INPUT_STYLE,
  ChannelEntriesEditor,
  channelIdentifierLine,
  DEFAULT_LIST_LABELS,
  EmptyWhitelistWarning,
  INVALID_CHANNEL_MESSAGE,
} from './ChannelEntries';

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

/**
 * The creator-mode block: the checkbox, and (while it is on) the optional field for the
 * user's own channel. `myChannel` is kept when the box is switched off so turning it back
 * on restores it; the field is merely hidden. Saving goes through the same `onChange` as
 * every other edit, so the worker's Commitment Lock gate sees it like any other weakening.
 */
function CreatorModeSection({
  creatorMode,
  myChannel,
  onCreatorMode,
  onMyChannel,
}: {
  creatorMode: boolean;
  myChannel: ChannelEntry | null;
  onCreatorMode: (next: boolean) => void;
  onMyChannel: (next: ChannelEntry | null) => void;
}) {
  const [input, setInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const identifierLine = myChannel === null ? null : channelIdentifierLine(myChannel);

  function handleSave() {
    const entry = parseChannelInput(input);
    if (entry === null) {
      setError(INVALID_CHANNEL_MESSAGE);
      return;
    }
    setError(null);
    setInput('');
    onMyChannel(entry);
  }

  return (
    <div style={{ marginTop: 18, borderTop: '1px solid var(--nudge-surface-variant)', paddingTop: 14 }}>
      <Toggle checked={creatorMode} onChange={onCreatorMode} label="Allow my channel and YouTube Studio" />
      <p style={{ margin: '6px 0 0 30px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
        Keeps YouTube Studio and uploading open while YouTube is blocked or limited, and lets
        videos from your own channel play. Studio time does not count toward your daily limit.
      </p>

      {creatorMode && (
        <div style={{ margin: '12px 0 0 30px' }}>
          {myChannel === null ? (
            <>
              <label
                htmlFor="nudge-my-channel"
                style={{ display: 'block', marginBottom: 6, fontSize: 13, fontWeight: 600 }}
              >
                Your channel (optional)
              </label>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <input
                  id="nudge-my-channel"
                  type="text"
                  value={input}
                  placeholder="@handle, channel URL or UC… id"
                  onChange={(e) => {
                    setInput(e.target.value);
                    setError(null);
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleSave();
                  }}
                  style={CHANNEL_INPUT_STYLE}
                />
                <Button onClick={handleSave}>Save channel</Button>
              </div>
              {error && (
                <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--nudge-danger)' }}>{error}</p>
              )}
            </>
          ) : (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                <span style={{ fontSize: 13, fontWeight: 600 }}>Your channel</span>
                <span style={{ fontSize: 14 }}>{myChannel.displayName}</span>
                {identifierLine !== null && (
                  <span style={{ fontSize: 12, color: 'var(--nudge-on-surface-variant)', wordBreak: 'break-all' }}>
                    {identifierLine}
                  </span>
                )}
              </div>
              <Button
                variant="muted"
                onClick={() => onMyChannel(null)}
                aria-label="Remove my channel"
                style={{ padding: '6px 12px', fontSize: 13, flexShrink: 0 }}
              >
                Remove
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** What went wrong with the last sync, in one short line. */
export function subscriptionErrorText(status: SubscriptionSyncStatus): string | null {
  const kept = status.count > 0 ? ' Your last list is kept.' : '';
  switch (status.error) {
    case 'signed-out':
      return `Couldn't sync: you're not signed in to YouTube in this browser.${kept}`;
    case 'unparseable':
      return `Couldn't read your subscriptions from YouTube.${kept}`;
    case 'network':
      return `Couldn't reach YouTube.${kept}`;
    case 'empty':
      return `YouTube returned no subscriptions.${kept}`;
    default:
      return null;
  }
}

/** "120 channels, synced 5 min ago" (or "Not synced yet"). */
export function subscriptionSummaryText(status: SubscriptionSyncStatus, now: number): string {
  if (status.syncedAt === null) return status.syncing ? 'Syncing…' : 'Not synced yet';
  const channels = `${status.count} ${status.count === 1 ? 'channel' : 'channels'}`;
  const minutes = Math.max(0, Math.floor((now - status.syncedAt) / 60_000));
  const ago =
    minutes < 1
      ? 'just now'
      : minutes < 60
        ? `${minutes} min ago`
        : minutes < 48 * 60
          ? `${Math.floor(minutes / 60)} h ago`
          : `${Math.floor(minutes / (24 * 60))} days ago`;
  return `${channels}, synced ${ago}`;
}

/** Why the synced list may be short, in a line. Every `SyncTruncation` has one (tested). */
export function subscriptionTruncationText(status: SubscriptionSyncStatus): string | null {
  if (status.truncated === 'cap') return `Only the first ${status.count} are allowed.`;
  if (status.truncated === 'partial') return 'Some may be missing: YouTube only sent part of your list.';
  if (status.truncated === 'pending') return 'The rest of your list loads next time you open YouTube.';
  return null;
}

/**
 * The worker's sync status, read only while the option is on. Never throws: outside the
 * extension (a test page with no `chrome`) it simply stays null.
 */
export function useSubscriptionStatus(enabled: boolean) {
  const [status, setStatus] = useState<SubscriptionSyncStatus | null>(null);
  // When the status was read: "synced 5 min ago" is measured from here, never from a clock
  // read during render.
  const [readAt, setReadAt] = useState(0);
  const refresh = useCallback(async () => {
    try {
      const next = await send({ type: 'GET_YT_SUBSCRIPTIONS' });
      setReadAt(Date.now());
      setStatus(next);
    } catch {
      // No worker to ask (or it is restarting): keep what is shown.
    }
  }, []);

  useEffect(() => {
    if (!enabled || typeof chrome === 'undefined' || chrome.storage?.onChanged === undefined) return;
    void refresh();
    // A sync finishing (automatic or "Sync now" in another tab) writes storage.local.
    const listener = () => void refresh();
    chrome.storage.onChanged.addListener(listener);
    return () => chrome.storage.onChanged.removeListener(listener);
  }, [enabled, refresh]);

  const syncNow = useCallback(async () => {
    setStatus((prev) => (prev === null ? prev : { ...prev, syncing: true }));
    try {
      const next = await send({ type: 'SYNC_YT_SUBSCRIPTIONS' });
      setReadAt(Date.now());
      setStatus(next);
    } catch {
      await refresh();
    }
  }, [refresh]);

  return { status, readAt, syncNow };
}

/**
 * "Also allow channels I'm subscribed to". Only offered for a WHITELIST: it widens an allow
 * list and has nothing to say to a block list. Turning it on goes through `onChange` like
 * every other edit, so the rule's "Save rule" sends it through the worker's Commitment Lock;
 * the worker then syncs on its own (`background/youtubeSubscriptions.ts`).
 */
export function SubscriptionsSection({
  allowSubscriptions,
  status,
  readAt,
  onToggle,
  onSyncNow,
  label = "Also allow channels I'm subscribed to",
}: {
  allowSubscriptions: boolean;
  status: SubscriptionSyncStatus | null;
  readAt: number;
  onToggle: (next: boolean) => void;
  onSyncNow: () => void;
  /** The toggle's label; a second list on the same screen needs its own. */
  label?: string;
}) {
  const error = status === null ? null : subscriptionErrorText(status);
  const truncation = status === null ? null : subscriptionTruncationText(status);
  return (
    <div style={{ margin: '12px 0 16px' }}>
      <Toggle
        checked={allowSubscriptions}
        onChange={onToggle}
        label={label}
      />
      {allowSubscriptions && status !== null && (
        <div style={{ margin: '8px 0 0 30px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
              {subscriptionSummaryText(status, readAt)}
            </span>
            <Button
              variant="muted"
              onClick={onSyncNow}
              disabled={status.syncing}
              style={{ padding: '4px 10px', fontSize: 12 }}
            >
              {status.syncing ? 'Syncing…' : 'Sync now'}
            </Button>
          </div>
          {truncation !== null && (
            <p style={{ margin: '6px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
              {truncation}
            </p>
          )}
          {error !== null && (
            <p role="status" style={{ margin: '6px 0 0', fontSize: 12, color: 'var(--nudge-danger)' }}>
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * The YouTube channel allowlist/blocklist block of the youtube.com rule's features —
 * moved out of the old standalone YoutubePanel tab (ext-13 §5) into a component owned by
 * `RuleEditor`, since a channel list is a per-site-rule setting like everything else now.
 *
 * `siteMode` is the SITE's own default mode. It changes what a disallowed channel falls
 * back to (ext-13 §3: "unknown channel resolves to the site's default"), which is both a
 * copy concern here AND the reason one of the controls below disappears; the actual
 * fallback decision lives in `core/channels.ts`/the worker, never in this component.
 */
export function ChannelListEditor({
  youtube,
  siteMode,
  onChange,
}: {
  youtube: YoutubeFeatureSettings;
  siteMode: SiteMode;
  onChange: (next: YoutubeFeatureSettings) => void;
}) {
  const isBlocked = isBlockMode(siteMode);

  function patch(changes: Partial<YoutubeFeatureSettings>) {
    onChange({ ...youtube, ...changes });
  }

  const subscriptionsOn = youtube.channelMode === 'WHITELIST' && youtube.allowSubscriptions;
  const { status: syncStatus, readAt: syncReadAt, syncNow } = useSubscriptionStatus(subscriptionsOn);
  // The worker's count is fresher than the list this screen loaded with (a sync can land
  // after the dashboard opened), so it wins when there is one.
  const subscribedCount = subscriptionsOn
    ? (syncStatus?.count ?? youtube.subscribedChannels.length)
    : 0;
  const whitelistIsEmpty =
    youtube.channelMode === 'WHITELIST' && youtube.channels.length === 0 && subscribedCount === 0;

  /**
   * A meaningless field combination is a UI bug, not just an engine one (repo lesson).
   *
   * `decideWatchGate` hands a WHITELIST's disallowed channels to the SITE's mode and pause
   * whenever the site rule is in force, and a site rule in a block mode always is. So with
   * the site set to Hard Block / Delay / Breathing, this control cannot change anything a
   * user would ever see: it is answering a question the site's own mode already answered.
   *
   * A BLACKLIST is deliberately NOT included. The site default only stands in for a
   * whitelist, so a named-and-blocked channel really is held behind THIS mode, and the
   * combination is reachable: a pause completed (or the Escape Hatch spent) on a Delay
   * site opens it for the temporary-access window, and the blacklist gates inside that.
   */
  const siteModeWins = isBlocked && youtube.channelMode === 'WHITELIST';

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

      {youtube.channelMode === 'WHITELIST' && (
        <SubscriptionsSection
          allowSubscriptions={youtube.allowSubscriptions}
          status={syncStatus}
          readAt={syncReadAt}
          onToggle={(allowSubscriptions) => patch({ allowSubscriptions })}
          onSyncNow={() => void syncNow()}
        />
      )}

      {whitelistIsEmpty && (
        <EmptyWhitelistWarning title="Your allow list is empty — this currently blocks ALL of YouTube." />
      )}

      {youtube.channelMode !== 'OFF' && (
        <>
          <div style={{ margin: '16px 0' }}>
            <p style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 600 }}>
              What happens to a {youtube.channelMode === 'BLACKLIST' ? 'blocked' : 'disallowed'} channel
            </p>
            {siteModeWins ? (
              <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
                Not used while this site is set to {SITE_MODE_LABELS[siteMode]} — a channel that
                is not on your list falls back to the site's own mode and pause. Switch the site to
                Allow to give disallowed channels their own.
              </p>
            ) : (
              <>
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
              </>
            )}
          </div>

          <ChannelEntriesEditor
            channels={youtube.channels}
            onChange={(channels) => patch({ channels })}
            labels={DEFAULT_LIST_LABELS}
            hideEmptyText={whitelistIsEmpty}
          />
        </>
      )}

      <CreatorModeSection
        creatorMode={youtube.creatorMode}
        myChannel={youtube.myChannel}
        onCreatorMode={(creatorMode) => patch({ creatorMode })}
        onMyChannel={(myChannel) => patch({ myChannel })}
      />

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
