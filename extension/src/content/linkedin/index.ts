/**
 * LinkedIn content script logic — thin glue over the shared `platformGate.ts` driver.
 *
 * Everything platform-generic (hide-toggle application, gate resolution, the interstitial,
 * SPA-nav detection) lives in `content/platformGate.ts`; this file supplies only what is
 * genuinely LinkedIn-specific: the hide-surface selector table and the copy shown on the
 * `feed` gate's interstitial.
 *
 * LinkedIn has exactly one gate (`feed`) and one hide surface (`suggestedFollows`).
 * Messaging and profile pages (e.g. `/in/someone/`) match no gate and stay reachable.
 */

import type { GateId } from '../../core/platforms';
import type { SiteConfig } from '../../core/protocol';
import { initPlatformContentScript, type GateCopy, type PlatformController } from '../platformGate';
import { HIDE_SURFACES } from './selectors';

/** Always LinkedIn's own safe homepage — never a gated surface. */
const BAIL_URL = 'https://www.linkedin.com/';

const GATE_RULE_LABELS: Record<GateId, string> = {
  feed: 'the feed',
  // Unused on LinkedIn, present only so the Record is total against the shared GateId
  // union without an `as` cast.
  shorts: 'Shorts',
  reels: 'Reels',
  explore: 'Explore',
  home: 'the home feed',
  foryou: 'the For You feed',
  live: 'Live',
  notifications: 'Notifications',
  watch: 'Watch',
  marketplace: 'Marketplace',
};

function linkedinGateCopy(gateId: GateId): GateCopy {
  return { ruleLabel: GATE_RULE_LABELS[gateId] };
}

export function initLinkedinContentScript(
  doc?: Document,
  fetchConfig?: (url: string) => Promise<SiteConfig>,
): PlatformController {
  return initPlatformContentScript(
    {
      platform: 'linkedin',
      hideSurfaces: HIDE_SURFACES,
      gateCopy: linkedinGateCopy,
      bailUrl: BAIL_URL,
    },
    doc,
    fetchConfig,
  );
}
