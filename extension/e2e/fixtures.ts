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
import { SCHEMA_VERSION, migrateSettings, newSiteRule } from '../src/core/settingsSchema';
import { compiledRuleCount, type UsageByKey } from '../src/background/dnr';
import type {
  GateSetting,
  NudgeSettings,
  SiteFeatures,
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
 * server answers with ERR_SSL_PROTOCOL_ERROR. instagram.com is preloaded too, which is why
 * it shares this certificate rather than getting a plain-HTTP server of its own.
 * Generated per-run into a temp dir rather than
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
      'subjectAltName=DNS:localhost,DNS:*.youtube.com,DNS:youtube.com,' +
        'DNS:*.instagram.com,DNS:instagram.com,DNS:*.test,IP:127.0.0.1',
    ],
    { stdio: 'ignore' },
  );
  return { key: readFileSync(`${dir}/key.pem`), cert: readFileSync(`${dir}/cert.pem`) };
}

export function startTestServer(): Promise<Server> {
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
  /**
   * Write settings and wait for the worker's DNR rule set to catch up.
   *
   * `usage` is today's usage as the worker will read it, and only matters when a rule's
   * behaviour depends on it — an Allow rule with a daily limit compiles no redirect until
   * that limit is spent. Seed the usage first (`seedUsage`), then pass the same numbers
   * here so the wait expects the right rule set.
   */
  setSettings: (settings: Partial<NudgeSettings>, usage?: UsageByKey) => Promise<void>;
  /** Seed today's usage rollup for a domain. */
  seedUsage: (domain: string, activeSec: number) => Promise<void>;
  /** URL of a page on `host`, served locally. */
  siteUrl: (host: string, pathname?: string) => string;
}

/**
 * Launch a Chrome carrying the extension, with every fixture host mapped onto `port`.
 *
 * `userDataDir` defaults to `''` (a throwaway profile Playwright manages). Pass a real
 * directory to get a profile that OUTLIVES the browser — which is how `migration.spec.ts`
 * restarts the extension on an existing install, the closest available stand-in for a Web
 * Store auto-update. (`chrome.runtime.reload()` is not usable for that: on an extension
 * loaded with `--load-extension` it tears the extension down and never brings it back, so
 * every extension URL afterwards answers ERR_BLOCKED_BY_CLIENT.)
 */
