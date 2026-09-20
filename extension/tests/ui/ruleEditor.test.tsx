// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { defaultFeatures, newSiteRule } from '../../src/core/settingsSchema';
import type { SiteRule } from '../../src/core/settingsSchema';
import { platformById } from '../../src/core/platforms';
import { RuleEditor } from '../../src/entrypoints/dashboard/RuleEditor';

const noop = () => {};

function makeRule(overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    ...newSiteRule({ domain: 'example.com', mode: 'DELAY', createdAt: 1700000000000 }),
    id: 'rule-example.com',
    dailyLimitMinutes: 30,
    showTimeRemaining: true,
    ...overrides,
  };
}

function renderEditor(rule: Partial<SiteRule>) {
  return render(<RuleEditor rule={makeRule(rule)} onSave={noop} onCancel={noop} />);
}

describe('RuleEditor — Daily Time Limit is not offered for Hard Block', () => {
  /**
   * Companion to the engine fix: a daily limit is meaningless on a Hard Block (the site is
   * barred outright, so there is no browsing time to budget). Offering the control invited
   * the exact combination that used to produce an infinite redirect loop, and still reads to
   * a user as "blocked, but only after 30 minutes".
   */
  afterEach(cleanup);

  it('explains why, instead of showing the limit controls, when mode is Hard Block', () => {
    renderEditor({ mode: 'HARD_BLOCK' });

    // Both the Pause length and the Daily Time Limit cards explain the same thing for
    // Hard Block now (neither a pause nor a budget means anything on a site that never
    // opens) — assert on the Daily Time Limit card specifically via its exact sentence.
    expect(
      screen.getByText(/not used with hard block — the site is always blocked/i),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'No limit' })).toBeNull();
    expect(screen.queryByRole('button', { name: '30m' })).toBeNull();
  });

  it('offers the limit controls for Delay', () => {
    renderEditor({ mode: 'DELAY' });

    expect(screen.getByRole('button', { name: 'No limit' })).toBeTruthy();
    expect(screen.queryByText(/not used with hard block — the site is always blocked/i)).toBeNull();
  });

  it('offers the limit controls for Breathing', () => {
    renderEditor({ mode: 'BREATHING' });

    expect(screen.getByRole('button', { name: 'No limit' })).toBeTruthy();
    expect(screen.queryByText(/not used with hard block — the site is always blocked/i)).toBeNull();
  });

  it('offers the limit controls for Allow — the new limit-only rule shape', () => {
    renderEditor({ mode: 'ALLOW' });

    expect(screen.getByRole('button', { name: 'No limit' })).toBeTruthy();
    expect(screen.queryByText(/not used with hard block — the site is always blocked/i)).toBeNull();
  });

  it('keeps the Daily Time Limit section itself present in every mode', () => {
    renderEditor({ mode: 'HARD_BLOCK' });
    expect(screen.getByText('Daily Time Limit')).toBeTruthy();
  });
});

describe('RuleEditor — Default behaviour includes Allow', () => {
  afterEach(cleanup);

  it('is selectable and shows the "opens normally" helper text', () => {
    renderEditor({ mode: 'HARD_BLOCK' });

    fireEvent.change(screen.getByLabelText('default mode'), { target: { value: 'ALLOW' } });

    expect((screen.getByLabelText('default mode') as HTMLSelectElement).value).toBe('ALLOW');
    expect(
      screen.getByText('The site opens normally. Use a daily limit, grayscale or site features to shape it.'),
    ).toBeTruthy();
  });

  it('lists Allow, Hard Block, Delay and Breathing as options', () => {
    renderEditor({ mode: 'DELAY' });
    const select = screen.getByLabelText('default mode') as HTMLSelectElement;
    const optionLabels = Array.from(select.options).map((o) => o.textContent);
    expect(optionLabels).toEqual(['Allow', 'Hard Block', 'Delay', 'Breathing']);
  });
});

describe('RuleEditor — grayscale toggle', () => {
  afterEach(cleanup);

  it('toggles the rule\'s grayscale flag', () => {
    renderEditor({ grayscale: false });
    const toggle = screen.getByLabelText('Turn on grayscale') as HTMLInputElement;
    expect(toggle.checked).toBe(false);

    fireEvent.click(toggle);
    expect(toggle.checked).toBe(true);
  });

  it('states the flash-free, all-pages behaviour', () => {
    renderEditor({});
    expect(screen.getByText(/flash-free, all pages of this site/i)).toBeTruthy();
  });
});

describe('RuleEditor — Site features', () => {
  afterEach(cleanup);

  it('renders the features section for a known platform', () => {
    const rule = makeRule({
      domain: 'youtube.com',
      features: defaultFeatures('youtube'),
    });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);

    expect(screen.getByText('Site features')).toBeTruthy();
  });

  it('does NOT render the features section for an ordinary (non-platform) site', () => {
    renderEditor({ domain: 'example.com', features: null });
    expect(screen.queryByText('Site features')).toBeNull();
  });

  it('renders one gate row per gate the registry defines for the platform, generically', () => {
    // Exercise a platform with MULTIPLE gates (Instagram: reels, explore, home) rather than
    // hardcoding "there must be a Shorts row" — a new gate added to the registry must show
    // up here without a matching UI change, so the assertion walks the registry itself.
    const rule = makeRule({ domain: 'instagram.com', features: defaultFeatures('instagram') });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);

    const definition = platformById('instagram');
    expect(definition.gates.length).toBeGreaterThan(1);
    for (const gate of definition.gates) {
      expect(screen.getByText(gate.label)).toBeTruthy();
      expect(screen.getByLabelText(`${gate.label} mode`)).toBeTruthy();
    }
  });

  it('shows a hide toggle per hide the registry defines', () => {
    const rule = makeRule({ domain: 'instagram.com', features: defaultFeatures('instagram') });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);

    const definition = platformById('instagram');
    expect(definition.hides.length).toBeGreaterThan(0);
    for (const hide of definition.hides) {
      expect(screen.getByLabelText(hide.label)).toBeTruthy();
    }
  });

  it('prints the platform note verbatim when one exists', () => {
    const rule = makeRule({ domain: 'instagram.com', features: defaultFeatures('instagram') });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);

    const definition = platformById('instagram');
    expect(definition.note).not.toBeNull();
    expect(screen.getByText(definition.note!)).toBeTruthy();
  });

  it('renders the YouTube channel list block only for youtube.com', () => {
    const rule = makeRule({ domain: 'youtube.com', features: defaultFeatures('youtube') });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);
    expect(screen.getByText('YouTube channels')).toBeTruthy();
  });

  it('changes the YouTube channel copy when the site mode is a BlockMode', () => {
    const rule = makeRule({
      domain: 'youtube.com',
      mode: 'HARD_BLOCK',
      features: defaultFeatures('youtube'),
    });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);
    expect(
      screen.getByText('YouTube is blocked; videos and pages from these channels are still allowed.'),
    ).toBeTruthy();
  });

  it('uses the default channel copy when the site mode is Allow', () => {
    const rule = makeRule({
      domain: 'youtube.com',
      mode: 'ALLOW',
      features: defaultFeatures('youtube'),
    });
    render(<RuleEditor rule={rule} onSave={noop} onCancel={noop} />);
    expect(screen.getByText(/lock things down to only the ones you choose/i)).toBeTruthy();
  });
});
