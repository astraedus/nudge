# Nudge for Chrome — MV3 Extension

The browser sibling of the Nudge Android app. Same philosophy: **friction, not walls** —
delay-to-open and breathing pauses instead of only hard blocks, plus daily time budgets,
schedules, YouTube Shorts controls, and local-only usage stats.

Free, GPL-3.0, **no account, zero telemetry, zero network requests.**

- Product spec: `~/ops/routes/nudge/research/ext-07-prd.md` (the MVP feature cut)
- Architecture: `~/ops/routes/nudge/research/ext-08-architecture.md` (fixed decisions)
- MV3 recipes: `~/ops/routes/nudge/research/ext-01-mv3-architecture.md`
- YouTube techniques: `~/ops/routes/nudge/research/ext-03-youtube-techniques.md`
- Android semantics being ported: `~/ops/routes/nudge/research/ext-05-android-feature-inventory.md`

## Commands

```bash
cd extension
npm install            # postinstall runs `wxt prepare` (generates .wxt/ types)
npm run dev            # WXT dev server with hot reload
npm run build          # production build -> .output/chrome-mv3
npm run zip            # packaged zip for the Chrome Web Store

npm run lint           # eslint
npm run typecheck      # wxt prepare && tsc --noEmit
npm test               # vitest (unit + fixture tests)
npm run e2e            # Playwright (needs `npm run build` first)
```

**Node/npm only — never bun/bunx.**

E2E locally needs a display; use `xvfb-run -a npm run e2e` (that is what CI does).
Run `npx playwright install chromium` once.

### Load unpacked in Chrome

1. `npm run build`
2. `chrome://extensions` → enable **Developer mode**
3. **Load unpacked** → select `extension/.output/chrome-mv3`

To test in Incognito you must explicitly turn on **Allow in Incognito** for the extension —
Chrome disables extensions there by default. (The onboarding page tells users this; it was a
recurring complaint about competitors.)

## Architecture

```
src/
  core/         PURE TypeScript. ZERO chrome.* imports. Ported from Android's pure-Kotlin
                domain layer, along with its test intent.
    types.ts, settingsSchema.ts, protocol.ts
    blockEngine.ts      <- domain/engine/BlockEngine.kt
    scheduleEvaluator.ts<- domain/engine/ScheduleEvaluator.kt
    domainMatcher.ts    <- domain/WebDomainMatcher.kt
    strictMode.ts       <- domain/lock/StrictModeChallenge.kt + RuleWeakening.kt
    emergencyPass.ts    <- domain/emergency/EmergencyPass.kt
    stats.ts            <- ui/screens/stats/StatsCalculator.kt
    budgets.ts, messages.ts, ruleResolver.ts
  background/   The service worker: dnr, tracker, tempAllow, alarmsHub, badge,
                messagesRouter, storage.
  content/      YouTube: selectors.ts (ALL selectors), youtube.ts, youtube.css
  entrypoints/  WXT entrypoints: background, blocked/, popup/, dashboard/, onboarding/,
                youtube.content.ts
  ui/           Shared React primitives + design tokens + typed rpc wrapper
tests/          vitest (core/, ui/, content/ + DOM fixtures)
e2e/            Playwright against a real Chrome with the extension loaded
```

**Dependency rule:** `core/` never imports from `background/`, `content/`, `ui/` or
`chrome.*`. Everything else may import `core/`. That is what keeps the engine exhaustively
unit-testable, exactly as the Android domain layer is.

### How blocking actually works

1. Settings change → `background/dnr.ts` recompiles **dynamic** DNR rules. Every enabled
   rule becomes ONE `main_frame` redirect to `blocked.html`. Hard Block, Delay and Breathing
   all start at the interstitial, so the network layer is mode-agnostic.
2. The block page asks the worker (`GET_BLOCK_CONTEXT`); the **engine** decides which of the
   three to render. The page never decides.
3. Completing a Delay/Breathing pause → `COMPLETE_PAUSE` → a **session** allow-rule at a
   higher priority + an expiry alarm. On expiry the allow-rule is removed so the *next*
   navigation re-blocks (the open page is never yanked away mid-read).
4. Crossing a Daily Time Limit inside an accounting step immediately revokes any grant and
   pushes open tabs on that domain to the block page.
5. YouTube SPA navigation is invisible to DNR, so `content/youtube.ts` handles in-page
   Shorts with 3-layer nav detection (`yt-navigate-finish` + `popstate` + debounced
   observer).

## Non-negotiables

- **Zero network requests.** No telemetry, no remote config, no CDN. All selectors are
  bundled. `no-restricted-globals` bans `fetch` in lint so this stays true, not aspirational.
