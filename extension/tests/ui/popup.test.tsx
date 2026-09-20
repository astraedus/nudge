// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PopupState } from '../../src/core/protocol';
import { newSiteRule } from '../../src/core/settingsSchema';
import { Popup } from '../../src/entrypoints/popup/Popup';

let sendMessageMock: ReturnType<typeof vi.fn>;

function makeState(overrides: Partial<PopupState> = {}): PopupState {
  return {
    globalEnabled: true,
    todayTotalSeconds: 600,
    currentDomain: 'youtube.com',
    currentRule: null,
    currentRemainingMs: null,
    currentUsageSeconds: 0,
    currentMode: null,
    currentApplies: false,
    currentGrayscale: false,
    currentFeatureSummary: null,
    ...overrides,
  };
}

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

describe('Popup — current-site status line', () => {
  it('shows "Not blocked" when there is no rule for the domain', async () => {
    sendMessageMock.mockResolvedValue(makeState({ currentRule: null, currentMode: null }));
    render(<Popup />);
    await flush();
    expect(screen.getByText('Not blocked')).toBeDefined();
  });

  it('shows "Allowed · Xm left" for an ALLOW rule under budget', async () => {
    const rule = newSiteRule({ domain: 'youtube.com', mode: 'ALLOW', createdAt: 0 });
    sendMessageMock.mockResolvedValue(
      makeState({
        currentRule: rule,
        currentMode: 'ALLOW',
        currentApplies: false,
        currentRemainingMs: 12 * 60_000,
      }),
    );
    render(<Popup />);
    await flush();
    expect(screen.getByText('Allowed · 12m left')).toBeDefined();
  });

  it('shows the block mode label when the rule applies now', async () => {
    const rule = newSiteRule({ domain: 'youtube.com', mode: 'DELAY', createdAt: 0 });
    sendMessageMock.mockResolvedValue(
      makeState({ currentRule: rule, currentMode: 'DELAY', currentApplies: true }),
    );
    render(<Popup />);
    await flush();
    expect(screen.getByText('Delay')).toBeDefined();
  });

  it('shows "Blocked · limit reached" for an ALLOW rule whose budget ran out', async () => {
    const rule = newSiteRule({ domain: 'youtube.com', mode: 'ALLOW', createdAt: 0 });
    sendMessageMock.mockResolvedValue(
      makeState({
        currentRule: rule,
        currentMode: 'ALLOW',
        currentApplies: true,
        currentRemainingMs: 0,
      }),
    );
    render(<Popup />);
    await flush();
    expect(screen.getByText('Blocked · limit reached')).toBeDefined();
  });

  it('shows the feature summary line when one is provided', async () => {
    const rule = newSiteRule({ domain: 'youtube.com', mode: 'ALLOW', createdAt: 0 });
    sendMessageMock.mockResolvedValue(
      makeState({
        currentRule: rule,
        currentMode: 'ALLOW',
        currentFeatureSummary: 'Shorts: Delay 15s · Comments hidden',
      }),
    );
    render(<Popup />);
    await flush();
    expect(screen.getByText('Shorts: Delay 15s · Comments hidden')).toBeDefined();
  });
});

describe('Popup — grayscale quick toggle', () => {
  it('sends SET_GRAYSCALE for the current domain when toggled', async () => {
    sendMessageMock.mockImplementation((request: { type: string }) => {
      if (request.type === 'GET_POPUP_STATE') return Promise.resolve(makeState({ currentGrayscale: false }));
      return Promise.resolve({ ok: true });
    });
    render(<Popup />);
    await flush();

    fireEvent.click(screen.getByLabelText('Grayscale this site'));
    await flush();

    expect(sendMessageMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'SET_GRAYSCALE', domain: 'youtube.com', grayscale: true }),
    );
  });

  it('reflects currentGrayscale as the toggle state', async () => {
    sendMessageMock.mockResolvedValue(makeState({ currentGrayscale: true }));
    render(<Popup />);
    await flush();

    expect((screen.getByLabelText('Grayscale this site') as HTMLInputElement).checked).toBe(true);
  });

  it('surfaces a returned Commitment Lock challenge instead of applying the change silently', async () => {
    const challenge = 'abcdefghijkl';
    sendMessageMock.mockImplementation((request: { type: string }) => {
      if (request.type === 'GET_POPUP_STATE') return Promise.resolve(makeState({ currentGrayscale: true }));
      return Promise.resolve({ ok: false, challenge, reason: 'challenge-required' });
    });
    render(<Popup />);
    await flush();

    // Turning grayscale OFF is a weakening — the worker gates it.
    fireEvent.click(screen.getByLabelText('Grayscale this site'));
    await flush();

    expect(screen.getByTestId('challenge-code')).toBeDefined();
  });

  it('retries with the typed challengeResponse on submit', async () => {
    const challenge = 'abcdefghijkl';
    let grayscaleCalls = 0;
    sendMessageMock.mockImplementation((request: { type: string; challengeResponse?: string }) => {
      if (request.type === 'GET_POPUP_STATE') return Promise.resolve(makeState({ currentGrayscale: true }));
      grayscaleCalls += 1;
      if (request.challengeResponse === undefined) {
        return Promise.resolve({ ok: false, challenge, reason: 'challenge-required' });
      }
      return Promise.resolve({ ok: true });
    });
    render(<Popup />);
    await flush();

    fireEvent.click(screen.getByLabelText('Grayscale this site'));
    await flush();

    fireEvent.change(screen.getByLabelText('Unlock code'), { target: { value: challenge } });
    fireEvent.click(screen.getByRole('button', { name: 'Unlock' }));
    await flush();

    expect(grayscaleCalls).toBe(2);
    expect(screen.queryByTestId('challenge-code')).toBeNull();
  });
});
