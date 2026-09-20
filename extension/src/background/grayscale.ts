/**
 * Gray-screen mode's registration side.
 *
 * The problem: static manifest CSS cannot be turned off, and CSS injected from JS cannot be
 * applied before the page paints (it has to await a storage read first), so a JS-gated
 * grayscale flashes the page in full colour on every load — exactly the dopamine hit the
 * feature exists to remove.
 *
 * The solution: register `grayscale.css` as a DYNAMIC content script while the feature is
 * on and unregister it when off. Chrome injects registered CSS "before any DOM is
 * constructed or displayed" (chrome.scripting docs), so there is no colour frame at all.
 *
 * Registration is derived from settings on every worker wake rather than toggled
 * incrementally: `persistAcrossSessions` defaults to true, so a stale registration would
 * otherwise outlive the setting that asked for it and grey a site out for a user who had
 * turned the feature off.
 *
 * ## v0.2: one registration, N sites
 *
 * Grayscale is now a per-site toggle rather than a YouTube-only global, so the match list
 * is DERIVED from the set of enabled rules that ask for it. It stays ONE registration id:
 * `matches` is a list, and one id with a changing list is the only shape where "which sites
 * are gray" can be answered by reading a single registration. N ids keyed on domains would
 * have to be diffed and reconciled on every save, and the failure mode of getting that
 * wrong is a site the user un-greyed staying grey forever.
 *
 * YouTube's colour reward (`html.nudge-color`, applied by the content script on an allowed
 * channel) is unaffected: it is a class on a page that this stylesheet has already greyed,
 * and it only ever appears on youtube.com.
 */

import { normalizeToBaseDomain } from '../core/domainMatcher';
import { platformForDomain } from '../core/platforms';
import type { NudgeSettings } from '../core/settingsSchema';

export const GRAYSCALE_SCRIPT_ID = 'nudge-grayscale';
const GRAYSCALE_CSS = 'grayscale.css';

/**
 * The `matches` list implied by `settings`: every enabled rule with `grayscale: true`.
 *
 * Pure and exported so the derivation can be unit-tested without a browser — the
 * registration call around it is thin on purpose.
 *
 * A known platform contributes ALL of its domains, not just the one the rule names. A user
 * who greys x.com and then follows a twitter.com link would otherwise get a full-colour
 * page on what is, to them, the same site; the registry already knows they are aliases, so
 * this is a lookup rather than a judgement call.
 */
export function grayscaleMatches(settings: NudgeSettings): string[] {
  if (!settings.globalEnabled) return [];

  const domains = new Set<string>();
  for (const rule of settings.rules) {
    if (!rule.enabled || !rule.grayscale) continue;
    const base = normalizeToBaseDomain(rule.domain);
    domains.add(base);
    const platform = platformForDomain(base);
    if (platform !== null) for (const alias of platform.domains) domains.add(alias);
  }
  return [...domains].sort().map((domain) => `*://*.${domain}/*`);
}

async function registeredMatches(): Promise<string[] | null> {
  try {
    const existing = await chrome.scripting.getRegisteredContentScripts({
      ids: [GRAYSCALE_SCRIPT_ID],
    });
    const script = existing[0];
    return script === undefined ? null : [...(script.matches ?? [])].sort();
  } catch {
    // A filter for an unknown id throws on some Chrome versions rather than returning [].
    return null;
  }
}

/**
 * Unregister, treating "it was already gone" as success.
 *
 * Absence is the outcome this call is asking for, so reaching it by another route is not a
 * failure and must not reach the console: the only thing a red error here tells a user is
 * that turning a cosmetic feature off is broken, which it is not. Anything else still
 * propagates to the caller's logging.
 */
async function unregisterTolerantly(): Promise<void> {
  try {
    await chrome.scripting.unregisterContentScripts({ ids: [GRAYSCALE_SCRIPT_ID] });
  } catch (error) {
    if (await registeredMatches() === null) return;
    throw error;
  }
}

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function registration(matches: string[]): chrome.scripting.RegisteredContentScript {
  return {
    id: GRAYSCALE_SCRIPT_ID,
    matches,
    css: [GRAYSCALE_CSS],
    // The whole point: before the first paint.
    runAt: 'document_start',
    allFrames: false,
    persistAcrossSessions: true,
  };
}

/**
 * Make the registration match `settings`. Idempotent, so it is safe to call on every worker
 * wake and on every settings change.
 *
 * The already-correct case is a no-op rather than an unregister/register pair: tearing the
 * registration down and rebuilding it on every wake would leave a window in which a page
 * loading at that instant paints in full colour, which is the one failure this whole
 * mechanism exists to avoid.
 */
export async function applyGrayscale(settings: NudgeSettings): Promise<void> {
  // SERIALIZED. Two callers routinely race: saving settings runs this directly AND fires
  // `storage.onChanged`, which runs it again. Both read the registration, both see one, and
  // the loser then unregisters something the winner already removed — Chrome answers
  // "Script with ID 'nudge-grayscale' does not exist or is not fully registered" and the
  // user gets a red console error for doing nothing more exotic than switching grayscale off
  // (live QA 2026-09-20, reproduced twice). Queueing makes each call read state the previous
  // one has finished writing.
  applyQueue = applyQueue.then(() => applyGrayscaleNow(settings)).catch(() => undefined);
  return applyQueue;
}

/** Tail of the serialization chain; never rejects, so one failure cannot poison the queue. */
let applyQueue: Promise<void> = Promise.resolve();

async function applyGrayscaleNow(settings: NudgeSettings): Promise<void> {
  const desired = grayscaleMatches(settings);
  const current = await registeredMatches();

  try {
    if (desired.length === 0) {
      if (current !== null) await unregisterTolerantly();
      return;
    }
    if (current !== null && sameList(current, desired)) return;

    if (current === null) {
      try {
        await chrome.scripting.registerContentScripts([registration(desired)]);
        return;
      } catch {
        // `registeredMatches` returns null both for "not registered" and for "the query
        // threw". In the second case a registration DOES exist and registering again is a
        // duplicate-id error, so fall through to an update rather than leaving the stale
        // match list in place.
      }
    }
    await chrome.scripting.updateContentScripts([registration(desired)]);
  } catch (error) {
    // Gray-screen is a cosmetic intervention; a registration failure must never take down
    // the blocking path that shares this worker.
    console.error('[nudge] gray-screen registration failed', error);
  }
}
