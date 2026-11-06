// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { GrantResult, RepeatPauseState, Request } from '../../src/core/protocol';
import { initRepeatPause, REPEAT_PAUSE_OVERLAY_ID } from '../../src/content/repeatPause';
import type { RepeatPauseController } from '../../src/content/repeatPause';
import { REPEAT_PAUSE_HOST_TAG } from '../../src/content/repeatPauseHost';

/**
 * Layer: the controller, under jsdom with fake timers and a scripted worker. The worker's own
 * answers are tested in `tests/background/repeatPauseRouter.test.ts`, and that this renders
 * correctly on a real page under a strict CSP is `e2e/repeatPause.spec.ts`.
 */

const START = new Date('2026-11-06T10:00:00Z').getTime();
const MIN = 60_000;

let worker: {
  state: RepeatPauseState;
  grants: (GrantResult | Error)[];
  leave: { ok: boolean } | Error;
  refuseFetch: boolean;
};
let sent: Request[];
let navigated: string[];
let hidden: boolean;
let controllers: RepeatPauseController[];

function active(overrides: Partial<RepeatPauseState & { active: true }> = {}): RepeatPauseState {
  return {
    active: true,
    mode: 'DELAY',
    delaySeconds: 3,
    everyMinutes: 10,
    until: START + 10 * MIN,
    domain: 'news.test',
    ...overrides,
  };
}

async function send(request: Request): Promise<unknown> {
  sent.push(request);
  switch (request.type) {
    case 'GET_REPEAT_PAUSE':
      if (worker.refuseFetch) throw new Error('Could not establish connection');
      return worker.state;
    case 'COMPLETE_PAUSE': {
      const grant = worker.grants.shift() ?? { ok: true, until: Date.now() + 10 * MIN };
      if (grant instanceof Error) throw grant;
      return grant;
    }
    case 'LEAVE_TO_DASHBOARD':
      if (worker.leave instanceof Error) throw worker.leave;
      return worker.leave;
    default:
      return { ok: true };
  }
}

function start(): RepeatPauseController {
  const controller = initRepeatPause({
    doc: document,
    send: send as never,
    styles: '.nudge-repeat-pill {}',
    navigate: (url) => navigated.push(url),
  });
  controllers.push(controller);
  return controller;
}

async function advance(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
}

function setHidden(value: boolean): void {
  hidden = value;
  document.dispatchEvent(new Event('visibilitychange'));
}

function root(): ShadowRoot | null {
  return document.querySelector(REPEAT_PAUSE_HOST_TAG)?.shadowRoot ?? null;
}

function pillText(): string | null {
  return root()?.querySelector('.nudge-repeat-pill')?.textContent ?? null;
}

function overlay(): Element | null {
  return root()?.querySelector(`#${REPEAT_PAUSE_OVERLAY_ID}`) ?? null;
}

function sentOf(type: Request['type']): Request[] {
  return sent.filter((request) => request.type === type);
}

function addVideo(): { pause: ReturnType<typeof vi.fn>; play: ReturnType<typeof vi.fn> } {
  const element = document.createElement('video') as HTMLVideoElement;
  const pause = vi.fn();
  const play = vi.fn(async () => undefined);
  Object.assign(element, { pause, play });
  Object.defineProperty(element, 'paused', { configurable: true, value: false });
  document.body.append(element);
  return { pause, play };
}

/**
 * Run a fresh page until the pause is on screen: the grant ends at +1s, the next tick confirms and
 * mounts the overlay there, and this returns at +2s, so a 3s countdown has 2s left.
 */
async function reachPause(state = active({ until: START + 1000 })): Promise<void> {
  worker.state = state;
  start();
  await advance(0);
  await advance(2000);
}

const UNTIL_COUNTDOWN_ENDS = 2000;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(START);
  worker = { state: active(), grants: [], leave: { ok: true }, refuseFetch: false };
  sent = [];
  navigated = [];
  hidden = false;
  controllers = [];
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  document.body.innerHTML = '';
});

