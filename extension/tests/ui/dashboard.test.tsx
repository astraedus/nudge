// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DashboardState, DayUsage } from '../../src/core/protocol';
import { NOTHING_ACTIVE } from '../../src/core/featureSummary';
import { DEFAULT_SETTINGS, defaultFeatures, structuredCloneSettings } from '../../src/core/settingsSchema';
import type { NudgeSettings, SiteRule } from '../../src/core/settingsSchema';
import { forDisplay } from '../../src/core/strictMode';
import { surfaceKey } from '../../src/core/surfaceKeys';
import { Dashboard } from '../../src/entrypoints/dashboard/Dashboard';
import { ChallengeDialog } from '../../src/entrypoints/dashboard/ChallengeDialog';
import { StatsPanel } from '../../src/entrypoints/dashboard/StatsPanel';

let sendMessageMock: ReturnType<typeof vi.fn>;

function makeDay(overrides: Partial<DayUsage> = {}): DayUsage {
  return {
    activeSec: 0,
    blocked: 0,
    walkedAway: 0,
    hourly: new Array<number>(24).fill(0),
    // Distinct items viewed today (v0.3), only ever non-zero on a `domain#gate` surface
    // key. 0 by default so every existing caller keeps testing the "no item stream, or no
    // items yet" shape it was written for; the "of which" count tests override this.
    items: 0,
    ...overrides,
  };
}

function makeRule(overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    id: 'rule-youtube.com',
    domain: 'youtube.com',
    mode: 'DELAY',
    delaySeconds: 15,
    dailyLimitMinutes: 30,
    enabled: true,
    createdAt: 1700000000000,
    showTimeRemaining: true,
    schedule: null,
    grayscale: false,
    features: null,
    ...overrides,
  };
}

function makeSettings(overrides: Partial<NudgeSettings> = {}): NudgeSettings {
  return { ...structuredCloneSettings(DEFAULT_SETTINGS), ...overrides };
}

const RECENT_DAYS = [
  '2026-07-20',
  '2026-07-21',
  '2026-07-22',
  '2026-07-23',
  '2026-07-24',
  '2026-07-25',
  '2026-07-26',
];

function makeState(overrides: Partial<DashboardState> = {}): DashboardState {
  const hourly = new Array<number>(24).fill(0);
  hourly[9] = 1200;
  hourly[21] = 2400;
  return {
    settings: makeSettings({ rules: [makeRule()] }),
    recentDays: RECENT_DAYS,
    usage: {
      '2026-07-25': { 'youtube.com': makeDay({ activeSec: 900, blocked: 2, walkedAway: 1 }) },
      '2026-07-26': {
        'youtube.com': makeDay({ activeSec: 3600, blocked: 4, walkedAway: 3, hourly }),
        'reddit.com': makeDay({ activeSec: 1800, blocked: 1, walkedAway: 0 }),
      },
    },
    allTimeBlocked: 42,
    allTimeWalkedAway: 17,
    ...overrides,
  };
}

/** An empty-but-valid state: every counter zero, no usage rollups at all. */
function makeEmptyState(): DashboardState {
  return {
    settings: makeSettings(),
    recentDays: RECENT_DAYS,
    usage: {},
    allTimeBlocked: 0,
    allTimeWalkedAway: 0,
  };
}

