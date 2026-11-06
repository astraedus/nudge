/**
 * The repeating pause's content script, registered only for the sites that need it.
 *
 * A rule can be on any site, so a manifest entry would mean a script on every page anyone
 * visits, Nudge's rules or not. Same mechanism as `grayscale.ts` and the same reasoning about
 * its failure modes: one dynamic registration whose `matches` derive from settings, compared
 * against what is registered on every wake and save, and only touched when it differs.
 *
 * What grayscale does not need: a site added while its tab is open. A registered script only
 * reaches FUTURE documents, and the block page's chooser saves seconds before the tab lands on
 * the site, so the page it lands on can commit before the registration does. Each newly
 * covered site therefore also gets the script injected into its open tabs. Injecting twice is
 * safe: WXT's `ContentScriptContext` stops the older copy of a same-named script when a new one
 * starts, and the host element is only ever one (`content/repeatPauseHost.ts`).
 */

import { normalizeToBaseDomain } from '../core/domainMatcher';
import { platformForDomain } from '../core/platforms';
import type { NudgeSettings } from '../core/settingsSchema';

export const REPEAT_PAUSE_SCRIPT_ID = 'nudge-repeat-pause';

/** Where the build emits `entrypoints/repeatPause.content.ts`. */
export const REPEAT_PAUSE_SCRIPT_FILE = 'content-scripts/repeatPause.js';

/** Chrome rejects the WHOLE registration over one bad pattern, so bad hosts never reach it. */
const VALID_HOST = /^[a-z0-9.-]+$/i;

/**
 * The `matches` list: every enabled rule with a repeat set, on any mode.
 *
 * Mode is not consulted because a schedule can turn an Allow rule into a Delay at 09:00 with no
 * save in between. The script asks the worker what applies, and is silent when nothing does.
 */
export function repeatPauseMatches(settings: NudgeSettings): string[] {
  if (!settings.globalEnabled) return [];
  const hosts = new Set<string>();
  for (const rule of settings.rules) {
    if (!rule.enabled || rule.repeatPauseMinutes === null) continue;
    const base = normalizeToBaseDomain(rule.domain);
    hosts.add(base);
    for (const alias of platformForDomain(base)?.domains ?? []) hosts.add(alias);
  }
  return Array.from(hosts)
    .filter((host) => VALID_HOST.test(host))
    .flatMap((host) => [`*://${host}/*`, `*://*.${host}/*`])
    .sort();
}

/** What is registered now: null when nothing is, or when the query itself fails. */
async function registeredMatches(): Promise<string[] | null> {
  try {
    const [script] = await chrome.scripting.getRegisteredContentScripts({
      ids: [REPEAT_PAUSE_SCRIPT_ID],
    });
    return script === undefined ? null : [...(script.matches ?? [])].sort();
  } catch {
    return null;
  }
}

/**
 * Real Chrome REJECTS an unregister for an id it does not hold, and the two callers (the save
 * and `storage.onChanged`) race, so the loser finds it gone. That is the state it wanted.
 */
async function unregisterTolerantly(): Promise<void> {
  try {
    await chrome.scripting.unregisterContentScripts({ ids: [REPEAT_PAUSE_SCRIPT_ID] });
  } catch (error) {
    if ((await registeredMatches()) === null) return;
    throw error;
  }
}

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function registration(matches: string[]): chrome.scripting.RegisteredContentScript {
  return {
    id: REPEAT_PAUSE_SCRIPT_ID,
    matches,
    js: [REPEAT_PAUSE_SCRIPT_FILE],
    runAt: 'document_idle',
    allFrames: false,
    persistAcrossSessions: true,
  };
}

/**
 * Start the feature in tabs that are already open on `matches`, without a reload. Top frame
 * only (executeScript's default). One tab refusing (a chrome error page, a tab mid-close) must
 * not stop the others, and none of it can matter to the save that got here.
 */
export async function injectIntoOpenTabs(matches: string[]): Promise<void> {
  if (matches.length === 0) return;
  try {
    const tabs = await chrome.tabs.query({ url: matches });
    await Promise.all(
      tabs.map(async (tab) => {
        if (tab.id === undefined) return;
        try {
          await chrome.scripting.executeScript({
            target: { tabId: tab.id },
            files: [REPEAT_PAUSE_SCRIPT_FILE],
          });
        } catch {
          // Nothing to recover. The next navigation in that tab gets the registered script.
        }
      }),
    );
  } catch (error) {
    console.error('[nudge] repeat pause injection failed', error);
  }
}

async function applyNow(settings: NudgeSettings): Promise<void> {
  try {
    const desired = repeatPauseMatches(settings);
    const current = await registeredMatches();

    if (desired.length === 0) {
      if (current !== null) await unregisterTolerantly();
      return;
    }
    if (current !== null && sameList(current, desired)) return;

    if (current === null) {
      try {
        await chrome.scripting.registerContentScripts([registration(desired)]);
      } catch {
        // The query can fail on an id that exists, and then this is a duplicate. Update fixes
        // the match list either way; if it also fails, the outer catch reports it.
        await chrome.scripting.updateContentScripts([registration(desired)]);
      }
    } else {
      await chrome.scripting.updateContentScripts([registration(desired)]);
    }

    const known = new Set(current ?? []);
    await injectIntoOpenTabs(desired.filter((match) => !known.has(match)));
  } catch (error) {
    console.error('[nudge] repeat pause registration failed', error);
  }
}

let applyQueue: Promise<void> = Promise.resolve();

/**
 * Bring the registration in line with `settings`. Serialized, because a save and its
 * `storage.onChanged` both land here and overlapping passes are what produced grayscale's
 * red "does not exist" error. Never throws out of the blocking path.
 */
export function applyRepeatPauseScript(settings: NudgeSettings): Promise<void> {
  applyQueue = applyQueue.then(() => applyNow(settings)).catch(() => undefined);
  return applyQueue;
}
