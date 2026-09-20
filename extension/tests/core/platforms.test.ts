import { describe, expect, it } from 'vitest';

import {
  PLATFORMS,
  gateForUrl,
  gateMatchesPath,
  gatePathRegex,
  isKnownGate,
  isKnownHide,
  platformById,
  platformForDomain,
  quickAddPlatforms,
  type Platform,
} from '../../src/core/platforms';

/**
 * The registry is the one place that says what a feature surface IS, and two very
 * different consumers read it: DNR compiles `paths` into RE2 `regexFilter`s for full page
 * loads, and the content scripts compile the same strings into JS RegExps for in-SPA
 * navigation. A pattern that only one of them can compile leaves the surface enforced on
 * one path and wide open on the other, which is invisible in any single test of either
 * layer — so the structural invariants are asserted here, over the WHOLE registry, rather
 * than per platform.
 */

describe('platform registry: structural invariants', () => {
  it('gives every platform at least one gate and a canonical domain', () => {
    for (const platform of PLATFORMS) {
      expect(platform.gates.length, `${platform.id} has no gates`).toBeGreaterThan(0);
      expect(platform.domains.length, `${platform.id} has no domains`).toBeGreaterThan(0);
      expect(platform.label).not.toBe('');
    }
  });

  it('gives every gate at least one path pattern, a label and a description', () => {
    for (const platform of PLATFORMS) {
      for (const gate of platform.gates) {
        expect(gate.paths.length, `${platform.id}/${gate.id} has no paths`).toBeGreaterThan(0);
        expect(gate.label, `${platform.id}/${gate.id} label`).not.toBe('');
        expect(gate.description, `${platform.id}/${gate.id} description`).not.toBe('');
      }
    }
  });

  it('gives every hide a label and a description', () => {
    for (const platform of PLATFORMS) {
      for (const hide of platform.hides) {
        expect(hide.label, `${platform.id}/${hide.id} label`).not.toBe('');
        expect(hide.description, `${platform.id}/${hide.id} description`).not.toBe('');
      }
    }
  });

  it('keeps gate ids and hide ids unique within a platform', () => {
    for (const platform of PLATFORMS) {
      const gateIds = platform.gates.map((g) => g.id);
      expect(new Set(gateIds).size, `${platform.id} duplicate gate id`).toBe(gateIds.length);
      const hideIds = platform.hides.map((h) => h.id);
      expect(new Set(hideIds).size, `${platform.id} duplicate hide id`).toBe(hideIds.length);
    }
  });

  it('never lets two platforms claim the same domain', () => {
    const domains = PLATFORMS.flatMap((p) => p.domains);
    expect(new Set(domains).size).toBe(domains.length);
  });

  it('compiles every path pattern as a regex that matches only whole pathnames', () => {
    for (const platform of PLATFORMS) {
      for (const gate of platform.gates) {
        for (const pattern of gate.paths) {
          const regex = gatePathRegex(pattern);
          expect(regex.source.startsWith('^')).toBe(true);
          expect(regex.source.endsWith('$')).toBe(true);
        }
      }
    }
  });

  it('starts every path pattern with a slash', () => {
    // Not cosmetic, and NOT covered by the URL-composition cases below: those build their
    // test URL from the pattern itself, so a pattern missing its '/' produces the bogus
    // URL "https://www.youtube.comshorts/x", which it then happily matches. The circularity
    // makes that whole class invisible unless it is asserted structurally. (Found by
    // deliberately planting the defect and watching the composition check wave it through.)
    for (const platform of PLATFORMS) {
      for (const gate of platform.gates) {
        for (const pattern of gate.paths) {
          expect(pattern.startsWith('/'), `${platform.id}/${gate.id}: ${pattern}`).toBe(true);
        }
      }
    }
  });

  it('uses only regex syntax RE2 also understands, so DNR and the page agree', () => {
    // RE2 (declarativeNetRequest's engine) has no lookaround and no backreferences. A
    // pattern using either compiles fine in the content script and is REJECTED by DNR,
    // leaving the surface gated in-SPA and unguarded on a full page load.
    const forbidden = /\(\?=|\(\?!|\(\?<|\\[1-9]/;
    for (const platform of PLATFORMS) {
      for (const gate of platform.gates) {
        for (const pattern of gate.paths) {
          expect(forbidden.test(pattern), `${platform.id}/${gate.id}: ${pattern}`).toBe(false);
        }
      }
    }
  });
});

describe('every path pattern survives being embedded in the DNR full-URL regex', () => {
  /**
   * The registry's `paths` are pathname fragments; DNR needs a whole-URL `regexFilter`, so
   * `background/dnr.ts` composes them into the shape below. That composition is where a
   * pattern can quietly stop working — it compiles, it just matches nothing, and the only
   * symptom is a surface that is gated in-SPA and wide open on a full page load.
   *
   * Asserting it HERE, over the whole registry, means adding a platform cannot introduce
   * that failure silently: a hand-written test per gate would only ever cover the gates
   * someone remembered to write a test for.
   */
  function escapeForRegex(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
  function gateUrlRegex(domain: string, pathPattern: string): RegExp {
    return new RegExp(
      `^https?://([^/:@?#]*\\.)?${escapeForRegex(domain)}(?::[0-9]+)?${pathPattern}(?:[?#].*)?$`,
    );
  }
  /** Turn a pattern into one concrete pathname it is supposed to match. */
  function concretePath(pattern: string): string {
    return pattern.replace(/\(\?:\/\.\*\)\?/g, '/x').replace(/\/\.\*$/, '/x').replace(/\\\./g, '.');
  }

  for (const platform of PLATFORMS) {
    for (const gate of platform.gates) {
      for (const pattern of gate.paths) {
        it(`${platform.id}/${gate.id}: ${pattern}`, () => {
          const domain = platform.domains[0]!;
          const regex = gateUrlRegex(domain, pattern);
          const path = concretePath(pattern);

          expect(regex.test(`https://www.${domain}${path}`)).toBe(true);
          // A query string or hash must not stop the surface being recognised.
          expect(regex.test(`https://www.${domain}${path}?a=b#c`)).toBe(true);
          // A lookalike host must never be caught by the domain anchor.
          expect(regex.test(`https://evil-${domain.replace('.', '-')}.example.com${path}`)).toBe(
            false,
          );
          if (pattern !== '/') {
            expect(regex.test(`https://www.${domain}/definitely-not-a-gate-surface`)).toBe(false);
          }
        });
      }
    }
  }
});

describe('platformForDomain', () => {
  it('finds a platform by its canonical domain', () => {
    expect(platformForDomain('youtube.com')?.id).toBe('youtube');
    expect(platformForDomain('instagram.com')?.id).toBe('instagram');
  });

  it('finds X by either of its domains, because twitter.com still resolves', () => {
    expect(platformForDomain('x.com')?.id).toBe('x');
    expect(platformForDomain('twitter.com')?.id).toBe('x');
  });

  it('is case- and whitespace-insensitive', () => {
    expect(platformForDomain('  YouTube.COM ')?.id).toBe('youtube');
  });

  it('returns null for an ordinary site, which is what makes features optional', () => {
    expect(platformForDomain('news.ycombinator.com')).toBeNull();
    expect(platformForDomain('example.com')).toBeNull();
  });
});

describe('quickAddPlatforms', () => {
  it('offers exactly one chip per platform, on its canonical domain', () => {
    const chips = quickAddPlatforms();
    expect(chips).toHaveLength(PLATFORMS.length);
    for (const chip of chips) {
      expect(platformForDomain(chip.domain)?.id).toBe(chip.platform);
    }
    // twitter.com is a matching alias, never something we would seed a new rule with.
    expect(chips.map((c) => c.domain)).not.toContain('twitter.com');
  });
});

describe('gateForUrl', () => {
  const cases: [Platform, string, string | null][] = [
    ['youtube', 'https://www.youtube.com/shorts/abc123', 'shorts'],
    ['youtube', 'https://www.youtube.com/shorts', 'shorts'],
    ['youtube', 'https://www.youtube.com/watch?v=abc', null],
    ['youtube', 'https://www.youtube.com/', null],

    ['instagram', 'https://www.instagram.com/reels/', 'reels'],
    ['instagram', 'https://www.instagram.com/reel/Cxyz/', 'reels'],
    ['instagram', 'https://www.instagram.com/explore/', 'explore'],
    ['instagram', 'https://www.instagram.com/', 'home'],
    ['instagram', 'https://www.instagram.com/someuser/', null],

    ['tiktok', 'https://www.tiktok.com/', 'foryou'],
    ['tiktok', 'https://www.tiktok.com/foryou', 'foryou'],
    ['tiktok', 'https://www.tiktok.com/following', 'foryou'],
    ['tiktok', 'https://www.tiktok.com/explore', 'explore'],
    ['tiktok', 'https://www.tiktok.com/live', 'live'],
    ['tiktok', 'https://www.tiktok.com/@someone/video/123', null],

    ['x', 'https://x.com/home', 'home'],
    ['x', 'https://x.com/explore', 'explore'],
    ['x', 'https://x.com/i/trending/123', 'explore'],
    ['x', 'https://x.com/notifications', 'notifications'],
    ['x', 'https://x.com/someone/status/1', null],

    ['facebook', 'https://www.facebook.com/', 'feed'],
    ['facebook', 'https://www.facebook.com/home.php', 'feed'],
    ['facebook', 'https://www.facebook.com/reel/123', 'reels'],
    ['facebook', 'https://www.facebook.com/watch', 'watch'],
    ['facebook', 'https://www.facebook.com/marketplace/item/1', 'marketplace'],

    ['reddit', 'https://www.reddit.com/', 'home'],
    ['reddit', 'https://www.reddit.com/r/popular', 'home'],
    ['reddit', 'https://www.reddit.com/r/all/top', 'home'],
    ['reddit', 'https://www.reddit.com/r/programming', null],

    ['linkedin', 'https://www.linkedin.com/feed/', 'feed'],
    ['linkedin', 'https://www.linkedin.com/in/someone', null],
  ];

  for (const [platform, url, expected] of cases) {
    it(`${platform}: ${url} -> ${expected ?? 'no gate'}`, () => {
      expect(gateForUrl(platform, url)).toBe(expected);
    });
  }

  it('ignores the query string and hash when matching', () => {
    expect(gateForUrl('x', 'https://x.com/home?foo=bar#baz')).toBe('home');
    // A "/" gate must not swallow a deeper path just because the query mentions it.
    expect(gateForUrl('instagram', 'https://www.instagram.com/?next=/reels/')).toBe('home');
  });

  it('prefers the more specific surface over a root gate', () => {
    // Both '/' (foryou) and '/explore' exist on TikTok; the specific one has to win.
    expect(gateForUrl('tiktok', 'https://www.tiktok.com/explore')).toBe('explore');
  });

  it('returns null rather than throwing on an unparseable URL', () => {
    expect(gateForUrl('youtube', 'not a url')).toBeNull();
    expect(gateForUrl('youtube', '')).toBeNull();
  });
});

describe('gateMatchesPath', () => {
  it('does not match a longer path that merely starts the same way', () => {
    const shorts = platformById('youtube').gates.find((g) => g.id === 'shorts')!;
    expect(gateMatchesPath(shorts, '/shorts')).toBe(true);
    expect(gateMatchesPath(shorts, '/shorts/abc')).toBe(true);
    // "/shortsomething" is a different page and must not be gated as Shorts.
    expect(gateMatchesPath(shorts, '/shortsomething')).toBe(false);
  });
});

describe('isKnownGate / isKnownHide', () => {
  it('accepts ids the platform defines', () => {
    expect(isKnownGate('youtube', 'shorts')).toBe(true);
    expect(isKnownHide('youtube', 'comments')).toBe(true);
  });

  it("rejects another platform's ids, which is what stops a stale key resurrecting", () => {
    expect(isKnownGate('youtube', 'reels')).toBe(false);
    expect(isKnownHide('linkedin', 'trends')).toBe(false);
    expect(isKnownGate('reddit', 'nonsense')).toBe(false);
  });
});
