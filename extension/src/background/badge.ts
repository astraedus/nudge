/**
 * Toolbar badge — the "Show time remaining" feature (Android parity).
 *
 * Hard constraint: only ~4 characters fit in a badge (ext-01 §5), hence `formatBadge`'s
 * "12m" / "1h2" style. Colors follow Android's thresholds: green >50%, orange 25–50%,
 * red <25% of the daily budget remaining.
 */

import { extractDomain } from '../core/domainMatcher';
import { limitMs, remainingMs, tightestLimit } from '../core/budgets';
import { resolveLightsOff } from '../core/lightsOff';
import { badgeColor, formatBadge } from '../ui/format';
import { loadSettings, todayUsageMs } from './storage';

/** Deep teal — deliberately none of the three budget colours, so "OFF" never reads as an error. */
const LIGHTS_OFF_BADGE_COLOR = '#0f3d36';

async function clearBadge(tabId?: number): Promise<void> {
  try {
    await chrome.action.setBadgeText(tabId === undefined ? { text: '' } : { text: '', tabId });
  } catch {
    // Tab vanished between query and set — nothing to clear.
  }
}

/** Recompute the badge for the active tab. Cheap enough to call on every activity event. */
export async function refreshBadge(): Promise<void> {
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab?.id === undefined || tab.url === undefined) {
      await clearBadge();
      return;
    }

    const settings = await loadSettings();

    // Lights Off outranks the per-site budget readout. The single most-repeated complaint
    // across every competitor is not a UX gripe, it is users no longer BELIEVING the block is
    // still in place (design §3e) — and this extension is zero-telemetry, so a local, always-
    // visible status surface is the only reassurance available. It is therefore checked before
    // the domain test, so the badge still says OFF on a page with no rule at all.
    if (resolveLightsOff(settings, new Date()).active) {
      await chrome.action.setBadgeText({ tabId: tab.id, text: 'OFF' });
      await chrome.action.setBadgeBackgroundColor({
        tabId: tab.id,
        color: LIGHTS_OFF_BADGE_COLOR,
      });
      return;
    }

    const domain = extractDomain(tab.url);
    if (domain === null) {
      await clearBadge(tab.id);
      return;
    }

    const rules = settings.rules.filter(
      (rule) => rule.enabled && rule.domain === domain && rule.showTimeRemaining,
    );
    const limit = settings.globalEnabled ? tightestLimit(rules) : null;
    if (limit === null) {
      await clearBadge(tab.id);
      return;
    }

    const usedMs = await todayUsageMs(domain, new Date());
    const left = remainingMs(limit, usedMs) ?? 0;
    await chrome.action.setBadgeText({ tabId: tab.id, text: formatBadge(left) });
    await chrome.action.setBadgeBackgroundColor({
      tabId: tab.id,
      color: badgeColor(left, limitMs(limit)),
    });
  } catch {
    // Badge is cosmetic: never let it break the tracking path.
  }
}
