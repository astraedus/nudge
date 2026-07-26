/**
 * Playwright fixtures for the extension e2e suite.
 *
 * Two things make this work:
 *
 * 1. **Extensions require a persistent context.** `chromium.launch()` cannot load one —
 *    only `launchPersistentContext` with `--load-extension` (ext-01 §7). The extension id
 *    is read off the service worker's URL.
 *
 * 2. **Real hostnames without a network.** The extension blocks by DOMAIN, so the suite
 *    needs pages served from distinguishable hosts. Chrome's `--host-resolver-rules` maps
 *    every `*.test` hostname onto a local server, so `http://blocked.test/` and
 *    `http://allowed.test/` are ordinary navigations to real hosts with zero DNS and zero
 *    internet — and, critically, they travel the normal network stack, so DNR sees them
 *    exactly as it would see youtube.com.
 */

import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  test as base,
  chromium,
  type BrowserContext,
  type Worker,
} from '@playwright/test';
import type { NudgeSettings, SiteRule } from '../src/core/settingsSchema';

const here = path.dirname(fileURLToPath(import.meta.url));
export const EXTENSION_PATH = path.resolve(here, '../.output/chrome-mv3');

/** The storage key the extension persists settings under (see background/storage.ts). */
const SETTINGS_KEY = 'nudge:settings';