afterEach(() => {
  // Stopping is what removes the hold's capturing listeners from the shared document.
  for (const controller of controllers) controller.stop();
  Reflect.deleteProperty(document, 'hidden');
  Reflect.deleteProperty(document, 'fullscreenElement');
  Reflect.deleteProperty(document, 'exitFullscreen');
  history.replaceState(null, '', '/');
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('time left', () => {
  it('shows exactly how long is left, and counts down', async () => {
    worker.state = active({ until: START + 572_000 });
    start();
    await advance(0);
    expect(pillText()).toBe('Next pause in 9:32');
    await advance(2000);
    expect(pillText()).toBe('Next pause in 9:30');
  });

  it('is not announced every second, and never takes a click from the site', async () => {
    start();
    await advance(0);
    const pill = root()?.querySelector('.nudge-repeat-pill');
    expect(pill?.getAttribute('aria-live')).toBeNull();
    expect(pill?.getAttribute('role')).toBeNull();
    const host = document.querySelector(REPEAT_PAUSE_HOST_TAG) as HTMLElement;
    expect(host.style.getPropertyValue('pointer-events')).toBe('none');
  });

  it('adds nothing at all to a page where no repeat applies', async () => {
    worker.state = { active: false };
    start();
    await advance(1000);
    expect(document.querySelector(REPEAT_PAUSE_HOST_TAG)).toBeNull();
  });

  it('goes by the wall clock, so time away from the tab still counts', async () => {
    worker.state = active({ until: START + 5 * MIN });
    start();
    await advance(0);
    // Timers throttled in a background tab: the clock moved on, the ticks did not.
    vi.setSystemTime(START + 4 * MIN);
    await advance(1000);
    expect(pillText()).toBe('Next pause in 0:59');
  });
});

describe('when the pause comes due', () => {
  it('asks the worker once more first, because another tab may have renewed the grant', async () => {
    worker.state = active({ until: START + 1000 });
    start();
    await advance(0);
    const before = sentOf('GET_REPEAT_PAUSE').length;
    worker.state = active({ until: START + 10 * MIN });
    await advance(1000);
    expect(sentOf('GET_REPEAT_PAUSE').length).toBe(before + 1);
    expect(overlay()).toBeNull();
    expect(sentOf('PAUSE_SHOWN')).toEqual([]);
    expect(pillText()).toMatch(/^Next pause in 9:5/);
  });

  it('visible: the overlay comes up over the page, and the pill goes', async () => {
    await reachPause();
    expect(overlay()?.textContent).toContain('Still want news.test?');
    expect(pillText()).toBeNull();
  });

  it('no grant at all (until null) is due straight away', async () => {
    worker.state = active({ until: null });
    start();
    await advance(0);
    expect(overlay()).not.toBeNull();
  });

  it('uses the rule’s mode and length', async () => {
    await reachPause(active({ until: null, mode: 'BREATHING', delaySeconds: 20 }));
    expect(overlay()?.textContent).toContain('Take a breath');
  });

  it('counts as one block event per cycle, and a second cycle counts again', async () => {
    await reachPause();
    expect(sentOf('PAUSE_SHOWN')).toEqual([{ type: 'PAUSE_SHOWN', url: location.href }]);

    worker.grants = [{ ok: true, until: START + 6000 }];
    worker.state = active({ until: START + 6000 });
    await advance(UNTIL_COUNTDOWN_ENDS);
    expect(overlay()).toBeNull();
    await advance(3000);
    expect(overlay()).not.toBeNull();
    expect(sentOf('PAUSE_SHOWN')).toHaveLength(2);
  });

  it('stops the media under it right away', async () => {
    const video = addVideo();
    await reachPause();
    expect(video.pause).toHaveBeenCalled();
    expect(video.play).not.toHaveBeenCalled();
  });
});

describe('a tab that is not visible', () => {
  it('due and hidden: media held, no overlay, no block event', async () => {
    const video = addVideo();
    worker.state = active({ until: START + 1000 });
    start();
    await advance(0);
    hidden = true;
    await advance(2000);
    expect(video.pause).toHaveBeenCalled();
    expect(overlay()).toBeNull();
    expect(sentOf('PAUSE_SHOWN')).toEqual([]);
  });

  it('a pause cannot be served out in the background', async () => {
    await reachPause();
    setHidden(true);
    await advance(0);
    expect(overlay()).toBeNull();

    await advance(60_000);
    expect(sentOf('COMPLETE_PAUSE')).toEqual([]);

    setHidden(false);
    await advance(0);
    // The whole length again, not what was left of it.
    expect(overlay()?.querySelector('.nudge-overlay__count')?.textContent).toBe('3');
    expect(sentOf('COMPLETE_PAUSE')).toEqual([]);
  });

  it('coming back does not count as a second block event', async () => {
    await reachPause();
    setHidden(true);
    await advance(0);
    setHidden(false);
    await advance(0);
    expect(overlay()).not.toBeNull();
    expect(sentOf('PAUSE_SHOWN')).toHaveLength(1);
  });

  it('keeps holding media across the gap', async () => {
    const video = addVideo();
    await reachPause();
    setHidden(true);
    await advance(0);
    video.pause.mockClear();
    document.querySelector('video')?.dispatchEvent(new Event('play'));
    expect(video.pause).toHaveBeenCalled();
    expect(video.play).not.toHaveBeenCalled();
  });

  it('coming back after another tab renewed shows no pause, not even a flash', async () => {
    const video = addVideo();
    await reachPause();
    setHidden(true);
    await advance(0);

    worker.state = active({ until: Date.now() + 10 * MIN });
    hidden = false;
    document.dispatchEvent(new Event('visibilitychange'));
    expect(overlay()).toBeNull();
    await advance(0);
    expect(overlay()).toBeNull();
    expect(video.play).toHaveBeenCalled();
  });
});

describe('finishing the pause', () => {
  it('asks for the grant, takes the new time, and plays what it stopped', async () => {
    const video = addVideo();
    await reachPause();
    await advance(UNTIL_COUNTDOWN_ENDS);
    expect(sentOf('COMPLETE_PAUSE')).toEqual([{ type: 'COMPLETE_PAUSE', target: location.href }]);
    expect(overlay()).toBeNull();
    expect(video.play).toHaveBeenCalledTimes(1);
    expect(pillText()).toMatch(/^Next pause in 10:00$|^Next pause in 9:59$/);
  });

  it('an answer from before the grant cannot bring the pause back', async () => {
    let answerStale: (value: RepeatPauseState) => void = () => undefined;
    const stale = new Promise<RepeatPauseState>((resolve) => {
      answerStale = resolve;
    });
    await reachPause();
    // A request goes out and gets no answer yet. The countdown ends and the grant lands.
    worker.state = stale as unknown as RepeatPauseState;
    controllers[0]?.refresh();
    await advance(UNTIL_COUNTDOWN_ENDS);
    expect(overlay()).toBeNull();

    answerStale(active({ until: START }));
    await advance(0);
    expect(overlay()).toBeNull();
  });

  it('refused once: stays up, tries once more after a second, and that can succeed', async () => {
    worker.grants = [{ ok: false, until: 0, reason: 'busy' }];
    await reachPause();
    await advance(UNTIL_COUNTDOWN_ENDS);
    expect(overlay()).not.toBeNull();
    expect(sentOf('COMPLETE_PAUSE')).toHaveLength(1);

    await advance(1000);
    expect(sentOf('COMPLETE_PAUSE')).toHaveLength(2);
    expect(overlay()).toBeNull();
    expect(navigated).toEqual([]);
  });

  it.each([
    ['refused', { ok: false, until: 0, reason: 'hard-block' } as GrantResult],
    ['thrown', new Error('Could not establish connection')],
  ])('%s twice: never drops the overlay, and lets DNR decide', async (_label, failure) => {
    const video = addVideo();
    worker.grants = [failure, failure];
    await reachPause();
    await advance(UNTIL_COUNTDOWN_ENDS);
    expect(navigated).toEqual([]);
    await advance(1000);
    expect(navigated).toEqual([location.href]);
    expect(overlay()).not.toBeNull();
    expect(video.play).not.toHaveBeenCalled();
  });

  it('does not start a second pause while the first is waiting on its grant', async () => {
    worker.grants = [{ ok: false, until: 0 }];
    await reachPause();
    await advance(UNTIL_COUNTDOWN_ENDS);
    const first = overlay();
    expect(first).not.toBeNull();
    await advance(500);
    expect(overlay()).toBe(first);
    expect(root()?.querySelectorAll(`#${REPEAT_PAUSE_OVERLAY_ID}`)).toHaveLength(1);
  });
});

describe('another tab renews the grant', () => {
  it('while this tab is paused: comes down without a grant of its own, and media resumes', async () => {
    const video = addVideo();
    await reachPause(active({ until: START + 1000, delaySeconds: 60 }));
    expect(overlay()).not.toBeNull();

    worker.state = active({ until: Date.now() + 10 * MIN, delaySeconds: 60 });
    await advance(5000);
    expect(overlay()).toBeNull();
    expect(video.play).toHaveBeenCalled();
    expect(sentOf('COMPLETE_PAUSE')).toEqual([]);
  });

  it('while this tab waits on a refused grant: does not reload the page anyway', async () => {
    worker.grants = [{ ok: false, until: 0 }, { ok: false, until: 0 }];
    await reachPause(active({ until: START + 1000 }));
    worker.state = active({ until: Date.now() + 10 * MIN });
    await advance(UNTIL_COUNTDOWN_ENDS);
    controllers[0]?.refresh();
    await advance(0);
    expect(overlay()).toBeNull();
    await advance(1000);
    expect(navigated).toEqual([]);
  });
});

describe('the repeat stops applying', () => {
  it('clears the pill, the overlay and the host, and plays what it stopped', async () => {
    const video = addVideo();
    await reachPause(active({ until: START + 1000, delaySeconds: 60 }));
    worker.state = { active: false };
    await advance(5000);
    expect(document.querySelector(REPEAT_PAUSE_HOST_TAG)).toBeNull();
    expect(video.play).toHaveBeenCalled();
  });

  it('an in-site hop to an allowed page ends the pause in the same tab', async () => {
    await reachPause(active({ until: START + 1000, delaySeconds: 60 }));
    worker.state = { active: false };
    history.pushState(null, '', '/allowed-channel-video');
    await advance(1000);
    expect(sentOf('GET_REPEAT_PAUSE').at(-1)).toEqual({
      type: 'GET_REPEAT_PAUSE',
      url: location.href,
    });
    expect(overlay()).toBeNull();
  });

  it('a saved setting takes effect through refresh, without waiting for the poll', async () => {
    const controller = start();
    await advance(0);
    expect(pillText()).not.toBeNull();
    worker.state = { active: false };
    controller.refresh();
    await advance(0);
    expect(pillText()).toBeNull();
  });
});

describe('polling and failure', () => {
  it('asks again every 30 seconds while a grant is running', async () => {
    start();
    await advance(0);
    const before = sentOf('GET_REPEAT_PAUSE').length;
    await advance(29_000);
    expect(sentOf('GET_REPEAT_PAUSE').length).toBe(before);
    await advance(1000);
    expect(sentOf('GET_REPEAT_PAUSE').length).toBe(before + 1);
  });

  it('a failed fetch keeps the last known state, and tries again sooner', async () => {
    start();
    await advance(0);
    worker.refuseFetch = true;
    await advance(30_000);
    expect(pillText()).toMatch(/^Next pause in 9:/);

    const before = sentOf('GET_REPEAT_PAUSE').length;
    worker.refuseFetch = false;
    worker.state = { active: false };
    await advance(5000);
    expect(sentOf('GET_REPEAT_PAUSE').length).toBeGreaterThan(before);
    expect(pillText()).toBeNull();
  });

  it('a first fetch that fails shows nothing, and is retried', async () => {
    worker.refuseFetch = true;
    start();
    await advance(1000);
    expect(document.querySelector(REPEAT_PAUSE_HOST_TAG)).toBeNull();
    worker.refuseFetch = false;
    await advance(5000);
    expect(pillText()).not.toBeNull();
  });

  it('a nonsense answer counts as a failed fetch, not as "nothing applies"', async () => {
    start();
    await advance(0);
    worker.state = undefined as unknown as RepeatPauseState;
    await advance(30_000);
    expect(pillText()).toMatch(/^Next pause in 9:/);
  });

  it('a worker that cannot answer does not switch the pause off', async () => {
    worker.state = active({ until: START + 1000 });
    start();
    await advance(0);
    worker.refuseFetch = true;
    await advance(1000);
    await advance(0);
    expect(overlay()).not.toBeNull();
  });
});

describe('fullscreen', () => {
  it('leaves fullscreen when the pause comes up, and again if the site goes back in', async () => {
    const exit = vi.fn(async () => undefined);
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exit });
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => document.body,
    });
    await reachPause();
    expect(exit).toHaveBeenCalledTimes(1);

    document.dispatchEvent(new Event('fullscreenchange'));
    expect(exit).toHaveBeenCalledTimes(2);
  });

  it('does not touch fullscreen for the pill alone, and stays on top of it', async () => {
    const exit = vi.fn(async () => undefined);
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exit });
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => document.body,
    });
    const show = vi.fn();
    const proto = HTMLElement.prototype as unknown as Record<string, unknown>;
    proto.showPopover = show;
    proto.hidePopover = () => undefined;
    try {
      start();
      await advance(0);
      show.mockClear();
      document.dispatchEvent(new Event('fullscreenchange'));
      expect(show).toHaveBeenCalledTimes(1);
      expect(exit).not.toHaveBeenCalled();
    } finally {
      delete proto.showPopover;
      delete proto.hidePopover;
    }
  });

  it('a page with no popover API still gets its pill', async () => {
    start();
    await advance(0);
    expect(pillText()).not.toBeNull();
  });
});

