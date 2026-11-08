// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defaultFeatures, newSiteRule } from '../../src/core/settingsSchema';
import type { ChannelEntry, ScheduleChannelOverride, SiteRule } from '../../src/core/settingsSchema';
import { RuleEditor } from '../../src/entrypoints/dashboard/RuleEditor';
import { scheduleOverride } from '../helpers/rules';

/**
 * "Channels during this window": the Scheduled Override's own YouTube channel list.
 * Anti's case: YouTube open all day with junk channels blocked, only study channels on
 * weekdays 09:00-17:00. The window list is edited beside the default one, so every test
 * here also checks the two never get mixed up.
 */

function entry(handle: string): ChannelEntry {
  return { channelId: null, handle, displayName: `@${handle}`, addedAt: 0 };
}

/** Entries typed in the editor carry an `addedAt` clock stamp; compare by handle. */
function handles(channels: ChannelEntry[] | undefined): (string | null)[] | undefined {
  return channels?.map((c) => c.handle);
}

function youtubeRule(overrides: Partial<SiteRule> = {}, window: ScheduleChannelOverride | null = null): SiteRule {
  const features = defaultFeatures('youtube');
  return {
    ...newSiteRule({ domain: 'youtube.com', mode: 'ALLOW', createdAt: 1700000000000 }),
    id: 'rule-youtube.com',
    features: {
      ...features,
      youtube: { ...features.youtube!, channelMode: 'BLACKLIST', channels: [entry('junk')] },
    },
    schedule: scheduleOverride({ enabled: true, days: [1, 2, 3, 4, 5], mode: 'ALLOW', channels: window }),
    ...overrides,
  };
}

function renderEditor(rule: SiteRule) {
  const onSave = vi.fn<(next: SiteRule) => void>();
  render(<RuleEditor rule={rule} onSave={onSave} onCancel={() => {}} />);
  return {
    save(): SiteRule {
      fireEvent.click(screen.getByRole('button', { name: 'Save rule' }));
      return onSave.mock.calls.at(-1)![0];
    },
  };
}

function windowGroup() {
  return within(screen.getByRole('group', { name: 'Channels during this window' }));
}

