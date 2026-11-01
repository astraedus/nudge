// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

import { channelGateCopy } from '../../src/content/youtube';

/**
 * Since 2026-11-01 a whitelist's channels stay watchable past the daily limit, so a video
 * the site default still holds at that point is one the LIMIT is refusing. The copy has to
 * say that, and when it ends, not "YouTube is blocked".
 */

const bySite = (reason: 'not-listed' | 'unknown-channel') =>
  ({ action: 'BLOCK', mode: 'HARD_BLOCK', delaySeconds: 0, source: 'site-default', reason }) as const;

describe('channelGateCopy after the daily limit', () => {
  it.each(['not-listed', 'unknown-channel'] as const)(
    'names the limit and the midnight reset for a %s video',
    (reason) => {
      const copy = channelGateCopy(bySite(reason), { limitReached: true });
      expect(copy.title.toLowerCase()).toContain('out of youtube');
      expect(copy.subtitle.toLowerCase()).toContain('limit');
      expect(copy.subtitle.toLowerCase()).toContain('midnight');
      expect(copy.ruleLabel).toBe('YouTube');
    },
  );

  it("does not accuse the channel when Nudge couldn't identify it", () => {
    const copy = channelGateCopy(bySite('unknown-channel'), { limitReached: true });
    expect(copy.subtitle).toContain("couldn't identify");
  });

  it('keeps the old site-blocked copy when the limit is not the reason', () => {
    expect(channelGateCopy(bySite('not-listed')).title).toBe('This channel is off your list');
    expect(channelGateCopy(bySite('unknown-channel')).title).toBe('YouTube is blocked right now');
  });

  it('leaves channel-rule copy alone even with the limit spent', () => {
    const copy = channelGateCopy(
      { action: 'BLOCK', mode: 'DELAY', delaySeconds: 15, source: 'channel-rule', reason: 'listed' },
      { limitReached: true },
    );
    expect(copy.ruleLabel).toBe('YouTube channels');
  });
});