function startTestServer(): Promise<Server> {
  const server = createServer((req, res) => {
    const host = (req.headers.host ?? 'unknown').split(':')[0]!;
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(
      `<!doctype html><html><head><title>${host}</title></head>` +
        `<body><h1 id="host">${host}</h1><p id="path">${req.url}</p></body></html>`,
    );
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

/**
 * The `usage:<yyyy-mm-dd>` storage key for today.
 *
 * Computed in Node rather than inside the worker: the browser is a child process on the
 * same machine so it shares this timezone, and MV3's CSP forbids `eval` in a service worker
 * (which any string-built expression would need).
 */
export function todayUsageKey(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `usage:${now.getFullYear()}-${month}-${day}`;
}

export interface ExtensionFixtures {
  context: BrowserContext;
  extensionId: string;
  serviceWorker: Worker;
  /** Overwrite the extension's settings and wait until DNR has actually caught up. */
  setSettings: (settings: Partial<NudgeSettings>) => Promise<void>;
  /** Seed today's usage rollup for a domain. */
  seedUsage: (domain: string, activeSec: number) => Promise<void>;
  /** URL of a page on `host`, served locally. */
  siteUrl: (host: string, pathname?: string) => string;
}

export const test = base.extend<ExtensionFixtures>({
  // eslint-disable-next-line no-empty-pattern
  context: async ({}, use) => {
    const server = await startTestServer();
    const { port } = server.address() as AddressInfo;

    const context = await chromium.launchPersistentContext('', {
      channel: 'chromium',
      args: [
        `--disable-extensions-except=${EXTENSION_PATH}`,
        `--load-extension=${EXTENSION_PATH}`,
        // Any *.test hostname resolves to the local server, so page URLs stay clean
        // (`http://blocked.test/`) and domain matching is realistic.
        `--host-resolver-rules=MAP *.test 127.0.0.1:${port}`,
        // Keep each browser's footprint small. The suite launches one persistent context
        // per test, and on a developer machine that is competing with a real browser for
        // memory; a bloated test browser gets OOM-killed and surfaces as the confusing
        // "Target page, context or browser has been closed" during fixture setup.
        '--disable-gpu',
        '--disable-dev-shm-usage',
        '--disable-background-networking',
        '--disable-features=Translate,MediaRouter,OptimizationHints',
        '--no-first-run',
        '--no-default-browser-check',
      ],
    });

    await use(context);

    await context.close();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  },

  serviceWorker: async ({ context }, use) => {
    let [worker] = context.serviceWorkers();
    // An explicit timeout here turns "the whole test timed out in setup" into a specific,
    // actionable failure when the worker never registers.
    worker ??= await context.waitForEvent('serviceworker', { timeout: 20_000 });
    await use(worker);
  },

  extensionId: async ({ serviceWorker }, use) => {
    await use(serviceWorker.url().split('/')[2]!);
  },

  siteUrl: async ({ context }, use) => {
    void context;
    await use((host: string, pathname = '/') => `http://${host}${pathname}`);
  },

  setSettings: async ({ serviceWorker }, use) => {
    await use(async (partial: Partial<NudgeSettings>) => {
      await serviceWorker.evaluate(
        async ([key, patch]) => {
          const existing = await chrome.storage.local.get(key);
          const merged = { ...((existing[key] as object | undefined) ?? {}), ...patch };
          // Writing to local fires the worker's storage.onChanged -> recompile path, which
          // is the same code path a real settings save takes.
          await chrome.storage.local.set({ [key]: merged });
          await chrome.storage.sync.set({ [key]: merged });
        },
        [SETTINGS_KEY, partial] as const,
      );

      // Wait until the DNR rule set actually reflects the new settings rather than
      // sleeping and hoping.
      const enabledRules = (partial.rules ?? []).filter((r) => r.enabled !== false).length;
      const expected = partial.globalEnabled === false ? 0 : enabledRules;
      await waitForRuleCount(serviceWorker, expected);
    });
  },

  seedUsage: async ({ serviceWorker }, use) => {
    await use(async (domain: string, activeSec: number) => {
      await serviceWorker.evaluate(
        async ([key, dom, secs]) => {
          const stored = await chrome.storage.local.get(key);
          const day = (stored[key] ?? {}) as Record<string, unknown>;
          day[dom] = {
            activeSec: secs,
            blocked: 0,
            walkedAway: 0,
            hourly: Array.from({ length: 24 }, () => 0),
          };
          await chrome.storage.local.set({ [key]: day });
        },
        [todayUsageKey(), domain, activeSec] as const,
      );
    });
  },
});

/** Poll the worker until the dynamic rule set has the expected size. */
export async function waitForRuleCount(worker: Worker, expected: number): Promise<void> {
  const deadline = Date.now() + 10_000;
  for (;;) {
    const count = await worker.evaluate(() =>
      chrome.declarativeNetRequest.getDynamicRules().then((rules) => rules.length),
    );
    if (count === expected) return;
    if (Date.now() > deadline) {
      throw new Error(`DNR rule count settled at ${count}, expected ${expected}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

/** Read a counter out of today's rollup for one domain. */
export async function readTodayCounter(
  worker: Worker,
  domain: string,
  field: 'blocked' | 'walkedAway' | 'activeSec',
): Promise<number> {
  return worker.evaluate(
    async ([key, dom, name]) => {
      const stored = await chrome.storage.local.get(key);
      const day = (stored[key] ?? {}) as Record<string, Record<string, number>>;
      return day[dom]?.[name] ?? 0;
    },
    [todayUsageKey(), domain, field] as const,
  );
}

/** Seed the tracker's in-flight interval, so the next accounting step attributes `elapsedMs`. */
export async function seedTrackerInterval(
  worker: Worker,
  domain: string,
  elapsedMs: number,
): Promise<void> {
  await worker.evaluate(
    async ([dom, elapsed]) => {
      await chrome.storage.session.set({
        'nudge:tracker': { domain: dom, since: Date.now() - elapsed },
      });
    },
    [domain, elapsedMs] as const,
  );
}

/** Poll until every temporary grant has lapsed and its DNR allow-rule is gone. */
export async function waitForNoTempAllows(
  worker: Worker,
  timeoutMs = 150_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const remaining = await worker.evaluate(() =>
      chrome.declarativeNetRequest.getSessionRules().then((rules) => rules.length),
    );
    if (remaining === 0) return;
    if (Date.now() > deadline) throw new Error('temporary access never expired');
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

/**
 * Send a runtime message from a real extension page.
 *
 * The service worker cannot message itself — `chrome.runtime.sendMessage` from the worker
 * does not reach its own `onMessage` listener — so requests are issued from an extension
 * page exactly as the real UI does.
 */
export async function sendFromExtensionPage<T>(
  context: BrowserContext,
  extensionId: string,
  request: unknown,
): Promise<T> {
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/dashboard.html`);
  const result = await page.evaluate(
    (req) => chrome.runtime.sendMessage(req) as Promise<unknown>,
    request,
  );
  await page.close();
  return result as T;
}

/** A minimal valid settings object for tests to spread over. */
export function baseSettings(overrides: Partial<NudgeSettings> = {}): Partial<NudgeSettings> {
  return {
    schemaVersion: 1,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [],
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    youtube: { shortsMode: 'INHERIT', hideShortsShelf: false, shortsDelaySeconds: 15 },
    tempAllowMinutes: 10,
    ...overrides,
  };
}

/** A site rule with sensible defaults. */
export function rule(domain: string, overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    id: `rule-${domain}`,
    domain,
    mode: 'HARD_BLOCK',
    delaySeconds: 15,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: 0,
    showTimeRemaining: false,
    schedule: null,
    ...overrides,
  };
}

export const expect = test.expect;
