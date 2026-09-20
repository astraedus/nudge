// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BlockContext } from '../../src/core/protocol';
import type { BlockDecision } from '../../src/core/types';
import type { ChannelEntry } from '../../src/core/settingsSchema';
import { formatNextPass } from '../../src/ui/format';
import { BlockPage, channelHomeUrl, isNavigableTarget } from '../../src/entrypoints/blocked/BlockPage';
import { limitLabelFor } from '../../src/entrypoints/blocked/HardBlockView';

const TARGET = 'https://distracting.example/feed';

function hardBlockDecision(overrides: Partial<Extract<BlockDecision, { type: 'BLOCK' }>> = {}) {
  return {
    type: 'BLOCK' as const,
    mode: 'HARD_BLOCK' as const,
    delaySeconds: 0,
    ruleName: 'Focus Time',
    dailyTimeRemainingMs: null,
    dailyLimitMinutes: null,
    limitReached: false,
    ...overrides,
  };
}

function makeContext(overrides: Partial<BlockContext> = {}): BlockContext {
  return {
    target: TARGET,
    domain: 'distracting.example',
    decision: hardBlockDecision(),
    delayTitle: 'Take a breath',
    delaySubtitle: 'It will still be here in a few seconds.',
    hardBlockMessage: 'This site is off-limits right now.',
    passEnabled: true,
    passAvailable: true,
    passNextAvailableMs: 0,
    strictModeEnabled: false,
    tempAllowMinutes: 10,
    gateId: null,
    gateLabel: null,
    // Count-budget fields (v0.3). Default to "no count budget involved" so every existing
    // caller of makeContext() keeps testing the minutes-only shape it was written for;
    // count-specific cases override these explicitly (see the `limitLabelFor` describe
    // block below).
    gateLimitKind: null,
    gateItemsToday: 0,
    gateCountLimit: null,
    gateItemNoun: null,
    allowedChannels: [],
    ...overrides,
  };
}

function channel(overrides: Partial<ChannelEntry> = {}): ChannelEntry {
  return {
    channelId: null,
    handle: null,
    displayName: 'Some Channel',
    addedAt: 0,
    ...overrides,
  };
}

let sendMessageMock: ReturnType<typeof vi.fn>;
let replaceMock: ReturnType<typeof vi.fn>;

/** The extension page the DNR rule redirects to. */
const BLOCKED_PAGE_URL = 'chrome-extension://nudgeid/blocked.html';

/**
 * Point the page at `target`, mirroring production byte-for-byte: the DNR rule appends the
 * ORIGINAL url VERBATIM as the last thing in the address (`regexSubstitution` cannot
 * percent-encode), so the target routinely carries its own `?`, `&` and `#`. Encoding it
 * here would test a contract the extension never actually produces.
 *
 * jsdom's real `window.location` has a non-configurable, non-writable `replace` method that
 * cannot be spied on, so navigation is verified against a full stand-in object. Vitest's
 * jsdom environment defines `location` as a plain accessor on `window`, so whole-object
 * reassignment (not `Object.defineProperty`) is what actually works.
 */
function setTarget(target: string | null) {
  const suffix = target === null ? '' : `?target=${target}`;
  (window as unknown as { location: unknown }).location = {
    href: `${BLOCKED_PAGE_URL}${suffix}`,
    search: suffix,
    replace: replaceMock,
  };
}

/** Flushes several microtask hops (mocked chrome promise -> rpc's send() -> component .then()),
 * wrapped in `act` at each hop so any resulting React state updates get committed. */
async function flush(times = 6) {
  for (let i = 0; i < times; i++) {
     
    await act(async () => {
      await Promise.resolve();
    });
  }
}

