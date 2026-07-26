// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BlockContext } from '../../src/core/protocol';
import type { BlockDecision } from '../../src/core/types';
import { formatNextPass } from '../../src/ui/format';
import { BlockPage, isNavigableTarget } from '../../src/entrypoints/blocked/BlockPage';

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
    ...overrides,
  };
}

let sendMessageMock: ReturnType<typeof vi.fn>;
let replaceMock: ReturnType<typeof vi.fn>;

// jsdom's real `window.location` has a non-configurable, non-writable `replace` method
// (can't be spied on), so the block page's own navigation calls are verified against a
// full stand-in object instead. Vitest's jsdom environment defines `location` as a plain
// accessor on `window`, so whole-object reassignment (not `Object.defineProperty`) is what
// actually works here.
/** The extension page the DNR rule redirects to. */
const BLOCKED_PAGE_URL = 'chrome-extension://nudgeid/blocked.html';

/**
 * Mirrors production byte-for-byte: the DNR rule appends the ORIGINAL url VERBATIM as the
 * last thing in the address (`regexSubstitution` cannot percent-encode), so the target
 * routinely carries its own `?`, `&` and `#`. Encoding it here would have tested a contract
 * the extension never actually produces.
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
    // eslint-disable-next-line no-await-in-loop
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
