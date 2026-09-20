/**
 * Reddit content script logic — thin glue over the shared `platformGate.ts` driver.
 *
 * Everything platform-generic (hide-toggle application, gate resolution, the interstitial,
 * SPA-nav detection) lives in `content/platformGate.ts`; this file supplies only what is
 * genuinely Reddit-specific: the (empty) hide-surface table and the copy shown on the
 * `home` gate's interstitial.
 *
 * Reddit has exactly one gate (`home` — the front page, r/popular and r/all) and zero
 * hide surfaces (see `./selectors.ts` for why). Individual subreddits and comment threads
 * are never gated — the registry's own `note` promises this explicitly, and this module
 * makes no attempt to widen that.
 */

import type { GateId } from '../../core/platforms';
import type { SiteConfig } from '../../core/protocol';
import { initPlatformContentScript, type GateCopy, type PlatformController } from '../platformGate';
import { HIDE_SURFACES } from './selectors';

/** Always Reddit's own safe homepage — never a gated surface. */
const BAIL_URL = 'https://www.reddit.com/';

const GATE_RULE_LABELS: Record<GateId, string> = {
  home: 'the front page',
  // Unused on Reddit, present only so the Record is total against the shared GateId union
  // without an `as` cast.
  shorts: 'Shorts',
  reels: 'Reels',
  explore: 'Explore',
  foryou: 'the For You feed',
  live: 'Live',
  notifications: 'Notifications',
  feed: 'the feed',
  watch: 'Watch',
  marketplace: 'Marketplace',
};

function redditGateCopy(gateId: GateId): GateCopy {
  return { ruleLabel: GATE_RULE_LABELS[gateId] };
}

export function initRedditContentScript(
  doc?: Document,
  fetchConfig?: (url: string) => Promise<SiteConfig>,
): PlatformController {
  return initPlatformContentScript(
    {
      platform: 'reddit',
      hideSurfaces: HIDE_SURFACES,
      gateCopy: redditGateCopy,
      bailUrl: BAIL_URL,
    },
    doc,
    fetchConfig,
  );
}