beforeEach(() => {
  vi.useFakeTimers();
  sendMessageMock = vi.fn();
  replaceMock = vi.fn();
  vi.stubGlobal('chrome', {
    runtime: {
      sendMessage: sendMessageMock,
      getURL: (path: string) => `chrome-extension://test-id/${path}`,
    },
  } as unknown as typeof chrome);
  setTarget(null);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('isNavigableTarget', () => {
  it('accepts http/https URLs', () => {
    expect(isNavigableTarget('https://example.com')).toBe(true);
    expect(isNavigableTarget('http://example.com/path?q=1')).toBe(true);
  });

  it('rejects javascript: URLs', () => {
    expect(isNavigableTarget('javascript:alert(1)')).toBe(false);
  });

  it('rejects data: URLs', () => {
    expect(isNavigableTarget('data:text/html,<script>alert(1)</script>')).toBe(false);
  });

  it('rejects chrome-extension: URLs', () => {
    expect(isNavigableTarget('chrome-extension://other-id/page.html')).toBe(false);
  });

  it('rejects missing/blank/unparsable input', () => {
    expect(isNavigableTarget(null)).toBe(false);
    expect(isNavigableTarget(undefined)).toBe(false);
    expect(isNavigableTarget('')).toBe(false);
    expect(isNavigableTarget('not a url')).toBe(false);
  });
});

describe('BlockPage — target parsing', () => {
  // Regression: the target arrives VERBATIM after "?target=" because DNR's regexSubstitution
  // cannot percent-encode. Parsing it with URLSearchParams truncated the url at its own first
  // "&", so "watch?v=abc&t=30" came back as "watch?v=abc" — the user would be sent to the
  // wrong place after completing a pause. Whole class: any target carrying its own query.
  it('preserves a target that carries its own query string, unsplit at "&"', async () => {
    const target = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s&list=PL1';
    sendMessageMock.mockResolvedValue(makeContext({ target, domain: 'youtube.com' }));
    setTarget(target);
    render(<BlockPage />);
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'GET_BLOCK_CONTEXT', target }),
    );
  });

  it('preserves a target that carries a #fragment', async () => {
    const target = 'https://example.com/a/b?x=1#section-2';
    sendMessageMock.mockResolvedValue(makeContext({ target, domain: 'example.com' }));
    setTarget(target);
    render(<BlockPage />);
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'GET_BLOCK_CONTEXT', target }),
    );
  });

  it('does not percent-decode a target containing a literal %XX sequence', async () => {
    // Both producers append the url verbatim, so decoding here would corrupt a real url.
    const target = 'https://example.com/search?q=100%25%20cotton';
    sendMessageMock.mockResolvedValue(makeContext({ target, domain: 'example.com' }));
    setTarget(target);
    render(<BlockPage />);
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'GET_BLOCK_CONTEXT', target }),
    );
  });
});

describe('BlockPage — target guard', () => {
  it('renders a safe fallback for a javascript: target, never fetches, never navigates', async () => {
    setTarget('javascript:alert(1)');
    render(<BlockPage />);
    await flush();

    expect(screen.getByText(/nothing to show here/i)).toBeTruthy();
    expect(sendMessageMock).not.toHaveBeenCalled();
    expect(replaceMock).not.toHaveBeenCalled();
  });

  it('renders a safe fallback for a data: target, never fetches, never navigates', async () => {
    setTarget('data:text/html,<script>alert(1)</script>');
    render(<BlockPage />);
    await flush();

    expect(screen.getByText(/nothing to show here/i)).toBeTruthy();
    expect(sendMessageMock).not.toHaveBeenCalled();
    expect(replaceMock).not.toHaveBeenCalled();
  });

  it('renders a safe fallback when target is missing', async () => {
    setTarget(null);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText(/nothing to show here/i)).toBeTruthy();
    expect(sendMessageMock).not.toHaveBeenCalled();
  });

  it('renders a safe fallback when target is blank', async () => {
    (window as unknown as { location: unknown }).location = {
      href: `${BLOCKED_PAGE_URL}?target=`,
      search: '?target=',
      replace: replaceMock,
    };
    render(<BlockPage />);
    await flush();

    expect(screen.getByText(/nothing to show here/i)).toBeTruthy();
    expect(sendMessageMock).not.toHaveBeenCalled();
  });
});

describe('BlockPage — loading and error', () => {
  it('renders a neutral loading state before the context resolves — never the wrong mode', () => {
    setTarget(TARGET);
    sendMessageMock.mockImplementationOnce(() => new Promise(() => {})); // never resolves
    render(<BlockPage />);

    expect(screen.getByText('Loading…')).toBeTruthy();
    expect(screen.queryByText('I changed my mind')).toBeNull();
    expect(screen.queryByText('Go Back')).toBeNull();
  });

  it('shows a retry affordance when GET_BLOCK_CONTEXT rejects, and retry recovers', async () => {
    setTarget(TARGET);
    sendMessageMock.mockRejectedValueOnce(new Error('receiving end does not exist'));
    render(<BlockPage />);
    await flush();

    const retryButton = screen.getByRole('button', { name: /retry/i });
    expect(retryButton).toBeTruthy();
    expect(screen.queryByText('Loading…')).toBeNull();

    const context = makeContext({ hardBlockMessage: 'Back to focus.' });
    sendMessageMock.mockResolvedValueOnce(context);
    fireEvent.click(retryButton);
    await flush();

    expect(screen.getByText('Back to focus.')).toBeTruthy();
  });
});

