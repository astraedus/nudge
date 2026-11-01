// Source scan: "Nudge makes no network requests, with one opt-in exception" (PRIVACY.md).
// Lint bans `fetch`, but a lint rule can be switched off on any line. This pins WHERE it may
// be switched off: the opt-in subscriptions read, and nowhere else.
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '../../src');

/** The only two files allowed to make a request (see extension/CLAUDE.md, Non-negotiables). */
const SANCTIONED = ['background/youtubeSubscriptions.ts', 'content/signedBrowse.ts'];

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.(ts|tsx)$/.test(entry.name) ? [path] : [];
  });
}

/** Files that switch off the fetch ban, or reach a network API lint does not cover. */
function networkCallers(files: Record<string, string>): string[] {
  const network = /no-restricted-globals|\bXMLHttpRequest\b|\bsendBeacon\b|\bWebSocket\b|\bEventSource\b/;
  return Object.entries(files)
    .filter(([, text]) => network.test(text))
    .map(([path]) => path)
    .sort();
}

describe('network calls', () => {
  const files = Object.fromEntries(
    sourceFiles(SRC).map((path) => [relative(SRC, path).split('\\').join('/'), readFileSync(path, 'utf8')]),
  );

  it('the scan sees the source tree', () => {
    expect(Object.keys(files).length).toBeGreaterThan(50);
  });

  it('live only in the two opt-in subscriptions files', () => {
    expect(networkCallers(files)).toEqual([...SANCTIONED].sort());
  });

  it('each sanctioned file switches the ban off exactly once', () => {
    for (const path of SANCTIONED) {
      expect(files[path]?.match(/no-restricted-globals/g) ?? []).toHaveLength(1);
    }
  });

  it('catches a planted request elsewhere', () => {
    const planted = {
      ...files,
      'core/stats.ts': `${files['core/stats.ts']}\n// eslint-disable-next-line no-restricted-globals\nfetch('x');`,
      'ui/beacon.ts': "navigator.sendBeacon('https://example.com', '');",
    };
    expect(networkCallers(planted)).toEqual([...SANCTIONED, 'core/stats.ts', 'ui/beacon.ts'].sort());
  });
});