export function launchExtensionContext(
  port: number,
  userDataDir = '',
): Promise<BrowserContext> {
  return chromium.launchPersistentContext(userDataDir, {
    channel: 'chromium',
    args: [
      `--disable-extensions-except=${EXTENSION_PATH}`,
      `--load-extension=${EXTENSION_PATH}`,
      // Any *.test hostname resolves to the local server, so page URLs stay clean
      // (`http://blocked.test/`) and domain matching is realistic.
      // youtube.com is mapped too, so the YouTube content script (which only matches
      // *.youtube.com) runs for real against a YouTube-shaped page, still zero network.
      // instagram.com likewise, for the platform content script and its Reels gate.
      `--host-resolver-rules=MAP *.test 127.0.0.1:${port}, MAP *.youtube.com 127.0.0.1:${port},` +
        ` MAP *.instagram.com 127.0.0.1:${port}`,
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
}

/** The extension's service worker, waiting for it to register if it has not yet. */
export async function extensionWorker(context: BrowserContext): Promise<Worker> {
  const [existing] = context.serviceWorkers();
  // An explicit timeout here turns "the whole test timed out in setup" into a specific,
  // actionable failure when the worker never registers.
  return existing ?? (await context.waitForEvent('serviceworker', { timeout: 20_000 }));
}

export const test = base.extend<ExtensionFixtures>({
  // eslint-disable-next-line no-empty-pattern
  context: async ({}, use) => {
    const server = await startTestServer();
    const { port } = server.address() as AddressInfo;

    const context = await launchExtensionContext(port);

    await use(context);

    await context.close();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  },

  serviceWorker: async ({ context }, use) => {
    await use(await extensionWorker(context));
  },

  extensionId: async ({ serviceWorker }, use) => {
    await use(serviceWorker.url().split('/')[2]!);
  },

  siteUrl: async ({ context }, use) => {
    void context;
    await use((host: string, pathname = '/') => `https://${host}${pathname}`);
  },

  setSettings: async ({ serviceWorker }, use) => {
    await use(async (partial: Partial<NudgeSettings>, usage?: UsageByKey) => {
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
      //
      // The expectation is DERIVED, never counted by hand: under schema v3 an Allow rule
      // under its budget compiles no rule at all, one gate can compile several, and a
      // YouTube whitelist adds one per allowed path. The old "one rule per enabled rule"
      // arithmetic was silently wrong in the permissive direction for all three, which
      // shows up as a flaky race rather than an honest failure.
      const expected = compiledRuleCount(migrateSettings(partial), usage ?? {}, new Date());
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

/**
 * Poll until the dynamically-registered grayscale stylesheet matches exactly `domains`.
 *
 * Needed because grayscale is registered OUT OF BAND from the DNR rule set: an Allow rule
 * carrying nothing but `grayscale: true` compiles ZERO dynamic rules, so `setSettings`'
 * rule-count wait returns instantly and a page opened immediately afterwards can load
 * before `chrome.scripting.registerContentScripts` has run. Registered CSS is injected
 * before first paint or not at all — polling `getComputedStyle` on an already-loaded page
 * would never recover — so the wait has to happen BEFORE the navigation.
 */
export async function waitForGrayscaleDomains(
  worker: Worker,
  domains: readonly string[],
): Promise<void> {
  const expected = [...domains].map((domain) => `*://*.${domain}/*`).sort();
  const deadline = Date.now() + 10_000;
  for (;;) {
    const current = await worker.evaluate(async () => {
      try {
        const scripts = await chrome.scripting.getRegisteredContentScripts({
          ids: ['nudge-grayscale'],
        });
        return [...(scripts[0]?.matches ?? [])].sort();
      } catch {
        return [];
      }
    });
    if (current.length === expected.length && current.every((m, i) => m === expected[i])) {
      return;
    }
    if (Date.now() > deadline) {
      throw new Error(
        `grayscale registration settled at [${current.join(', ')}], expected [${expected.join(', ')}]`,
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

/**
 * Write a RAW settings blob, exactly as an older version of the extension stored it.
 *
 * Deliberately not `setSettings`: that one normalizes nothing but does wait for the rule
 * set, and the point of a legacy blob is that the WORKER must be the thing that migrates
 * it on read. Both storage areas are written because a real v0.1.0 install has both (see
 * `background/storage.ts`: sync is authoritative, local is the mirror) — seeding only one
 * would prove the migration handles a half-populated profile nobody actually has.
 */
export async function seedRawSettings(worker: Worker, blob: unknown): Promise<void> {
  await worker.evaluate(
    async ([key, value]) => {
      await chrome.storage.local.set({ [key]: value });
      await chrome.storage.sync.set({ [key]: value });
    },
    [SETTINGS_KEY, blob] as const,
  );
}

/** The dynamic rule count the worker should settle on for these settings. */
export function expectedRuleCount(
  raw: unknown,
  usage: UsageByKey = {},
  now: Date = new Date(),
): number {
  return compiledRuleCount(migrateSettings(raw), usage, now);
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
    tempAllowMinutes: 10,
    ...overrides,
  };
}

/**
 * A site rule with sensible defaults.
 *
 * Built on `newSiteRule` rather than a literal, so a rule for a known platform
 * automatically carries its (all-off) feature block exactly as the product's own "add a
 * site" path produces it. A hand-written literal here would drift from the real shape the
 * moment the schema grows, and e2e is the last place that should be testing a shape the
 * product never actually stores.
 */
export function rule(domain: string, overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    ...newSiteRule({ domain, mode: 'HARD_BLOCK' }),
    id: `rule-${domain}`,
    ...overrides,
  };
}

/**
 * A rule for a known platform with some of its feature surfaces configured.
 *
 * `gates`/`hides` are merged over the platform's defaults, so a test only names the
 * surfaces it cares about and every other surface stays off.
 */
export function platformRule(
  domain: string,
  config: {
    gates?: Record<string, Partial<GateSetting>>;
    hides?: Record<string, boolean>;
    youtube?: Partial<NonNullable<SiteFeatures['youtube']>>;
  },
  overrides: Partial<SiteRule> = {},
): SiteRule {
  const base = rule(domain, overrides);
  const features = base.features;
  if (features === null) {
    throw new Error(`${domain} is not a known platform, so it has no features to configure`);
  }
  for (const [gateId, gate] of Object.entries(config.gates ?? {})) {
    const existing = features.gates[gateId as keyof typeof features.gates];
    if (existing === undefined) throw new Error(`${domain} has no gate "${gateId}"`);
    features.gates[gateId as keyof typeof features.gates] = { ...existing, ...gate };
  }
  for (const [hideId, hidden] of Object.entries(config.hides ?? {})) {
    if (!(hideId in features.hides)) throw new Error(`${domain} has no hide "${hideId}"`);
    features.hides[hideId as keyof typeof features.hides] = hidden;
  }
  if (config.youtube !== undefined) {
    if (features.youtube === undefined) throw new Error(`${domain} has no channel lists`);
    features.youtube = { ...features.youtube, ...config.youtube };
  }
  return base;
}

export const expect = test.expect;