describe('I changed my mind', () => {
  function bail(): void {
    const button = overlay()?.querySelector('.nudge-overlay__bail') as HTMLButtonElement;
    expect(button.textContent).toBe('I changed my mind');
    button.click();
  }

  it('logs the walk-away, leaves through the worker, and does not restart the media', async () => {
    const video = addVideo();
    await reachPause();
    bail();
    await advance(0);
    const types = sent.map((request) => request.type);
    expect(types.indexOf('WALKED_AWAY')).toBeLessThan(types.indexOf('LEAVE_TO_DASHBOARD'));
    expect(sentOf('WALKED_AWAY')).toEqual([{ type: 'WALKED_AWAY', target: location.href }]);
    expect(video.play).not.toHaveBeenCalled();
    expect(sentOf('COMPLETE_PAUSE')).toEqual([]);
    expect(navigated).toEqual([]);
  });

  it('nothing brings the pause back while the tab is on its way out', async () => {
    await reachPause();
    bail();
    await advance(0);
    const first = overlay();
    await advance(10_000);
    expect(overlay()).toBe(first);
    expect(sentOf('PAUSE_SHOWN')).toHaveLength(1);
  });

  it('a renewal from another tab does not restart the media of a tab that is leaving', async () => {
    const video = addVideo();
    await reachPause();
    bail();
    await advance(0);
    const first = overlay();
    worker.state = active({ until: Date.now() + 10 * MIN });
    controllers[0]?.refresh();
    await advance(0);
    expect(overlay()).toBe(first);
    expect(video.play).not.toHaveBeenCalled();
  });

  it.each([
    ['the worker cannot leave', { ok: false }],
    ['the message fails', new Error('Could not establish connection')],
  ])('%s: DNR decides rather than leaving the user stuck', async (_label, leave) => {
    worker.leave = leave;
    await reachPause();
    bail();
    await advance(0);
    expect(navigated).toEqual([location.href]);
  });

  it('Back from the dashboard, restored from bfcache, gets a working pause again', async () => {
    await reachPause();
    bail();
    await advance(0);
    const frozen = overlay();

    const event = new Event('pageshow');
    Object.defineProperty(event, 'persisted', { value: true });
    window.dispatchEvent(event);
    await advance(0);
    expect(overlay()).not.toBeNull();
    expect(overlay()).not.toBe(frozen);
    expect(overlay()?.querySelector('.nudge-overlay__count')?.textContent).toBe('3');

    await advance(3000);
    expect(sentOf('COMPLETE_PAUSE')).toHaveLength(1);
  });

  it('an ordinary pageshow changes nothing', async () => {
    await reachPause();
    const first = overlay();
    window.dispatchEvent(new Event('pageshow'));
    await advance(0);
    expect(overlay()).toBe(first);
  });
});

