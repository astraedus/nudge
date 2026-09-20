// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';

import { createChannelObserver } from '../../src/content/channelObserver';
import type { ChannelConfig } from '../../src/content/channelFilter';
import { SETTLE_MS, channelKey } from '../../src/core/channelFreshness';
import type { ChannelObservation } from '../../src/core/channels';
import {
  REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID,
  REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE,
  WATCH_DOM_ONLY_HTML,
  WATCH_FIXTURE_VIDEO_ID,
  WATCH_ID_INLINE_HANDLE_DOM_HTML,
  WATCH_NOTHING_HTML,
} from './fixtures/watchPage';

/**
 * The observer is the one content-script path that can WRITE the user's settings, so what is
 * under test here is entirely about restraint: it must stay silent while the settle window
 * is open (a stale channel merged into a stored entry would be the documented P0 made
 * permanent), silent when the observation cannot teach anything, and silent on a repeat.
 *
 * What it reports is asserted as the message a user's list would actually learn from, never
 * as "the function was called".
 */

const BOTH_AXES_URL = `https://www.youtube.com/watch?v=${WATCH_FIXTURE_VIDEO_ID}`;
/** The DOM-only fixture carries no inline data, so nothing can vouch for this video id. */
const DOM_ONLY_URL = 'https://www.youtube.com/watch?v=domonlyvideo01';
const DOM_ONLY_CHANNEL_ID = 'UCdomonlystandard0000006';

function config(overrides: Partial<ChannelConfig> = {}): ChannelConfig {
  return {
    enabled: true,
    siteMode: 'ALLOW',
    siteApplies: false,
    siteDelaySeconds: 15,
    grayscale: false,
    youtube: {
      channelMode: 'WHITELIST',
      channels: [],
      channelBlockMode: 'DELAY',
      channelDelaySeconds: 15,
      disableAutoplay: false,
    },
    ...overrides,
  };
}

function load(html: string): Document {
  document.body.innerHTML = html;
  return document;
}

/** Collects what the observer would have sent to the worker. */
function recorder(): { sent: ChannelObservation[]; report: (o: ChannelObservation) => void } {
  const sent: ChannelObservation[] = [];
  return { sent, report: (observation) => sent.push(observation) };
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('reporting a confirmed channel', () => {
  it('reports both identifiers and the author name from one watch page', () => {
    const { sent, report } = recorder();
    const doc = load(WATCH_ID_INLINE_HANDLE_DOM_HTML);

    createChannelObserver(report).observe(doc, config(), { url: BOTH_AXES_URL });

    expect(sent).toEqual([
      {
        channelId: REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID,
        // Sent exactly as detected, leading '@' and all — normalising it is the worker's job,
        // and doing it in two places is how the two spellings drift apart.
        handle: `@${REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE}`,
        displayName: 'Veritasium',
      },
    ]);
  });

  it('reports only once however many times the page is re-checked', () => {
    const { sent, report } = recorder();
    const doc = load(WATCH_ID_INLINE_HANDLE_DOM_HTML);
    const observer = createChannelObserver(report);

    // What `refresh()` really does: a mutation burst plus five settle timers per navigation.
    for (let i = 0; i < 8; i += 1) observer.observe(doc, config(), { url: BOTH_AXES_URL });

    expect(sent).toHaveLength(1);
  });

  it('still reports a DIFFERENT channel seen later in the same page load', () => {
    const { sent, report } = recorder();
    const observer = createChannelObserver(report);

    observer.observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), config(), { url: BOTH_AXES_URL });
    observer.observe(load(WATCH_DOM_ONLY_HTML), config(), {
      url: DOM_ONLY_URL,
      msSinceNav: SETTLE_MS + 1,
    });

    expect(sent).toHaveLength(2);
    expect(sent[1]?.channelId).toBe(DOM_ONLY_CHANNEL_ID);
  });

  it('gives a fresh page load a fresh memory', () => {
    const { sent, report } = recorder();
    const doc = load(WATCH_ID_INLINE_HANDLE_DOM_HTML);

    createChannelObserver(report).observe(doc, config(), { url: BOTH_AXES_URL });
    createChannelObserver(report).observe(doc, config(), { url: BOTH_AXES_URL });

    expect(sent).toHaveLength(2);
  });
});

