import { useState } from 'react';
import type { ChannelListMode, ScheduleChannelOverride, YoutubeFeatureSettings } from '../../core/settingsSchema';
import { Chip } from '../../ui/components';
import { ChannelEntriesEditor, EmptyWhitelistWarning } from './ChannelEntries';
import type { ChannelEntriesLabels } from './ChannelEntries';
import { SubscriptionsSection, useSubscriptionStatus } from './ChannelListEditor';

/** "Same as default" is the null override; the rest are the list's own modes. */
type WindowChoice = 'DEFAULT' | ChannelListMode;

const WINDOW_CHOICES: { choice: WindowChoice; label: string; title: string }[] = [
  { choice: 'DEFAULT', label: 'Same as default', title: 'Use the YouTube channels setting below' },
  { choice: 'OFF', label: 'Off', title: 'No channel filtering during this window' },
  { choice: 'BLACKLIST', label: 'Block these channels', title: 'Videos from these channels are blocked during this window' },
  {
    choice: 'WHITELIST',
    label: 'Only allow these channels',
    title: 'Only videos from these channels play during this window',
  },
];

const WINDOW_LIST_LABELS: ChannelEntriesLabels = {
  input: 'Add YouTube channel for this window',
  addButton: 'Add channel to this window',
  remove: (name) => `Remove ${name} from this window`,
};

/**
 * "Channels during this window": the Scheduled Override's own channel list (schema v8),
 * e.g. YouTube open all day with a few channels blocked, but only study channels on
 * weekdays 09:00-17:00. Null is "Same as default", which is what every existing schedule
 * reads as, so nothing changes until the user picks something here.
 *
 * Only the LIST is overridden (mode, entries, subscriptions). What happens to a channel the
 * list rejects, creator mode and autoplay stay the rule's, set in "YouTube channels".
 */
export function ScheduleChannelsEditor({
  override,
  youtube,
  onChange,
}: {
  override: ScheduleChannelOverride | null;
  youtube: YoutubeFeatureSettings;
  onChange: (next: ScheduleChannelOverride | null) => void;
}) {
  // Flipping to "Same as default" and back while editing should not throw away the list
  // just typed; the stash lives only as long as the editor is open.
  const [stash, setStash] = useState<ScheduleChannelOverride | null>(override);
  const choice: WindowChoice = override === null ? 'DEFAULT' : override.channelMode;

  function choose(next: WindowChoice) {
    if (next === 'DEFAULT') {
      if (override !== null) setStash(override);
      onChange(null);
      return;
    }
    const base = override ?? stash ?? { channelMode: next, channels: [], allowSubscriptions: false };
    onChange({ ...base, channelMode: next });
  }

  function patch(changes: Partial<ScheduleChannelOverride>) {
    if (override !== null) onChange({ ...override, ...changes });
  }

  const subscriptionsOn = override?.channelMode === 'WHITELIST' && override.allowSubscriptions;
  const { status, readAt, syncNow } = useSubscriptionStatus(subscriptionsOn);
  const subscribedCount = subscriptionsOn ? (status?.count ?? youtube.subscribedChannels.length) : 0;
  const whitelistIsEmpty =
    override?.channelMode === 'WHITELIST' && override.channels.length === 0 && subscribedCount === 0;

  return (
    <div>
      <p
        id="nudge-window-channels-label"
        style={{ margin: '0 0 6px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}
      >
        Channels during this window
      </p>
      <div
        role="group"
        aria-labelledby="nudge-window-channels-label"
        style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}
      >
        {WINDOW_CHOICES.map(({ choice: option, label, title }) => (
          <Chip
            key={option}
            label={label}
            title={title}
            active={choice === option}
            onClick={() => choose(option)}
          />
        ))}
      </div>

      {override !== null && override.channelMode !== 'OFF' && (
        <div style={{ marginTop: 12 }}>
          {override.channelMode === 'WHITELIST' && (
            <SubscriptionsSection
              allowSubscriptions={override.allowSubscriptions}
              status={status}
              readAt={readAt}
              onToggle={(allowSubscriptions) => patch({ allowSubscriptions })}
              onSyncNow={() => void syncNow()}
              label="Also allow channels I'm subscribed to during this window"
            />
          )}
          {whitelistIsEmpty && (
            <EmptyWhitelistWarning title="This window's allow list is empty — it blocks ALL of YouTube during the window." />
          )}
          <ChannelEntriesEditor
            channels={override.channels}
            onChange={(channels) => patch({ channels })}
            labels={WINDOW_LIST_LABELS}
            hideEmptyText={whitelistIsEmpty}
          />
        </div>
      )}
    </div>
  );
}
