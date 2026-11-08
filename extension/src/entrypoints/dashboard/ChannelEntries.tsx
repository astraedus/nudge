import { useState } from 'react';
import type { CSSProperties } from 'react';
import { addChannel, parseChannelInput, removeChannel } from '../../core/channels';
import type { ChannelEntry } from '../../core/settingsSchema';
import { Button } from '../../ui/components';

/**
 * The pieces of a channel list that the rule's DEFAULT list and a Scheduled Override's
 * window list share: the add field, the rows, and the empty-whitelist warning. They are
 * parametrised by their labels because both lists can be on screen at once, and two inputs
 * both called "Add YouTube channel" would leave a screen reader (and every test) unable to
 * tell which list it is typing into.
 */

export const INVALID_CHANNEL_MESSAGE = "That doesn't look like a channel handle, URL, or channel ID.";

export const CHANNEL_INPUT_STYLE: CSSProperties = {
  flex: '1 1 220px',
  padding: '10px 12px',
  fontSize: 14,
  borderRadius: 8,
  border: '1px solid var(--nudge-outline)',
  background: 'var(--nudge-background)',
  color: 'var(--nudge-on-surface)',
};

/** A stable React key for a channel entry — it has no id field, but at least one of
 * `channelId`/`handle` is always present (coerceChannel drops entries with neither). */
function channelKey(entry: ChannelEntry): string {
  return entry.channelId ?? entry.handle ?? entry.displayName;
}

/**
 * The secondary "what we actually know" line under a channel row's display name.
 *
 * Background: handle-entry enrichment learns the missing identifier and a real display
 * name over time, so a row that starts as the `@handle`/id fallback for `displayName`
 * eventually gets a real name plus both identifiers underneath. Returns null rather than
 * an empty string when there is nothing to add beyond the primary line — a row must never
 * show the same string twice, so a single known identifier that IS the (fallback)
 * displayName is suppressed rather than repeated one line down.
 */
export function channelIdentifierLine(entry: ChannelEntry): string | null {
  const parts: string[] = [];
  if (entry.handle !== null) parts.push(`@${entry.handle}`);
  if (entry.channelId !== null) parts.push(entry.channelId);

  if (parts.length === 0) return null;
  if (parts.length === 1) return parts[0] === entry.displayName ? null : parts[0]!;
  return parts.join(' · ');
}

export interface ChannelEntriesLabels {
  /** aria-label of the text field. */
  input: string;
  /** aria-label of the add button; its visible text is always "Add channel". */
  addButton: string;
  /** aria-label of one row's remove button. */
  remove: (displayName: string) => string;
}

export const DEFAULT_LIST_LABELS: ChannelEntriesLabels = {
  input: 'Add YouTube channel',
  addButton: 'Add channel',
  remove: (name) => `Remove ${name}`,
};

/** The add field plus the list of rows. `hideEmptyText` is for when a louder warning
 * (the empty whitelist) is already saying the list is empty. */
export function ChannelEntriesEditor({
  channels,
  onChange,
  labels,
  hideEmptyText = false,
}: {
  channels: ChannelEntry[];
  onChange: (next: ChannelEntry[]) => void;
  labels: ChannelEntriesLabels;
  hideEmptyText?: boolean;
}) {
  const [input, setInput] = useState('');
  const [error, setError] = useState<string | null>(null);

  function handleAdd() {
    const entry = parseChannelInput(input);
    if (entry === null) {
      setError(INVALID_CHANNEL_MESSAGE);
      return;
    }
    setError(null);
    setInput('');
    onChange(addChannel(channels, entry));
  }

  return (
    <>
      <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
        <input
          type="text"
          aria-label={labels.input}
          value={input}
          placeholder="@handle, channel URL, or channel ID"
          onChange={(e) => {
            setInput(e.target.value);
            setError(null);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleAdd();
          }}
          style={CHANNEL_INPUT_STYLE}
        />
        <Button onClick={handleAdd} aria-label={labels.addButton}>
          Add channel
        </Button>
      </div>
      {error && <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--nudge-danger)' }}>{error}</p>}

      {channels.length === 0 ? (
        !hideEmptyText && (
          <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            No channels added yet — add one above.
          </p>
        )
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {channels.map((entry) => {
            const identifierLine = channelIdentifierLine(entry);
            return (
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
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0, flex: '1 1 auto' }}>
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
                  {identifierLine !== null && (
                    <span
                      style={{
                        fontSize: 12,
                        color: 'var(--nudge-on-surface-variant)',
                        wordBreak: 'break-all',
                      }}
                    >
                      {identifierLine}
                    </span>
                  )}
                </div>
                <Button
                  variant="muted"
                  onClick={() => onChange(removeChannel(channels, entry))}
                  aria-label={labels.remove(entry.displayName)}
                  style={{ padding: '6px 12px', fontSize: 13, flexShrink: 0 }}
                >
                  Remove
                </Button>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}

/** The loud "an empty allow list blocks everything" warning. */
/** One line under an "Only allow these channels" list whose mode in force is Allow. */
export function WhitelistOpenNote({ scope }: { scope: 'site' | 'window' }) {
  return (
    <p style={{ margin: '0 0 12px', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
      Home, search and Shorts stay open unless the {scope} mode is Hard Block, Delay or Breathing.
    </p>
  );
}

export function EmptyWhitelistWarning({ title }: { title: string }) {
  return (
    <div
      style={{
        margin: '10px 0 16px',
        padding: '12px 14px',
        borderRadius: 'var(--nudge-radius-sm)',
        border: '1px solid var(--nudge-danger)',
      }}
    >
      <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: 'var(--nudge-danger)' }}>{title}</p>
      <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--nudge-on-surface-variant)' }}>
        Add at least one channel below to let anything through.
      </p>
    </div>
  );
}