function addToWindow(text: string) {
  fireEvent.change(screen.getByLabelText('Add YouTube channel for this window'), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Add channel to this window' }));
}

afterEach(cleanup);

describe('Scheduled Override: channels during this window', () => {
  it('is offered for a YouTube rule with the schedule on, starting at "Same as default"', () => {
    renderEditor(youtubeRule());

    const same = windowGroup().getByRole('button', { name: 'Same as default' });
    expect(same.getAttribute('aria-pressed')).toBe('true');
    // Nothing to edit until a list is picked.
    expect(screen.queryByLabelText('Add YouTube channel for this window')).toBeNull();
  });

  it('is not offered for a site that is not YouTube', () => {
    const rule: SiteRule = {
      ...newSiteRule({ domain: 'example.com', mode: 'DELAY', createdAt: 1700000000000 }),
      schedule: scheduleOverride({ enabled: true }),
    };
    renderEditor(rule);

    expect(screen.queryByRole('group', { name: 'Channels during this window' })).toBeNull();
  });

  it('is not offered while the schedule is off', () => {
    renderEditor(youtubeRule({ schedule: scheduleOverride({ enabled: false }) }));

    expect(screen.queryByRole('group', { name: 'Channels during this window' })).toBeNull();
  });

  it('builds a window whitelist and leaves the default blacklist alone', () => {
    const editor = renderEditor(youtubeRule());

    fireEvent.click(windowGroup().getByRole('button', { name: 'Only allow these channels' }));
    addToWindow('@3blue1brown');
    addToWindow('https://www.youtube.com/@veritasium');
    const saved = editor.save();

    expect(saved.schedule?.channels?.channelMode).toBe('WHITELIST');
    expect(saved.schedule?.channels?.allowSubscriptions).toBe(false);
    expect(handles(saved.schedule?.channels?.channels)).toEqual(['3blue1brown', 'veritasium']);
    expect(saved.features?.youtube?.channelMode).toBe('BLACKLIST');
    expect(saved.features?.youtube?.channels).toEqual([entry('junk')]);
  });

  it('removes from the window list only', () => {
    const editor = renderEditor(
      youtubeRule({}, { channelMode: 'WHITELIST', channels: [entry('junk'), entry('study')], allowSubscriptions: false }),
    );

    // The same channel on both lists: each Remove button names its own list.
    fireEvent.click(screen.getByRole('button', { name: 'Remove @junk from this window' }));
    const saved = editor.save();

    expect(saved.schedule?.channels?.channels).toEqual([entry('study')]);
    expect(saved.features?.youtube?.channels).toEqual([entry('junk')]);
  });

  it('warns when the window whitelist is empty, and the warning names the window', () => {
    renderEditor(youtubeRule());

    fireEvent.click(windowGroup().getByRole('button', { name: 'Only allow these channels' }));
    expect(screen.getByText(/This window's allow list is empty/)).toBeTruthy();

    addToWindow('@study');
    expect(screen.queryByText(/This window's allow list is empty/)).toBeNull();
  });

  it('"Same as default" saves null, and picking a list again brings back what was typed', () => {
    const editor = renderEditor(youtubeRule());

    fireEvent.click(windowGroup().getByRole('button', { name: 'Only allow these channels' }));
    addToWindow('@study');
    fireEvent.click(windowGroup().getByRole('button', { name: 'Same as default' }));
    expect(editor.save().schedule?.channels).toBeNull();

    fireEvent.click(windowGroup().getByRole('button', { name: 'Only allow these channels' }));
    expect(handles(editor.save().schedule?.channels?.channels)).toEqual(['study']);
  });

  it('Off saves an empty-handed Off override, which turns the default list off for the window', () => {
    const editor = renderEditor(youtubeRule());

    fireEvent.click(windowGroup().getByRole('button', { name: 'Off' }));

    expect(editor.save().schedule?.channels).toEqual({ channelMode: 'OFF', channels: [], allowSubscriptions: false });
    expect(screen.queryByLabelText('Add YouTube channel for this window')).toBeNull();
  });

  it('switching between block and allow keeps the window list', () => {
    const editor = renderEditor(
      youtubeRule({}, { channelMode: 'BLACKLIST', channels: [entry('a')], allowSubscriptions: false }),
    );

    fireEvent.click(windowGroup().getByRole('button', { name: 'Only allow these channels' }));

    expect(editor.save().schedule?.channels).toEqual({
      channelMode: 'WHITELIST',
      channels: [entry('a')],
      allowSubscriptions: false,
    });
  });

  it('has its own subscriptions toggle, separate from the default list', () => {
    const rule = youtubeRule({}, { channelMode: 'WHITELIST', channels: [entry('a')], allowSubscriptions: false });
    rule.features!.youtube!.channelMode = 'WHITELIST';
    const editor = renderEditor(rule);

    fireEvent.click(screen.getByLabelText("Also allow channels I'm subscribed to during this window"));
    const saved = editor.save();

    expect(saved.schedule?.channels?.allowSubscriptions).toBe(true);
    expect(saved.features?.youtube?.allowSubscriptions).toBe(false);
  });

  it('keeps the default list controls uniquely labelled while the window list is open', () => {
    renderEditor(youtubeRule({}, { channelMode: 'WHITELIST', channels: [], allowSubscriptions: false }));

    // getBy* throws on two matches: one field per list.
    expect(screen.getByLabelText('Add YouTube channel')).toBeTruthy();
    expect(screen.getByLabelText('Add YouTube channel for this window')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Add channel' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Add channel to this window' })).toBeTruthy();
  });

  it('keeps details on hover rather than as text under each option', () => {
    renderEditor(youtubeRule());

    const only = windowGroup().getByRole('button', { name: 'Only allow these channels' });
    expect(only.getAttribute('title')).toBe('Only videos from these channels play during this window');
    expect(screen.queryByText('Only videos from these channels play during this window')).toBeNull();
  });
});
