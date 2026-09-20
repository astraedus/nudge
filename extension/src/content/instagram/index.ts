/**
 * Instagram content script logic — thin glue over the shared `platformGate.ts` driver.
 *
 * Everything platform-generic (hide-toggle application, gate resolution, the interstitial,
 * SPA-nav detection) lives in `content/platformGate.ts`; this file supplies only what is
 * genuinely Instagram-specific: the hide-surface selector table and the copy shown on each
 * gate's interstitial.
 *
 * Reel cards mixed into the home feed, or on a profile's Reels grid, are NOT filtered —
 * ext-12 marks per-card Reel filtering `unverified` (no known OSS implementation solves
 * it), and the registry's own `note` field already tells the user so. Nothing here should
 * imply otherwise.
 */

import type { GateId } from '../../core/platforms';
import type { SiteConfig } from '../../core/protocol';
import { initPlatformContentScript, type GateCopy, type PlatformController } from '../platformGate';
import { INSTAGRAM_HIDE_SURFACES } from './selectors';

/** Always Instagram's own safe homepage — never a gated surface. */
const BAIL_URL = 'https://www.instagram.com/';

const GATE_RULE_LABELS: Record<GateId, string> = {
  reels: 'Reels',
  explore: 'Explore',
  home: 'the home feed',
  // Unused on Instagram, present only so the Record is total against the shared GateId
  // union without an `as` cast.
  shorts: 'Shorts',
  foryou: 'the For You feed',
  live: 'Live',
  notifications: 'Notifications',
  feed: 'the feed',
  watch: 'Watch',
  marketplace: 'Marketplace',
};

function instagramGateCopy(gateId: GateId): GateCopy {
  return { ruleLabel: GATE_RULE_LABELS[gateId] };
}

export function initInstagramContentScript(
  doc?: Document,
  fetchConfig?: (url: string) => Promise<SiteConfig>,
): PlatformController {
  return initPlatformContentScript(
    {
      platform: 'instagram',
      hideSurfaces: INSTAGRAM_HIDE_SURFACES,
      gateCopy: instagramGateCopy,
      bailUrl: BAIL_URL,
    },
    doc,
    fetchConfig,
  );
}
