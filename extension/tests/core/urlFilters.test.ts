import { describe, expect, it } from 'vitest';

import {
  domainRegexFilter,
  exactHostRegexFilter,
  gateRegexFilter,
  urlMatchesFilter,
} from '../../src/core/urlFilters';

describe('domainRegexFilter: a rule for youtube.com covers the whole site and only that site', () => {
  const filter = domainRegexFilter('youtube.com');

  it.each([
    'https://youtube.com',
    'https://youtube.com/',
    'https://www.youtube.com/watch?v=x',
    'https://m.youtube.com/feed',
    'https://a.b.youtube.com/x',
    'http://youtube.com:8080/x',
    'https://youtube.com?x=1',
    'https://youtube.com#frag',
  ])('blocks %s', (url) => {
    expect(urlMatchesFilter(url, filter)).toBe(true);
  });

  it.each([
    'https://notyoutube.com/',
    'https://youtube.com.evil.test/',
    'https://youtube.com@evil.test/',
    'https://evil.test/?u=youtube.com',
    'https://evil.test/youtube.com/',
    'ftp://youtube.com/',
  ])('does not catch the unrelated site %s', (url) => {
    expect(urlMatchesFilter(url, filter)).toBe(false);
  });
});

describe('gateRegexFilter: a path gate hits only that path', () => {
  const filter = gateRegexFilter('youtube.com', '/watch');

  it('matches /watch with a query string', () => {
    expect(urlMatchesFilter('https://www.youtube.com/watch?v=x', filter)).toBe(true);
    expect(urlMatchesFilter('https://youtube.com/watch', filter)).toBe(true);
  });

  it('does not match a longer path that merely starts with /watch', () => {
    expect(urlMatchesFilter('https://www.youtube.com/watchlater', filter)).toBe(false);
  });
});

describe('exactHostRegexFilter: one host and nothing that merely resembles it', () => {
  const filter = exactHostRegexFilter('studio.youtube.com');

  it.each([
    'https://studio.youtube.com/',
    'https://studio.youtube.com/channel/UC123/videos',
    'https://studio.youtube.com:8443/x',
    'https://STUDIO.youtube.com/',
  ])('opens Studio at %s', (url) => {
    expect(urlMatchesFilter(url, filter)).toBe(true);
  });

  it.each([
    'https://www.youtube.com/',
    'https://evil.studio.youtube.com/',
    'https://studio.youtube.com.evil.test/',
    'https://xstudio.youtube.com/',
    'https://studio.youtube.com@evil.test/',
  ])('keeps %s blocked', (url) => {
    expect(urlMatchesFilter(url, filter)).toBe(false);
  });
});

describe('urlMatchesFilter', () => {
  it('matches case-insensitively, like DNR, so /@Veritasium and /@veritasium agree', () => {
    const filter = gateRegexFilter('youtube.com', '/@veritasium(?:/.*)?');
    expect(urlMatchesFilter('https://www.youtube.com/@Veritasium', filter)).toBe(true);
  });

  it('treats an invalid regex as no match instead of throwing', () => {
    expect(urlMatchesFilter('https://youtube.com/', '([')).toBe(false);
  });
});

describe('every filter is anchored so DNR substitutes the whole URL', () => {
  it.each([
    ['domain', domainRegexFilter('youtube.com')],
    ['gate', gateRegexFilter('youtube.com', '/watch')],
    ['exact host', exactHostRegexFilter('studio.youtube.com')],
  ])('%s filter starts with ^ and ends with $', (_name, filter) => {
    expect(filter.startsWith('^')).toBe(true);
    expect(filter.endsWith('$')).toBe(true);
  });
});
