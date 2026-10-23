// @vitest-environment jsdom
/**
 * `decidePageGate`: what the YouTube content script does with a PAGE (not a video) that the
 * network layer would redirect or deliberately let through.
 *
 * Phrased as the user's outcome per row. The one hard line is a FULL page load that DNR
 * should have redirected and did not: reloading it could loop forever, so it is only logged.
 */

import { describe, expect, it } from 'vitest';
import { decidePageGate, type PageGateAction } from '../../src/content/youtube';
import type { YoutubePageAccess } from '../../src/core/youtubeAccess';

const ACCESSES: readonly YoutubePageAccess[] = ['open', 'creator', 'carved', 'verify', 'blocked'];
const BOOLS = [true, false] as const;
const LISTED = [true, false, null] as const;

describe('the page gate', () => {
  it('never touches a page the rule lets through (open, creator, carved), in any circumstance', () => {
    for (const access of ['open', 'creator', 'carved'] as const) {
      for (const configIsCurrent of BOOLS) {
        for (const coldLoad of BOOLS) {
          for (const legacyListed of LISTED) {
            expect(decidePageGate({ access, configIsCurrent, coldLoad, legacyListed })).toBe('none');
          }
        }
      }
    }
  });

  it('only logs a refused page on a full load: reloading it could loop forever', () => {
    for (const configIsCurrent of BOOLS) {
      for (const legacyListed of LISTED) {
        expect(
          decidePageGate({ access: 'blocked', configIsCurrent, coldLoad: true, legacyListed }),
        ).toBe('canary');
      }
    }
  });

  it('hands a refused page reached by an in-page hop to the network layer, once the config is current', () => {
    for (const legacyListed of LISTED) {
      expect(
        decidePageGate({ access: 'blocked', configIsCurrent: true, coldLoad: false, legacyListed }),
      ).toBe('hand-to-network');
    }
  });

  it('waits on a refused in-page hop while the config in hand is for another page', () => {
    for (const legacyListed of LISTED) {
      expect(
        decidePageGate({ access: 'blocked', configIsCurrent: false, coldLoad: false, legacyListed }),
      ).toBe('wait');
    }
  });

  describe('a legacy /c/ or /user/ channel page that must prove whose it is', () => {
    it('waits while the config is stale, however it was reached', () => {
      for (const coldLoad of BOOLS) {
        for (const legacyListed of LISTED) {
          expect(
            decidePageGate({ access: 'verify', configIsCurrent: false, coldLoad, legacyListed }),
          ).toBe('wait');
        }
      }
    });

    it('reloads after an in-page hop, because the inline metadata is the previous page\'s', () => {
      for (const legacyListed of LISTED) {
        expect(
          decidePageGate({ access: 'verify', configIsCurrent: true, coldLoad: false, legacyListed }),
        ).toBe('hand-to-network');
      }
    });

    it('lets a full load through when its own metadata names a listed channel', () => {
      expect(
        decidePageGate({ access: 'verify', configIsCurrent: true, coldLoad: true, legacyListed: true }),
      ).toBe('none');
    });

    it('refuses a full load whose channel is not listed, or cannot be told', () => {
      for (const legacyListed of [false, null] as const) {
        expect(
          decidePageGate({ access: 'verify', configIsCurrent: true, coldLoad: true, legacyListed }),
        ).toBe('refuse');
      }
    });
  });

  it('answers every combination with a known action', () => {
    const known: readonly PageGateAction[] = ['none', 'wait', 'hand-to-network', 'refuse', 'canary'];
    for (const access of ACCESSES) {
      for (const configIsCurrent of BOOLS) {
        for (const coldLoad of BOOLS) {
          for (const legacyListed of LISTED) {
            expect(known).toContain(
              decidePageGate({ access, configIsCurrent, coldLoad, legacyListed }),
            );
          }
        }
      }
    }
  });
});