describe('Hard Block', () => {
  it('renders the hard-block message and offers no way through', async () => {
    setTarget(TARGET);
    const context = makeContext({ hardBlockMessage: 'Stay focused. Try again later.' });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Stay focused. Try again later.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Go Back' })).toBeTruthy();
    expect(screen.queryByText(/open anyway|continue to site|proceed/i)).toBeNull();
    expect(screen.getByText('Rule: Focus Time')).toBeTruthy();
  });

  it('shows "Daily limit reached" without duplicating the ruleName suffix', async () => {
    setTarget(TARGET);
    const context = makeContext({
      hardBlockMessage: 'Budget is gone for today.',
      decision: hardBlockDecision({
        ruleName: 'Focus Time (limit reached)',
        dailyTimeRemainingMs: 0,
        dailyLimitMinutes: 30,
        limitReached: true,
      }),
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Daily limit reached')).toBeTruthy();
    // ruleName already carries the suffix from the background — must appear exactly once.
    expect(screen.getAllByText(/Focus Time \(limit reached\)/)).toHaveLength(1);
    expect(screen.queryByText('Focus Time (limit reached) (limit reached)')).toBeNull();
  });

  it('"Go Back" never navigates to the blocked target', async () => {
    setTarget(TARGET);
    const historyBackSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
    const context = makeContext();
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    fireEvent.click(screen.getByRole('button', { name: 'Go Back' }));

    expect(replaceMock).not.toHaveBeenCalledWith(TARGET);
    // Either history.back() fired, or (no history) it fell back to a neutral about:blank —
    // never the blocked site.
    const wentToTarget = replaceMock.mock.calls.some((call) => call[0] === TARGET);
    expect(wentToTarget).toBe(false);
    historyBackSpy.mockRestore();
  });
});

describe('Delay', () => {
  it('renders title/subtitle and, after the delay elapses, completes the pause and navigates', async () => {
    setTarget(TARGET);
    const context = makeContext({
      decision: {
        type: 'BLOCK',
        mode: 'DELAY',
        delaySeconds: 15,
        ruleName: 'Focus Time',
        dailyTimeRemainingMs: null,
        dailyLimitMinutes: null,
        limitReached: false,
      },
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Take a breath')).toBeTruthy();
    expect(screen.getByText('It will still be here in a few seconds.')).toBeTruthy();

    sendMessageMock.mockResolvedValueOnce({ ok: true, until: Date.now() + 600_000 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000);
    });
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith({ type: 'COMPLETE_PAUSE', target: TARGET });
    expect(replaceMock).toHaveBeenCalledWith(TARGET);
  });

  it('"I changed my mind" sends WALKED_AWAY and navigates to the dashboard', async () => {
    setTarget(TARGET);
    const context = makeContext({
      decision: {
        type: 'BLOCK',
        mode: 'DELAY',
        delaySeconds: 15,
        ruleName: 'Focus Time',
        dailyTimeRemainingMs: null,
        dailyLimitMinutes: null,
        limitReached: false,
      },
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    sendMessageMock.mockResolvedValueOnce({ ok: true });
    fireEvent.click(screen.getByRole('button', { name: 'I changed my mind' }));
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith({ type: 'WALKED_AWAY', target: TARGET });
    expect(replaceMock).toHaveBeenCalledWith('chrome-extension://test-id/dashboard.html');
  });
});

describe('Breathing', () => {
  it('alternates Breathe in/out on the fixed 4s/4s cycle and completes into COMPLETE_PAUSE + navigation', async () => {
    setTarget(TARGET);
    const context = makeContext({
      decision: {
        type: 'BLOCK',
        mode: 'BREATHING',
        delaySeconds: 8,
        ruleName: 'Focus Time',
        dailyTimeRemainingMs: null,
        dailyLimitMinutes: null,
        limitReached: false,
      },
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Breathe in...')).toBeTruthy();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    await flush();
    expect(screen.getByText('Breathe out...')).toBeTruthy();

    sendMessageMock.mockResolvedValueOnce({ ok: true, until: Date.now() + 600_000 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith({ type: 'COMPLETE_PAUSE', target: TARGET });
    expect(replaceMock).toHaveBeenCalledWith(TARGET);
  });
});

describe('Escape Hatch', () => {
  it('shows the available label and, on tap, calls USE_EMERGENCY_PASS then navigates', async () => {
    setTarget(TARGET);
    const context = makeContext({ passEnabled: true, passAvailable: true, strictModeEnabled: false });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    const passButton = screen.getByRole('button', { name: 'Use for 2 minutes · once a day' });
    expect((passButton as HTMLButtonElement).disabled).toBe(false);

    sendMessageMock.mockResolvedValueOnce({ ok: true, until: Date.now() + 120_000 });
    fireEvent.click(passButton);
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith({ type: 'USE_EMERGENCY_PASS', target: TARGET });
    expect(replaceMock).toHaveBeenCalledWith(TARGET);
  });

  it('shows the disabled spent label with the correct hours', async () => {
    setTarget(TARGET);
    const passNextAvailableMs = 5 * 3_600_000;
    const context = makeContext({
      passEnabled: true,
      passAvailable: false,
      passNextAvailableMs,
      strictModeEnabled: false,
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    const label = `Daily pass used · next in ${formatNextPass(passNextAvailableMs)}`;
    const passButton = screen.getByRole('button', { name: label });
    expect((passButton as HTMLButtonElement).disabled).toBe(true);
  });

  it('renders nothing under Strict Mode', async () => {
    setTarget(TARGET);
    const context = makeContext({ passEnabled: true, passAvailable: true, strictModeEnabled: true });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.queryByText(/use for 2 minutes/i)).toBeNull();
    expect(screen.queryByText(/daily pass used/i)).toBeNull();
  });

  it('renders nothing when the pass is disabled entirely', async () => {
    setTarget(TARGET);
    const context = makeContext({ passEnabled: false, passAvailable: true, strictModeEnabled: false });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.queryByText(/use for 2 minutes/i)).toBeNull();
    expect(screen.queryByText(/daily pass used/i)).toBeNull();
  });
});

describe('channelHomeUrl', () => {
  it('prefers a handle, building the @handle youtube.com URL', () => {
    expect(channelHomeUrl(channel({ handle: 'someuser', channelId: 'UC123' }))).toBe(
      'https://www.youtube.com/@someuser',
    );
  });

  it('falls back to the channel id when there is no handle', () => {
    expect(channelHomeUrl(channel({ channelId: 'UC123' }))).toBe(
      'https://www.youtube.com/channel/UC123',
    );
  });

  it('returns null when the entry names neither a handle nor an id', () => {
    expect(channelHomeUrl(channel())).toBeNull();
  });

  // Regression: a hand-edited settings import can carry a handle containing '/' or '?'.
  // The identifier must still land in exactly ONE path segment on youtube.com — never a
  // URL that escapes the channel path onto a different host or route.
  it('keeps a handle containing "/" confined to one youtube.com path segment', () => {
    const href = channelHomeUrl(channel({ handle: 'evil/../other' }));
    expect(href).not.toBeNull();
    expect(href).toMatch(/^https:\/\/www\.youtube\.com\/@[^/]+$/);
  });

  it('keeps a handle containing "?" from starting a query string', () => {
    const href = channelHomeUrl(channel({ handle: 'name?redirect=evil.example' }));
    expect(href).not.toBeNull();
    expect(href).toMatch(/^https:\/\/www\.youtube\.com\/@[^/?]+$/);
  });
});

describe('BlockPage — allowed channels (the way back in)', () => {
  it('shows a heading and one working link per allowed channel, labelled and addressed correctly', async () => {
    setTarget(TARGET);
    const context = makeContext({
      allowedChannels: [
        channel({ handle: 'coolchannel', displayName: 'Cool Channel' }),
        channel({ channelId: 'UC999', displayName: 'Other Channel' }),
      ],
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Your allowed channels')).toBeTruthy();

    const first = screen.getByRole('link', { name: 'Cool Channel' });
    expect(first.getAttribute('href')).toBe('https://www.youtube.com/@coolchannel');

    const second = screen.getByRole('link', { name: 'Other Channel' });
    expect(second.getAttribute('href')).toBe('https://www.youtube.com/channel/UC999');
  });

  it('shows no allowed-channels section when the list is empty', async () => {
    setTarget(TARGET);
    const context = makeContext({ allowedChannels: [] });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.queryByText('Your allowed channels')).toBeNull();
  });

  it('skips an entry that names neither a handle nor an id, rather than rendering a broken link', async () => {
    setTarget(TARGET);
    const context = makeContext({
      allowedChannels: [
        channel({ displayName: 'Nameless Channel' }),
        channel({ handle: 'realchannel', displayName: 'Real Channel' }),
      ],
    });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Your allowed channels')).toBeTruthy();
    expect(screen.queryByText('Nameless Channel')).toBeNull();
    expect(screen.getByRole('link', { name: 'Real Channel' })).toBeTruthy();
  });
});

describe('BlockPage — surface label', () => {
  it('names the gate surface (e.g. "Shorts"), not the bare domain, when a gate was hit', async () => {
    setTarget(TARGET);
    const context = makeContext({ domain: 'youtube.com', gateId: 'shorts', gateLabel: 'Shorts' });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Shorts')).toBeTruthy();
    expect(screen.queryByText('youtube.com')).toBeNull();
  });

  it('falls back to the domain when the whole site was blocked, not a gate', async () => {
    setTarget(TARGET);
    const context = makeContext({ domain: 'youtube.com', gateId: null, gateLabel: null });
    sendMessageMock.mockResolvedValueOnce(context);
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('youtube.com')).toBeTruthy();
  });
});

/**
 * The Hard Block path is where "block YouTube except these channels" actually lands, and
 * it is the case with no other route in: home, search and subscriptions are all blocked
 * too. These are the tests that stop that combination shipping as a dead end.
 */
describe('BlockPage — Hard Block with an active channel whitelist', () => {
  const WHITELIST = [
    channel({ handle: 'veritasium', displayName: 'Veritasium' }),
    channel({ channelId: 'UCallowed0000000000000', displayName: 'Kurzgesagt' }),
  ];

  it('offers the allowed channels as working links on a Hard Block, not only on a pause', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        decision: hardBlockDecision({ ruleName: 'youtube.com' }),
        allowedChannels: WHITELIST,
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Your allowed channels')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Veritasium' }).getAttribute('href')).toBe(
      'https://www.youtube.com/@veritasium',
    );
    expect(screen.getByRole('link', { name: 'Kurzgesagt' }).getAttribute('href')).toBe(
      'https://www.youtube.com/channel/UCallowed0000000000000',
    );
  });

  it('offers the free way in BEFORE the daily pass, so nobody burns a pass to reach a channel they allowed', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        decision: hardBlockDecision({ ruleName: 'youtube.com' }),
        allowedChannels: WHITELIST,
        passEnabled: true,
        passAvailable: true,
      }),
    );
    const { container } = render(<BlockPage />);
    await flush();

    const channelLink = screen.getByRole('link', { name: 'Veritasium' });
    const dailyPass = screen.getByRole('button', { name: /Use for 2 minutes/ });
    // DOCUMENT_POSITION_FOLLOWING: the pass comes after the channel list in reading order.
    expect(
      channelLink.compareDocumentPosition(dailyPass) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(container.textContent).toContain('Your allowed channels');
  });
});

describe('BlockPage — whose daily limit ran out', () => {
  const exhausted = hardBlockDecision({
    ruleName: 'youtube.com (limit reached)',
    dailyTimeRemainingMs: 0,
    dailyLimitMinutes: 10,
    limitReached: true,
  });

  it('names the SURFACE when a gate budget is spent — saying YouTube is out of time would be false', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        gateId: 'shorts',
        gateLabel: 'Shorts',
        decision: exhausted,
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Daily Shorts limit reached')).toBeTruthy();
    // The unqualified wording would send the user hunting for a site limit they never set.
    expect(screen.queryByText('Daily limit reached')).toBeNull();
  });

  it('keeps the plain Android wording when the whole SITE budget is what ran out', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({ domain: 'youtube.com', gateId: null, gateLabel: null, decision: exhausted }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Daily limit reached')).toBeTruthy();
  });
});

describe('limitLabelFor — the count sentence vs the minutes sentence (v0.3)', () => {
  /**
   * `limitLabelFor` is the ONE place that decides which of the two sentences prints, so it
   * gets its own direct unit coverage rather than only the round trip through BlockPage —
   * the round trip below pins that the rendered page actually calls it, this pins the
   * decision table itself.
   */

  it('returns the count sentence when gateLimitKind is "count" and an item noun is present', () => {
    const context = makeContext({
      gateId: 'shorts',
      gateLabel: 'Shorts',
      gateLimitKind: 'count',
      gateItemsToday: 20,
      gateCountLimit: 20,
      gateItemNoun: 'Shorts',
    });

    expect(limitLabelFor(context)).toBe("You've watched 20 Shorts today");
  });

  it('falls back to itemsToday when the gate somehow carries no countLimit', () => {
    // Defensive: countLimit is the number that SHOULD always be set alongside a count
    // verdict, but the sentence must still read a real number rather than "null" if it
    // is not — itemsToday is the next best true thing to say.
    const context = makeContext({
      gateLimitKind: 'count',
      gateItemsToday: 7,
      gateCountLimit: null,
      gateItemNoun: 'Reels',
    });

    expect(limitLabelFor(context)).toBe("You've watched 7 Reels today");
  });

  it('returns the minutes sentence, qualified with the gate label, when gateLimitKind is "minutes"', () => {
    const context = makeContext({
      gateId: 'shorts',
      gateLabel: 'Shorts',
      gateLimitKind: 'minutes',
      gateItemNoun: 'Shorts',
    });

    expect(limitLabelFor(context)).toBe('Daily Shorts limit reached');
  });

  it('returns the plain site sentence when gateLimitKind is null and no gate is involved', () => {
    const context = makeContext({ gateId: null, gateLabel: null, gateLimitKind: null });

    expect(limitLabelFor(context)).toBe('Daily limit reached');
  });

  it('a gate with NO item noun never produces the count sentence, even if gateLimitKind claims "count"', () => {
    // Fail toward the copy that is always true: "You've watched N <noun> today" is
    // meaningless without a noun to fill in, so an inconsistent worker response (a bug
    // there, not something the page should ever trust blindly) must still degrade to the
    // ordinary "Daily <label> limit reached" sentence rather than rendering broken text.
    const context = makeContext({
      gateId: 'shorts',
      gateLabel: 'Shorts',
      gateLimitKind: 'count',
      gateItemsToday: 20,
      gateCountLimit: 20,
      gateItemNoun: null,
    });

    expect(limitLabelFor(context)).toBe('Daily Shorts limit reached');
  });
});

describe('BlockPage — the count sentence renders in the Hard Block view (v0.3)', () => {
  it('shows "You\'ve watched N <Noun> today" for a count-exhausted gate, not the minutes sentence', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        gateId: 'shorts',
        gateLabel: 'Shorts',
        gateLimitKind: 'count',
        gateItemsToday: 20,
        gateCountLimit: 20,
        gateItemNoun: 'Shorts',
        decision: hardBlockDecision({
          ruleName: 'youtube.com · Shorts (limit reached)',
          limitReached: true,
        }),
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText("You've watched 20 Shorts today")).toBeTruthy();
    expect(screen.queryByText('Daily Shorts limit reached')).toBeNull();
  });
});

