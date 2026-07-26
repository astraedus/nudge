// @vitest-environment jsdom
import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_SETTINGS } from '../../src/core/settingsSchema';
import type { YoutubeSettings } from '../../src/core/settingsSchema';
import { YoutubePanel } from '../../src/entrypoints/dashboard/YoutubePanel';

function makeYoutubeSettings(overrides: Partial<YoutubeSettings> = {}): YoutubeSettings {
  return {
    ...DEFAULT_SETTINGS.youtube,
    channels: [...DEFAULT_SETTINGS.youtube.channels],
    ...overrides,
  };
}

/** Renders YoutubePanel as a controlled component so interactive flows (add/remove a
 * channel, flip a toggle) are actually visible in the DOM after the change, the same way
 * the real Dashboard/SettingsPanel controls it. `onEmit` lets tests additionally inspect
 * exactly what was emitted on a given change without having to diff DOM state. */
function Harness({
  initial,
  onEmit,
}: {
  initial: YoutubeSettings;
  onEmit?: (next: YoutubeSettings) => void;
}) {
  const [settings, setSettings] = useState(initial);
  return (
    <YoutubePanel
      settings={settings}
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

describe('YoutubePanel — channel list', () => {
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

describe('YoutubePanel — hide toggles', () => {
  const HIDE_TOGGLES: { label: string; key: keyof YoutubeSettings }[] = [
    { label: 'Hide home feed', key: 'hideHomeFeed' },
    { label: 'Hide sidebar recommendations', key: 'hideSidebarRecs' },
    { label: 'Hide end-screen suggestions', key: 'hideEndScreen' },
    { label: 'Hide comments', key: 'hideComments' },
    { label: 'Disable autoplay', key: 'disableAutoplay' },
  ];

  for (const { label, key } of HIDE_TOGGLES) {
    it(`flipping "${label}" changes only that setting in the emitted settings object`, () => {
      const initial = makeYoutubeSettings();
      const onEmit = vi.fn();
      render(<Harness initial={initial} onEmit={onEmit} />);

      fireEvent.click(screen.getByLabelText(label));

      expect(onEmit).toHaveBeenCalledTimes(1);
      const emitted = onEmit.mock.calls[0]![0] as YoutubeSettings;
      expect(emitted[key]).toBe(true);
      for (const other of HIDE_TOGGLES) {
        if (other.key === key) continue;
        expect(emitted[other.key]).toBe(initial[other.key]);
      }
      // Untouched fields outside the hide-toggle group stay untouched too.
      expect(emitted.grayScreen).toBe(initial.grayScreen);
      expect(emitted.channelMode).toBe(initial.channelMode);
      expect(emitted.shortsMode).toBe(initial.shortsMode);
    });
  }
});

describe('YoutubePanel — gray-screen mode', () => {
  it('flipping the gray-screen toggle changes only grayScreen', () => {
    const initial = makeYoutubeSettings();
    const onEmit = vi.fn();
    render(<Harness initial={initial} onEmit={onEmit} />);

    fireEvent.click(screen.getByLabelText('Turn YouTube grayscale'));

    expect(onEmit).toHaveBeenCalledTimes(1);
    const emitted = onEmit.mock.calls[0]![0] as YoutubeSettings;
    expect(emitted.grayScreen).toBe(true);
    expect(emitted.hideHomeFeed).toBe(initial.hideHomeFeed);
    expect(emitted.hideSidebarRecs).toBe(initial.hideSidebarRecs);
    expect(emitted.hideEndScreen).toBe(initial.hideEndScreen);
    expect(emitted.hideComments).toBe(initial.hideComments);
    expect(emitted.disableAutoplay).toBe(initial.disableAutoplay);
    expect(emitted.channelMode).toBe(initial.channelMode);
    expect(emitted.shortsMode).toBe(initial.shortsMode);
  });

  it('states honestly that gray-screen depends on the channel list', () => {
    render(<Harness initial={makeYoutubeSettings()} />);
    expect(screen.getByText(/depends on the channel list/i)).toBeDefined();
  });
});

describe('YoutubePanel — autoplay caveat', () => {
  it('shows the best-effort caveat for disable autoplay', () => {
    render(<Harness initial={makeYoutubeSettings()} />);
    expect(screen.getByText(/best-effort/i)).toBeDefined();
    expect(screen.getByText(/YouTube can restore its own player state/i)).toBeDefined();
  });
});
