import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  applyRepeatPauseScript,
  injectIntoOpenTabs,
  repeatPauseMatches,
  REPEAT_PAUSE_SCRIPT_FILE,
  REPEAT_PAUSE_SCRIPT_ID,
} from '../../src/background/repeatPauseScript';
import { settings, siteRule } from '../helpers/rules';
import {
  installAttention,
  installScripting,
  resetBrowser,
  type AttentionState,
  type ScriptingState,
} from './fakeApis';

/**
 * A rule can be on any site, so this script is registered only for the sites that asked. What
 * a user would notice: the repeat starts on a site they just turned it on for without a reload,
 * and no site without it ever runs our script.
 */

let scripting: ScriptingState;
let attention: AttentionState;

function registered(): chrome.scripting.RegisteredContentScript | undefined {
  return scripting.scripts.find((script) => script.id === REPEAT_PAUSE_SCRIPT_ID);
}

function withRepeat(...domains: string[]) {
  return settings({
    rules: domains.map((domain, index) =>
      siteRule({ id: `r${index}`, domain, mode: 'DELAY', repeatPauseMinutes: 10 }),
    ),
  });
}

beforeEach(() => {
  resetBrowser();
  scripting = installScripting();
  attention = installAttention();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('repeatPauseMatches', () => {
  it('lists the bare domain and its subdomains, only for rules with a repeat', () => {
    const config = settings({
      rules: [
        siteRule({ id: 'a', domain: 'news.test', mode: 'DELAY', repeatPauseMinutes: 5 }),
        siteRule({ id: 'b', domain: 'other.test', mode: 'DELAY', repeatPauseMinutes: null }),
      ],
    });
    expect(repeatPauseMatches(config)).toEqual(['*://*.news.test/*', '*://news.test/*']);
  });

  it('covers a platform’s other domains, so an x.com rule reaches twitter.com', () => {
    expect(repeatPauseMatches(withRepeat('x.com'))).toEqual([
      '*://*.twitter.com/*',
      '*://*.x.com/*',
      '*://twitter.com/*',
      '*://x.com/*',
    ]);
  });

  it('keeps a rule whose mode is ALLOW, because a schedule can make it a Delay with no save', () => {
    const config = settings({
      rules: [siteRule({ domain: 'news.test', mode: 'ALLOW', repeatPauseMinutes: 5 })],
    });
    expect(repeatPauseMatches(config)).toHaveLength(2);
  });

  it('is empty for a switched-off rule and while Nudge itself is off', () => {
    const disabled = settings({
      rules: [siteRule({ domain: 'news.test', enabled: false, repeatPauseMinutes: 5 })],
    });
    const nudgeOff = settings({
      globalEnabled: false,
      rules: [siteRule({ domain: 'news.test', repeatPauseMinutes: 5 })],
    });
    expect(repeatPauseMatches(disabled)).toEqual([]);
    expect(repeatPauseMatches(nudgeOff)).toEqual([]);
  });

  it('drops a host Chrome would reject, which would otherwise lose every other site too', () => {
    const config = settings({
      rules: [
        siteRule({ id: 'a', domain: 'news.test', repeatPauseMinutes: 5 }),
        siteRule({ id: 'b', domain: 'bad host/*', repeatPauseMinutes: 5 }),
      ],
    });
    expect(repeatPauseMatches(config)).toEqual(['*://*.news.test/*', '*://news.test/*']);
  });
});

describe('applyRepeatPauseScript', () => {
  it('registers the built script in the top frame, surviving a browser restart', async () => {
    await applyRepeatPauseScript(withRepeat('news.test'));
    expect(registered()).toMatchObject({
      js: [REPEAT_PAUSE_SCRIPT_FILE],
      allFrames: false,
      runAt: 'document_idle',
      persistAcrossSessions: true,
      matches: ['*://*.news.test/*', '*://news.test/*'],
    });
  });

  it('leaves the registration alone when nothing changed', async () => {
    await applyRepeatPauseScript(withRepeat('news.test'));
    scripting.calls.length = 0;
    await applyRepeatPauseScript(withRepeat('news.test'));
    expect(scripting.calls).toEqual(['get']);
  });

  it('updates in place when the site list changes', async () => {
    await applyRepeatPauseScript(withRepeat('news.test'));
    scripting.calls.length = 0;
    await applyRepeatPauseScript(withRepeat('news.test', 'feed.test'));
    expect(scripting.calls).toContain('update');
    expect(scripting.calls).not.toContain('register');
    expect(registered()?.matches).toContain('*://feed.test/*');
  });

  it('unregisters at zero, and a second pass does not ask Chrome to remove what is gone', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await applyRepeatPauseScript(withRepeat('news.test'));
    await applyRepeatPauseScript(settings({ rules: [] }));
    expect(registered()).toBeUndefined();

    scripting.calls.length = 0;
    await applyRepeatPauseScript(settings({ rules: [] }));
    expect(scripting.calls).toEqual(['get']);
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('two racing passes (save + onChanged) do not surface Chrome’s unknown-id rejection', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await applyRepeatPauseScript(withRepeat('news.test'));
    await Promise.all([
      applyRepeatPauseScript(settings({ rules: [] })),
      applyRepeatPauseScript(settings({ rules: [] })),
    ]);
    expect(registered()).toBeUndefined();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('recovers when the query throws on an id that already exists', async () => {
    await applyRepeatPauseScript(withRepeat('news.test'));
    scripting.throwOnQuery = true;
    await applyRepeatPauseScript(withRepeat('news.test', 'feed.test'));
    expect(registered()?.matches).toContain('*://feed.test/*');
  });

  it('never throws out of the blocking path, and a later pass still works', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const original = chrome.scripting.registerContentScripts;
    const update = chrome.scripting.updateContentScripts;
    chrome.scripting.registerContentScripts = vi.fn().mockRejectedValue(new Error('boom'));
    chrome.scripting.updateContentScripts = vi.fn().mockRejectedValue(new Error('boom'));
    await expect(applyRepeatPauseScript(withRepeat('news.test'))).resolves.toBeUndefined();
    expect(consoleError).toHaveBeenCalled();

    chrome.scripting.registerContentScripts = original;
    chrome.scripting.updateContentScripts = update;
    await applyRepeatPauseScript(withRepeat('news.test'));
    expect(registered()).toBeDefined();
  });
});

describe('starting without a reload', () => {
  it('injects into open tabs of a newly covered site', async () => {
    attention.tabs = [{ id: 7, url: 'https://news.test/a' }];
    await applyRepeatPauseScript(withRepeat('news.test'));
    expect(scripting.injected).toEqual([{ tabId: 7, files: [REPEAT_PAUSE_SCRIPT_FILE] }]);
  });

  it('does not inject again on a wake or save that changed nothing', async () => {
    attention.tabs = [{ id: 7, url: 'https://news.test/a' }];
    await applyRepeatPauseScript(withRepeat('news.test'));
    scripting.injected.length = 0;
    await applyRepeatPauseScript(withRepeat('news.test'));
    expect(scripting.injected).toEqual([]);
  });

  it('asks only for the sites just added, so sites already running are not re-injected', async () => {
    await applyRepeatPauseScript(withRepeat('news.test'));
    const query = vi.spyOn(chrome.tabs, 'query');
    await applyRepeatPauseScript(withRepeat('news.test', 'feed.test'));
    expect(query).toHaveBeenCalledWith({ url: ['*://*.feed.test/*', '*://feed.test/*'] });
  });

  it('does not touch tabs at all when the feature is being switched off', async () => {
    await applyRepeatPauseScript(withRepeat('news.test'));
    const query = vi.spyOn(chrome.tabs, 'query');
    await applyRepeatPauseScript(settings({ rules: [] }));
    expect(query).not.toHaveBeenCalled();
  });

  it('one tab refusing does not stop the others', async () => {
    attention.tabs = [
      { id: 7, url: 'https://news.test/a' },
      { id: 8, url: 'https://news.test/b' },
    ];
    scripting.refuseInjectionInto = [7];
    await injectIntoOpenTabs(['*://news.test/*']);
    expect(scripting.injected.map((entry) => entry.tabId)).toEqual([8]);
  });

  it('a failed tab query never reaches the save that got here', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(chrome.tabs, 'query').mockRejectedValue(new Error('no tabs'));
    await expect(applyRepeatPauseScript(withRepeat('news.test'))).resolves.toBeUndefined();
    expect(registered()).toBeDefined();
    expect(consoleError).toHaveBeenCalled();
  });
});