describe('the settle window', () => {
  /**
   * The reverse-direction P0, one layer deeper. For seconds after a watch -> watch hop the
   * byline can still name the PREVIOUS video's channel; showing an interstitial off that is
   * bad for three seconds, but PERSISTING it would weld a stale identifier into the user's
   * list forever. Nothing may be reported until the detection is CONFIRMED.
   */
  const SETTLING_OPTIONS = {
    url: DOM_ONLY_URL,
    // The channel we were confident about before the hop is the same one being detected now,
    // so nothing can prove the byline has re-rendered yet.
    previousKey: channelKey({ channelId: DOM_ONLY_CHANNEL_ID, handle: null }),
    msSinceNav: 0,
  };

  it('reports nothing while the detection is still settling', () => {
    const { sent, report } = recorder();
    const doc = load(WATCH_DOM_ONLY_HTML);

    createChannelObserver(report).observe(doc, config(), SETTLING_OPTIONS);

    expect(sent).toEqual([]);
  });

  it('reports once the backstop has elapsed and the detection is accepted', () => {
    const { sent, report } = recorder();
    const doc = load(WATCH_DOM_ONLY_HTML);

    createChannelObserver(report).observe(doc, config(), {
      ...SETTLING_OPTIONS,
      msSinceNav: SETTLE_MS + 1,
    });

    expect(sent).toHaveLength(1);
    expect(sent[0]?.channelId).toBe(DOM_ONLY_CHANNEL_ID);
  });
});

describe('staying quiet when there is nothing to learn', () => {
  it('says nothing when the channel could not be identified at all', () => {
    const { sent, report } = recorder();

    createChannelObserver(report).observe(load(WATCH_NOTHING_HTML), config(), {
      url: BOTH_AXES_URL,
    });

    expect(sent).toEqual([]);
  });

  it('says nothing for a lone identifier with no name attached', () => {
    // One identifier alone can teach an entry nothing: whatever entry it matches was matched
    // BY that identifier, so the entry already holds it.
    const { sent, report } = recorder();
    const doc = load(`
      <div id="page-manager">
        <ytd-watch-flexy>
          <ytd-channel-name id="channel-name">
            <a class="yt-formatted-string" href="/@namelesschannel"></a>
          </ytd-channel-name>
        </ytd-watch-flexy>
      </div>
    `);

    createChannelObserver(report).observe(doc, config(), { url: BOTH_AXES_URL });

    expect(sent).toEqual([]);
  });

  it('says nothing on a page that is not a watch page', () => {
    const { sent, report } = recorder();

    createChannelObserver(report).observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), config(), {
      url: 'https://www.youtube.com/feed/subscriptions',
    });

    expect(sent).toEqual([]);
  });

  it('says nothing while the channel lists are switched off', () => {
    // No list means nothing an observation could enrich, so there is no reason to spend a
    // message, let alone a settings write.
    const { sent, report } = recorder();
    const off = config({
      youtube: {
        channelMode: 'OFF',
        channels: [],
        channelBlockMode: 'DELAY',
        channelDelaySeconds: 15,
        disableAutoplay: false,
      },
    });

    createChannelObserver(report).observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), off, {
      url: BOTH_AXES_URL,
    });

    expect(sent).toEqual([]);
  });

  it('says nothing while Nudge is switched off entirely', () => {
    const { sent, report } = recorder();

    createChannelObserver(report).observe(
      load(WATCH_ID_INLINE_HANDLE_DOM_HTML),
      config({ enabled: false }),
      { url: BOTH_AXES_URL },
    );

    expect(sent).toEqual([]);
  });
});
