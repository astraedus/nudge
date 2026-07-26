/**
 * Chrome Web Store validity rules for the BUILT manifest.
 *
 * These are submission blockers, not style preferences: the extension had shipped with no
 * `icons` key at all and a 160-character description, and neither shows up in lint, tsc,
 * vitest, the build, or e2e — the CWS listing-package prep found them by hand
 * (ops/routes/nudge/research/ext-09-listing-package/). This module exists so the next
 * regression is caught by CI instead of by a person about to hit submit.
 *
 * Plain ESM with no dependencies so both the build gate (scripts/verify-manifest.mjs) and the
 * unit tests can share ONE definition of the rules — a duplicated limit is a limit that
 * drifts.
 */

/**
 * Hard limit on `manifest.description`, verified 2026-07-27 against
 * https://developer.chrome.com/docs/apps/manifest/description
 */
export const DESCRIPTION_MAX_CHARS = 132;

/** The icon size the Chrome Web Store requires for a submission. */
export const REQUIRED_ICON_SIZE = '128';

/**
 * Check a parsed manifest object.
 *
 * @param {unknown} manifest
 * @returns {string[]} human-readable problems; empty means store-valid.
 */
export function findStoreValidityProblems(manifest) {
  /** @type {string[]} */
  const problems = [];

  if (manifest === null || typeof manifest !== 'object') {
    return ['manifest is not an object'];
  }

  const { icons, description } = /** @type {Record<string, unknown>} */ (manifest);

  if (icons === null || typeof icons !== 'object') {
    problems.push('missing `icons` key — the Chrome Web Store requires a 128x128 icon');
  } else {
    const entry = /** @type {Record<string, unknown>} */ (icons)[REQUIRED_ICON_SIZE];
    if (typeof entry !== 'string' || entry.trim() === '') {
      problems.push(
        `icons.${REQUIRED_ICON_SIZE} is missing or empty — the Chrome Web Store requires a ` +
          `${REQUIRED_ICON_SIZE}x${REQUIRED_ICON_SIZE} icon`,
      );
    }
  }

  if (typeof description !== 'string' || description.trim() === '') {
    problems.push('missing `description`');
  } else if (description.length > DESCRIPTION_MAX_CHARS) {
    problems.push(
      `description is ${description.length} characters, over the ` +
        `${DESCRIPTION_MAX_CHARS}-character Chrome Web Store limit`,
    );
  }

  return problems;
}
