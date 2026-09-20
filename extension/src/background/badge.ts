/**
 * Toolbar badge — the "Show time remaining" feature (Android parity).
 *
 * Hard constraint: only ~4 characters fit in a badge (ext-01 §5), hence `formatBadge`'s
 * "12m" / "1h2" style. Colors follow Android's thresholds: green >50%, orange 25–50%,
 * red <25% of the daily budget remaining.
 */

import { extractDomain } from '../core/domainMatcher';
import { limitMs, remainingMs, tightestLimit } from '../core/budgets';
import { rulesForDomain, usageKeyForHost } from '../core/ruleResolver';
import { badgeColor, formatBadge } from '../ui/format';
import { loadSettings, todayUsageMs } from './storage';

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

    const domain = extractDomain(tab.url);
    if (domain === null) {
      await clearBadge(tab.id);
      return;
    }

    const settings = await loadSettings();
    // Subdomain-aware like every other reader of "which rule covers this page".
    const rules = rulesForDomain(settings.rules, domain).filter(
      (rule) => rule.showTimeRemaining,
    );
    const limit = settings.globalEnabled ? tightestLimit(rules) : null;
    if (limit === null) {
      await clearBadge(tab.id);
      return;
    }

    // Same bucket the tracker fills, the matching rule's domain, not the host.
    const usedMs = await todayUsageMs(usageKeyForHost(settings.rules, domain), new Date());
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
