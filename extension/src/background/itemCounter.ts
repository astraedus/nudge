/**
 * COUNT budgets: "20 Shorts a day, then the gate." (v0.3)
 *
 * The minute budget in `tracker.ts` answers "how long have you been here"; this answers
 * "how many of these have you watched". They are separate measurements of the same
 * surface, and both live in that surface's `domain#gate` rollup.
 *
 * ## The worker owns the count, the page only reports where it is
 *
 * A content script sends `ITEM_VIEWED` with its current URL and nothing else. Everything
 * that decides anything happens here: resolving the URL to a gate and an item id,
 * de-duplicating against today's seen set, incrementing the rollup, and firing the
 * crossing. Two reasons, and the second is the one that matters:
 *
 *  - The page is the untrusted side. A tally kept in the page is a tally the user can edit
 *    with devtools open, on the very surface the budget exists to limit.
 *  - Seven platform scripts each keeping their own tally is seven chances to disagree with
 *    the budget the block page and DNR read. That is the documented failure mode of this
 *    whole codebase ("one question, one function, when more than one layer answers it").
 *
 * ## Why an item is counted ONCE per day
 *
 * "20 Shorts" means twenty pieces of content, not twenty navigations. Swiping back to the
 * Short you were just on, or re-opening a reel from a friend's message, must not spend the
 * allowance again: a counter that charges for a back button is impossible to reason about
 * and reads as a bug. `core/seenItems.ts` holds the day's ids and owns the cap and the
 * midnight reset (which falls out of the blob's own day stamp).
 *
 * ## Why an item URL is not a gated surface
 *
 * `itemForUrl` (counting) and `gateForUrl` (the surface DNR redirects and the in-page gate
 * enforces) are deliberately different questions. On YouTube and Instagram an item URL is
 * also a surface URL, so the two agree. On TikTok a For You item is `/@user/video/<id>`,
 * which is NOT the For You surface: a link a friend sent you must not be treated as the
 * feed. See `GateDefinition.itemPaths`.
 */

import { crossesCount, tightestGateCount } from '../core/budgets';
import { extractDomain } from '../core/domainMatcher';
import { itemForUrl, platformForDomain, type GateId } from '../core/platforms';
import { rulesForDomain, usageKeyForHost } from '../core/ruleResolver';
import { localDayKey } from '../core/scheduleEvaluator';
import { recordSeenItem } from '../core/seenItems';
import type { NudgeSettings } from '../core/settingsSchema';
import { recordItemView } from '../core/stats';
import { surfaceKey } from '../core/surfaceKeys';
import { applyRules, redirectOpenTabs } from './dnr';
import {
  loadSeenItems,
  loadSettings,
  saveSeenItems,
  todayItemCount,
  updateDomainUsage,
} from './storage';
import { revokeTempAllow } from './tempAllow';
import { refreshBadge } from './badge';
import { trackedGate } from './tracker';

/** What one `ITEM_VIEWED` report resolved to, or null when the URL is not an item. */
export interface ResolvedItem {
  /** The usage bucket's site half: the matching RULE's domain, never the raw host. */
  domain: string;
  gateId: GateId;
  itemId: string;
}

/**
 * Resolve a reported URL into the surface bucket its item belongs to. PURE-ish (settings
 * in, answer out), so the whole mapping is unit-testable without touching storage.
 *
 * The domain is the RULE's, via `usageKeyForHost`, exactly as the tracker attributes
 * minutes. Keying counts off the raw host instead would fill `m.youtube.com#shorts` while
 * every budget check read `youtube.com#shorts`, and the limit could never fire, however
 * many Shorts went past. That bug has already been paid for once on the minute axis (live
 * QA 2026-09-20); it is not being re-earned here.
 */
