/**
 * In-memory stand-ins for the chrome APIs WXT's `fakeBrowser` does not implement.
 *
 * `fakeBrowser` ships real in-memory implementations of storage, alarms, tabs and
 * runtime.getURL, and THROWS "not implemented" for everything else — which is the right
 * default, but it means the three APIs the background layer leans on hardest
 * (declarativeNetRequest, scripting, idle) have to be supplied here.
 *
 * These are deliberately stateful rather than bare `vi.fn()` spies. The behaviours under
 * test are about what the rule set / registration LOOKS LIKE after a sequence of calls
 * ("registering twice does not duplicate", "an exhausted budget adds a redirect"), and a
 * call-count assertion would pass just as happily on a wrong rule set.
 *
 * Not a `.test.ts` file, so vitest's `include` glob does not collect it.
 */

import { fakeBrowser } from 'wxt/testing';

type Rule = chrome.declarativeNetRequest.Rule;

export interface DnrState {
  dynamic: Rule[];
  session: Rule[];
}

function applyUpdate(
  current: Rule[],
  options: { removeRuleIds?: number[]; addRules?: Rule[] },
): Rule[] {
  const removed = new Set(options.removeRuleIds ?? []);
  return [...current.filter((rule) => !removed.has(rule.id)), ...(options.addRules ?? [])];
}

/** Replace `chrome.declarativeNetRequest` with an in-memory rule store. */
export function installDnr(): DnrState {
  const state: DnrState = { dynamic: [], session: [] };
  Object.defineProperty(chrome, 'declarativeNetRequest', {
    configurable: true,
    writable: true,
    value: {
      getDynamicRules: async () => state.dynamic,
      getSessionRules: async () => state.session,
      updateDynamicRules: async (options: { removeRuleIds?: number[]; addRules?: Rule[] }) => {
        state.dynamic = applyUpdate(state.dynamic, options);
      },
      updateSessionRules: async (options: { removeRuleIds?: number[]; addRules?: Rule[] }) => {
        state.session = applyUpdate(state.session, options);
      },
    },
  });
  return state;
}

export interface ScriptingState {
  scripts: chrome.scripting.RegisteredContentScript[];
  /** Every call made, so "updated rather than re-registered" is observable. */
  calls: string[];
  /** When set, `getRegisteredContentScripts` throws — the real API does on some builds. */
  throwOnQuery: boolean;
}

/** Replace `chrome.scripting` with an in-memory content-script registry. */
export function installScripting(): ScriptingState {
  const state: ScriptingState = { scripts: [], calls: [], throwOnQuery: false };
  Object.defineProperty(chrome, 'scripting', {
    configurable: true,
    writable: true,
    value: {
      getRegisteredContentScripts: async (filter?: { ids?: string[] }) => {
        state.calls.push('get');
        if (state.throwOnQuery) throw new Error('not supported');
        const ids = filter?.ids;
        return ids === undefined
          ? state.scripts
          : state.scripts.filter((script) => ids.includes(script.id));
      },
      registerContentScripts: async (
        scripts: chrome.scripting.RegisteredContentScript[],
      ) => {
        state.calls.push('register');
        for (const script of scripts) {
          if (state.scripts.some((existing) => existing.id === script.id)) {
            throw new Error(`Duplicate script ID '${script.id}'`);
          }
          state.scripts.push(script);
        }
      },
      updateContentScripts: async (
        scripts: chrome.scripting.RegisteredContentScript[],
      ) => {
        state.calls.push('update');
        for (const script of scripts) {
          const index = state.scripts.findIndex((existing) => existing.id === script.id);
          if (index === -1) throw new Error(`Nonexistent script ID '${script.id}'`);
          state.scripts[index] = script;
        }
      },
      unregisterContentScripts: async (filter?: { ids?: string[] }) => {
        state.calls.push('unregister');
        const ids = filter?.ids;
        state.scripts =
          ids === undefined
            ? []
            : state.scripts.filter((script) => !ids.includes(script.id));
      },
    },
  });
  return state;
}

export interface AttentionState {
  idle: `${chrome.idle.IdleState}`;
  windowFocused: boolean;
  tabs: { id: number; url: string }[];
  /** Tab id -> the URL it was navigated to, so a forced redirect is observable. */
  navigatedTo: Record<number, string>;
}

/**
 * Replace `chrome.idle` and the focus/tab surface the tracker reads.
 *
 * The tracker's whole premise is "count time only while Chrome has OS focus and the
 * machine is not idle", so those two answers are inputs to every accounting assertion.
 */
export function installAttention(): AttentionState {
  const state: AttentionState = {
    idle: 'active',
    windowFocused: true,
    tabs: [],
    navigatedTo: {},
  };

  Object.defineProperty(chrome, 'idle', {
    configurable: true,
    writable: true,
    value: {
      queryState: async () => state.idle,
      setDetectionInterval: async () => undefined,
      onStateChanged: { addListener: () => undefined },
    },
  });
  Object.defineProperty(chrome, 'windows', {
    configurable: true,
    writable: true,
    value: {
      getLastFocused: async () => ({ focused: state.windowFocused }),
      onFocusChanged: { addListener: () => undefined },
    },
  });
  Object.defineProperty(chrome, 'tabs', {
    configurable: true,
    writable: true,
    value: {
      query: async () => state.tabs,
      update: async (tabId: number, options: { url?: string }) => {
        if (options.url !== undefined) state.navigatedTo[tabId] = options.url;
      },
    },
  });
  Object.defineProperty(chrome, 'action', {
    configurable: true,
    writable: true,
    value: {
      setBadgeText: async () => undefined,
      setBadgeBackgroundColor: async () => undefined,
    },
  });

  return state;
}

/** Wipe storage/alarms between tests; the stateful mocks above are re-installed per test. */
export function resetBrowser(): void {
  fakeBrowser.reset();
}
