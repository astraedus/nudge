// @vitest-environment jsdom
import { useState } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { defaultYoutubeFeatureSettings } from '../../src/core/settingsSchema';
import type { YoutubeFeatureSettings } from '../../src/core/settingsSchema';
import type { SubscriptionSyncStatus } from '../../src/core/protocol';

const send = vi.fn();
vi.mock('../../src/ui/rpc', () => ({ send: (request: unknown) => send(request) }));

const {
  ChannelListEditor,
  subscriptionErrorText,
  subscriptionSummaryText,
} = await import('../../src/entrypoints/dashboard/ChannelListEditor');

const TOGGLE = "Also allow channels I'm subscribed to";
const NOW = Date.UTC(2026, 10, 1, 12, 0, 0);

function status(overrides: Partial<SubscriptionSyncStatus> = {}): SubscriptionSyncStatus {
  return { count: 0, syncedAt: null, error: null, truncated: null, syncing: false, ...overrides };
}

function Harness({
  initial,
  onEmit,
}: {
  initial: Partial<YoutubeFeatureSettings>;
  onEmit?: (next: YoutubeFeatureSettings) => void;
}) {
  const [youtube, setYoutube] = useState({ ...defaultYoutubeFeatureSettings(), ...initial });
  return (
    <ChannelListEditor
      youtube={youtube}
      siteMode="HARD_BLOCK"
      onChange={(next) => {
        onEmit?.(next);
        setYoutube(next);
      }}
    />
  );
}

beforeEach(() => {
  send.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('the "also allow channels I\'m subscribed to" toggle', () => {
  it.each(['OFF', 'BLACKLIST'] as const)('is not offered for a %s list', (channelMode) => {
    render(<Harness initial={{ channelMode }} />);
    expect(screen.queryByLabelText(TOGGLE)).toBeNull();
  });

  it('is offered, off by default, for a whitelist, and asks the worker nothing while off', () => {
    render(<Harness initial={{ channelMode: 'WHITELIST' }} />);
    expect((screen.getByLabelText(TOGGLE) as HTMLInputElement).checked).toBe(false);
    expect(send).not.toHaveBeenCalled();
  });

  it('turning it on emits allowSubscriptions through the normal rule edit (so Save goes through the lock)', () => {
    const emitted: YoutubeFeatureSettings[] = [];
    send.mockResolvedValue(status());
    render(<Harness initial={{ channelMode: 'WHITELIST' }} onEmit={(n) => emitted.push(n)} />);
    fireEvent.click(screen.getByLabelText(TOGGLE));
    expect(emitted.at(-1)?.allowSubscriptions).toBe(true);
  });

  it('shows the count and sync time, and "Sync now" asks the worker to sync', async () => {
    send.mockImplementation(async (request: { type: string }) =>
      request.type === 'SYNC_YT_SUBSCRIPTIONS'
        ? status({ count: 4, syncedAt: Date.now() })
        : status({ count: 3, syncedAt: Date.now() }),
    );
    render(<Harness initial={{ channelMode: 'WHITELIST', allowSubscriptions: true }} />);
    expect(await screen.findByText('3 channels, synced just now')).toBeDefined();

    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }));
    expect(await screen.findByText('4 channels, synced just now')).toBeDefined();
    expect(send).toHaveBeenCalledWith({ type: 'SYNC_YT_SUBSCRIPTIONS' });
  });

  it('shows a short error and says the last list is kept', async () => {
    send.mockResolvedValue(status({ count: 12, syncedAt: Date.now(), error: 'signed-out' }));
    render(<Harness initial={{ channelMode: 'WHITELIST', allowSubscriptions: true }} />);
    expect(await screen.findByRole('status')).toHaveProperty(
      'textContent',
      "Couldn't sync: you're not signed in to YouTube in this browser. Your last list is kept.",
    );
  });

  it('subscriptions count as "something allowed": no empty-list warning while they are synced', async () => {
    send.mockResolvedValue(status({ count: 5, syncedAt: Date.now() }));
    render(<Harness initial={{ channelMode: 'WHITELIST', channels: [], allowSubscriptions: true }} />);
    await waitFor(() => expect(screen.queryByText(/Your allow list is empty/)).toBeNull());
  });

  it('the empty-list warning still shows with the option off', () => {
    render(<Harness initial={{ channelMode: 'WHITELIST', channels: [] }} />);
    expect(screen.getByText(/Your allow list is empty/)).toBeDefined();
  });
});

describe('subscriptionSummaryText', () => {
  it.each([
    [status(), 'Not synced yet'],
    [status({ syncing: true }), 'Syncing…'],
    [status({ count: 1, syncedAt: NOW }), '1 channel, synced just now'],
    [status({ count: 120, syncedAt: NOW - 5 * 60_000 }), '120 channels, synced 5 min ago'],
    [status({ count: 120, syncedAt: NOW - 3 * 60 * 60_000 }), '120 channels, synced 3 h ago'],
    [status({ count: 120, syncedAt: NOW - 3 * 24 * 60 * 60_000 }), '120 channels, synced 3 days ago'],
  ])('%j reads %j', (s, text) => {
    expect(subscriptionSummaryText(s, NOW)).toBe(text);
  });
});

describe('subscriptionErrorText', () => {
  it('is null without an error', () => {
    expect(subscriptionErrorText(status({ count: 3 }))).toBeNull();
  });

  it.each(['signed-out', 'unparseable', 'network', 'empty'] as const)('has one short line for %s', (error) => {
    const kept = subscriptionErrorText(status({ count: 3, error }));
    const none = subscriptionErrorText(status({ count: 0, error }));
    expect(kept).toMatch(/Your last list is kept\.$/);
    expect(none).not.toMatch(/kept/);
    expect((kept ?? '').length).toBeLessThan(110);
  });
});