describe('BlockPage — what is off-limits, in the headline', () => {
  const spentGateBudget = hardBlockDecision({
    ruleName: 'youtube.com · Shorts (limit reached)',
    dailyTimeRemainingMs: 0,
    dailyLimitMinutes: 10,
    limitReached: true,
  });

  it('names the SURFACE in the headline when a gate is what blocked the page', async () => {
    // The page contradicted itself: the biggest text said the whole site was off-limits
    // while the line under it said only the Shorts budget was spent. Someone reading the
    // headline goes hunting for a site block they never set.
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        gateId: 'shorts',
        gateLabel: 'Shorts',
        hardBlockMessage: 'This site is off-limits right now.',
        decision: spentGateBudget,
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Shorts is off-limits right now')).toBeTruthy();
    expect(screen.queryByText('This site is off-limits right now.')).toBeNull();
    expect(screen.getByText('Daily Shorts limit reached')).toBeTruthy();
  });

  it('does the same for a gate set to Hard Block outright, not only a spent budget', async () => {
    // The whole class: every gate block had the site-level headline, the spent budget was
    // just the case QA happened to walk.
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'instagram.com',
        gateId: 'reels',
        gateLabel: 'Reels',
        hardBlockMessage: "You've blocked access to this site",
        decision: hardBlockDecision({ ruleName: 'instagram.com · Reels' }),
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('Reels is off-limits right now')).toBeTruthy();
    expect(screen.queryByText("You've blocked access to this site")).toBeNull();
  });

  it('keeps the rotating site message when the whole SITE is what was blocked', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        gateId: null,
        gateLabel: null,
        hardBlockMessage: 'You set this boundary for a reason',
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByText('You set this boundary for a reason')).toBeTruthy();
  });
});

