// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { holdMediaPaused as startHold, pauseMedia } from '../../src/content/overlay';
import type { MediaHold } from '../../src/content/overlay';

/**
 * Live QA run 51 (2026-09-20): the Hard Block overlay was on screen while the video
 * underneath paused at +2.0s and was playing again by +2.5s, unmuted, for the next twelve
 * seconds. YouTube's own autoplay resumes the player after a one-shot pause, so the
 * interstitial was a lid over a running video and the audio kept playing.
 *
 * jsdom has no real playback, so `play()` is stubbed and "paused" is asserted through the
 * calls the hold makes, what is under test is the WIRING (does a resume get answered),
 * which is exactly the part that was missing.
 */

interface FakeMedia extends HTMLElement {
  pause: () => void;
}

function addVideo(): { el: FakeMedia; pause: ReturnType<typeof vi.fn> } {
  const el = document.createElement('video') as FakeMedia;
  const pause = vi.fn();
  el.pause = pause;
  document.body.appendChild(el);
  return { el, pause };
}

/**
 * Every hold registers CAPTURING listeners on the shared `document`, so a hold left running
 * by one case keeps answering events in the next one. (Found the honest way: three cases
 * failed with "called 2 times" until the leak was released.)
 */
const holds: MediaHold[] = [];

function holdMediaPaused(doc: Document): MediaHold {
  const hold = startHold(doc);
  holds.push(hold);
  return hold;
}

beforeEach(() => {
  document.body.replaceChildren();
});

afterEach(() => {
  for (const hold of holds.splice(0)) hold.release();
});

describe('holding media paused while a gate overlay is up', () => {
  it('pauses what is already playing the moment the hold starts', () => {
    const { pause } = addVideo();

    holdMediaPaused(document);

    expect(pause).toHaveBeenCalled();
  });

  it('re-pauses a video that starts itself again behind the overlay', () => {
    const { el, pause } = addVideo();
    holdMediaPaused(document);
    pause.mockClear();

    // What YouTube's autoplay does a beat after our first pause.
    el.dispatchEvent(new Event('play'));

    expect(pause).toHaveBeenCalledTimes(1);
  });

  it('answers `playing` too, not only `play`', () => {
    const { el, pause } = addVideo();
    holdMediaPaused(document);
    pause.mockClear();

    el.dispatchEvent(new Event('playing'));

    expect(pause).toHaveBeenCalledTimes(1);
  });

  it('holds a player that YouTube swaps in AFTER the overlay went up', () => {
    // The listener is capturing and on the document precisely so a replaced player is still
    // covered, `play` does not bubble, so a per-element listener would miss this.
    holdMediaPaused(document);
    const { el, pause } = addVideo();

    el.dispatchEvent(new Event('play'));

    expect(pause).toHaveBeenCalled();
  });

  it('lets media play again once the overlay comes down', () => {
    const { el, pause } = addVideo();
    const hold = holdMediaPaused(document);

    hold.release();
    pause.mockClear();
    el.dispatchEvent(new Event('play'));

    // A hold that outlived its interstitial would leave the page permanently un-playable
    // with nothing on screen to explain why.
    expect(pause).not.toHaveBeenCalled();
  });

  it('survives a player whose pause() throws', () => {
    const el = document.createElement('video') as FakeMedia;
    el.pause = () => {
      throw new Error('embed refused');
    };
    document.body.appendChild(el);

    expect(() => holdMediaPaused(document)).not.toThrow();
    expect(() => el.dispatchEvent(new Event('play'))).not.toThrow();
  });

  it('pauses audio as well as video', () => {
    const el = document.createElement('audio') as FakeMedia;
    const pause = vi.fn();
    el.pause = pause;
    document.body.appendChild(el);

    pauseMedia(document);

    expect(pause).toHaveBeenCalled();
  });
});
