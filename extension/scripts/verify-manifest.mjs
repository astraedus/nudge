/**
 * Build gate: assert the BUILT manifest is Chrome-Web-Store-valid.
 *
 * Deliberately checks `.output/`, not `wxt.config.ts`: WXT synthesizes parts of the manifest
 * (icons are auto-discovered from `public/icon/`, so they appear in NO source file), and what
 * gets submitted is the built artifact. Validating the source would have passed while the
 * shipped manifest was still missing its icons.
 *
 * Run after `npm run build`. Exits non-zero with the specific problems so CI fails loudly.
 */

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findStoreValidityProblems } from './store-validity.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const manifestPath = path.resolve(here, '../.output/chrome-mv3/manifest.json');

let manifest;
try {
  manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
} catch (error) {
  console.error(
    `[nudge] could not read the built manifest at ${manifestPath}\n` +
      `Run \`npm run build\` first.\n${String(error)}`,
  );
  process.exit(1);
}

const problems = findStoreValidityProblems(manifest);

if (problems.length > 0) {
  console.error('[nudge] the built manifest is NOT Chrome Web Store valid:');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

console.log(
  `[nudge] manifest is store-valid: icons.128 present, ` +
    `description ${manifest.description.length} chars.`,
);
