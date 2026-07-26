import { describe, expect, it } from 'vitest';

// Plain ESM build tooling (typed via scripts/store-validity.d.ts), shared verbatim with the
// build gate scripts/verify-manifest.mjs so the CWS limits have ONE definition.
import {
  DESCRIPTION_MAX_CHARS,
  findStoreValidityProblems,
} from '../../scripts/store-validity.mjs';

/**
 * The RULES are unit-tested here; the built artifact is checked by `npm run verify:manifest`
 * after the build (both share scripts/store-validity.mjs).
 *
 * Why this exists: the extension shipped with no `icons` key and a 160-character description,
 * and lint, tsc, 598 unit tests, the build and 37 e2e specs were all green throughout. Store
 * validity is invisible to every other gate we have.
 */

function manifest(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    icons: { 16: 'icon/16.png', 128: 'icon/128.png' },
    description: 'A short, legal description.',
    ...overrides,
  };
}

describe('a manifest that could be submitted to the Chrome Web Store', () => {
  it('passes when it has a 128px icon and a short enough description', () => {
    expect(findStoreValidityProblems(manifest())).toEqual([]);
  });
});

describe('a manifest the Chrome Web Store would reject', () => {
  it('is rejected when the icons key is missing entirely', () => {
    const problems = findStoreValidityProblems(manifest({ icons: undefined }));

    expect(problems).toHaveLength(1);
    expect(problems[0]).toMatch(/128/);
  });

  it('is rejected when it has icons but not the 128px one', () => {
    const problems = findStoreValidityProblems(
      manifest({ icons: { 16: 'icon/16.png', 48: 'icon/48.png' } }),
    );

    expect(problems).toHaveLength(1);
    expect(problems[0]).toMatch(/128/);
  });

  it('is rejected when the 128px entry is present but empty', () => {
    const problems = findStoreValidityProblems(manifest({ icons: { 128: '   ' } }));

    expect(problems).toHaveLength(1);
  });

  it('is rejected when the description is one character over the limit', () => {
    const tooLong = 'x'.repeat(DESCRIPTION_MAX_CHARS + 1);

    const problems = findStoreValidityProblems(manifest({ description: tooLong }));

    expect(problems).toHaveLength(1);
    expect(problems[0]).toMatch(new RegExp(String(DESCRIPTION_MAX_CHARS)));
  });

  it('accepts a description exactly at the limit', () => {
    const exactly = 'x'.repeat(DESCRIPTION_MAX_CHARS);

    expect(findStoreValidityProblems(manifest({ description: exactly }))).toEqual([]);
  });

  it('is rejected when the description is missing or blank', () => {
    expect(findStoreValidityProblems(manifest({ description: undefined }))).toHaveLength(1);
    expect(findStoreValidityProblems(manifest({ description: '  ' }))).toHaveLength(1);
  });

  it('reports BOTH problems at once rather than stopping at the first', () => {
    // Someone fixing a submission wants the whole list, not one round trip per problem.
    const problems = findStoreValidityProblems({
      icons: undefined,
      description: 'x'.repeat(DESCRIPTION_MAX_CHARS + 1),
    });

    expect(problems).toHaveLength(2);
  });

  it('does not throw on junk instead of a manifest', () => {
    expect(findStoreValidityProblems(null)).toHaveLength(1);
    expect(findStoreValidityProblems('not a manifest')).toHaveLength(1);
  });
});

describe('the real shipped description', () => {
  it('is the verified listing copy and fits the limit', () => {
    // Verbatim from ops/routes/nudge/research/ext-09-listing-package/listing-copy.md.
    const shipped =
      'Friction, not walls: delay and breathing pauses block distracting sites. ' +
      'Daily limits, local screen time. No account, no tracking.';

    expect(shipped.length).toBeLessThanOrEqual(DESCRIPTION_MAX_CHARS);
    expect(findStoreValidityProblems(manifest({ description: shipped }))).toEqual([]);
  });
});
