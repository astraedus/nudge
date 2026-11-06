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
    repeatPause.ts      (the repeating pause: range, grant length, weakening, time-left text)
  background/   The service worker: dnr, tracker, itemCounter, tempAllow, alarmsHub,
                badge, messagesRouter, storage, grayscale, repeatPauseScript.
  content/      spaNav.ts   (the generic SPA-navigation layer), overlay.ts (the shared
                             in-page gate overlay + the media hold + the bail helper) —
                             BOTH shared by all seven platform scripts, YouTube included
                itemCounter.ts (reports "we are on one item of a gate's stream")
                repeatPause.ts + repeatPauseHost.ts (the repeating pause, on ANY site)
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
   ≥1 channel is on, `/watch`, `/live/<id>`, `/playlist`, the legacy `/c/` and `/user/`
   channel URLs (verified in-page, see below) and each listed channel's `/@handle` and
   `/channel/UC…` get `allow` rules above the site redirect, while `/`, `/feed/*`,
   `/results` and `/shorts/` stay redirected. **Every pattern comes from
   `core/youtubeAccess.ts`**, and the content script's in-SPA page gate
   (`youtubePageAccess`) tests the SAME strings with `urlMatchesFilter` (case-insensitive,
   like DNR's regex): before it existed, in-SPA clicks to the home feed, search and other
   channels were wide open while the same URLs typed in went to the block page. For a
   page DNR would redirect, the content script does not draw its own wall: it
   `location.replace`s the URL so DNR decides, once per URL, and only with the config
   fetched FOR that URL (a pause may have expired since). On a FULL load it never reloads
   (DNR already let it through; a reload could loop), it logs a one-shot canary. `/c/` and
   `/user/` do not equal the handle, so they are allowed wholesale and checked on a full
   load against the page's own `channelMetadataRenderer` (`channelPageIdentity`); unlisted
   or unidentifiable = `replace(blockPageUrl(...))`, built by `core/blockPage.ts`, the ONE
   block-page URL builder that DNR's substitution also uses.
   **Creator mode** (`creatorMode`, schema v5) adds `studio.youtube.com` as ONE EXACT host
   (never the subdomain wildcard) plus the www routes that redirect into Studio
   (`CREATOR_PATHS`). These stay open after the budget is spent, are not counted by the
   tracker, and `myChannel` is an implicit whitelist entry through `effectiveChannelLists`,
   the one place both the DNR compiler and `decideWatchGate` read the lists from. Turning it
   ON (or changing `myChannel` while on) is a Strict-Mode weakening; OFF is free. A channel identifier containing anything outside `[A-Za-z0-9_.-]` is
   SKIPPED rather than interpolated, so a hand-edited settings blob cannot inject a pattern
   that allows all of YouTube. **An EXHAUSTED daily limit keeps an explicit WHITELIST open**
   (Anti, 2026-11-01, reversing the earlier "no allow-rules after the limit" ruling): the
   listed channels are always watchable, the limit budgets everything else (home, search,
   Shorts, other channels stay blocked; their videos are held in-page with limit copy). Time
   on a whitelisted channel's video is NOT counted (`limitSparesWhitelist`,
   `isWhitelistedVideoUrl`): the content script reports "video V is by channel C" from a
   CONFIRMED detection (`VIDEO_CHANNEL`, `content/videoChannelReporter.ts`), the worker keeps
   it in `storage.session` (`background/videoChannels.ts`) and re-decides against the
   current list; no report = counted. The limit-crossing redirect spares those tabs
   (`survivesLimitCrossing`). Creator mode's IMPLICIT whitelist and BLACKLIST/OFF modes get
   no such exemption. Strict Mode: switching an existing list INTO a whitelist is a
   weakening (it allows every entry at once).
   **Subscribed channels** (`allowSubscriptions`, schema v6, WHITELIST only, off by default):
   the worker fetches `https://www.youtube.com/feed/channels` with the user's own cookies
   (`credentials: 'include'`, host permission already held, NO new manifest permission),
   parses `ytInitialData` (`core/youtubeSubscriptions.ts`, pure; header/shelf channels are
   ignored, only the grid's `channelRenderer`s count). **Continuations are signed, and only
   inside a youtube.com tab** (2026-11-02). YouTube serves a cookie-only `youtubei/v1/browse`
   continuation as a SIGNED-OUT 200 (`loggedOut: true`, zero channels; from the worker it is
   a 403 "Sorry"), so the worker never posts one. It sends `NUDGE_YT_SIGNED_BROWSE`
   (`{continuation, context, session}`, nothing else) with `chrome.tabs.sendMessage(tabId,
   msg, {frameId: 0})` to a youtube.com tab the user ALREADY has open
   (`background/youtubeTabBrowse.ts`: exact origin `https://www.youtube.com`, never m./music.,
   non-discarded, the GET_SITE_CONFIG sender's tab first; never opens, focuses or reloads a
   tab). The youtube content script (`content/signedBrowse.ts`, registered FIRST in
   `youtube.content.ts` so it runs on allowed /watch pages while YouTube is blocked) reads
   `SAPISID` (or `__Secure-3PAPISID`) from `document.cookie`, builds the header with
   `core/signedBrowse.ts`, POSTs same-origin to the ONE constant URL
   `https://www.youtube.com/youtubei/v1/browse?prettyPrint=false` (no `key=`) with
   `{context: INNERTUBE_CONTEXT, continuation}`, and replies with the JSON only. **The cookie
   and its hash never leave that function**: not in a message, a reply, a log or storage
   (`tests/content/signedBrowse.test.ts` "never leaves", with a planted `console.debug`
   caught; the worker integration test greps every message, reply, worker request and both
   storage areas). Header shape, live-verified against YouTube's own page and matching
   yt-dlp: `Authorization: SAPISIDHASH <ts>_<sha1>_u SAPISID1PHASH <ts>_<sha1>_u
   SAPISID3PHASH <ts>_<sha1>_u` (1P/3P only when those cookies exist), `sha1("<USER_SESSION_ID>
   <ts> <cookie> https://www.youtube.com")`, ts in Unix SECONDS, `_u` only when the
   USER_SESSION_ID (parsed from the first page's `ytcfg`) is folded in, plus `X-Origin`,
   `X-Goog-AuthUser` (`SESSION_INDEX`, default 0) and `X-Goog-PageId` for a delegated
   (brand) account. **Live probe 2026-11-02** (signed-in profile copy, the page's own
   sort-chip token): unsigned = 200 `loggedOut: true`, 0 channels; plain SAPISIDHASH, `_u`
   SAPISIDHASH and the three-scheme `_u` header = 200 `loggedOut: false` with the channels.
   No `cookies` permission is needed because the content script reads `document.cookie`
   (SAPISID is not httpOnly). Outcomes: a refused, thrown or signed-out page = `partial`; no
   qualifying tab (dashboard "Sync now", tab closed mid-list) = `pending`; partial/pending
   MERGE with the previous list (keep what we have), only a whole or `cap` read replaces it.
   A `pending` list is due again on the next youtube.com visit, floored at `PENDING_RETRY_MS`
   (60s) so a page view storm does not re-read it. The parser fixture is a sanitized live
   capture (`tests/core/fixtures/youtubeSubscriptionsCapture.ts`): on this page a
   channelRenderer's `subscriberCountText` is the HANDLE and `videoCountText` the subscriber
   count; the sort chips carry `continuationCommand`s that must NOT be followed; zero
   subscriptions = a section list with no `contents` (parsed as ok, 0 channels). The block
   page links typed channels plus up to `MAX_BLOCK_PAGE_SUBSCRIPTIONS` subscribed ones (the
   subscriptions page itself is redirected, so the block page is the only way in). Signed out = the request ends on
   `accounts.google.com`, judged by the FINAL URL, not the status. The list lives in
   `storage.local` under `nudge:ytSubscriptions` (never `storage.sync`: it is a derived,
   private, up-to-500-entry blob), `loadSettings` hydrates it into the youtube rule through
   `withSubscribedChannels`, `saveSettings` strips it, and `handleSave` replaces any list a
   page carried with the stored one BEFORE the strict verdict (a page cannot smuggle
   channels in). It reaches DNR and the watch gate ONLY through `effectiveChannelLists`, so
   it inherits the limit exemption and `isWhitelistedVideoUrl` for free. Sync: on toggle ON,
   on "Sync now", and at most daily on a youtube.com visit (`maybeAutoSync`); concurrent
   syncs dedupe; a failure keeps the last list. Strict: ON while a whitelist (or switching
   into a whitelist with the flag on) is a weakening; OFF is free; later syncs are not
   judged (the consent was to "my subscriptions", not a snapshot). Export strips the list.
   Packing: identifiers are packed into alternation groups under
   `CHANNEL_ALTERNATION_BUDGET` characters so 500 subscriptions compile to a few hundred
   rules, not 1000+.
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

### The repeating pause

`SiteRule.repeatPauseMinutes` (schema v7, null = off, 1 to 120) makes a Delay or Breathing
site pause again every N minutes while the user stays on it. The worker answers
(`GET_REPEAT_PAUSE`), `content/repeatPause.ts` keeps the page in step with the answer.

- **In place, not a redirect, because that is the feature.** The grant's normal expiry never
  yanks an open page (step 3 above), so a repeat has to come to the page. Sending it through
  the block page would throw away the half-typed comment and the video position, and a pause
  that punishes staying gets switched off. So the overlay mounts over the SAME document and,
  once served, the page carries on (the e2e asserts the typed value and a window marker survive).
- **The grant IS the interval.** While the repeat is in force, `completePause` grants
  `repeatPauseMinutes` rather than `tempAllowMinutes` (`pauseGrantMinutes`, the one place). A
  separate timer would disagree with DNR: "Next pause in 9:32" while a full navigation hits
  the block page, or the reverse. With one `until`, the pill cannot lie.
- **Wall clock, deliberately.** The pill compares `Date.now()` to the grant's `until`, not a
  countdown of time spent in the tab. A grant is per domain and runs out whether or not this
  tab is looking at it, so the timer keeps running when you leave, and if it has passed when
  you come back the pause is due straight away. A due tab holds media at once whether or not it
  is visible, but mounts the overlay only when visible, and going hidden tears the overlay down
  and restarts the countdown on return: a pause cannot be served out in a background tab.
- **`repeatInForce` is inactive for anything a pause never governed.** Off when: master toggle
  off, no rule, no repeat set, the rule does not apply now, the mode is not a pause, the rule's
  budget is spent, or a gate is in force on the URL (`completePause` evaluates the gate first,
  so an in-page overlay there would get the wrong grant, and two overlays would stack).
  **YouTube carve-outs are inactive too**: `youtubePageAccess` with `tempAllowed: false` must
  say `'blocked'`, so an allowed channel's video and Studio never see it. Repeating a pause on
  what the user allowed is the same "punished for watching what they said they wanted" failure
  described under the channel lists below, and it is the worst thing this feature could do.
- **Budget is asked directly (`isOverBudget`), not through `reason`.** See Lessons.
- **Registered only where a rule needs it.** `background/repeatPauseScript.ts` follows
  `grayscale.ts`: one dynamic registration whose `matches` derive from rules with a repeat set,
  diffed on every wake and save, unregistered at zero, called wherever grayscale's sync is. It
  also injects into already-open tabs of newly added domains, and of every listed domain on an
  extension update. A host Chrome would reject is filtered out, or one hand-edited domain would
  fail the whole registration. The entrypoint is `registration: 'runtime'` with NO `matches`,
  so WXT adds nothing to `content_scripts` or `host_permissions`. Re-injection replaces the old
  controller: WXT invalidates the older same-named context, and `removeStaleHosts` clears its
  element, so there is no window flag to go stale.
- **Rendering on pages nobody here has seen** (`content/repeatPauseHost.ts`). One shadow host
  holds the pill and the overlay, with its own box set inline `!important`. The overlay is
  `createGateOverlay` and `platformOverlay.css`, so there is no second overlay to drift; the
  host passes every click through (`pointer-events: none`) and the overlay turns that back on
  for itself, because a click-blocking pill on someone's "Send" button would be a bug report.
  Styles go in through `adoptedStyleSheets`. A `manual` popover keeps the pill above fullscreen;
  an overlay coming due exits fullscreen first.
- **A pause never ends in nothing.** The overlay comes down only on a successful grant.
  Refused twice, it `location.replace`s the URL so DNR decides, overlay still up. A failed fetch
  keeps the last known state, and a failed confirm while locally due counts as due: "could not
  ask" must not let a sleeping worker switch the pause off. Another tab renewing the shared
  per-domain grant ends this tab's pause, which is why due is confirmed with the worker before
  anything is shown. Bail is WALKED_AWAY then `LEAVE_TO_DASHBOARD` (tab id from the SENDER only,
  like `CLOSE_TAB`). bfcache `pageshow` resets the controller so Back from the dashboard does
  not leave a frozen overlay.
- **Strict Mode** (`isRepeatPauseWeakening`, in `isRuleWeakened`, gate in the worker): on to off
  and raising N are weakenings; off to on is one ONLY if N > `tempAllowMinutes`, because it then
  lengthens the grant; lowering N and off to on at or under it are free. The block page's
  chooser offers only the free options (`repeatPauseChoices`). It waits for its save before
  `COMPLETE_PAUSE` (`repeatPauseSaveSettled`, capped at 1.5s), so a save made during the countdown
  reaches the grant it was made for, and a failed or slow save cannot break the pause.

Known limits: a site's modal `<dialog>` makes outside content inert, so the bail button cannot
be pressed while one is open (the countdown still finishes). Media inside a shadow root is not
held, as with `holdMediaPaused` everywhere. Lowering N leaves a live longer grant as it is, and
the pill reads the real grant. An Emergency Pass grant also ends in a repeat pause. Adding a
repeat on an open tab with no live grant makes the pause due at once.

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
  **A learned id must also survive the USER's next save.** Screens save the whole settings
  object they loaded, so a dashboard tab opened before the visit wrote the handle-only entry
  back over the id. `handleSave` therefore runs `keepLearnedChannelIdentifiers(current, next)`
  BEFORE the lock judges the save (the same `enrichEntries` rules, replaying each stored
  entry as an observation, so it inherits the invariant), and the dashboard re-reads
  `GET_SETTINGS` after a successful save. Tests: `tests/background/learnedChannelSurvivesSave.test.ts`.
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

- **Nothing leaves the browser.** No telemetry, no remote config, no CDN. All selectors are
  bundled. `no-restricted-globals` bans `fetch` in lint so this stays true, not aspirational.
  The ONE exception is the opt-in subscriptions read, with exactly two `eslint-disable`
  lines: the worker's GET of the user's own youtube.com page
  (`background/youtubeSubscriptions.ts`) and the youtube.com content script's signed POST of
  each continuation to `youtubei/v1/browse` (`content/signedBrowse.ts`), both in the user's
  own session, result kept in `storage.local`. The sign-in cookie is read and hashed inside
  the content script per request and never stored, logged or messaged. Nothing of ours is
  sent anywhere; `tests/core/noNetwork.test.ts` pins the two call sites. A
  second exception needs the same shape (opt-in, off by default, first-party read, stays
  local) and its own line in the onboarding copy and PRIVACY.md.
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

**Release = push an `ext-v<version>` tag** matching `package.json`:
`.github/workflows/extension-publish.yml` gates, builds, uploads to the Chrome Web Store
(API v2, service account) and submits for review.

**Merged means released.** A user-facing change merged to main ships in the SAME session:
bump `package.json` + `package-lock.json`, rename `## Unreleased` to `## <version> (date)`,
merge, then push the tag. `## Unreleased` never outlives the session that merged into it.
Dispatches that say "do not tag" only mean the dev agent leaves it to the orchestrator; the
orchestrator still tags. If a case can only be checked signed in, run it on a copy of the real
Chrome profile before tagging, not after. **Branded Chrome (148) IGNORES `--load-extension`**:
launch with `--remote-debugging-pipe --enable-unsafe-extension-debugging` and send CDP
`Extensions.loadUnpacked` over the pipe, then drive it with Playwright `connectOverCDP`.
Cookies decrypt only with the real `--password-store` (kwallet) on the real session bus;
to keep `chrome.idle` "active" on a locked desktop, give Chrome an `xdg-dbus-proxy`
filtered bus that talks to kwallet/secrets only (ScreenSaver hidden). Never import cookies
into a `basic`-store copy: Chrome's account consistency signs that copy out within minutes.

CI: `.github/workflows/extension-ci.yml` (lint → typecheck → unit → build → Playwright under
xvfb), scoped with `paths: ['extension/**']`. The Android workflow carries the mirror-image
`paths-ignore: ['extension/**']`.

## Lessons

- **A DNR redirect target MUST be in `web_accessible_resources`.** Redirecting to a page the
  extension owns still fails *silently* otherwise (w3c/webextensions#604).
- **`regexSubstitution` needs `regexFilter`, and `\0` is the ENTIRE match** — so the pattern
  must be anchored `^...$` for `\0` to be the whole URL rather than just its prefix. This is
  how the original URL reaches the block page.
- **An `@` in a URL is userinfo ONLY inside the authority** (before the first `/?#`).
  `extractDomain` once cut at the first `@` anywhere, so `youtube.com/@handle` parsed to host
  "handle", no rule, block page ALLOW, redirect loop, "internal error" (0.3.1). Any engine
  ALLOW on the block page is a two-layers-disagree bug: find WHICH input differs before
  touching the loop guard. Regression: `tests/core/domainMatcher.test.ts`.
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
- **Anything the worker writes into settings on its own is lost to the next UI save** unless
  `handleSave` folds it back in. `SAVE_SETTINGS` is last-writer-wins on a whole object, and
  a dashboard tab stays open for hours. 0.4.0 shipped learned channel ids that a stale tab
  silently erased (signed-in QA). A new background writer gets its own fold in `handleSave`,
  ahead of `isWeakening`, plus a stale-save test.
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
- **An animation and its numbers need ONE clock, and it must not be a React tick.** The
  0.3.1 Breathing pacer derived the circle's scale from a `setInterval` countdown state and
  smoothed it with a 100ms LINEAR CSS transition (a staircase that reversed at full speed
  at every phase boundary), and the overlay toggled a class every 200ms under a 4s
  transition (a second clock that started 200ms behind its own label). Now
  `core/breathing.ts` maps elapsed time to everything shown (eased sine level, phase,
  4-3-2-1 count, progress, done) and `ui/breathingClock.ts` advances ACTIVE time per
  animation frame (frozen while hidden, at most 250ms per frame) and writes
  `style.transform`/`opacity` directly. In React, keep the driven properties OUT of the JSX
  `style` prop or the once-a-second re-render resets them mid-breath.
- **"Nothing identified YET" is not "unknowable" (live QA 2026-10-23).** Opening an allowed
  channel's video from its channel page failed closed with "couldn't identify this video's
  channel": the channel page has no watch byline, so `previousChannelKey` was null, so the
  freshness check had no stale value to wait out and called the empty detection CONFIRMED.
  A null detection inside the settle window after an SPA hop is SETTLING. The DOM tier's
  real freshness signal is `ytd-watch-flexy[video-id]`: live capture shows it and the owner
  byline update in the same render, ~1.6-2.3s after the URL changes, so a flexy naming a
  different video than the URL is stale whatever the byline says.
- **Unscoped DOM detection makes chimeras.** `ytd-page-manager` keeps the page you came from
  as a HIDDEN `ytd-browse`, full of channel links. A document-wide query welded one
  channel's handle to another's id and said "off your list" on Back to an allowed video.
  The watch DOM tier reads only the visible flexy's `ytd-watch-metadata #owner`.
- **A media hold must resume what it stopped, and only in place.** `MediaHold.release`
  takes a REQUIRED `{ resume }`: resume when the gate cleared on the same URL (the owner
  turned out allowed, a pause completed), never when the user left. Before this, every
  cleared gate left the video paused at 0:00, which reads as a block. Each pass that keeps
  the hold calls `repause()`, which also records a player YouTube swapped in already running.
- **Fixtures from the live DOM, not the taxonomy.** Every one of these four bugs passed a
  green unit suite whose hand-authored fixture had no page manager, no hidden browse page
  and no `video-id`. `tests/content/fixtures/youtubeLiveWatch.ts` is built from the
  2026-10-23 capture; new YouTube DOM tests should use it.
- **Inline data proves a watch page only through its root `currentVideoEndpoint`.** Every
  page's `ytInitialData` is full of `watchEndpoint`s, one per video card. A search for "the
  first one" on a channel page finds the first grid video, the one a user clicks first.
  After that SPA hop, the stale channel-page data then "proved" an empty detection fresh: a
  1s false "couldn't identify" flash, and a stuck pause on Back. That survived the unit
  suite and was caught only by the live after-matrix. **And an interstitial is keyed to the
  URL it was raised for** (`overlayFor`). One left up across a hop kept the hold pinned to
  the old URL, and completing it would have released the new video.
- **DNR caps regex rules at 1000 per extension, and RE2 caps each regex's memory.** One allow
  rule per channel URL stops scaling long before 500 subscriptions, and one giant
  alternation is silently rejected (`isRegexSupported` says `memoryLimitExceeded` from
  roughly 72 identifier characters in one `(a|b|c)` group). `core/youtubeAccess.ts` packs
  identifiers into groups under `CHANNEL_ALTERNATION_BUDGET` (60); `tests/core/youtubeAccess.test.ts`
  "channel packing" pins the rule count, the per-group budget and exact-match (no prefix
  match, dots escaped) over 500 synthetic channels.
- **`vi.unstubAllGlobals()` REMOVES WXT's fake `chrome`**, not just your stubs (WxtVitest
  installs it as a stubbed global). Every later `chrome.*` then throws "chrome is not
  defined". Stub `fetch` by assigning `globalThis.fetch` and restore the saved original in
  `afterEach` instead.
- **A "page cannot smuggle X in" test must assert on what is ENFORCED, not what is stored.**
  The first smuggle test checked storage and passed with the guard deleted, because
  `saveSettings` strips the list anyway; it now reads `getDynamicRules()`. Plant the defect.
- **`reason === 'limit-exhausted'` does not mean "the budget is spent".** `siteRuleAppliesNow`
  checks the block mode first, so a Delay rule with a spent limit answers `'mode-blocks'`, while
  the engine escalates it to a Hard Block. The repeat's first check read the reason and would
  have offered a Delay overlay the worker then refuses. To ask about the budget, ask the budget.
- **A page's CSP does not bind an isolated-world `<style>` in Chromium.** The positive control
  on the strict-CSP fixture shows the page's own `<style>` refused; with `adoptedStyleSheets`
  disabled, ours still rendered. So that e2e guards "the overlay is styled and covers the
  viewport", not "the constructable sheet is what got it there", and the first planted defect
  passed it. A plant that passes is a finding: work out which claim was false before moving on.
- The React Compiler advisory lint rules (`set-state-in-effect`,
  `preserve-manual-memoization`, `use-memo`) are deliberately **off**; `rules-of-hooks` is
  deliberately **on** and has already earned its keep.

## Known gaps (MVP)

- Strict Mode cannot stop removal from `chrome://extensions`. The dashboard says so plainly
  rather than pretending; honesty is the differentiator (ext-02).
- Most YouTube fixtures in `tests/content/fixtures/` are hand-authored from the ext-03
  taxonomy; only `youtubeLiveWatch.ts` (watch + channel page, 2026-10-23) is from a live
  capture. Refresh the others from real YouTube DOM when they are next touched.
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
