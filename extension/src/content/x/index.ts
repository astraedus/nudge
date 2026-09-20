/**
 * X (Twitter) content script — thin wrapper around the shared `initPlatformContentScript`
 * controller (`src/content/platformGate.ts`). Supplies only what's platform-specific: the
 * hide surfaces (`./selectors.ts`) and the gate copy for `home` / `explore` / `notifications`.
 *
 * The registry's `x` platform lists TWO domains (`x.com`, `twitter.com`) because
 * twitter.com still resolves and users still have rules against it — this module itself
 * is domain-agnostic (the worker resolves `platform` from whichever domain the page is on),
 * only the entrypoint's `matches` array needs to name both hosts.
 */

import type { GateId, Platform } from '../../core/platforms';
import type { SiteConfig } from '../../core/protocol';
import {
  initPlatformContentScript,
  type GateCopy,
  type PlatformController,
} from '../platformGate';
import { HIDE_SURFACES } from './selectors';

const PLATFORM: Platform = 'x';

/** Canonical safe homepage — the platform's "get me out of here". */
const BAIL_URL = 'https://x.com/';

const GATE_RULE_LABELS: Partial<Record<GateId, string>> = {
  home: 'the timeline',
  explore: 'Explore',
  notifications: 'Notifications',
};

export function xGateCopy(gateId: GateId): GateCopy {
  return { ruleLabel: GATE_RULE_LABELS[gateId] ?? gateId };
}

export function initXContentScript(
  doc?: Document,
  fetchConfig?: (url: string) => Promise<SiteConfig>,
): PlatformController {
  return initPlatformContentScript(
    { platform: PLATFORM, hideSurfaces: HIDE_SURFACES, gateCopy: xGateCopy, bailUrl: BAIL_URL },
    doc,
    fetchConfig,
  );
}
