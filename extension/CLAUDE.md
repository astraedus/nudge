# Nudge for Chrome — MV3 Extension

The browser sibling of the Nudge Android app. Same philosophy: **friction, not walls** —
delay-to-open and breathing pauses instead of only hard blocks, plus daily time budgets,
schedules, local-only usage stats, and per-site control of the feeds themselves.

**The site RULE is the unit.** Everything about a site lives inside its rule: how the whole
site behaves (Allow / Hard Block / Delay / Breathing), the pause length, the daily budget,
the schedule override, whether the site is grayscaled, and — for the seven known platforms —
that site's *features*: URL-addressable **gates** (YouTube Shorts, Instagram Reels, the X
home timeline) each with their own mode, delay and budgets (time AND, on a short-form item
stream, a count of items), boolean **hides** for page
elements, and YouTube's channel lists. There is no separate YouTube tab.

Free, GPL-3.0, **no account, zero telemetry, zero network requests.**

- Per-site rules design (v0.2): `~/ops/routes/nudge/research/ext-13-per-site-rules-design.md`
- Platform selectors + per-platform strategy: `~/ops/routes/nudge/research/ext-12-reels-and-feeds-web-techniques.md`
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
    platforms.ts        (the platform registry: which gates/hides exist per platform,
                         their labels and their URL PATH PATTERNS)
    applies.ts          (siteRuleAppliesNow / gateAppliesNow -- THE predicate)
    blockEngine.ts      <- domain/engine/BlockEngine.kt
    scheduleEvaluator.ts<- domain/engine/ScheduleEvaluator.kt
    domainMatcher.ts    <- domain/WebDomainMatcher.kt
    strictMode.ts       <- domain/lock/StrictModeChallenge.kt + RuleWeakening.kt
    emergencyPass.ts    <- domain/emergency/EmergencyPass.kt
    stats.ts            <- ui/screens/stats/StatsCalculator.kt
    channels.ts         (channel-list decision matrix; pure, no DOM)
    surfaceKeys.ts      ("domain#gateId" usage buckets for per-feature budgets)
    featureSummary.ts   (the one "what is switched on for this site" line)
    budgets.ts, messages.ts, ruleResolver.ts, channelFreshness.ts
    seenItems.ts        (the day-scoped "already counted this item" set)
  background/   The service worker: dnr, tracker, itemCounter, tempAllow, alarmsHub,
                badge, messagesRouter, storage, grayscale.
  content/      spaNav.ts   (the generic SPA-navigation layer), overlay.ts (the shared
                             in-page gate overlay + the media hold + the bail helper) —
                             BOTH shared by all seven platform scripts, YouTube included
                itemCounter.ts (reports "we are on one item of a gate's stream")
                YouTube: selectors.ts (ALL selectors), youtube.ts, youtube.css,
                channelDetection.ts (3-tier channel identification), channelFilter.ts
                (feed filtering + watch gate + colour flip), channelObserver.ts (reports a
                CONFIRMED channel so the stored entry can learn its missing identifier),
                unhook.ts (hide toggles)
                <platform>/{selectors,index}.ts for instagram, tiktok, x, facebook,
                reddit, linkedin -- same architecture, one CSS class per hide feature
  entrypoints/  WXT entrypoints: background, blocked/, popup/, dashboard/, onboarding/,
                and one <platform>.content.ts per supported platform
  ui/           Shared React primitives + design tokens + typed rpc wrapper
tests/          vitest (core/, ui/, content/, background/ + DOM fixtures)
                helpers/rules.ts is the ONE fixture builder -- add a schema field there,
                not in each test file
