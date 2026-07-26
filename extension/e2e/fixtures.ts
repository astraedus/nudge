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

import { execFileSync } from 'node:child_process';
import type { Server } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  test as base,
  chromium,
  type BrowserContext,
  type Worker,
} from '@playwright/test';
import { LIGHTS_OFF_RULE_ID_BASE } from '../src/background/dnr';
import {
  DEFAULT_SETTINGS,
  defaultLightsOffProfile,
  SCHEMA_VERSION,
} from '../src/core/settingsSchema';
import type {
  LightsOffProfile,
  NudgeSettings,
  SiteRule,
} from '../src/core/settingsSchema';

const here = path.dirname(fileURLToPath(import.meta.url));
export const EXTENSION_PATH = path.resolve(here, '../.output/chrome-mv3');

/** The storage key the extension persists settings under (see background/storage.ts). */
const SETTINGS_KEY = 'nudge:settings';

/**
 * A YouTube-shaped page, served for the mapped youtube.com host.
 *
 * `?channel=UCxxxx&name=Foo` embeds a real `ytInitialPlayerResponse` so the extension's
 * own detection runs against the shape it expects, this is what lets the channel features
 * be tested end to end (real content script, real registered CSS, real service worker) with
 * no network access at all.
 */
function youtubePage(url: URL): string {
  const channelId = url.searchParams.get('channel') ?? '';
  const name = url.searchParams.get('name') ?? 'Test Channel';
  const videoId = url.searchParams.get('v') ?? '';
  // `staleVideo` reproduces the SPA case: inline JSON pinned to a DIFFERENT video than the
  // URL names, which is what YouTube actually serves after a client-side navigation.
  const inlineVideoId = url.searchParams.get('staleVideo') ?? videoId;

  const playerResponse =
    channelId === ''
      ? ''
      : `<script>var ytInitialPlayerResponse = ${JSON.stringify({
          videoDetails: { videoId: inlineVideoId, channelId, author: name, title: 'A video' },
        })};</script>`;

  // A real watch page also carries a channel byline in the DOM, which YouTube re-renders on
  // every navigation, that is the tier the staleness guard falls through to.
  const domChannelId = url.searchParams.get('domChannel') ?? channelId;
  const byline =
    domChannelId === ''
      ? ''
      : `<ytd-channel-name id="channel-name"><a class="yt-formatted-string" ` +
        `href="/channel/${domChannelId}" aria-label="Go to channel ${name}">${name}</a>` +
        `</ytd-channel-name>`;

  return (
    `<!doctype html><html><head><title>${name} - YouTube</title></head><body>` +
    `<h1 id="host">www.youtube.com</h1><p id="path">${url.pathname}${url.search}</p>` +
    playerResponse +
    `<ytd-watch-flexy><div id="primary"><video id="player"></video>${byline}</div>` +
    `<div id="secondary"><div id="related">recommendations</div></div></ytd-watch-flexy>` +
    `<div id="comments"><div id="contents">comments</div></div>` +
    `</body></html>`
  );
}

/**
 * A throwaway self-signed cert for the test server.
 *
 * Needed because youtube.com is in Chrome's HSTS PRELOAD list: `http://www.youtube.com/` is
 * force-upgraded to HTTPS before it ever reaches our resolver rule, so a plain-HTTP fixture
 * server answers with ERR_SSL_PROTOCOL_ERROR. Generated per-run into a temp dir rather than
 * committed - a private key in a public repo is a bad habit even when it is worthless - and
 * Chrome is launched with --ignore-certificate-errors so the cert never has to be trusted.
 */
function generateSelfSignedCert(): { key: Buffer; cert: Buffer } {
  const dir = mkdtempSync(`${tmpdir()}/nudge-e2e-cert-`);
  execFileSync(
    'openssl',
    [
      'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
      '-keyout', `${dir}/key.pem`,
      '-out', `${dir}/cert.pem`,
      '-days', '1',
      '-subj', '/CN=localhost',
      '-addext',
      'subjectAltName=DNS:localhost,DNS:*.youtube.com,DNS:youtube.com,DNS:*.test,IP:127.0.0.1',
    ],
    { stdio: 'ignore' },
  );
  return { key: readFileSync(`${dir}/key.pem`), cert: readFileSync(`${dir}/cert.pem`) };
}