- **Usage data never leaves the device.** Settings go to `storage.sync` (free cross-device
  sync via the user's Chrome account); usage rollups are `storage.local` ONLY.
- Android naming parity: "Hard Block" / "Delay" / "Breathing", "Daily Time Limit",
  "Scheduled Override", "Commitment Lock", "Escape Hatch", "I changed my mind", "Rule: X".
- Teal in-app palette (`src/ui/tokens.css`), light + dark. The maroon palette is
  **marketing only** and never appears in the UI.
- **No emoji as icons.** Inline SVG or nothing.
- Every feature ships with tests (repo rule). Bug fix ⇒ a regression test that fails before.

## Release

Version lives in `extension/package.json` and is independent of the Android app's.
Tag format is **`ext-v*`** (e.g. `ext-v0.1.0`) so it can never collide with the Android
`v*` release flow.

**No `ext-v*` tag exists yet and none should be created until the extension is ready to
publish.** CI does not currently build releases from tags — wire that up together with the
Chrome Web Store upload when the listing is ready (ext-04 §5).

CI: `.github/workflows/extension-ci.yml` (lint → typecheck → unit → build → Playwright under
xvfb), scoped with `paths: ['extension/**']`. The Android workflow carries the mirror-image
`paths-ignore: ['extension/**']`.

## Lessons

- **A DNR redirect target MUST be in `web_accessible_resources`.** Redirecting to a page the
  extension owns still fails *silently* otherwise (w3c/webextensions#604).
- **`regexSubstitution` needs `regexFilter`, and `\0` is the ENTIRE match** — so the pattern
  must be anchored `^...$` for `\0` to be the whole URL rather than just its prefix. This is
  how the original URL reaches the block page.
- **Never parse that target with `URLSearchParams`.** `regexSubstitution` cannot
  percent-encode, so the target arrives verbatim and routinely contains its own `?`/`&`/`#`.
  URLSearchParams truncated `watch?v=abc&t=30` to `watch?v=abc` and sent users to the wrong
  page after a pause. Read `location.href` and slice after the marker. Regression tests:
  `tests/ui/blockPage.test.tsx` → "target parsing".
- **Never accumulate elapsed time in a service-worker global.** The worker dies after ~30s
  idle. Every accounting step is an atomic read-modify-write against `storage.session`.
- **Re-arm alarms on every worker wake.** Alarms are not guaranteed to survive a restart.
  Midnight self-reschedules on an absolute `when`, because `periodInMinutes: 1440` drifts off
  local midnight across a DST change.
- **Return `true` from the `onMessage` listener** to keep the channel open for an async
  response, and always send *something* — otherwise callers hang forever.
- **Gate settings changes in the WORKER, not the UI.** A gate a page can skip is not a gate.
- **ENGINE INVARIANT: if any rule applies, the verdict is a BLOCK.** ALLOW means "no rule
  applies here" and nothing else. DNR has already redirected by the time the engine runs, so
  an ALLOW while a rule still applies is not a harmless no-op — it bounces the user back to
  the site, straight into the redirect again: an infinite loop that also hammers the worker.
  Any new mode or qualifier MUST get its own branch in `blockEngine.ts`.
- **A test that asserts the bug is worse than no test.** The Hard-Block-plus-Daily-Limit
  redirect loop survived 400 unit tests and 24 e2e specs because TWO unit tests had encoded
  the buggy `ALLOW` as the expected result — they were written from a spec sentence
  ("a Hard Block rule with a limit is NOT unconditional") that described the implementation
  rather than the desired behaviour. When writing a test from a spec, state the USER-VISIBLE
  outcome ("the site is blocked"), never the internal branch it should take. The engine now
  has an exhaustive mode x limit x usage matrix asserting the invariant directly, so the
  whole class is covered rather than the one reported instance.
- **A meaningless field combination is a UI bug, not just an engine one.** A daily limit on a
  Hard Block can never mean anything; `RuleEditor` now explains that instead of offering the
  control, so the invalid state cannot be authored in the first place.
- **React hooks before early returns.** `DelayView`/`BreathingView` returned `null` before
  calling their hooks; `react-hooks/rules-of-hooks` caught it. The fix needed an `enabled`
  flag on `useCompleteOnZero`, because a disarmed view has a zero-length countdown and
  "remaining === 0" would otherwise complete the pause instantly and grant real access.
- **e2e needs real hostnames without a network.** `--host-resolver-rules=MAP *.test
  127.0.0.1:<port>` maps arbitrary hosts onto a local server, so DNR sees ordinary
  navigations. Extensions load only via `launchPersistentContext` + `--load-extension`.
- **"Browser has been closed" in an e2e fixture usually means OOM**, not a code bug — this
  machine runs `earlyoom` with `--prefer ^chrome$`. The lean Chrome flags in `e2e/fixtures.ts`
  exist for that reason.
- **A `<button>` inside `role="tablist"` is exposed as role `tab`, not `button`.** The
  dashboard's Stats/Settings tabs are invisible to `getByRole('button')` and only match
  `getByRole('tab')`. Correct behaviour, surprising in tests — when a locator finds nothing
  that plainly exists in the DOM, dump the a11y roles before assuming the page is broken.
- **The dashboard paints a loading state first** and fills in when `GET_DASHBOARD_STATE`
  resolves, which is slower when the worker was asleep. e2e must wait for loaded content,
  not assume an instant render.
- The React Compiler advisory lint rules (`set-state-in-effect`,
  `preserve-manual-memoization`, `use-memo`) are deliberately **off**; `rules-of-hooks` is
  deliberately **on** and has already earned its keep.

## Known gaps (MVP)

- **A rule always blocks in some mode; there is no "allow by default, block only during the
  window" schedule.** Outside a Scheduled Override the rule's own "Default Behavior" applies
  — faithful to Android, but users asking for "block only during work hours" cannot express
  it yet. Likely the first schedule follow-up.
- Strict Mode cannot stop removal from `chrome://extensions`. The dashboard says so plainly
  rather than pretending; honesty is the differentiator (ext-02).
- YouTube fixtures in `tests/content/fixtures/` are hand-authored from the ext-03 taxonomy,
  not live DOM captures. Refresh them from real YouTube DOM when possible.
- v1.1 scope (channel whitelist/blacklist, gray-screen mode, Instagram/TikTok/X surfaces) is
  specified in the PRD but not built.
