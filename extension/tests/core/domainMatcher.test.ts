import { describe, expect, it } from 'vitest';

import {
  dnrUrlFilter,
  extractDomain,
  matchesDomain,
  normalizeToBaseDomain,
  normalizeUserInput,
} from '../../src/core/domainMatcher';

/**
 * Port of app/src/test/java/.../WebDomainMatcherTest.kt, extended for the TS port's
 * wider internal-scheme set (chrome-extension, moz-extension, blob, view-source,
 * devtools, ...), userinfo stripping, and the illegal-hostname-character guard —
 * none of which exist in the Kotlin original (it only ever saw a Chrome URL bar).
 */

describe('extractDomain — happy paths', () => {
  it('extracts from a full URL with path/query', () => {
    expect(extractDomain('https://www.youtube.com/watch?v=abc')).toBe('youtube.com');
  });

  it('extracts from a bare domain', () => {
    expect(extractDomain('instagram.com')).toBe('instagram.com');
  });

  it('strips path, query, fragment, and port together', () => {
    expect(extractDomain('https://youtube.com:8080/watch?v=abc#t=10')).toBe('youtube.com');
  });

  it('strips userinfo (user:pass@host) before parsing the host', () => {
    expect(extractDomain('https://user:pass@example.com/path')).toBe('example.com');
  });

  it('normalizes uppercase input to lowercase', () => {
    expect(extractDomain('HTTPS://WWW.YOUTUBE.COM/WATCH')).toBe('youtube.com');
  });

  it.each(['www', 'm', 'mobile', 'l', 'lm'])('strips the known "%s." subdomain prefix', (sub) => {
    expect(extractDomain(`${sub}.youtube.com/path`)).toBe('youtube.com');
  });

  it('leaves a NON-known multi-part subdomain intact', () => {
    expect(extractDomain('foo.bar.example.com')).toBe('foo.bar.example.com');
  });

  it('leaves a two-part host untouched', () => {
    expect(extractDomain('example.com')).toBe('example.com');
  });
});

describe('extractDomain — returns null', () => {
  it('for a blank string', () => {
    expect(extractDomain('')).toBeNull();
  });

  it('for a whitespace-only string', () => {
    expect(extractDomain('   ')).toBeNull();
  });

  it('for chrome://', () => {
    expect(extractDomain('chrome://extensions')).toBeNull();
  });

  it('for chrome-extension://', () => {
    expect(extractDomain('chrome-extension://abc/blocked.html')).toBeNull();
  });

  it('for about:blank', () => {
    expect(extractDomain('about:blank')).toBeNull();
  });

  it('for javascript:', () => {
    expect(extractDomain('javascript:void(0)')).toBeNull();
  });

  it('for file://', () => {
    expect(extractDomain('file:///tmp/x.html')).toBeNull();
  });

  it('for data:', () => {
    expect(extractDomain('data:text/html,x')).toBeNull();
  });

  it('for a host with no dot ("localhost")', () => {
    expect(extractDomain('localhost')).toBeNull();
    expect(extractDomain('localhost:3000/api')).toBeNull();
  });

  it('for a host containing illegal characters', () => {
    expect(extractDomain('exam_ple.com')).toBeNull();
  });
});

describe('normalizeToBaseDomain', () => {
  it('strips a known subdomain prefix', () => {
    expect(normalizeToBaseDomain('www.instagram.com')).toBe('instagram.com');
  });

  it('leaves a two-part host untouched', () => {
    expect(normalizeToBaseDomain('instagram.com')).toBe('instagram.com');
  });

  it('leaves a non-known multi-part subdomain intact', () => {
    expect(normalizeToBaseDomain('api.example.com')).toBe('api.example.com');
  });
});

describe('matchesDomain', () => {
  it('matches a www subdomain against a bare-domain rule', () => {
    expect(matchesDomain('https://www.youtube.com/feed', 'youtube.com')).toBe(true);
  });

  it('matches an m subdomain against a bare-domain rule', () => {
    expect(matchesDomain('https://m.youtube.com/shorts', 'youtube.com')).toBe(true);
  });

  it('does not match a different domain', () => {
    expect(matchesDomain('https://vimeo.com/123', 'youtube.com')).toBe(false);
  });

  it('does not match when the rule domain is empty', () => {
    expect(matchesDomain('https://youtube.com', '')).toBe(false);
  });

  it('does NOT false-positive on a superstring domain ("notyoutube.com" vs "youtube.com")', () => {
    expect(matchesDomain('https://notyoutube.com/watch', 'youtube.com')).toBe(false);
  });

  it('returns false for an unparseable URL', () => {
    expect(matchesDomain('chrome://settings', 'settings.com')).toBe(false);
  });
});

describe('dnrUrlFilter', () => {
  it('produces the anchored ||domain^ filter', () => {
    expect(dnrUrlFilter('youtube.com')).toBe('||youtube.com^');
  });

  it('normalizes a www-prefixed rule domain to the same filter', () => {
    expect(dnrUrlFilter('www.youtube.com')).toBe('||youtube.com^');
  });
});

describe('normalizeUserInput', () => {
  it('accepts a pasted full URL', () => {
    expect(normalizeUserInput('https://www.youtube.com/feed')).toBe('youtube.com');
  });

  it('accepts a bare domain with a known subdomain prefix', () => {
    expect(normalizeUserInput('m.youtube.com')).toBe('youtube.com');
  });

  it('accepts a plain bare domain', () => {
    expect(normalizeUserInput('youtube.com')).toBe('youtube.com');
  });

  it('returns null on garbage input', () => {
    expect(normalizeUserInput('this is not a url')).toBeNull();
  });
});