function startTestServer(): Promise<Server> {
  const { key, cert } = generateSelfSignedCert();
  const server = createHttpsServer({ key, cert }, (req, res) => {
    const host = (req.headers.host ?? 'unknown').split(':')[0]!;
    const url = new URL(req.url ?? '/', `http://${host}`);
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });

    if (host.endsWith('youtube.com')) {
      res.end(youtubePage(url));
      return;
    }

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
        // youtube.com is mapped too, so the YouTube content script (which only matches
        // *.youtube.com) runs for real against a YouTube-shaped page, still zero network.
        `--host-resolver-rules=MAP *.test 127.0.0.1:${port}, MAP *.youtube.com 127.0.0.1:${port}`,
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
        // The fixture server presents a throwaway self-signed cert (see above).
        '--ignore-certificate-errors',
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
    await use((host: string, pathname = '/') => `https://${host}${pathname}`);
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

/**
 * Poll the worker until the PER-SITE dynamic rule set has the expected size.
 *
 * Lights Off compiles into the same dynamic rule set but occupies its own id range, and its
 * rule count varies with the allow-list and the wall clock — so it is filtered out here rather
 * than folded into `expected`. Use `waitForLightsOffRules` for that half.
 */
export async function waitForRuleCount(worker: Worker, expected: number): Promise<void> {
  const deadline = Date.now() + 10_000;
  for (;;) {
    const count = await worker.evaluate(
      (idBase) =>
        chrome.declarativeNetRequest
          .getDynamicRules()
          .then((rules) => rules.filter((rule) => rule.id < idBase).length),
      LIGHTS_OFF_RULE_ID_BASE,
    );
    if (count === expected) return;
    if (Date.now() > deadline) {
      throw new Error(`per-site DNR rule count settled at ${count}, expected ${expected}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

/** Every dynamic rule Lights Off installed, as the real DNR engine holds it. */
export async function lightsOffRules(
  worker: Worker,
): Promise<chrome.declarativeNetRequest.Rule[]> {
  return worker.evaluate(
    (idBase) =>
      chrome.declarativeNetRequest
        .getDynamicRules()
        .then((rules) => rules.filter((rule) => rule.id >= idBase)),
    LIGHTS_OFF_RULE_ID_BASE,
  );
}

/** Poll until Lights Off has installed (or withdrawn) its rules. */
export async function waitForLightsOffRules(
  worker: Worker,
  expected: number,
): Promise<chrome.declarativeNetRequest.Rule[]> {
  const deadline = Date.now() + 10_000;
  for (;;) {
    const rules = await lightsOffRules(worker);
    if (rules.length === expected) return rules;
    if (Date.now() > deadline) {
      throw new Error(
        `Lights Off rule count settled at ${rules.length}, expected ${expected}`,
      );
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
    schemaVersion: SCHEMA_VERSION,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [],
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    // Spread the REAL defaults so adding a settings field never breaks the whole suite.
    youtube: { ...DEFAULT_SETTINGS.youtube },
    lightsOff: structuredClone(DEFAULT_SETTINGS.lightsOff),
    tempAllowMinutes: 10,
    ...overrides,
  };
}

/**
 * A Lights Off profile, DISABLED by default so a spec has to ask for the lockdown explicitly.
 * `minuteOfDay` below is what tests use to place a window around "now".
 */
export function lightsOffProfile(
  overrides: Partial<LightsOffProfile> = {},
): LightsOffProfile {
  return { ...defaultLightsOffProfile(), ...overrides };
}

/** Minutes from local midnight, offset by `deltaMinutes`, wrapped into 0..1439. */
export function minuteOfDay(deltaMinutes = 0, now: Date = new Date()): number {
  return (((now.getHours() * 60 + now.getMinutes() + deltaMinutes) % 1440) + 1440) % 1440;
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
