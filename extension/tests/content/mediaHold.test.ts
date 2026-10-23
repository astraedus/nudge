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
  /** jsdom's HTMLElement has no media properties; the hold sets this one. */
  muted: boolean;
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
  for (const hold of holds.splice(0)) hold.release({ resume: false });
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

    hold.release({ resume: false });
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

describe('muting while the overlay is up', () => {
  /**
   * QA R2: 1 of 5 cold loads still produced 0.68s of audible playback. The player is
   * already running when the overlay mounts, so there is a window between "the page starts
   * sound" and "our first pause lands" that no listener can close, because the sound has
   * already left. Muting is synchronous, so that window is silent even when the pause is a
   * beat late.
   */
  function addVideoWithMute(muted = false): { el: FakeMedia; pause: ReturnType<typeof vi.fn> } {
    const made = addVideo();
    made.el.muted = muted;
    return made;
  }

  it('mutes immediately, so the gap before the pause lands is silent', () => {
    const { el } = addVideoWithMute();

    holdMediaPaused(document);

    expect(el.muted).toBe(true);
  });

  it('mutes a player that starts up behind the overlay', () => {
    holdMediaPaused(document);
    const { el } = addVideoWithMute();

    el.dispatchEvent(new Event('play'));

    expect(el.muted).toBe(true);
  });

  it('un-mutes on release, so the page is usable again', () => {
    const { el } = addVideoWithMute(false);
    const hold = holdMediaPaused(document);

    hold.release({ resume: false });

    expect(el.muted).toBe(false);
  });

  it('leaves a video the USER had muted still muted', () => {
    // Restoring blindly to un-muted would be its own small bug: the user chose silence.
    const { el } = addVideoWithMute(true);
    const hold = holdMediaPaused(document);

    hold.release({ resume: false });

    expect(el.muted).toBe(true);
  });
});

describe('releasing the hold', () => {
  /**
   * Live QA 2026-10-23 (B1, E1, G1): after a Delay countdown completed, or after the channel
   * check settled on an ALLOWED channel, the video under the hold stayed paused at 0:00. The
   * hold had stopped it and nothing ever started it again, so a cleared gate still read as a
   * block. A release that CLEARS the gate resumes what the hold stopped; a release because the
   * user is leaving does not.
   */
  function addPlayingVideo(paused: boolean): { el: FakeMedia; play: ReturnType<typeof vi.fn> } {
    const { el } = addVideo();
    const play = vi.fn(() => Promise.resolve());
    (el as unknown as { play: unknown }).play = play;
    Object.defineProperty(el, 'paused', { configurable: true, get: () => paused });
    return { el, play };
  }

  it('resumes a video that was playing when the hold started', () => {
    const { play } = addPlayingVideo(false);
    const hold = holdMediaPaused(document);

    hold.release({ resume: true });

    expect(play).toHaveBeenCalledTimes(1);
  });

  it('resumes a video that tried to start while the hold stood', () => {
    // The SPA hop: the overlay is up before YouTube's player starts the new video.
    const hold = holdMediaPaused(document);
    const { el, play } = addPlayingVideo(true);
    el.dispatchEvent(new Event('play'));

    hold.release({ resume: true });

    expect(play).toHaveBeenCalledTimes(1);
  });

  it('does not start a video the user had paused themselves', () => {
    const { play } = addPlayingVideo(true);
    const hold = holdMediaPaused(document);

    hold.release({ resume: true });

    expect(play).not.toHaveBeenCalled();
  });

  it('does not resume anything when the user is leaving', () => {
    const { play } = addPlayingVideo(false);
    const hold = holdMediaPaused(document);

    hold.release({ resume: false });

    expect(play).not.toHaveBeenCalled();
  });

  it('skips a player YouTube removed while the hold stood', () => {
    const { el, play } = addPlayingVideo(false);
    const hold = holdMediaPaused(document);
    el.remove();

    hold.release({ resume: true });

    expect(play).not.toHaveBeenCalled();
  });

  it('restores the muted state before it resumes, so it comes back audible', () => {
    const { el, play } = addPlayingVideo(false);
    el.muted = false;
    let mutedAtPlay: boolean | null = null;
    play.mockImplementation(() => {
      mutedAtPlay = el.muted;
      return Promise.resolve();
    });
    const hold = holdMediaPaused(document);

    hold.release({ resume: true });

    expect(mutedAtPlay).toBe(false);
  });

  it('survives an autoplay refusal', async () => {
    const { play } = addPlayingVideo(false);
    play.mockImplementation(() => Promise.reject(new Error('NotAllowedError')));
    const hold = holdMediaPaused(document);

    expect(() => hold.release({ resume: true })).not.toThrow();
    await Promise.resolve();
  });

  it('resumes only once even if released twice', () => {
    const { play } = addPlayingVideo(false);
    const hold = holdMediaPaused(document);

    hold.release({ resume: true });
    hold.release({ resume: true });

    expect(play).toHaveBeenCalledTimes(1);
  });
});