e2e/            Playwright against a real Chrome with the extension loaded
```

**The registry owns URL shape; the content scripts own DOM shape.** A gate's path patterns
live in `core/platforms.ts` because DNR (full page loads, typed URLs) and the in-SPA overlay
must agree on what "the Reels surface" IS; two copies of that answer is how a feature ends
up enforced on one path and wide open on the other. Selectors stay in
`content/<platform>/selectors.ts` because they rot on a completely different schedule.

**Dependency rule:** `core/` never imports from `background/`, `content/`, `ui/` or
`chrome.*`. Everything else may import `core/`. That is what keeps the engine exhaustively
unit-testable, exactly as the Android domain layer is.

### How blocking actually works

0. **`core/applies.ts` decides whether a rule is in force, and nothing else does.**
   `siteRuleAppliesNow(rule, usageMs, now)` is called by DNR compilation, the engine's rule
   resolver, the popup and the badge. Before v0.2 "a rule exists" and "a rule is enforced"
   were the same fact; Allow mode split them, and four consumers answering that question
   separately is how a user gets a block page for a site the popup calls "Allowed".
   A rule applies when it is enabled AND the resolved mode at `now` (schedule considered) is
   a BlockMode, **or** the mode is ALLOW but the daily budget is spent.
1. Settings change → `background/dnr.ts` recompiles **dynamic** DNR rules from
   (settings, exhausted-budget set, now). Every rule that APPLIES becomes ONE `main_frame`
   redirect to `blocked.html`; an Allow rule under budget compiles nothing. Hard Block,
   Delay and Breathing all start at the interstitial, so the network layer is mode-agnostic.
   Recompilation is triggered by a settings save, a worker wake, an accounting step that
   crosses a limit, the midnight reset, and a schedule-boundary alarm.
1b. **Gate surfaces** (`/shorts/`, `/reels/`, `/home`, ...) compile their own redirects at a
   priority ABOVE the site rule, from the path patterns in `core/platforms.ts`. That is what
   lets a site be Allowed while one of its surfaces is blocked. In-SPA navigation to the same
   surface is the content script's job — DNR cannot see it.
1c. **YouTube channel allow-rules.** When the youtube.com rule applies AND a whitelist with
   ≥1 channel is on, `/watch` and each listed channel's `/@handle` and `/channel/UC…` get
   `allow` rules above the site redirect, while `/`, `/feed/*`, `/results` and `/shorts/`
   stay redirected. A channel identifier containing anything outside `[A-Za-z0-9_.-]` is
   SKIPPED rather than interpolated, so a hand-edited settings blob cannot inject a pattern
   that allows all of YouTube. **An EXHAUSTED daily limit compiles no allow-rules**: a limit
   budgets how MUCH of the site, a channel list restricts WHAT, so once the budget is spent
   there is no allowance left to carve out of — otherwise "1 hour of YouTube a day" would be
   unlimited for allowed channels and the limit would only ever bite the content the user
   asked for less of.
1d. **The priority ladder**: site redirect 1 < gate redirect 2 < channel allow 3 < temp allow 4.
   Rung 4 above rung 2 is deliberate — a pause completed ON a gate surface must not bounce
   straight back into that gate's redirect. The cost is that a site-level grant also opens
   the site's gate surfaces at the network layer for the grant window, which is acceptable
   ONLY because every gate surface has a content script that gates it in-page on full loads
   too. So `GET_SITE_CONFIG` must never resolve a gate to OFF because a site temp-allow is
   live: the gate's own mode stands, and only a pause completed on the gate surface
   satisfies the gate.
2. The block page asks the worker (`GET_BLOCK_CONTEXT`); the **engine** decides which of the
   three to render. The page never decides.
3. Completing a Delay/Breathing pause → `COMPLETE_PAUSE` → a **session** allow-rule at a
   higher priority + an expiry alarm. On expiry the allow-rule is removed so the *next*
   navigation re-blocks (the open page is never yanked away mid-read).
4. Crossing a Daily Time Limit inside an accounting step immediately revokes any grant and
   pushes open tabs on that domain to the block page.
5. SPA navigation is invisible to DNR, so the content scripts handle in-page navigation to
   a gated surface with layered nav detection (the site's own nav event where one exists,
   `popstate`/`hashchange`, an href-diff poll, and a debounced observer as a re-apply safety
   net). **Isolated-world content scripts cannot observe the page's own `history.pushState`**
   — confirmed independently on YouTube, TikTok, X, Instagram and Facebook — which is why the
   poll is not optional. `content/spaNav.ts` is that layer and EVERY platform script uses
   it, YouTube included. YouTube configures it with its own cadence (`yt-navigate-finish` as
   the site event, a 1s poll, a 250ms mutation debounce, and `mutateOnIdlePoll` so a page
   that has gone quiet is still re-checked) and keeps only the parts that are genuinely
   YouTube-shaped: `previousChannelKey`, `navAt` and the fixed `SETTLE_RECHECK_MS` ladder.
   The interstitial itself is `content/overlay.ts`'s for every platform; only the element id
   differs, and `youtube.css` keys its backdrop rule off YouTube's.
6. **Grayscale is per site.** `background/grayscale.ts` derives one dynamic registration's
   `matches` from the set of enabled rules with `grayscale: true`, compares it against what
   is currently registered on every wake/save, and only re-registers when it differs.
   Zero gray domains ⇒ unregistered.
7. **Feature budgets, on TWO independent axes.** The tracker attributes a focused tab's
   time to the site bucket and, when the URL is on a gate surface, also to `domain#gateId`
   in the same `DayUsage` map. Anything that LISTS sites must filter surface keys out
   (`core/surfaceKeys.ts`) or the stats table grows a phantom "youtube.com#shorts" site.

   Since v0.3 a gate can also carry a **count** budget ("20 Shorts a day"), stored as
   `DayUsage.items` on that same surface key. Minutes and count are INDEPENDENT: either,
   both or neither may be set, and `core/applies.ts#gateAppliesNow` applies the gate once
   EITHER is spent, with `reason: 'count-exhausted'` so the block page can name it. Both
   caps are checked BEFORE the gate's mode, because an exhausted budget is unconditional
   and the engine escalates it to a Hard Block anyway — asking the mode first is how the
   in-page overlay came to offer a Delay pause that bought access the block page refused.

   **One item = one `itemPaths` match in `core/platforms.ts`, deduped per day.** An item
   pattern carries exactly one capture group, and that group is the id, so `/shorts/abc`
   and `/shorts/abc/` are the same item and re-watching one does not spend two. The day's
   ids live in `core/seenItems.ts` under one day-stamped key, so the midnight reset falls
   out of reading it and the set cannot accumulate across days (capped at 2,000 per
   surface; past the cap the OLDEST ids drop, which over-counts rather than under-counts).

   **`itemPaths` are NOT surface paths, and the two questions stay two functions.**
   `gateForUrl` answers "is this URL on the gate's surface" (what DNR redirects and what
   the in-page gate enforces); `itemForUrl` answers "is this URL one item of the gate's
   stream" (what the count measures). On YouTube and Instagram the item paths are a subset
   of the surface paths, so the two agree. On TikTok a For You item is `/@user/video/<id>`,
   which is NOT the For You surface — a link a friend sent you must not be treated as the
   feed. Collapsing them would gate that link in-page while DNR let the same URL through on
   a full load, the exact half-enforced split the one-string `paths` rule exists to prevent.
   The visible consequence is honest and documented: on TikTok a crossed count takes effect
   the next time the user lands on the feed itself, not mid-swipe.

   **The page reports, the worker counts.** A content script sends `ITEM_VIEWED` with its
   URL and nothing else; `background/itemCounter.ts` resolves the gate, de-duplicates,
   increments, and fires the crossing (revoke grant -> recompile DNR -> redirect the open
   tabs that are on that gate's SURFACE). A tally kept in the page is a tally the user can
   edit with devtools open on the very surface it limits. The shared controller in
   `content/platformGate.ts` wires the reporter for all six non-YouTube platforms; YouTube
   starts a standalone counter from its ENTRYPOINT instead, leaving `content/youtube.ts`
   untouched. YouTube now shares `overlay.ts` and `spaNav.ts`, but it still runs its OWN
   controller rather than `initPlatformContentScript`, so the shared reporter never fires
   there. Keeping the counter in the entrypoint is deliberate even so: counting is a pure
   observation with no verdict of its own, and hanging it off the channel-freshness settle
   machinery would couple it to the one piece of this codebase whose regressions are
   live-only, for no user-visible gain. The cost is a second `observeNavigation` on a
   YouTube page (one poll, one observer), which is cheap and buys that isolation.

   **Reels mixed into a feed still cannot be counted**, for the same reason they cannot be
   filtered (see Known gaps): they have no per-item URL. Only whole-surface item streams
   carry `itemPaths`, and a gate without them never offers a count control — a limit that
   can never increment is indistinguishable from a broken blocker.

### Channel lists, gray-screen and the hide toggles (v1.1)

- **Channel identification is three-tier** (ext-03 §3): `ytInitialPlayerResponse.videoDetails`
  first, then an `ytInitialData` brace-counting scan, then a DOM selector chain. YouTube
  ships different shapes on different surfaces, so one path is not enough.
- **THE INLINE TIERS GO STALE ON SPA NAVIGATION.** YouTube does not rewrite
  `ytInitialPlayerResponse` / `ytInitialData` on a watch -> watch client-side hop, so they
  keep describing whatever video was last FULL-loaded. The fast tier is therefore the
  *stalest* one during normal browsing. Both inline tiers must prove they describe the
  current video (their declared videoId === the URL's `v`); a tier that declares a different
  id, or none at all, is skipped in favour of the DOM byline, which YouTube really does
  re-render. Live QA caught this bypassing the whitelist, blacklist AND gray-screen at once.
- **THE BYLINE ITSELF LAGS TOO, hence the settle window.** Fixing the permanent bypass left
  a transient one: `yt-navigate-finish` fires BEFORE YouTube re-renders the owner byline, so
  for ~1.5-5s even the DOM tier reports the previous video's channel. The forward direction
  (allowed -> blocked) is a couple of ungated seconds; the REVERSE (blocked -> allowed) was
  far worse, a channel the user explicitly allowed was accused of being "off your list" for
  3-5s. Punishing someone for watching what they said they wanted is the most damaging thing
  this extension can do. `core/channelFreshness.ts` resolves it with a cheap discriminator:
  staleness only MATTERS while the byline still names the channel from before the hop, so
  detection is CONFIRMED the moment it differs (or the inline data is authoritative, or a
  6s backstop elapses) and SETTLING until then. While settling we withhold the interstitial
  and stay gray, both fail-safe directions.
- **A NAVIGATION MUST NOT BE DEBOUNCED.** The first refresh after a full page load is what
  NOTICES the url changed and starts the settle machinery, so routing `yt-navigate-finish`
  through the 250ms debounce meant the mutation storm delayed even that, for ~2s the cold
  first hop simply held the PREVIOUS video's verdict, colour and all (live QA). Navigation is
  a discrete, known-important event: handle it immediately and let the debounced pass follow
  for the DOM settling after it.
- **Colour and the verdict get DIFFERENT patience** (`SETTLE_COLOR_MS` 2.5s vs `SETTLE_MS`
  6s). The tradeoff, weighed deliberately: on a hop between two videos by the SAME channel
  the byline never changes, so nothing can confirm freshness and only the backstop ends the
  wait. **Yes, this means an allowed -> allowed same-channel hop sits in grayscale for a
  beat**, accepted, because the alternative is holding colour on a video that might be from
  a channel the user is avoiding, and that is the exact hit the feature exists to remove.
  Grayscale is cosmetic and self-correcting; a colour leak is the product failing. The
  interstitial keeps the full 6s either way, so the reverse-direction protection is untouched.
- **A debounce can be STARVED.** The corrective re-check was losing to YouTube's
  post-navigation mutation storm, which kept resetting the 250ms debounce, that starvation
  is what stretched the window to 5s. Anything that MUST happen after an event needs its own
  timer that page activity cannot reset (`SETTLE_RECHECK_MS`), not just a debounced observer.
- **The decision is pure and separate from the DOM.** `core/channels.ts` answers "what does
  this channel mean" with no DOM at all; `content/channelFilter.ts` only applies the answer.
  That split is what let the full mode x listed x unknown matrix be tested exhaustively.
- **The unknown-channel case resolves to the SITE'S DEFAULT (v0.2), and the two directions
  are different on purpose.** If detection fails (YouTube moved its DOM, the page hasn't
  hydrated), what should happen depends on what the user said about YouTube *as a site*:
  - site mode **ALLOW** → fail OPEN, as before, with a distinct `reason: 'unknown-channel'`
    so "we checked and it's fine" stays distinguishable from "we couldn't tell". A hard
    whitelist that failed shut here would block ALL of YouTube the moment a selector rots.
  - site in a **BlockMode** → fail CLOSED to that mode. The user's stated default for this
    site is "blocked", so silently opening it on selector rot defeats the rule with no
    signal — and unlike the ALLOW case there is no over-blocking risk, because the site was
    already blocked. The pause and Escape Hatch still apply.
  Keep the one-shot console canary in BOTH directions; a fail-open you cannot see is a
  silent no-op, and a fail-closed you cannot see is an unexplained block.
  Gray-screen takes the OPPOSITE bias throughout: an unidentified channel never earns
  colour, because staying gray is harmless.
- **AN ENTRY CAN LEARN ITS MISSING IDENTIFIER, AND THAT IS ALL IT MAY EVER LEARN.** A stored
  entry only holds the identifier the user typed, and `dnr.ts` can only carve out the
  identifiers it holds — so a handle-only entry's own `/channel/UC…` page was redirected
  despite being allowed, which no content script can fix. `CHANNEL_OBSERVED` closes that: a
  CONFIRMED observation (never a settling one — persisting a stale byline would make the
  reverse-direction P0 permanent) goes to `core/channels.enrichEntries`, which fills the null
  axis, upgrades an `@handle`/id placeholder name, and merges two rows it proves are one
  channel. THE INVARIANT: enrichment never adds a channel, never drops one, never overwrites a
  non-null identifier, and refuses a contradiction on a shared axis outright. The covered set
  is therefore unchanged, which is exactly what `strictMode.isWeakening` measures, so the
  save goes through the normal gated `handleSave` and the Commitment Lock simply never fires.
  Full reasoning and the rules in `core/channels.ts`; tests pin the invariant over a table.
- **The channel gate bails BACKWARDS, not to the site root.** "I changed my mind" on the
  channel interstitial calls `history.back()` (`content/overlay.ts`'s `bailAwayFromGate`),
  and when there is no previous entry — a tab opened straight onto the gated video — asks
  the worker to close the tab (`CLOSE_TAB`, whose tab id comes from the message SENDER, so a
  page can only ever close itself). The site root is not an option here: under "block
  YouTube except these channels" it is exactly what DNR redirects, so the old bail landed
  the user on the block page. The SHORTS gate still uses the root, and correctly — that
  gate only fires while the site itself is open.
- **Feed cards are matched per-card.** The channel chain is run SCOPED to each card; an
  unscoped query returns the first channel on the page for every card.
- **A selector match that yields nothing is a MISS, not an answer.** `channelFromDom`
  iterates every rung and every match until one produces an identifier, skipping hrefless
  placeholder anchors, taking `elements[0]` of the first matching rung let a hidden empty
  anchor on search results mask the real `/@handle` link and leak the whitelist.
- **Some selector chains are ladders, some are lists.** A chain is normally alternatives
  for ONE element (first match wins), but a few surfaces are genuinely several elements
  shown together, the end-screen grid and the creator end-cards coexist, so those carry
  `matchAll: true`. Getting this wrong hides one and leaves the other on screen.
- **The fail-open is observable, but only counts cards that SHOULD have had a channel.**
  When some cards resolve and others do not, the content script logs a one-shot warning so
  DOM churn shows up in devtools instead of silently degrading filtering into a no-op.
  Ads and Shorts lockups have no channel BY NATURE, so counting them made it fire on every
  normal home feed (~15 cards), and a warning that appears when nothing is wrong is a
  warning nobody reads, which costs you the real signal. `isChannellessCard` excludes them
  (a card that IS one, CONTAINS one, YouTube wraps in-feed ads in an ordinary
  `ytd-rich-item-renderer`, or sits INSIDE a Shorts shelf).

#### Gray-screen: the mechanism and its flash behaviour

The requirement is contradictory on its face, the CSS must be *gateable* (off when the
feature is off) AND applied *before first paint* (or the page flashes in full colour, which
is exactly the hit the feature exists to remove). Neither obvious option delivers both:

| Approach | Gateable? | Flash-free? |
|---|---|---|
| Static `content_scripts.css` in the manifest | No, always on | Yes |
| CSS injected from JS after a storage read | Yes | No, paints in colour first |
| **Dynamic `chrome.scripting.registerContentScripts` with `css`** | **Yes** | **Yes** |

We use the third. The service worker registers `public/grayscale.css` while the feature is on
and unregisters it when off; Chrome injects registered CSS "before any DOM is constructed or
displayed" (verified in the `chrome.scripting` reference). Registration is re-derived from
settings on every worker wake, because `persistAcrossSessions` defaults to true and a stale
registration would otherwise outlive the setting that asked for it.

**The one flash that remains, and why it is the right one:** going gray -> COLOUR flickers
once, after the channel check resolves, on an *allowed* channel. That direction is
unavoidable (we cannot know the channel before the page exists) and is the correct trade: a
brief gray frame on a video you're allowed to watch is a far better failure than a colour
frame on every video you are trying not to be pulled into.

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
`v*` release flow. The changelog is **`extension/CHANGELOG.md`**, deliberately separate from
the repo-root one: two independently-numbered products in one file invites the reader to
match an Android `v1.17.0` against an extension `0.2.0` and conclude something about both.

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
- **`chrome.scripting.registerContentScripts` accepts `css` and injects it before first
  paint.** That is the only way to have CSS that is BOTH runtime-gateable and flash-free;
  static manifest CSS cannot be turned off and JS-injected CSS is always too late.
- **A hard whitelist must fail OPEN.** Whatever identifies the thing being filtered can
  break; if 'unidentified' means 'blocked', one rotted selector blocks the entire site.
  Fail open, but carry a distinct reason so the fail-open is observable rather than silent.
- **e2e can drive the YouTube content script with no network**: map `*.youtube.com` onto
  the local fixture server too. It MUST be served over HTTPS, youtube.com is in Chrome's
  HSTS preload list, so `http://` is force-upgraded before the resolver rule applies and a
  plain-HTTP server answers ERR_SSL_PROTOCOL_ERROR. The fixture generates a throwaway
  self-signed cert per run (never committed) and Chrome runs with --ignore-certificate-errors.
- **Each hiding feature owns its own CSS class — OR one reconcile pass, proven by test.**
  Shorts hiding, the Unhook toggles and the channel filter use three different classes:
  with one shared class, turning any of them off would un-hide the others' elements. The
  platform scripts take the equivalent route (`hideClassFor(id)`, one class per hide id)
  and additionally recompute the FULL desired set every pass, so a toggle flipped off is
  revealed on the next pass without anyone tracking what was hidden. Either design is fine;
  what is not optional is an explicit test that turning one feature off leaves the others
  hidden.
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
- **`chrome.runtime.reload()` UNLOADS a `--load-extension` extension permanently** — it does
  not restart it. No replacement `serviceworker` event ever fires, `context.serviceWorkers()`
  stays at 0, and every extension URL then answers `ERR_BLOCKED_BY_CLIENT`, so there is
  nothing to rebind to. Test a restart with TWO browsers over ONE `userDataDir` instead — and
  **clear the state the first one derived** (dynamic DNR rules and any
  `persistAcrossSessions` registration both survive), or the second browser inherits a
  correct rule set it never had to rebuild and the spec passes vacuously.
- **`chrome.storage.set` with an UNCHANGED value fires no `onChanged`.** "Re-save the same
  settings so the worker recompiles" is a silent no-op, and it fails later and somewhere
  else. Drive a recompile through the real production path (`SAVE_SETTINGS`), which
  recompiles unconditionally.
- **"Browser has been closed" in an e2e fixture usually means OOM**, not a code bug — this
  machine runs `earlyoom` with `--prefer ^chrome$`. The lean Chrome flags in `e2e/fixtures.ts`
  exist for that reason.
- **A LOCKED desktop makes `chrome.idle` report `locked`, so no usage accrues at all** — any
  budget test then sits there and silently never fires, looking exactly like a broken limit.
  Run device/QA sessions under a private D-Bus session bus so the screen state is the
  harness's, not the machine's.
- **A full `/tmp` (tmpfs) crashes Chrome renderers at random**, with failures that move
  around between runs and implicate innocent specs. Put Playwright profiles somewhere else
  before believing a flaky e2e result.
- **A fake chrome.* API that is more forgiving than the real one hides real bugs.** The
  scripting fake used to shrug at `unregisterContentScripts` for an id it did not hold;
  real Chrome REJECTS it, and that gap is exactly why a red console error on a plain
  grayscale-off reached live QA with the unit suite green. When a fake and the real API
  disagree about an error, the fake is wrong.
- **A `<button>` inside `role="tablist"` is exposed as role `tab`, not `button`.** The
  dashboard's Stats/Settings tabs are invisible to `getByRole('button')` and only match
  `getByRole('tab')`. Correct behaviour, surprising in tests — when a locator finds nothing
  that plainly exists in the DOM, dump the a11y roles before assuming the page is broken.
- **The dashboard paints a loading state first** and fills in when `GET_DASHBOARD_STATE`
  resolves, which is slower when the worker was asleep. e2e must wait for loaded content,
  not assume an instant render.
- **One question, one function, when more than one layer answers it.** "Is this rule in
  force right now?" is read by DNR, the engine's resolver, the popup and the badge; four
  copies means a block page for a site the popup calls "Allowed". `core/applies.ts` is that
  function, and it is also what let Allow mode exist WITHOUT touching `blockEngine.evaluate`
  — filter before the engine, never add a branch inside it.
- **Routing SOME consumers through a new resolver is worse than routing none.** Subdomain
  matching was added to the popup, badge, grayscale and tracker and MISSED the block page,
  which then found the rule correctly but read usage under the host while the tracker filled
  the rule's bucket — so a spent limit looked unspent and the engine answered ALLOW for a
  URL DNR had just redirected. 217 navigations in 8s and a crashed renderer. When a lookup
  gains a new rule, ENUMERATE every caller (`grep` the primitive, not the helper) and
  justify each one you leave alone; a half-migrated resolver breaks the invariant that the
  un-migrated half used to satisfy by accident.
- **Give an invariant a structural backstop, not just a fix.** The ENGINE INVARIANT is now
  also enforced at the point it would hurt: `core/redirectLoopGuard.ts` lets the block page
  bounce a target once and then stops, logs, and renders a plain error. Any FUTURE break of
  that invariant costs a visible message instead of a pegged CPU.
- **A URL pattern shared by DNR and a content script must be ONE string.** RE2 (DNR) has no
  lookaround or backreferences, so a pattern that works in the page can be silently dropped
  by the network layer, leaving a surface gated in-SPA and wide open on a full page load.
  `tests/core/platforms.test.ts` guards the whole registry against that class.
- **A guard built by composing the thing under test can be circular.** The registry's
  path-pattern check builds its test URL FROM the pattern, so a pattern missing its leading
  `/` produced the bogus URL `https://www.youtube.comshorts/x` and matched it happily. Found
  by planting the defect rather than assuming; leading-slash is now a separate structural
  assertion. Plant a defect in every guard you write.
- **A migration that MOVES data must merge, not assign.** v2→v3 folds the top-level YouTube
  block into the youtube.com rule; taking the stronger value per axis makes it idempotent
  and safe when a browser that has upgraded syncs against one that has not. The first
  version dropped a channel list stored with `channelMode: 'OFF'` — a list is data the user
  typed, not a stance to choose between.
- **A list can weaken in opposite directions depending on its mode.** Adding to a WHITELIST
  and REMOVING from a BLACKLIST are both weakenings; no "did the array shrink" rule
  expresses that, and getting it backwards leaves half the cases ungated.
- **Isolated-world content scripts cannot see the page's own `history.pushState`** —
  confirmed on YouTube, TikTok, X, Instagram and Facebook. An href-diff poll is mandatory,
  not a fallback.
- **A NEW numeric field on a stored rollup fails OPEN unless it is filled in on read.**
  Usage data carries no schema version and is deliberately never migrated in place, so
  `DayUsage.items` (v0.3) is simply absent from every rollup written before it. Incrementing
  it straight off storage is `undefined + 1` = NaN, every `NaN >= limit` is false, and the
  budget silently never fires. `core/stats.ts#coerceDayUsage` repairs the shape in
  `background/storage.ts`'s two read paths, once, for every consumer.
- **A required parameter is a compile error; an optional one is a silent no-op.**
  `gateAppliesNow` takes `usageCount` with no default for the same reason `resolveRule`
  takes `usageMs` with none: a default of 0 means "this budget is never spent" at any call
  site that forgot to pass it. Making it required turned "did every consumer get updated"
  into something `tsc` answers instead of something a person has to remember to grep. Same
  reason the DNR compiler takes ONE `UsageSnapshot` rather than two parallel maps.
- **An element id a stylesheet keys off is a CONTRACT, and nothing else checks it.**
  `overlayIdFor()` derives `nudge-gate-<platform>`, but `platformOverlay.css` still styled
  `#nudge-platform-gate` from before that id was per-platform — so on all six platforms
  the interstitial's full-screen backdrop rule matched nothing and it rendered as an
  ordinary in-flow block: a gate that looks broken, with a green typecheck, a green unit
  suite (every DOM test asserts the ELEMENT is attached, not that it covers anything) and no
  console error. Found while migrating YouTube onto the same builder, which is the same
  trap one rename away. `tests/content/overlayStyling.test.ts` now pins every platform's
  overlay id to a matching `#<id>.nudge-overlay` rule in the stylesheet its entrypoint
  imports, YouTube's included, and plants a bogus id to prove the check is not vacuous.
  Generalise: when a value crosses from TS into CSS (or any other language the compiler
  cannot follow), a test has to be the type system.
- The React Compiler advisory lint rules (`set-state-in-effect`,
  `preserve-manual-memoization`, `use-memo`) are deliberately **off**; `rules-of-hooks` is
  deliberately **on** and has already earned its keep.

## Known gaps (MVP)

- Strict Mode cannot stop removal from `chrome://extensions`. The dashboard says so plainly
  rather than pretending; honesty is the differentiator (ext-02).
- YouTube fixtures in `tests/content/fixtures/` are hand-authored from the ext-03 taxonomy,
  not live DOM captures. Refresh them from real YouTube DOM when possible.
- **Reels mixed INTO a feed cannot be filtered out, or counted.** No OSS implementation
  anywhere does per-card filtering of reels/short videos in a mixed feed (ext-12), so Nudge
  ships whole-surface control only: the Reels/For You/Explore *pages* are gated by URL, and
  whole containers (stories tray, nav entries, suggested blocks) are hidden. A count budget
  needs a per-item URL for the same reason a filter needs one, so an in-feed reel is
  invisible to it as well. Each platform in
  `core/platforms.ts` carries a `note` saying so, and the dashboard prints it — an honest
  limitation beats a feature the code does not actually deliver. Reel tiles on a profile
  grid are likewise out of reach.
- **Five sourced YouTube hides are researched but unshipped**: merch shelf, live-chat
  sidebar, subscribe button, annotations, and mix/radio playlists (ext-12 §F has an
  ImprovedTube selector for each). They need registry ids and selector rungs; nothing about
  them is hard, they were simply out of scope for v0.2.0.
- Reddit ships no hide toggles: the only sourced technique is a generic whole-`main`
  container hide, which is what the home gate already does properly. LinkedIn ships one.
  Shipping a toggle we cannot implement reliably would be a promise the code does not keep.
- The new platforms' selectors are hand-authored from ext-12's sourced tables, not live DOM
  captures — same caveat as the YouTube fixtures below. X in particular ships breaking UI
  changes every 2-6 weeks, though `data-testid` values themselves are stable; the rot is in
  positional selectors, so keep those to a last-resort rung.
- **Disabling autoplay is best-effort.** There is no API for it, so we click the player's
  own switch when it reads `aria-checked="true"`. YouTube re-renders the player and can
  restore its own state, so it is re-applied on every SPA navigation and is not a guarantee.
  The dashboard says so plainly rather than implying certainty.
- Channel detection depends on YouTube's `ytInitialPlayerResponse` / `ytInitialData` shapes.
  Fixture tests cover the documented shapes, but they are hand-authored, not live captures.