export function resolveItem(settings: NudgeSettings, url: string): ResolvedItem | null {
  const host = extractDomain(url);
  if (host === null) return null;
  const domain = usageKeyForHost(settings.rules, host);
  const platform = platformForDomain(domain);
  if (platform === null) return null;
  const item = itemForUrl(platform.id, url);
  return item === null ? null : { domain, gateId: item.gateId, itemId: item.itemId };
}

/**
 * Handle one `ITEM_VIEWED` report.
 *
 * `counted` is false for a URL that is not an item of any gate's stream, for an item
 * already seen today, and while Nudge is globally off. Never throws: the router must
 * always answer, and a content script that is told nothing hangs forever.
 *
 * Counting happens for any KNOWN PLATFORM, whether or not a budget is configured, for the
 * same reason the tracker fills surface buckets unconditionally: a dashboard line that
 * starts at zero the moment a user turns a budget on reads as a bug and is useless for
 * deciding what the budget should be. Only the ENFORCEMENT below is conditional on a limit.
 */
export async function handleItemViewed(
  url: string,
  now: Date = new Date(),
): Promise<{ ok: boolean; counted: boolean }> {
  const settings = await loadSettings();
  // "Behave as if uninstalled": a disabled Nudge measures nothing, exactly as it enforces
  // nothing. Counting while off would let a budget be silently spent by a user who
  // believes the extension is inert, and bite them the moment they switch it back on.
  if (!settings.globalEnabled) return { ok: true, counted: false };

  const item = resolveItem(settings, url);
  if (item === null) return { ok: true, counted: false };

  const today = localDayKey(now);
  const key = surfaceKey(item.domain, item.gateId);

  // The seen set is written BEFORE the rollup, so a teardown between the two costs an
  // UNDER-count of one rather than a double count on the next report of the same item.
  // (Every step here is an atomic read-modify-write against storage for the MV3 reason in
  // tracker.ts: nothing may be held in a worker global between two events.)
  const { state, isNew } = recordSeenItem(await loadSeenItems(now), today, key, item.itemId);
  if (!isNew) return { ok: true, counted: false };
  await saveSeenItems(state);

  const previousCount = await todayItemCount(key, now);
  await updateDomainUsage(today, key, recordItemView);

  await enforceCountBudget(settings, item, previousCount, previousCount + 1, now);
  return { ok: true, counted: true };
}

/**
 * Flip a surface to blocked the instant its daily ITEM COUNT is crossed.
 *
 * The count sibling of `tracker.ts#enforceBudget`, and the same three things have to
 * happen in the same order: revoke any in-flight grant, RECOMPILE the DNR rule set (the
 * gate's redirect does not exist until now), then push the open tabs. Doing them in any
 * other order bounces the user off the page and then lets them walk straight back in.
 *
 * `crossesCount` is true only on the transition, so this runs exactly once rather than on
 * every item after the twentieth.
 */
async function enforceCountBudget(
  settings: NudgeSettings,
  item: ResolvedItem,
  previousCount: number,
  nextCount: number,
  now: Date,
): Promise<void> {
  const rules = rulesForDomain(settings.rules, item.domain);
  if (rules.length === 0) return;

  const limit = tightestGateCount(rules, item.gateId);
  if (!crossesCount(limit, previousCount, nextCount)) return;

  await revokeTempAllow(item.domain, now.getTime());
  await applyRules(settings, now);

  // Open tabs are matched by the gate's SURFACE (`trackedGate`), not by the item pattern
  // that just counted. The surface set is precisely what DNR will redirect from here on,
  // so it is precisely the set of tabs that would otherwise sit readable until the next
  // navigation. A tab on an item URL that is NOT a surface URL (a TikTok `/@user/video/`
  // page) is deliberately left alone: sending it to the block page would resolve no gate
  // there, render ALLOW, and bounce straight back, which is a flash of nothing rather
  // than an enforcement.
  await redirectOpenTabs(item.domain, (url) => trackedGate(item.domain, url) === item.gateId);
  await refreshBadge();
}