describe('BlockPage — layout', () => {
  it('puts the whole interstitial in ONE card that stays centred and never clips its own top', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        decision: hardBlockDecision({ ruleName: 'youtube.com' }),
        allowedChannels: [channel({ handle: 'veritasium', displayName: 'Veritasium' })],
        passEnabled: true,
        passAvailable: true,
      }),
    );
    render(<BlockPage />);
    await flush();

    const card = screen.getByTestId('block-card');
    // Everything the user reads or acts on belongs to the same object, rather than three
    // loose stacks floating on the page background.
    expect(card.contains(screen.getByRole('button', { name: 'Go Back' }))).toBe(true);
    expect(card.contains(screen.getByRole('link', { name: 'Veritasium' }))).toBe(true);
    expect(card.contains(screen.getByRole('button', { name: /Use for 2 minutes/ }))).toBe(true);
    expect(card.contains(screen.getByText('Rule: youtube.com'))).toBe(true);

    // Auto margins, not `justify-content: center`. Both centre a card in a tall viewport;
    // only auto margins keep the TOP reachable once the card outgrows it (a long channel
    // list, an error state under a pause), because a centred flex item overflows in both
    // directions and the part above the scroll origin cannot be scrolled back to.
    expect(card.style.margin).toBe('auto');
    const page = card.parentElement as HTMLElement;
    expect(page.style.minHeight).toBe('100vh');
    expect(page.style.justifyContent).toBe('');
    expect(page.style.alignItems).toBe('');
  });

  it('centres the card on the loading and error states too, not just the block itself', async () => {
    setTarget(TARGET);
    sendMessageMock.mockImplementationOnce(() => new Promise(() => {}));
    render(<BlockPage />);

    const card = screen.getByTestId('block-card');
    expect(card.contains(screen.getByText('Loading…'))).toBe(true);
    expect(card.style.margin).toBe('auto');
  });
});

