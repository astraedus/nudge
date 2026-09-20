// @vitest-environment jsdom
import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defaultYoutubeFeatureSettings } from '../../src/core/settingsSchema';
import type { YoutubeFeatureSettings } from '../../src/core/settingsSchema';
import { ChannelListEditor } from '../../src/entrypoints/dashboard/ChannelListEditor';

function makeYoutubeSettings(overrides: Partial<YoutubeFeatureSettings> = {}): YoutubeFeatureSettings {
  return { ...defaultYoutubeFeatureSettings(), ...overrides };
}

/** Renders ChannelListEditor as a controlled component so interactive flows (add/remove a
 * channel, flip a toggle) are actually visible in the DOM after the change, the same way
 * RuleEditor controls it. `onEmit` lets tests additionally inspect exactly what was
 * emitted on a given change without having to diff DOM state. */
function Harness({
  initial,
  isBlocked = false,
  onEmit,
}: {
  initial: YoutubeFeatureSettings;
  isBlocked?: boolean;
  onEmit?: (next: YoutubeFeatureSettings) => void;
}) {
  const [settings, setSettings] = useState(initial);
  return (
    <ChannelListEditor
      youtube={settings}
      isBlocked={isBlocked}
      onChange={(next) => {
        onEmit?.(next);
        setSettings(next);
      }}
    />
  );
}

function addChannel(text: string) {
  fireEvent.change(screen.getByLabelText('Add YouTube channel'), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Add channel' }));
}

afterEach(() => {
  cleanup();
});

describe('ChannelListEditor — copy changes with the site\'s block state', () => {
  it('explains the whitelist-as-exception-list framing when the site is blocked', () => {
    render(<Harness initial={makeYoutubeSettings()} isBlocked />);
    expect(
      screen.getByText('YouTube is blocked; videos and pages from these channels are still allowed.'),
    ).toBeDefined();
  });

  it('explains the generic filter framing when the site is not blocked', () => {
    render(<Harness initial={makeYoutubeSettings()} isBlocked={false} />);
    expect(screen.getByText(/Filter YouTube by channel/i)).toBeDefined();
  });
});

describe('ChannelListEditor — channel list', () => {
  it('adds a channel by @handle to the list', () => {
    render(<Harness initial={makeYoutubeSettings({ channelMode: 'BLACKLIST' })} />);

    addChannel('@veritasium');

    expect(screen.getByText('@veritasium')).toBeDefined();
    // The input clears on a successful add — the user can immediately type the next one.
    expect((screen.getByLabelText('Add YouTube channel') as HTMLInputElement).value).toBe('');
  });

  it('adds a channel by full channel URL to the list', () => {
    render(<Harness initial={makeYoutubeSettings({ channelMode: 'BLACKLIST' })} />);

    addChannel('https://www.youtube.com/@testchannel/videos');

    expect(screen.getByText('@testchannel')).toBeDefined();
  });

  it('adds a channel by UCxxxx channel id to the list', () => {
    render(<Harness initial={makeYoutubeSettings({ channelMode: 'BLACKLIST' })} />);
    const channelId = 'UCabcdefghijklmnopqrstuv'; // "UC" + 22 chars, the exact accepted shape

    addChannel(channelId);

    expect(screen.getByText(channelId)).toBeDefined();
  });

  it('shows an inline error and adds nothing for garbage input', () => {
    render(<Harness initial={makeYoutubeSettings({ channelMode: 'BLACKLIST' })} />);

    addChannel('this is not a channel at all!!');

    expect(screen.getByText(/doesn't look like a channel/i)).toBeDefined();
    // Still the empty-list state — nothing was added.
    expect(screen.getByText('No channels added yet — add one above.')).toBeDefined();
  });

  it('removes a channel from the list', () => {
    const entry = { channelId: null, handle: 'veritasium', displayName: '@veritasium', addedAt: 0 };
    render(
      <Harness initial={makeYoutubeSettings({ channelMode: 'BLACKLIST', channels: [entry] })} />,
    );

    expect(screen.getByText('@veritasium')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Remove @veritasium' }));

    expect(screen.queryByText('@veritasium')).toBeNull();
    expect(screen.getByText('No channels added yet — add one above.')).toBeDefined();
  });

  it('warns prominently when "only allow these channels" is chosen with an empty list, and the warning clears once a channel is added', () => {
    render(<Harness initial={makeYoutubeSettings({ channelMode: 'OFF' })} />);

    expect(screen.queryByText(/blocks ALL of YouTube/i)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Only allow these channels/ }));
    expect(screen.getByText(/blocks ALL of YouTube/i)).toBeDefined();

    addChannel('@somechannel');
    expect(screen.queryByText(/blocks ALL of YouTube/i)).toBeNull();
  });
});

describe('ChannelListEditor — disable autoplay', () => {
  it('flips only disableAutoplay, leaving everything else untouched', () => {
    const initial = makeYoutubeSettings();
    const onEmit = vi.fn();
    render(<Harness initial={initial} onEmit={onEmit} />);

    fireEvent.click(screen.getByLabelText('Disable autoplay'));

    expect(onEmit).toHaveBeenCalledTimes(1);
    const emitted = onEmit.mock.calls[0]![0] as YoutubeFeatureSettings;
    expect(emitted.disableAutoplay).toBe(true);
    expect(emitted.channelMode).toBe(initial.channelMode);
    expect(emitted.channels).toEqual(initial.channels);
    expect(emitted.channelBlockMode).toBe(initial.channelBlockMode);
    expect(emitted.channelDelaySeconds).toBe(initial.channelDelaySeconds);
  });

  it('shows the best-effort caveat', () => {
    render(<Harness initial={makeYoutubeSettings()} />);
    expect(screen.getByText(/best-effort/i)).toBeDefined();
    expect(screen.getByText(/YouTube can restore its own player state/i)).toBeDefined();
  });
});
