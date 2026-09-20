/**
 * TikTok content script — thin wrapper around the shared `initPlatformContentScript`
 * controller (`src/content/platformGate.ts`). Supplies only what's platform-specific:
 * the hide surfaces (`./selectors.ts`) and the gate copy for `foryou` / `explore` / `live`.
 *
 * Per the platform registry's `note` for TikTok: there is no filtered feed view here, so
 * the `foryou` gate (which also matches the bare root `/`, per ext-12's finding that
 * TikTok's home has no meaningfully-safe alternative) is the one control that matters.
 */

import type { GateId, Platform } from '../../core/platforms';
import type { SiteConfig } from '../../core/protocol';
import {
  initPlatformContentScript,
  type GateCopy,
  type PlatformController,
} from '../platformGate';
import { HIDE_SURFACES } from './selectors';

const PLATFORM: Platform = 'tiktok';

/** Always TikTok's own safe homepage — the platform's canonical "get me out of here". */
const BAIL_URL = 'https://www.tiktok.com/';

const GATE_RULE_LABELS: Partial<Record<GateId, string>> = {
  foryou: 'the For You feed',
  explore: 'Explore',
  live: 'Live',
};

export function tiktokGateCopy(gateId: GateId): GateCopy {
  return { ruleLabel: GATE_RULE_LABELS[gateId] ?? gateId };
}

export function initTiktokContentScript(
  doc?: Document,
  fetchConfig?: (url: string) => Promise<SiteConfig>,
): PlatformController {
  return initPlatformContentScript(
    {
      platform: PLATFORM,
      hideSurfaces: HIDE_SURFACES,
      gateCopy: tiktokGateCopy,
      bailUrl: BAIL_URL,
    },
    doc,
    fetchConfig,
  );
}