describe('living on a page we did not write', () => {
  it('puts itself back if the site removes it', async () => {
    start();
    await advance(0);
    document.querySelector(REPEAT_PAUSE_HOST_TAG)?.remove();
    await advance(1000);
    expect(pillText()).toMatch(/^Next pause in /);
  });

  it('a site removing the host cannot remove the pause', async () => {
    await reachPause(active({ until: START + 1000, delaySeconds: 60 }));
    document.querySelector(REPEAT_PAUSE_HOST_TAG)?.remove();
    await advance(1000);
    expect(overlay()).not.toBeNull();
  });

  it('a second copy of the script replaces the first, leaving one host', async () => {
    start();
    await advance(0);
    controllers[0]?.stop();
    start();
    await advance(0);
    expect(document.querySelectorAll(REPEAT_PAUSE_HOST_TAG)).toHaveLength(1);
    expect(pillText()).not.toBeNull();
  });

  it('removes a host a dead copy left behind', async () => {
    document.documentElement.append(document.createElement(REPEAT_PAUSE_HOST_TAG));
    start();
    await advance(0);
    expect(document.querySelectorAll(REPEAT_PAUSE_HOST_TAG)).toHaveLength(1);
  });

  it('stopping removes everything, ends the polling and does not resume media', async () => {
    const video = addVideo();
    await reachPause();
    const controller = controllers[0] as RepeatPauseController;
    controller.stop();
    expect(document.querySelector(REPEAT_PAUSE_HOST_TAG)).toBeNull();
    expect(video.play).not.toHaveBeenCalled();

    const count = sent.length;
    await advance(60_000);
    expect(sent.length).toBe(count);
  });

  it('a grant answering after the script stopped does nothing', async () => {
    let refuse: (value: GrantResult) => void = () => undefined;
    const pending = new Promise<GrantResult>((resolve) => {
      refuse = resolve;
    });
    worker.grants = [pending as unknown as GrantResult];
    await reachPause();
    await advance(UNTIL_COUNTDOWN_ENDS);
    expect(sentOf('COMPLETE_PAUSE')).toHaveLength(1);

    controllers[0]?.stop();
    refuse({ ok: false, until: 0 });
    await advance(5000);
    expect(document.querySelector(REPEAT_PAUSE_HOST_TAG)).toBeNull();
    expect(navigated).toEqual([]);
});
});

