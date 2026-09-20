/**
 * Facebook content script logic — thin glue over the shared `platformGate.ts` driver, same
 * shape as `content/instagram/index.ts`. Everything platform-generic (hide-toggle
 * application, gate resolution, the interstitial, SPA-nav detection) lives in
 * `content/platformGate.ts`; this file supplies only Facebook-specific selector table +
 * gate copy.
 */

import type { GateId } from '../../core/platforms';
import type { SiteConfig } from '../../core/protocol';
import { initPlatformContentScript, type GateCopy, type PlatformController } from '../platformGate';
import { FACEBOOK_HIDE_SURFACES } from './selectors';

/** Always Facebook's own safe homepage — never a gated surface. */
const BAIL_URL = 'https://www.facebook.com/';

const GATE_RULE_LABELS: Record<GateId, string> = {
  feed: 'the News Feed',
  reels: 'Reels',
  watch: 'Watch',
  marketplace: 'Marketplace',
  // Unused on Facebook, present only so the Record is total against the shared GateId
  // union without an `as` cast.
  shorts: 'Shorts',
  explore: 'Explore',
  home: 'the home feed',
  foryou: 'the For You feed',
  live: 'Live',
  notifications: 'Notifications',
};

function facebookGateCopy(gateId: GateId): GateCopy {
  return { ruleLabel: GATE_RULE_LABELS[gateId] };
}

export function initFacebookContentScript(
  doc?: Document,
  fetchConfig?: (url: string) => Promise<SiteConfig>,
): PlatformController {
  return initPlatformContentScript(
    {
      platform: 'facebook',
      hideSurfaces: FACEBOOK_HIDE_SURFACES,
      gateCopy: facebookGateCopy,
      bailUrl: BAIL_URL,
    },
    doc,
    fetchConfig,
  );
}