describe('BlockPage — allowed channels look like the way in', () => {
  it('renders each channel as a teal link with an affordance, not a flat grey pill', async () => {
    // This list is the ONLY route into a channel the user explicitly allowed while the site
    // is Hard Blocked, so it is the primary action on the page. Rendered as grey pills it
    // read as disabled metadata, which turns the whole feature back into a dead end.
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        allowedChannels: [channel({ handle: 'veritasium', displayName: 'Veritasium' })],
      }),
    );
    render(<BlockPage />);
    await flush();

    const link = screen.getByRole('link', { name: 'Veritasium' });
    expect(link.getAttribute('href')).toBe('https://www.youtube.com/@veritasium');
    expect(link.style.color).toBe('var(--nudge-link)');
    expect(link.style.fontWeight).toBe('600');
    // The fill comes from the CLASS, not from here. This assertion used to require the
    // inline background, which is precisely what made the hover rule unreachable (QA R3):
    // an inline declaration outranks any class rule, so the test was pinning the bug.
    expect(link.style.background).toBe('');
    // The hover/focus states live in a stylesheet, since inline styles cannot express them.
    expect(link.className).toContain('nudge-channel-link');
    // Inline SVG arrow, never an emoji glyph (repo rule), and never announced.
    const arrow = link.querySelector('svg');
    expect(arrow).not.toBeNull();
    expect(arrow!.getAttribute('aria-hidden')).toBe('true');
  });

  it('lets a long channel name truncate instead of widening the whole page', async () => {
    // Regression, introduced by this very restyle and caught by measuring a real 420px
    // render: a grid track and a flex item both default their minimum size to MIN-CONTENT,
    // so one long name refused to shrink and pushed the card 106px past the window edge.
    // `ellipsis` can never fire until both floors are lifted, so both are pinned here.
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        allowedChannels: [
          channel({ handle: 'longone', displayName: 'A Very Very Very Long Channel Name' }),
        ],
      }),
    );
    render(<BlockPage />);
    await flush();

    const link = screen.getByRole('link', { name: 'A Very Very Very Long Channel Name' });
    const label = link.querySelector('span') as HTMLElement;
    expect(label.style.textOverflow).toBe('ellipsis');
    expect(label.style.minWidth).toBe('0px');

    const list = link.closest('ul') as HTMLElement;
    expect(list.style.gridTemplateColumns).toBe('minmax(0, 1fr)');
  });

  it('keeps the accessible name to the channel name, so the arrow is not read out', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        allowedChannels: [channel({ channelId: 'UC999', displayName: 'Other Channel' })],
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.getByRole('link', { name: 'Other Channel' })).toBeTruthy();
  });

  it('still refuses to build a link for an unsafe identifier', async () => {
    // The https-only guard is unchanged by the restyle: prove it still holds here, not only
    // in channelHomeUrl's own unit tests.
    setTarget(TARGET);
    sendMessageMock.mockResolvedValueOnce(
      makeContext({
        domain: 'youtube.com',
        allowedChannels: [
          channel({ displayName: 'Nameless Channel' }),
          channel({ handle: 'evil/../other', displayName: 'Sneaky' }),
        ],
      }),
    );
    render(<BlockPage />);
    await flush();

    expect(screen.queryByText('Nameless Channel')).toBeNull();
    expect(screen.getByRole('link', { name: 'Sneaky' }).getAttribute('href')).toMatch(
      /^https:\/\/www\.youtube\.com\/@[^/]+$/,
    );
  });
});