describe('another Nudge gate on the page', () => {
  function addGate(attrs: { id?: string; className?: string } = { id: 'nudge-gate' }): HTMLElement {
    const gate = document.createElement('div');
    if (attrs.id !== undefined) gate.id = attrs.id;
    if (attrs.className !== undefined) gate.className = attrs.className;
    document.body.append(gate);
    return gate;
  }

  const DUE = () => active({ until: START - 1000 });

  it.each([
    ['the channel gate', { id: 'nudge-gate' }],
    ['any other gate overlay', { id: 'nudge-reddit-gate', className: 'nudge-overlay' }],
  ])('does not mount over %s, and still holds the media', async (_name, attrs) => {
    const { pause } = addVideo();
    addGate(attrs);
    worker.state = DUE();
    start();
    await advance(2000);
    expect(overlay()).toBeNull();
    expect(pause).toHaveBeenCalled();
    expect(sentOf('PAUSE_SHOWN')).toEqual([]);
  });

  it('gives way the moment a gate arrives over its own overlay, with no tick in between', async () => {
    await reachPause();
    expect(overlay()).not.toBeNull();
    addGate();
    await advance(0);
    expect(overlay()).toBeNull();
  });

  it('does not count a second pause for one it gave way on', async () => {
    await reachPause();
    const gate = addGate();
    await advance(0);
    gate.remove();
    worker.state = DUE();
    await advance(5000);
    expect(overlay()).not.toBeNull();
    expect(sentOf('PAUSE_SHOWN')).toHaveLength(1);
  });

  it('hides the pill while a gate is up, and brings it back after', async () => {
    worker.state = active();
    start();
    await advance(0);
    expect(pillText()).not.toBeNull();
    const gate = addGate();
    await advance(0);
    expect(pillText()).toBeNull();
    gate.remove();
    await advance(1000);
    expect(pillText()).not.toBeNull();
  });

  describe('when it comes down', () => {
    it('never flashes a pause over a clock the gate just renewed', async () => {
      const { play } = addVideo();
      const gate = addGate();
      worker.state = DUE();
      start();
      await advance(2000);

      // The gate reports and comes down, and the first answers still predate the report.
      gate.remove();
      for (let beat = 0; beat < 2; beat += 1) {
        await advance(1000);
        expect(overlay()).toBeNull();
      }
      worker.state = active({ until: Date.now() + 10 * MIN });
      await advance(1000);

      expect(overlay()).toBeNull();
      expect(pillText()).not.toBeNull();
      expect(play).toHaveBeenCalled();
      expect(sentOf('PAUSE_SHOWN')).toEqual([]);
    });

    it('asks again at once, so a clock that did not exist yet shows without a long wait', async () => {
      const gate = addGate();
      worker.state = { active: false };
      start();
      await advance(1000);
      expect(pillText()).toBeNull();

      // The first answer after the gate came down beat the report to the worker.
      gate.remove();
      await advance(1000);
      expect(pillText()).toBeNull();

      worker.state = active({ until: Date.now() + 10 * MIN });
      await advance(1000);
      expect(pillText()).not.toBeNull();
    });

    it('still pauses if it really is due, since a gate is not a way out of one', async () => {
      const gate = addGate();
      worker.state = DUE();
      start();
      await advance(1000);
      gate.remove();
      await advance(5000);
      expect(overlay()).not.toBeNull();
    });

    it('asks once a tick while it waits, not once per answer', async () => {
      const gate = addGate();
      worker.state = DUE();
      start();
      await advance(1000);
      gate.remove();
      await advance(0);
      const before = sentOf('GET_REPEAT_PAUSE').length;
      await advance(2000);
      expect(sentOf('GET_REPEAT_PAUSE').length - before).toBeLessThanOrEqual(3);
    });
  });
});