/** Flush the microtask hops between the mocked chrome promise and the committed React state. */
async function flush(times = 6) {
  for (let i = 0; i < times; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

beforeEach(() => {
  sendMessageMock = vi.fn();
  (globalThis as unknown as { chrome: unknown }).chrome = {
    runtime: { sendMessage: sendMessageMock, getURL: (p: string) => `chrome-extension://nudgeid/${p}` },
    tabs: { create: vi.fn() },
  };
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('StatsPanel', () => {
  it('renders real numbers for today and all-time counters', () => {
    render(<StatsPanel data={makeState()} />);

    // Today = 3600 + 1800 active seconds across two domains -> "1h 30m".
    expect(screen.getByText('1h 30m')).toBeDefined();
    // Blocked today = 4 + 1; Walked Away today = 3 + 0.
    expect(screen.getByText('5')).toBeDefined();
    expect(screen.getByText('3')).toBeDefined();
    expect(screen.getByText(/42 all-time/)).toBeDefined();
    expect(screen.getByText(/17 all-time/)).toBeDefined();
  });

  it('uses the exact "Blocked" and "Walked Away" stat labels', () => {
    render(<StatsPanel data={makeState()} />);
    expect(screen.getByText('Blocked')).toBeDefined();
    expect(screen.getByText('Walked Away')).toBeDefined();
  });

  it('lists top sites with proportional bars', () => {
    render(<StatsPanel data={makeState()} />);
    expect(screen.getByText('youtube.com')).toBeDefined();
    expect(screen.getByText('reddit.com')).toBeDefined();
  });

  it('survives the all-zero empty state without dividing by zero', () => {
    const { container } = render(<StatsPanel data={makeEmptyState()} />);

    expect(screen.getByText(/No activity tracked yet/)).toBeDefined();
    expect(screen.getByText(/Nothing tracked in the last 7 days/)).toBeDefined();

    // No NaN/Infinity anywhere in the rendered markup (the classic /0 leak).
    expect(container.innerHTML).not.toMatch(/NaN|Infinity/);

    // The 7-day chart still renders 7 bars, each with a finite height.
    const rects = container.querySelectorAll('rect');
    expect(rects.length).toBeGreaterThanOrEqual(7);
    rects.forEach((rect) => {
      const height = Number(rect.getAttribute('height'));
      expect(Number.isFinite(height)).toBe(true);
      expect(height).toBeGreaterThanOrEqual(0);
    });
  });

  it('renders a 24-cell hourly heatmap', () => {
    const { container } = render(<StatsPanel data={makeState()} />);
    expect(container.querySelectorAll('[title$=":00 — 0s"], [title*=":00 — "]').length).toBe(24);
  });
});

describe('StatsPanel — the "of which" line (v0.3 count budgets)', () => {
  /**
   * A gate's time is attributed to BOTH its site key ("youtube.com") and its own surface
   * key ("youtube.com#shorts") — see `core/surfaceKeys.ts` — so every fixture below carries
   * both, exactly like the real tracker.
   */
  function stateWithSurfaces(overrides: Partial<DashboardState> = {}): DashboardState {
    return {
      settings: makeSettings({ rules: [] }),
      recentDays: RECENT_DAYS,
      usage: {
        '2026-07-26': {
          'youtube.com': makeDay({ activeSec: 720 }),
          [surfaceKey('youtube.com', 'shorts')]: makeDay({ activeSec: 720, items: 34 }),
          'instagram.com': makeDay({ activeSec: 60 }),
          [surfaceKey('instagram.com', 'reels')]: makeDay({ activeSec: 60, items: 0 }),
          'x.com': makeDay({ activeSec: 180 }),
          [surfaceKey('x.com', 'home')]: makeDay({ activeSec: 180 }),
        },
      },
      allTimeBlocked: 0,
      allTimeWalkedAway: 0,
      ...overrides,
    };
  }

  it('shows the item count for a counted surface, in the registry noun', () => {
    // The "of which" line is one text node ("of which " + the joined rows), so this checks
    // the rendered markup contains the row rather than matching it as a standalone node —
    // same pattern the existing summary-line tests in this file already use.
    const { container } = render(<StatsPanel data={stateWithSurfaces()} />);
    expect(container.textContent).toContain('Shorts: 12m (34 Shorts)');
  });

  it('shows "0 <Noun>" rather than omitting the line, when the surface has a count but no items yet', () => {
    // An absent line reads as the counter not working; "0 Reels" is a real, reassuring
    // answer (StatsPanel.tsx `surfaceRowText` doc comment).
    const { container } = render(<StatsPanel data={stateWithSurfaces()} />);
    expect(container.textContent).toContain('Reels: 1m (0 Reels)');
  });

  it('shows no count at all for a surface with no item stream', () => {
    // X's home timeline gate carries no `itemPaths` in the registry, so it can never have
    // a meaningful count — the line stays time-only, with no "(0 …)" suffix invented for it.
    const { container } = render(<StatsPanel data={stateWithSurfaces()} />);
    expect(container.textContent).toContain('Home timeline: 3m');
    expect(container.textContent).not.toMatch(/Home timeline: 3m\s*\(/);
  });

  it('the phantom-site invariant still holds: a domain#gate key never appears as its own site row', () => {
    // `isSurfaceKey`/`domainKeysOnly` (core/surfaceKeys.ts) exist specifically so a stats
    // table built by naively listing the usage map's keys does not grow a phantom
    // "youtube.com#shorts" site alongside the real "youtube.com" row.
    render(<StatsPanel data={stateWithSurfaces()} />);

    expect(screen.getByText('youtube.com')).toBeDefined();
    expect(screen.queryByText('youtube.com#shorts')).toBeNull();
    expect(screen.queryByText(surfaceKey('youtube.com', 'shorts'))).toBeNull();
    expect(screen.getAllByText('youtube.com')).toHaveLength(1);
  });
});

describe('ChallengeDialog', () => {
  const CHALLENGE = 'abcde12345fghij67890klmn';

  it('renders the challenge dash-grouped and never pre-fills the input', () => {
    render(<ChallengeDialog challenge={CHALLENGE} onSubmit={vi.fn()} onCancel={vi.fn()} />);

    expect(screen.getByTestId('challenge-code').textContent).toBe(forDisplay(CHALLENGE));
    expect((screen.getByLabelText('Unlock code') as HTMLInputElement).value).toBe('');
  });

  it('BLOCKS paste — the whole point of the friction', () => {
    render(<ChallengeDialog challenge={CHALLENGE} onSubmit={vi.fn()} onCancel={vi.fn()} />);
    const input = screen.getByLabelText('Unlock code') as HTMLInputElement;

    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(pasteEvent, 'clipboardData', {
      value: { getData: () => CHALLENGE },
    });
    fireEvent(input, pasteEvent);

    expect(pasteEvent.defaultPrevented).toBe(true);
    expect(input.value).toBe('');
  });

  it('only enables submit once the typed input matches the challenge length', () => {
    const onSubmit = vi.fn();
    render(<ChallengeDialog challenge={CHALLENGE} onSubmit={onSubmit} onCancel={vi.fn()} />);
    const input = screen.getByLabelText('Unlock code');
    const submit = screen.getByRole('button', { name: 'Unlock' }) as HTMLButtonElement;

    expect(submit.disabled).toBe(true);

    fireEvent.change(input, { target: { value: CHALLENGE.slice(0, 5) } });
    expect(screen.getByTestId('challenge-progress').textContent).toBe(`5/${CHALLENGE.length}`);
    expect((screen.getByRole('button', { name: 'Unlock' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(input, { target: { value: CHALLENGE } });
    expect(screen.getByTestId('challenge-progress').textContent).toBe(
      `${CHALLENGE.length}/${CHALLENGE.length}`,
    );
    const enabled = screen.getByRole('button', { name: 'Unlock' }) as HTMLButtonElement;
    expect(enabled.disabled).toBe(false);

    fireEvent.click(enabled);
    expect(onSubmit).toHaveBeenCalledWith(CHALLENGE);
  });

  it('counts dashes typed by the user as formatting, not content', () => {
    render(<ChallengeDialog challenge={CHALLENGE} onSubmit={vi.fn()} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Unlock code'), {
      target: { value: forDisplay(CHALLENGE) },
    });

    expect(screen.getByTestId('challenge-progress').textContent).toBe(
      `${CHALLENGE.length}/${CHALLENGE.length}`,
    );
  });
});

describe('Dashboard', () => {
  it('shows a retry instead of a dead spinner when the service worker is asleep', async () => {
    sendMessageMock.mockRejectedValue(new Error('Could not establish connection.'));
    render(<Dashboard />);
    await flush();

    expect(screen.getByText(/Could not establish connection/)).toBeDefined();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeDefined();
  });

  it('shows the naming-parity strings on the Settings tab', async () => {
    sendMessageMock.mockResolvedValue(makeState());
    render(<Dashboard />);
    await flush();

    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    for (const label of ['Daily Time Limit', 'Commitment Lock', 'Escape Hatch']) {
      expect(screen.getAllByText(new RegExp(label)).length).toBeGreaterThan(0);
    }

    // "Hard Block" / "Delay" / "Breathing" live inside a rule's editor (ext-13 §5 moved the
    // mode picker off the bare sites list), so open one to check the naming parity there.
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    for (const label of ['Hard Block', 'Delay', 'Breathing']) {
      expect(screen.getAllByText(new RegExp(label)).length).toBeGreaterThan(0);
    }
  });

  it('states the Commitment Lock honesty note about chrome://extensions removal', async () => {
    sendMessageMock.mockResolvedValue(makeState());
    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect(screen.getByText(/cannot prevent its own removal/)).toBeDefined();
    expect(screen.getAllByText(/chrome:\/\/extensions/).length).toBeGreaterThan(0);
    expect(screen.getByText(/commitment device, not an unbreakable/)).toBeDefined();
  });

  it('exposes "Scheduled Override" and "Show time remaining" in the rule editor', async () => {
    sendMessageMock.mockResolvedValue(makeState());
    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

    expect(screen.getByText('Scheduled Override')).toBeDefined();
    expect(screen.getByText('Show time remaining')).toBeDefined();
    expect(screen.getByText('Daily Time Limit')).toBeDefined();
  });

  it('surfaces the challenge when the background rejects a weakening save', async () => {
    const challenge = 'qwertyuiopas';
    sendMessageMock.mockImplementation((request: { type: string }) => {
      if (request.type === 'GET_DASHBOARD_STATE') {
        return Promise.resolve(makeState({ settings: makeSettings({ rules: [makeRule()] }) }));
      }
      return Promise.resolve({ ok: false, challenge, reason: 'challenge-required' });
    });

    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    // Turning the master toggle off is a weakening change.
    fireEvent.click(screen.getByLabelText('Nudge is on'));
    await flush();

    expect(screen.getByTestId('challenge-code').textContent).toBe(forDisplay(challenge));
    expect((screen.getByRole('button', { name: 'Unlock' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('retries the save with the typed challengeResponse and closes on success', async () => {
    const challenge = 'qwertyuiopas';
    let saveCalls = 0;
    sendMessageMock.mockImplementation((request: { type: string; challengeResponse?: string }) => {
      if (request.type === 'GET_DASHBOARD_STATE') return Promise.resolve(makeState());
      saveCalls += 1;
      if (request.challengeResponse === undefined) {
        return Promise.resolve({ ok: false, challenge, reason: 'challenge-required' });
      }
      return Promise.resolve({ ok: true });
    });

    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Nudge is on'));
    await flush();

    fireEvent.change(screen.getByLabelText('Unlock code'), { target: { value: challenge } });
    fireEvent.click(screen.getByRole('button', { name: 'Unlock' }));
    await flush();

    expect(saveCalls).toBe(2);
    expect(sendMessageMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'SAVE_SETTINGS', challengeResponse: challenge }),
    );
    expect(screen.queryByTestId('challenge-code')).toBeNull();
  });

  it('tells the user when the typed code was wrong and keeps the same challenge', async () => {
    const challenge = 'qwertyuiopas';
    sendMessageMock.mockImplementation((request: { type: string; challengeResponse?: string }) => {
      if (request.type === 'GET_DASHBOARD_STATE') return Promise.resolve(makeState());
      return Promise.resolve({
        ok: false,
        challenge,
        reason: request.challengeResponse === undefined ? 'challenge-required' : 'challenge-incorrect',
      });
    });

    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Nudge is on'));
    await flush();

    fireEvent.change(screen.getByLabelText('Unlock code'), { target: { value: challenge } });
    fireEvent.click(screen.getByRole('button', { name: 'Unlock' }));
    await flush();

    expect(screen.getByText(/doesn't match/)).toBeDefined();
    expect(screen.getByTestId('challenge-code').textContent).toBe(forDisplay(challenge));
  });

  it('shows the tagline', async () => {
    sendMessageMock.mockResolvedValue(makeState());
    render(<Dashboard />);
    await flush();

    expect(screen.getByText('Break the scroll. Take back your time.')).toBeDefined();
  });
});

describe('SettingsPanel — sites list (ext-13 §5)', () => {
  it('renders quick-add chips for every platform in the registry, not a hardcoded list', async () => {
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [] }) }));
    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    for (const label of ['YouTube', 'Instagram', 'TikTok', 'X (Twitter)', 'Facebook', 'Reddit', 'LinkedIn']) {
      expect(screen.getByRole('button', { name: label })).toBeDefined();
    }
  });

  it('a quick-add chip seeds an ALLOW rule with default features and opens the editor', async () => {
    let saved: NudgeSettings | null = null;
    sendMessageMock.mockImplementation((request: { type: string; settings?: NudgeSettings }) => {
      if (request.type === 'GET_DASHBOARD_STATE') {
        return Promise.resolve(makeState({ settings: saved ?? makeSettings({ rules: [] }) }));
      }
      saved = request.settings ?? null;
      return Promise.resolve({ ok: true });
    });

    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Instagram' }));
    await flush();

    expect(saved).not.toBeNull();
    const created = saved!.rules.find((r) => r.domain === 'instagram.com');
    expect(created).toBeDefined();
    expect(created!.mode).toBe('ALLOW');
    expect(created!.features).not.toBeNull();
    expect(created!.features!.platform).toBe('instagram');
    // The editor for the newly-created rule opens immediately.
    expect(screen.getByRole('dialog', { name: /instagram\.com/i })).toBeDefined();
  });

  it('shows "Nothing active" for an ALLOW rule with no limit, grayscale or feature on', async () => {
    const rule = makeRule({
      domain: 'example.com',
      mode: 'ALLOW',
      dailyLimitMinutes: null,
      grayscale: false,
      features: null,
    });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    // Assert the RENDERED state via the canonical constant, not a re-typed string — the
    // decision itself is core/featureSummary's, tested there (17 cases); this only checks
    // SettingsPanel actually shows it.
    expect(screen.getByText(NOTHING_ACTIVE)).toBeDefined();
  });

  it('shows the mode chip, including "Allow", on every rule card', async () => {
    const allowRule = makeRule({ domain: 'allow.example', mode: 'ALLOW', dailyLimitMinutes: 30 });
    const blockRule = makeRule({ domain: 'block.example', mode: 'HARD_BLOCK' });
    sendMessageMock.mockResolvedValue(
      makeState({ settings: makeSettings({ rules: [allowRule, blockRule] }) }),
    );
    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect(screen.getAllByText('Allow').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Hard Block').length).toBeGreaterThan(0);
  });

  it("states a rule's mode exactly once, on the chip, not again at the head of its summary", async () => {
    // Every card read "Hard Block" twice in a row, once as the badge and once as the first
    // word of its own summary line. A fact stated twice on one card reads as two facts that
    // happen to agree, and it crowds out the parts that are only said once.
    const rule = makeRule({
      domain: 'block.example',
      mode: 'HARD_BLOCK',
      dailyLimitMinutes: 30,
      grayscale: false,
      features: null,
      schedule: null,
    });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    const { container } = render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    const printed = (container.textContent ?? '').split('Hard Block').length - 1;
    expect(printed).toBe(1);
    // Everything the chip does NOT already say survives.
    expect(container.textContent).toContain('30m/day');
  });

  it('keeps the pause length on the summary, since the chip only names the mode', async () => {
    const rule = makeRule({
      domain: 'delay.example',
      mode: 'DELAY',
      delaySeconds: 30,
      dailyLimitMinutes: null,
      grayscale: false,
      features: null,
      schedule: null,
    });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    const { container } = render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect(container.textContent).toContain('30s pause');
    expect(container.textContent).not.toContain('Delay · 30s');
  });

  it('drops the summary line entirely when the chip is already the whole story', async () => {
    const rule = makeRule({
      domain: 'plain.example',
      mode: 'HARD_BLOCK',
      dailyLimitMinutes: null,
      grayscale: false,
      features: null,
      schedule: null,
    });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    const { container } = render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect((container.textContent ?? '').split('Hard Block').length - 1).toBe(1);
    // An empty second line is a visible gap under the domain; render nothing instead.
    expect(container.querySelector('p[style*="margin: 2px 0px 0px"]')).toBeNull();
  });

  it('states grayscale once too, on its own badge, not again inside the summary', async () => {
    // Same defect as the mode, same card, same line: the badge said "Grayscale" and the
    // summary under it said "Grayscale" again.
    const rule = makeRule({
      domain: 'gray.example',
      mode: 'ALLOW',
      grayscale: true,
      dailyLimitMinutes: 30,
      features: null,
      schedule: null,
    });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    const { container } = render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect((container.textContent ?? '').split('Grayscale').length - 1).toBe(1);
    expect(container.textContent).toContain('30m/day');
  });

  it('shows a grayscale badge on a rule with grayscale on', async () => {
    const rule = makeRule({ domain: 'gray.example', mode: 'ALLOW', grayscale: true });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect(screen.getByText('Grayscale')).toBeDefined();
  });

  it('summarises a rule whose ONLY effect is a gate\'s COUNT budget — not "Nothing active" (v0.3)', async () => {
    // `SettingsPanel.tsx`'s `ruleSummary` reads `core/featureSummaryParts` as the single
    // source of truth for "does this rule do anything" — it lists a gate's count budget
    // alongside its minutes budget, so a rule whose sole effect is "20 Shorts a day" (no
    // site limit, no grayscale, the gate's own mode left OFF) must not summarise as having
    // no effect: that combination is exactly the shape count budgets exist for, and it is
    // the only thing standing between the user and an afternoon of Shorts.
    const features = defaultFeatures('youtube');
    features.gates.shorts = { ...features.gates.shorts!, dailyLimitCount: 20 };
    const rule = makeRule({
      domain: 'youtube.com',
      mode: 'ALLOW',
      dailyLimitMinutes: null,
      grayscale: false,
      schedule: null,
      features,
    });
    sendMessageMock.mockResolvedValue(makeState({ settings: makeSettings({ rules: [rule] }) }));
    const { container } = render(<Dashboard />);
    await flush();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));

    expect(screen.queryByText(NOTHING_ACTIVE)).toBeNull();
    expect(container.textContent).toContain('Shorts: 20 Shorts/day');
  });
});