describe('the redirect-loop backstop', () => {
  /**
   * QA R2 (2026-09-20): the block page was told ALLOW for a URL DNR had just redirected,
   * bounced to the site, got redirected back, and did it 217 times in 8 seconds until the
   * renderer crashed. The cause is fixed; this is the structural guarantee that the next
   * invariant break shows the user an error instead of eating their CPU.
   */
  beforeEach(() => {
    window.sessionStorage.clear();
    setTarget(TARGET);
  });

  it('sends the user on the first time, because that is the normal case', async () => {
    sendMessageMock.mockResolvedValue(makeContext({ decision: { type: 'ALLOW' } }));
    render(<BlockPage />);
    await flush();

    expect(replaceMock).toHaveBeenCalledWith(TARGET);
  });

  it('stops instead of bouncing the same target a second time', async () => {
    sendMessageMock.mockResolvedValue(makeContext({ decision: { type: 'ALLOW' } }));

    render(<BlockPage />);
    await flush();
    cleanup();
    replaceMock.mockClear();

    // The loop: the site redirected us straight back to the same target.
    render(<BlockPage />);
    await flush();

    expect(replaceMock).not.toHaveBeenCalled();
  });

  it('says plainly that Nudge is at fault, and still offers the link', async () => {
    sendMessageMock.mockResolvedValue(makeContext({ decision: { type: 'ALLOW' } }));
    render(<BlockPage />);
    await flush();
    cleanup();

    render(<BlockPage />);
    await flush();

    expect(screen.getByText(/internal error/i)).toBeDefined();
    const link = screen.getByText(TARGET) as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe(TARGET);
  });

  it('logs the loop so it is visible in devtools, naming the target', async () => {
    const errors: string[] = [];
    const original = console.error;
    console.error = (...args: unknown[]) => errors.push(String(args[0]));
    try {
      sendMessageMock.mockResolvedValue(makeContext({ decision: { type: 'ALLOW' } }));
      render(<BlockPage />);
      await flush();
      cleanup();
      render(<BlockPage />);
      await flush();
    } finally {
      console.error = original;
    }

    expect(errors.some((line) => line.includes('redirect loop detected'))).toBe(true);
    expect(errors.some((line) => line.includes(TARGET))).toBe(true);
  });

  it('a real block clears the history, so a later pause-and-continue still works', async () => {
    // Bounce once, then get genuinely blocked, then bounce again later: that is an ordinary
    // day and must not be mistaken for a loop.
    sendMessageMock.mockResolvedValue(makeContext({ decision: { type: 'ALLOW' } }));
    render(<BlockPage />);
    await flush();
    cleanup();

    sendMessageMock.mockResolvedValue(makeContext());
    render(<BlockPage />);
    await flush();
    cleanup();

    replaceMock.mockClear();
    sendMessageMock.mockResolvedValue(makeContext({ decision: { type: 'ALLOW' } }));
    render(<BlockPage />);
    await flush();

    expect(replaceMock).toHaveBeenCalledWith(TARGET);
  });
});

describe('the allowed-channel link hover state', () => {
  /**
   * QA R3: the hover background and border were dead because the anchor set `background`
   * and `border` INLINE, and an inline declaration outranks any class rule, so
   * `.nudge-channel-link:hover` could never win. It looked like hover worked, because the
   * arrow nudge (a transform on a child) was unaffected and still moved.
   *
   * Asserted as "the inline style does not claim these properties", which is the thing that
   * has to stay true; jsdom applies no stylesheet, so the hover itself cannot be observed
   * here and asserting a computed colour would be theatre.
   */
  it('leaves background and border to the stylesheet so :hover can win', async () => {
    setTarget(TARGET);
    sendMessageMock.mockResolvedValue(
      makeContext({
        allowedChannels: [channel({ handle: 'veritasium', displayName: 'Veritasium' })],
      }),
    );
    render(<BlockPage />);
    await flush();

    const link = screen.getByRole('link', { name: 'Veritasium' }) as HTMLAnchorElement;
    expect(link.className).toContain('nudge-channel-link');
    expect(link.style.background).toBe('');
    expect(link.style.backgroundColor).toBe('');
    expect(link.style.border).toBe('');
    expect(link.style.borderColor).toBe('');
  });
});
