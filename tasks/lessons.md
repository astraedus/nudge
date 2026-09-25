# Nudge -- Project Lessons

## Stats display: "today only" looks like data loss (2026-05-20)

HomeScreen stats (Blocked, Walked Away) were filtered to today only. Users updating the app (often at start of day) saw 0 stats and thought the update wiped their data. No actual data loss -- all Room migrations are additive ALTER TABLE, no `fallbackToDestructiveMigration()` or `clearAllTables()` anywhere.

Fix: Added "All Time" stats alongside "Today" on the home screen. When adding new stats, always consider whether the user needs both a time-scoped view and a cumulative view.

## cleanup() is dead code (2026-05-20)

`UsageRepository.cleanup()` exists but is never called anywhere. No scheduled cleanup, no startup cleanup. If we add cleanup later, verify the retention window doesn't surprise users (30 days default).

## Migration test must track currentVersion (2026-05-20)

`NudgeDatabaseMigrationTest` was stuck at `currentVersion = 6` while the DB was at version 7. When adding a new migration, always update the test's `allMigrations` list AND `currentVersion`.

## AccessibilityNodeInfo needs mockk in JVM tests (2026-06-16)

`WebDomainDetectorTest` originally only tested `isBrowser`/null cases because reading `AccessibilityNodeInfo` (Android framework class) throws "not mocked" in plain JVM tests. To test `detectUrl`'s node reads, add `testImplementation("io.mockk:mockk:1.13.13")` and `mockk<AccessibilityNodeInfo>(relaxed = true)`. Better still: extract the pure logic (e.g. `urlBarViewIdsFor()` id resolution) so most coverage needs no Android mocking at all.

## Play draft→full: don't run publish-to-play.sh twice (2026-07-19)

`scripts/publish-to-play.sh <ver>` UPLOADS the AAB on every run. The documented two-step (draft to verify, then `STATUS=completed ROLLOUT=1.0 …` for full rollout) fails on the second run with `Error 403: Version code N has already been used` — the draft already consumed that versionCode. To promote an already-staged draft to a completed full rollout WITHOUT re-uploading: `gplay edits create` → `gplay tracks update --edit <id> --track production --releases '[{"status":"completed","versionCodes":["N"],"releaseNotes":[…]}]'` → `gplay edits validate` → `gplay edits commit`. (The single completed release supersedes the prior one automatically.) Better: for a confident release, skip the draft step and run the full-rollout invocation once. NB: the script truncates CHANGELOG release notes at ~500 chars mid-word — for a clean Play "What's new", pass hand-written notes in the `tracks update` releases JSON.

## Content filter framing is a hard constraint (2026-06-16)

The web content filter blocks adult sites but MUST stay generically framed everywhere user-visible: setting title "Block restricted websites", overlay rule name "Restricted content". Blocklist (`assets/content_filter_domains.txt`) + `DEFAULT_KEYWORDS` live only in code/assets. When grepping for accidental leaks, note `hasExisting`/`hasExceeded` are false-positive substring hits for "sex"/"xxx", exactly the ambiguous-token class the keyword list avoids.

## A transient GitHub API 5xx must not fail the Release run (2026-07-20)

Release run 29710052363 failed on a docs-only commit: the "Publish rolling dev build (main)" step of `.github/workflows/release.yml` hit `error checking for existing release: HTTP 503` from `gh release create`. The build, tests, AAB/APK, and artifact upload had ALL succeeded -- only the convenience `main-latest` publish flaked, and it had no retry, so one momentary GitHub API blip sank the whole run.

Fix: the rolling-tag publish is now an idempotent `publish_rolling_build()` (delete-then-create) retried with exponential backoff (5 attempts). Lesson for any CI step that calls a flaky external API: wrap network mutations in a retry-with-backoff; make them idempotent (a failed delete is re-attempted before create); and let a *persistent* failure still exit non-zero so real breakage stays loud. Do NOT reach for `continue-on-error` -- that would swallow genuine failures too.

## The content-filter blocklist was a 274k upstream blob and it over-blocked badly (2026-08-19)

Anti reported the filter as "way too punishing, blocks Reddit sometimes and even government websites". Both halves were data bugs, not matcher bugs, and every unit test was green throughout because they ran against a hand-written 2-entry fake blocklist.

`assets/content_filter_domains.txt` was a ~274,642-domain (4.5MB) third-party blob. Still in it at v1.10.0: `virginia.gov`, `purdue.edu`, `rice.edu`, `ku.edu`, `metrostate.edu`, `ohiochristian.edu`, `itu.int`, `utwente.nl`. Worse, `matchesDomain` walks parent domains, so its entries for `amazonaws.com`, `cloudfront.net`, `wordpress.com`, `blogspot.com`, `myshopify.com` and `appspot.com` silently blocked **every site hosted on those platforms**. The v1.9.2 `reddit.com` ALLOWLIST guard was the same defect surfacing once and being treated as a one-off; a one-site allowlist patch for a bad-data problem always is.

`DEFAULT_KEYWORDS` separately raw-substring-matched ordinary English words: "escort" (ford escort, police escort), "hardcore" (hardcore punk), "creampie" (recipe slugs), "fetish" (`wikipedia.org/wiki/Commodity_fetishism`, and note ALLOWLIST exempts the DOMAIN match only, so Wikipedia was never protected from the keyword layer).

Fixed by replacing the blob with 486 hand-curated domains (12KB). Curation was data-driven rather than from memory: the blob was intersected with the Tranco top-1M so its entries could be ranked by real traffic, that ranking was hand-reviewed (the gov/edu false positives all sat in the top 5k, the ranking surfaces them for free), then cross-checked against current Semrush category rankings. 458 of the 486 are corroborated by the old blob; the rest are newer sites it predates.

Rules this repo now runs on:
- **Curation policy: precision over recall.** A false positive on a government or university site is far worse than missing adult site #4000. It is written into the asset's own `#` header (`ContentFilterRepository.parseLine` skips comments) as well as CLAUDE.md.
- **The domain list only exists for names the keyword layer can't see.** `matchesKeyword` substring-matches the full URL, so anything containing porn/xxx/hentai/xvideos already blocks. Spend curation effort on bangbros/beeg/e621/missav/coomer-class names.
- **Never list CDNs, ad networks or hosting.** The filter reads the browser URL bar, nobody navigates to a CDN, so they are pure false-positive risk with zero blocking value.
- **`ContentFilterAssetTest` gates the SHIPPED file**, parsed with the app's own parser: ≤3,000-entry/<1MB ceiling (the guard against pasting a blob back in), no `.gov`/`.edu`/`.mil`/`.int`/`.ac.*`, no public-suffix entry (`co.uk` would block every British site), no ALLOWLIST collision, no parent-redundant entry, and a ~65-domain benign corpus that must match via neither layer. Tests over a fake fixture could never have caught this class.
- **New keyword test: would this plausibly appear in a benign URL, or in a search a normal person makes?** If yes it doesn't go in, and it does NOT get demoted to `AMBIGUOUS_QUERY_KEYWORDS` either unless the whole-word-in-query form is genuinely safe ("ford escort review" still contains the whole word "escort").

## Verifying a data-migration feature on a RELEASE install: sign debug with the release key (2026-08-20)

Device-verifying the "export/import carries usage history" change needed three things a release build won't give you: the exported file's bytes, the DB's real row counts, and a corpus worth transferring. The corpus lived in the RELEASE install on the Pixel (19 blocked / 7 walked away), and a release build is not `run-as`-able, so neither the file nor the DB could be read; installing a debug build over it fails on signature mismatch, and uninstalling first destroys the very corpus under test.

The way through: **temporarily point the debug build type at the release signing config** (`debug { signingConfig = signingConfigs.getByName("release") }`), `assembleDebug`, then `adb install -r` over the release install. Same signature so the data survives, `debuggable` so `run-as` works. From there: `adb shell "run-as dev.astraedus.nudge cat databases/nudge.db"` (plus the `-wal`, or the DB reads as an empty 4KB file) for ground truth, and `adb exec-out "run-as … cat cache/nudge-rules-export.json"` to pull the exported file the app actually wrote. Then push that file to `/sdcard/Download`, uninstall, install the real RELEASE APK, and import through the normal UI. Revert the gradle change immediately (`git checkout app/build.gradle.kts`), it must never be committed.

Two traps this walked into, worth pre-empting next time:
- **A fresh install can silently restore an old cloud backup** (`allowBackup=true`), which would fake a passing restore test. Always confirm the tiles read 0 BEFORE importing.
- **Only ONE agent may drive the device.** Running a device-tester agent and driving ADB from the orchestrator at the same time produced phantom symptoms on both sides (a rule toggle "flipping by itself", back presses "not working", the file picker "opening on its own") and cost a good ten minutes of misdiagnosis. Hand the device over explicitly, and wait for the acknowledgement before touching it.
- A11y-service state is not restored by finishing onboarding: after any uninstall/reinstall, re-enable it explicitly (`astra-adb accessibility-enable dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService`) and check `dumpsys accessibility` shows it under **Bound services**, not just Enabled, and remember the home-screen MASTER TOGGLE is a second, independent switch. A fresh install with the master toggle off blocks nothing, which looks exactly like a broken build.

## A Compose chart that owns its own selection is a bug factory (2026-08-27)

Reported by Anti: *"in the weekly trends i can click on different days, but then it doesn't swap the numbers to that day??? but it indicates that i've selected that day."*

`WeeklyBarChart` and `BlockedTrendChart` each held `var selectedIndex by remember { mutableStateOf<Int?>(null) }`. A tap dimmed the neighbouring bars and printed a tooltip. That was the entire effect, the screen's real day lived in `StatsViewModel`, moved only by the date arrows. **Two sources of truth for one question, and the loudest one was inert.** It compiled, it animated, it looked designed, and every number under it was wrong for the day the user had just picked.

**Rule: a chart may own selection state ONLY when its selection has no consequence outside itself.** A readout of the tapped bar's own value with nothing to drill into (`HourlyHeatmap`, `RateBarChart` on the insight pages) is legitimate. The moment anything else on the screen is scoped by that selection, the state must be hoisted, the chart takes `selectedIndex` + `onSelectDay` and holds nothing. Pinned by `ChartSelectionContractTest` (source-level, because Compose UI is not JVM-testable here).

Three things this dragged out of the same drawer, all worth checking for by name:
- **State captured in a ViewModel constructor goes stale.** `HomeViewModel` computed `todayStart` once at construction, so a phone left open past midnight counted yesterday's rows under a heading saying "Today". Anything day-scoped must re-derive its boundary from a tick, not an initializer.
- **The same number worded two ways on two screens.** Stats guarded `ms < 60_000` and printed "< 1m" for a day with ZERO usage; App Detail guarded `ms in 1 until 60_000` and printed "0s". Formatting rules for a shared quantity belong in one function.
- **Layout arithmetic copy-pasted between the hit-test and the draw block.** Each chart computed bar widths twice, so a chart could hit-test against a layout it was not drawing, and label rows using `SpaceBetween` (cells of `width/count`) drifted off bars of `(width - totalSpacing)/count`. One `ChartGeometry`, unit-tested.

Also: keying `Modifier.pointerInput(days, onSelectDay)` on a `viewModel::method` reference rebuilds the gesture detector on **every** recomposition, a method reference is a fresh object each time. Key on what the hit-test actually reads (the bar count) and reach the callback through `rememberUpdatedState`.

## A chart and its drill-down must be ONE read, not two implementations expected to agree (2026-08-27)

Device QA tapped a Wednesday whose screen-time bar was tall and dark and got a drill-down reading hero total "0s", "No usage recorded", empty app list. Each half was internally consistent; they described the same calendar day and disagreed completely.

Root cause: two computations. The weekly bars came from `queryUsageStats(INTERVAL_DAILY)`, pre-aggregated buckets that are stale and midnight-misaligned on Android 12+, a fact `ScreenTimeProvider`'s own comment already stated ten lines above the code that used them, while the drill-down summed live `queryEvents` ACTIVITY_RESUMED→PAUSED spans. This is the [[Compose chart owning its own selection]] lesson one layer down: same shape, two answers to one question, and the fix is the same shape too.

**The fix that generalises: do not make the two agree, make them the same value.** `getWeeklyUsage` returns per-day *per-app* totals from one `queryEvents` pass; the bars are `dailyTotals()`, the drill-down is `perAppOn(dayStart)`, Home's Screen Time tile is `totalOn(todayStart)`. Then the day-scoped APIs that made a second computation possible were **deleted**, a leftover `getTotalScreenTime(dayStart, dayEnd)` is an invitation, and the next person will accept it. A source-level contract test asserts the deleted signatures stay deleted and that each screen reads the week exactly once.

Four things worth checking for by name next time:
- **Mirroring semantics between two implementations is not a fix.** The tempting version of this was "make the weekly pass follow the single-day path's rules exactly". That leaves two code paths, and it also permanently inherits that path's blind spot: a session crossing midnight has one endpoint outside each day's own query, so a per-day query drops it from *both* days. One pass over the whole window can see it and split it.
- **A day is addressed by its start timestamp, never by an index into whatever is loaded.** The selection moves the instant an arrow is tapped while the new window is still in flight; an index resolves to a different date for that frame, one day's numbers under another day's heading, which is the original bug again. Return empty for a day you do not hold, and label the bars from the window the DATA covers, not from the selection.
- **`dayStart + 86_400_000` is not a day.** Two days a year it is an hour off true local midnight, and these series are drawn side by side, so one chart's "Wed" would cover different hours from its neighbour's. `TimeTracker.startOfDayDaysBefore` everywhere, including `-1` to walk forward to the exclusive end of a window.
- **Malformed event sequences must be worth zero, never negative.** The old code did a bare `paused - resumed`; a backwards pair (the platform emits them) would have subtracted from a bar. Clamp the span, then take the overlap.

Cost note for the 3GB Pixel 3: this went from ~10 binder round-trips per 30 s poll (7 weekly queries + 3 day queries) to 2 (one week of events + one hourly read), at the price of walking a week of events in memory, a few thousand cheap iterations.

## An early return added for surface X also skips every cleanup below it (2026-08-27)

The delay-to-open feature was silently one-shot per app for every user with a stock launcher: complete YouTube's delay, press Home, reopen YouTube, no delay, forever. `onAccessibilityEvent`'s `if (packageName in SYSTEM_PACKAGES) { clearOverlays(); return }` sits ~200 lines above `PassthroughManager.clearIfAppChanged`, and the stock launchers are in `SYSTEM_PACKAGES`. Nothing about the passthrough logic was wrong; it was simply never reached on the most common exit path there is.

The class, and how to catch it next time:
- **When you add or extend an early return in a long event handler, enumerate what is BELOW it and ask which of those cleanups the new short-circuit now silently skips.** This service has four of them stacked (PiP-only, overlay-active, own-package, transient-window, SYSTEM_PACKAGES) and each one is a place a downstream invariant can quietly stop running. Grep the function for every `return` before adding another.
- **A grouped constant set is a decision you inherit without noticing.** `SYSTEM_PACKAGES` bundles the launcher (= the user LEFT the app) with SystemUI / IME / permission dialogs (= transient, the user did NOT leave). One membership test answered two different questions. Same defect shape as `hasEntry` vs `isCounterEnabled` (v1.10.0) and issue #5's hardcoded IME list.
- **Fix direction matters more than the fix.** Clearing for *every* system package would have fixed the report and re-delayed users for pulling the notification shade, worse than the bug. The allowlist (resolve the launcher, clear only for it) fails toward the OLD behaviour when resolution fails or goes stale.
- **`CATEGORY_HOME` does not mean "launcher".** On stock AOSP `queryIntentActivities` returns `com.android.settings` (`Settings$FallbackHome`, the pre-unlock placeholder home) and, with no default home set, `resolveActivity` returns the framework's ResolverActivity in package `android`. Both must be filtered or Settings becomes "home".
- **When the bug is WHERE code sits, only a source-level test can see it.** Value-level unit tests over the extracted pure function pass just as happily with the call site deleted. `HomeScreenPassthroughContractTest` asserts the ordering itself (verified to fail with the one call line removed), the same tool `BlockOverlayWalkAwayContractTest` and `ImportedSettingsWriteContractTest` already use.

## Replacing an aggregate with your own arithmetic means inheriting its guarantees too (2026-08-31)

v1.15.0 moved every screen-time number off `queryUsageStats(INTERVAL_DAILY)` onto our own `queryEvents` pairing, the right call, and the day after it shipped Anti's daily-driver phone reported **~17 hours of screen time before lunchtime**. The pre-aggregated buckets we replaced had two properties nobody wrote down because the platform simply provided them: *only one app is foreground at a time*, and *a day is at most a day*. The replacement had neither.

Three read paths each kept their own `package -> startTime` map. A map holds one open span per package, so several apps could be "in the foreground" at once, and every span still open at the end of the stream was billed from its RESUMED **to now**, unbounded. Reproduced against the v1.15.0 code: two apps with one missing PAUSED → 16 h in a day; four unclosed apps 11 hours in → 38 h today; one lost close event three days back → 75 h across the week with three bars pinned at 24/24/12; a phone watched to 22:00 then asleep → 9 h "today" at 09:00.

The class, and what to check for by name next time:
- **When you replace something the platform aggregated, write down the invariants you are now responsible for, then test them as invariants.** Not "does this case give the right number" but "can this EVER exceed the wall clock it was measured over". Those two properties were free from `INTERVAL_DAILY` and had to become code.
- **A `Map<Package, StartTime>` is a data-model claim that N apps can be foreground at once.** The shape permitted the bug before any line of logic was wrong. One open span, and the next app's RESUMED closes the last one, makes the invariant structural, the totals need no clamp, which matters because a clamped *total* over unclamped per-app entries would recreate the bar-disagrees-with-drill-down defect from [[the one events-based source]] one release earlier.
- **Separate what you MEASURED from what you INFERRED, and only ever cap the inferred half.** A span with both endpoints observed is data, however long. A span with only a start is a guess, and an uncapped guess is how one dropped event became 75 hours.
- **The absence of an event is a state change you have to model.** `SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` / `DEVICE_SHUTDOWN` were not in the `when`, so a phone in a pocket kept accruing screen time, the single biggest real-world contributor, and invisible to any test that only feeds RESUMED/PAUSED pairs.
- **Filtering the stream before interpreting it destroys the context the interpreter needs.** `getPerAppHourlyScreenTime` skipped other packages' events *before* pairing, so nothing was left that could tell it this app had been superseded. Filter the OUTPUT (the spans), never the input.
- **Three copies of a parse loop is three answers.** The same defect existed three times with three slightly different bodies. `ScreenTimeSourceContractTest` now asserts the provider holds no `foregroundStarts` map and exactly one `while (events.hasNextEvent())`.
- **A test can pin the bug.** `packages are kept apart` asserted two apps RESUMED at 09:00 were worth three hours across two wall-clock hours, and it was green through the whole incident. When a test's numbers encode an impossible world, fix the contract and say why in the test, do not delete the case.

## A feature's early return is where the rest of the feature goes to die (2026-08-31)

Anti, on his own phone: *"it'll delay me to go on insta web but once I'm on there's no other blocks
or it doesn't track anything."* Web-domain blocking enforced its entry gate perfectly and enforced
nothing else, ever. The cause was not that the other enforcement was wrong; it was that it was
**unreachable**. `evaluateWebDomain` returned early for "you already passed this domain", and every
line that could have measured the visit sat below that return, so the branch the user spends 100% of
their visit in did nothing but return.

This is the third time this repo has shipped this exact shape (the SYSTEM_PACKAGES/Home passthrough
bug, the #7 content-change fallback, this one). The generalisation worth keeping:

- **Ask "what does the user do MOST of the time, and which branch is that?"** Entry into a site is one
  event; being on the site is thousands. The rare path had all the code. The app-level pipeline gets
  this right and says so in a comment, `updateForegroundTimeTicker` is deliberately placed *above*
  the emergency-pass/cooldown/passthrough returns because "a user who has just completed a delay is
  exactly who a time-based auto-kick is for". The web path had the same requirement and the opposite
  ordering, and nobody noticed because there was no equivalent comment to copy.
- **A per-package cache silently defines who can be enforced.** `CounterCacheRefresher` was keyed by
  `rule.packageName`, so a browser was never in it, so `hasEntry` was false, so the ticker stopped
  instantly, so `autoKickAfterMinutes` was inert on the web, four steps, none of them a bug, adding
  up to a feature that only worked in half the places its UI offered it. When a cache decides
  eligibility, enumerate what is NOT in it and check whether the UI promises those things anyway.
- **A grant handed out at attempt time is not a grant.** `lastBlockedDomain = domain` ran before the
  overlay was even launched, so walking away granted entry. Compare `onTimerComplete`, which is
  lifecycle-gated *and* guarded by `isFinishing`/`onStop` because issue #8 already taught this lesson
  on the app side. **The rule: state that represents "the user earned X" may only be written on the
  code path where they earned it.** If it is written where the block is *decided*, it is not a reward,
  it is a bypass.
- **Two packages in one decision means two questions, and one field cannot answer both.** A web block
  is attributed to the rule's app (label, stats, PiP record) and happens in the browser (passthrough,
  emergency pass). One `EXTRA_PACKAGE_NAME` served both, so completing a website delay opened the app.
  Whenever a decision spans two subjects, name them separately at the boundary, the bug is otherwise
  invisible, because each individual use of the field looks correct.
- **`x != y` is not "x changed" when x can be null.** `extractedDomain != lastBlockedDomain` treated an
  unreadable address bar as "the user navigated away" and revoked a live pass mid-visit. The service
  already had the right precedent one function over: a null active window in the #7 fallback means
  *do nothing*, never *act*. Unverifiable is a third state, and collapsing it into either of the other
  two picks a failure direction by accident.
- **When the gap needs data the platform does not have, split at the durability line, not at the
  feature line.** Session-scoped web time needed no persistence (the app path's session clock is
  in-memory too) and shipped; daily-scoped web time needs persisted per-domain records and did not.
  Building the daily half in memory would have produced a budget that silently resets on a service
  restart, a blocker that stops blocking without saying so. Shipping the reachable half and writing
  the other half down (`docs/BACKLOG.md`) beats half-wiring both.

## A watchdog that cannot be observed cannot be debugged, and a silent `false` is the same as no code (2026-09-01)

Device QA on the time-based auto-kick: threshold 2 minutes, sat on it for 2m20s, no kick, on the web
path AND the native app path. Logcat showed `session baseline set … threshold=2min` once, then
nothing. QA black-boxed it to a standstill and could go no further, and they were right to stop, 
**the subsystem emitted no evidence at all.**

Four paths returned `false` with no log, and the important one was `TimeKickEvaluator.WAIT`: the
branch a *healthy* clock spends its entire session in. So "ticking, hasn't reached the threshold" and
"dead for ten minutes" produced byte-identical logcat, and no amount of device time could separate
them. This is the v1.12.0 picture-in-picture failure repeating in a different subsystem, where the
same sentence was already written down: *"detection fired but stayed silent" and "detection never
fired" being indistinguishable is what cost a release cycle.*

- **Log the boring branch.** The rule that generalises: in any polling/watchdog loop, the
  *steady-state* outcome must be observable, not just the exciting one. A log line only on KICK
  tells you nothing when the complaint is "it didn't kick". Include the numbers the decision was made
  from (`elapsed`, `threshold`, the raw reading) so one run separates "the clock is dead" from "the
  reading is wrong", two very different bugs that present identically.
- **`?: return false` is a decision, and undocumented decisions become invisible failures.**
  `counterCache.getEntry(pkg)?.autoKickAfterMinutes ?: return false` silently disables the whole
  feature whenever a cache refresh misses that key. Every silent early return in a loop is a place
  the feature can switch itself off and nobody finds out.
- **`while (isActive) { tick(); delay(n) }` on a SupervisorJob scope is a single point of silent
  failure.** One throw from the tick body ends the loop for good; the child dies alone, the scope
  survives, nothing restarts it. Guard the ITERATION, not the loop, and log the exit reason. Both of
  Nudge's clocks had this shape and neither logged a thing.
- **The grouped-constant trap, third sighting.** `SYSTEM_PACKAGES` had already been caught answering
  two questions at once for the passthrough grant; the foreground-time clock was still stopping for
  every member of it, so a heads-up notification ended a running session's clock, and for a browser
  nothing ever restarted it, because content changes return early on the browser branch. When a fix
  teaches you that a constant set conflates two questions, **grep every other consumer of that set
  the same day**; the other consumers are wrong too, and they will be found by a user.
- **Answer "is this a regression?" from the source before touching anything.** `git diff` over the
  shared machinery proved the app path was untouched by the recent web work (the only hits were doc
  comments), which reframed the whole investigation from "what did I break" to "this never worked
  reliably". Two independent implementations failing identically is itself the clue: **suspect what
  they SHARE, not either one.**
- **A sweep that collapses N copies of a loop must be verified by search, not by intent.** v1.15.1
  merged three copies of the RESUMED/PAUSED pairing into `ForegroundSpanTracker` and added a contract
  test asserting "exactly one event loop", scoped to `ScreenTimeProvider`. The FOURTH copy sat in
  `UsageRepository`, uncapped open tail and all, and was the clock behind the auto-kick and every
  daily budget. Scope the invariant to the CODEBASE, not to the file you were editing.

## The mechanism a bug report names is a hypothesis, not a finding (2026-09-07)

A QA session on the shared Pixel reported Nudge "throwing its MainActivity in front of Telegram
repeatedly" after the nightly backup killed the process, with one YouTube rule configured, zero block
events ever recorded for Telegram, and one "keeps stopping" dialog. The written-up cause was
`NudgeMonitorService`, described as a foreground service that "independently polls foreground-app
usage and can relaunch Nudge's UI".

It does not, and it never has. The file was 81 lines: create a channel, `startForeground`, return
`START_STICKY`. Its only `MainActivity` reference was a notification `contentIntent`. A grep for
`startActivity` across the whole app finds **no** programmatic `MainActivity` launch anywhere, so the
only routes to that screen are the launcher icon and the ongoing notification's own tap target, which
is very reachable on a device being driven blind over ADB. The `am_crash` in the same logcat is
`RemoteServiceException: shell-induced crash` -- the QA session's own induced crash -- and the two
`FATAL EXCEPTION`s are `UiAutomationService ... already registered` collisions from two ADB drivers,
the one-agent-per-device lesson from 2026-08-20 showing up again.

- **Settle the mechanism in the source before fixing the mechanism.** Three greps (`startActivity`,
  `MainActivity`, the accused file itself) were enough, and they cost less than one device session.
  Fixing the reported cause here would have meant rewriting a file that had no defect in it.
- **Read the reporter's evidence for what it RULES OUT, not just what it shows.** "Zero block events
  for Telegram" is a strong negative: every decision `EvaluateBlockUseCase` makes writes a
  `UsageEvent`, so the absence of rows proves the block path was not involved. That pointed straight
  at the one enforcement branch that writes no event -- the auto-kick cooldown.
- **An induced crash in a logcat is not a crash.** `shell-induced` and `UiAutomation` in a stack trace
  mean the harness, not the app. Grepping for `FATAL EXCEPTION` and reporting the count is how a QA
  artifact becomes a phantom P0.

Four real defects came out of root-causing it anyway, and they are the useful part:

1. **A blocker that stops blocking must say so.** After the kill, Android's Settings said "Enabled,
   but your phone stopped it" while Nudge's permanent notification read "Nudge is active" -- because
   that string was a constant. `ServiceHealth` (pure, four states, no `else`) now drives the copy, and
   a degraded state raises a separate `IMPORTANCE_DEFAULT` alert deep-linking to accessibility
   settings. **The recovery prompt is a notification, never an Activity**: a service that opens a
   screen over whatever the user is doing is the very thing the report described.
2. **"Enabled" and "connected" are different questions**, and they disagree for minutes after a
   process kill. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is what the user controls;
   `NudgeAccessibilityService.isConnected()` is what the system controls. Collapsing them hides the
   whole failure window.
3. **`android:allowBackup="true"` on a no-INTERNET, "all data local" app.** It uploaded the database
   to Google Drive, and running the backup force-killed the process nightly -- the trigger for
   everything above. Off now on every API level (`allowBackup` + `dataExtractionRules` +
   `fullBackupContent`, every domain excluded on both cloud-backup and device-transfer).
4. **The auto-kick cooldown enforced on remembered authority.** It is the only branch that blocks
   before reading a rule and writes no `UsageEvent`; delete the rule while the timer runs and it kept
   ejecting the user from an app nothing was configured to block, invisibly. `CooldownGate` makes the
   authority derived (a live counter-cache entry) instead of remembered, in **both** places the
   pattern existed -- the app path and the web path.

Also fixed in passing: `NudgeMonitorService.start` had exactly one caller (`BootReceiver`) and `stop`
had none, so a fresh install ran no foreground service until the next reboot and the notification
outlived the master toggle. `sync(context, enabled)` is now called from boot, app launch, and the
toggle collector. **A `stop()` with no callers is a lifecycle that was never finished, not dead code
to delete.**

## Do NOT re-enable `allowBackup` "for testing" (2026-09-07)
`android:allowBackup="false"` landed in 1.15.4 for two reasons, and the weaker one is the one that will tempt someone to revert it.

**The strong reason (privacy, non-negotiable):** with backup on and no rules, Android Auto Backup uploads the Room database to the user's Drive. Nudge has no INTERNET permission and its whole positioning is that data stays on the device. That was untrue while backup was on.

**The weaker reason (reliability):** the backup run kills our process. Verified signature in logcat, `full_backup_package: <pkg>` immediately followed by `am_proc_died`, with services `am_schedule_service_restart`'d a few seconds later. (Note "reason 100" in `am_proc_died` is the OomAdj field, not a reason code.) Measured rebinds are 150ms-3s, so this produces a **seconds-long** window, enough to make the ongoing status notification lie. It is NOT an explanation of the overnight stoppages in issue #23; that remains unexplained and is why the watchdog exists. Do not let the changelog or a future investigation conflate the two.

**The trap:** turning backup back on is the only way to make `bmgr backupnow` kill our process, which makes it an attractive fault injector for testing the watchdog. With backup off the framework skips our package entirely and `bmgr backupnow` returns `Backup is not allowed`. **Losing that injector is an accepted trade.** If you need to exercise a process-death path, use `run-as <pkg> am stopservice` (stops a service while the process lives, which is what the notification-id regression test needs) or a real low-memory kill. Never ship, and never QA against, a build with the privacy bug reinstated.

## A package list is never the answer to "has the user left the app?" (2026-09-11)

Issue #28 was the fifth bug in this subsystem, and the first four fixes are why it happened.

Each one — #5 (keyboards), the Home path, #7 (recents re-entry), #19 (picture-in-picture) — found a
window event that was being misread as "the user switched apps", and each one fixed it by adding
names to a set: `SYSTEM_PACKAGES`, `IME_PACKAGES`, `NEVER_LAUNCHER_PACKAGES`. Every one of those
fixes was correct. Together they guaranteed a fifth bug, because the thing they were all patching was
a **model**: *a foreign package fired a window event, therefore the user left*. That model is simply
false. A photo picker, a share sheet, the Pixel's `com.google.android.permissioncontroller` (which is
NOT the `com.android.permissioncontroller` that was in the list), a custom tab, a notification hop and
an OEM volume panel are all ordinary app packages that an app puts in front of you while you are
still using it. No list will ever contain them all, and the list you write today goes stale the next
time an OEM ships a package you have not heard of.

**The tell:** you are adding an entry to a hardcoded set to fix a bug report, and that set already
has entries added by previous bug reports. The number of previous entries is the number of times this
has already failed. Fix the question, not the list.

The replacement — `SittingTracker` — ends a sitting only on evidence that the user actually stopped:
Home, screen-off, or another app holding the foreground past a five-minute return window (the same
`SESSION_EXPIRY_MS` the interaction counter already used, so the two cannot disagree). A sub-flow is
then a sub-flow by construction, with nothing to recognise.

Related trap, same file, third occurrence: **one membership test answering two different questions.**
`SYSTEM_PACKAGES` had already sprung this on the passthrough grant and on the foreground-time clock
before it sprang here. It now answers only "should this be evaluated / should the overlays hide", and
is structurally barred from the "did the user leave" question.

## A debounce is a rate limit, and rate is not a measurement (2026-09-11)

The other half of #28. The interaction counter counted one interaction per `TYPE_VIEW_SCROLLED` that
survived a 500ms debounce, and — for packages outside `InAppDetector.SUPPORTED_PACKAGES` — one per
second of `TYPE_WINDOW_CONTENT_CHANGED` as a stand-in for taps.

Measured on the Pixel 3: **one deliberate 2.5-second drag emits ten scroll events**, and an
**untouched phone sitting on the Instagram feed emits them anyway**. So the counter reported 5-6 for
one gesture and ticked upward with nobody touching the device — and it feeds auto-kick, so it could
eject a user for doing nothing. Every previous attempt to fix "the counter is wrong" tuned the
debounce, which cannot work: the debounce was never the bug, using rate as the unit was.

The ground truth had been in the events the whole time and was never read. `TYPE_VIEW_SCROLLED`
carries `fromIndex`, `toIndex`, `currentItemIndex`, `itemCount`, and `scrollDeltaX/Y` on API 28+.
Across that ten-event drag, `fromIndex` reads `0,0,0,0,0,0,0,0,1,1` — one transition.

**Before tuning a threshold on a signal, check whether the event already carries the quantity you
actually want.** Debounces are fine as guards against duplicate delivery. They are never the thing
that decides what a user did.

Corollary that cost a release here: **a proxy signal that cannot fail visibly will not be noticed for
years.** The content-change-as-taps proxy shipped, and "the counter is a bit odd" sat in the backlog
as an unexplained item for four versions. It is now deleted rather than tightened: a package we
cannot measure reads zero, which is honest, where the old number was fabricated and wore the word
"taps".

## Capture the event stream before theorising about it (2026-09-11)

Every bug in this subsystem — #5, #7, #19, #28 — was a disagreement between the event stream we
assumed and the one the device produces, and each cost at least one device cycle to localise by
guessing. #19 cost a whole release.

`scripts/a11y-capture.sh` now writes the real stream to
`app/src/test/resources/a11y-captures/<name>.jsonl` and JVM tests replay it. **The workflow for the
next report is: reproduce with the capture running, commit the `.jsonl`, write the assertion that
FAILS on it, then fix.** Three things learned building it, all of which cost time:

- **A fixture with no stated oracle cannot fail.** Each capture's `#` header carries HOW it was taken
  and what a human EXPECTED, and a test asserts every committed capture has both.
- **Pair each fixture assertion with a counterfactual** that runs the OLD rule over the same capture
  and asserts it still fails. Otherwise a capture that quietly stops reproducing leaves a green test
  asserting nothing.
- **A source-level contract test must strip comments before grepping.** The comments in this file
  necessarily quote the code they explain; a contract test greping raw source read its own
  explanation of the fix as evidence the bug was still there — and, worse, could pass because a
  comment mentions something the code no longer does.

## `mCurrentFocus` does not tell you which SCREEN you are on (2026-09-11)

Three device captures were taken on a YouTube video player while every focus check said
`com.google.android.youtube`, because YouTube is a single-activity shell. The captures looked clean
and contained no scroll events at all, which nearly became the finding "YouTube emits no scroll
events" — false for the feed, true for Shorts, and the two are only distinguishable by looking.
**Screenshot the screen and read it.** A capture of the wrong surface is worse than no capture,
because it looks like data.

Second trap on the same run: **relaunching a backgrounded YouTube drops it into picture-in-picture**
when the block overlay backgrounds it, #19's PiP gate then swallows its events, and the gestures land
on the launcher instead. 5 of 8 launches landed wrong. `tools/qa/scroll-capture.sh` force-stops
first; anything that relaunches an app for a capture should.

## An unmeasured "perf" line item cost two whole screens their audience (2026-09-11)

Commit 32b9348, "perf: eliminate recomposition waste and object allocation hotspots", is eleven
bullets of real, measured work and one that was neither: *"Disable Material ripple globally
(Theme.kt)"*. No benchmark, no jank report, no design note — a guess, bundled into a commit whose
other items made it read as obviously correct, and reviewed as one unit.

Four months later the owner reported: *"I had no idea I could click the Blocked and Walked Away
tiles on Home and that they lead to two different screens with charts."* With `LocalRippleConfiguration
provides null` at the theme root, **every `Modifier.clickable` in the entire app produced zero touch
feedback**. Two insight screens (`WillpowerScreen`, `InterventionsScreen`) — correct, charted, 37
calculator tests behind them — had effectively no users, because their only entry points were silent.

- **A change with no measurement behind it is not a perf change, it is a behaviour change.** The tell
  is the mismatch between scope and evidence: the other ten bullets each name the thing they fixed
  ("was re-converting every recompose during scroll"); this one named nothing. If a bullet cannot say
  what it measured, it does not belong in a commit whose neighbours can.
- **Global switches deserve a written reason or they do not get flipped.** The right shape for
  "ripple is expensive on this surface" is an override on *that* composable. App-wide is never the
  cheap version of a local fix; it is a different, much larger change wearing the same diff size.
- **A feature is not shipped when it is correct, it is shipped when someone can reach it.**
  `docs/architecture/stats-and-charts.md` had documented "two sources of truth for one number" twice;
  this is its mirror image — one source of truth, invisible. Neither is catchable by a value test,
  so both are now pinned by source-level contract tests (`HomeTileAffordanceContractTest`,
  `StatsInsightEntryContractTest`), including one asserting nobody reintroduces the global ripple kill.
- Corollary for review: when a UI element's whole job is to be tapped, "does it look tappable" is
  part of the acceptance criteria, not polish to be done later.

## A guard clause that returns before notifying a collaborator is a dropped message (2026-09-12)

Issue #28 shipped a caption invalidation that could never fire. The handler had a correct, tested
branch for "detection recognised nothing, so the caption is stale" — and the caller was:

```kotlin
val feature = detectFeature(packageName, rootNode) ?: return
interactionHandler.noteDetectedFeature(packageName, feature)
```

The elvis-return reads like ordinary defensive style. It meant the handler was only ever told about a
RECOGNISED feature, so the null branch was unreachable from production while passing its own unit
tests happily. Device QA found it; nothing else could have.

**The tell:** you add a branch to a callee for the null/empty/absent case, and never check that the
caller can actually produce one. Grep the call sites for `?: return`, `?: return@`, `if (x == null)
return` ABOVE the notification, every time you make a collaborator care about absence. "Absence" is a
message, and an early return is how a message gets dropped.

Corollary, cheap and worth it: the branch that consumes absence belongs in a SOURCE-LEVEL contract
test, not only a unit test. A unit test calls `noteDetectedFeature(pkg, null)` directly and passes
whether or not anything in the app ever does. Only the caller can be wrong, and only a test that
reads the caller can see it.

## Every test exercising the same trigger will miss a bug reached by a different one (2026-09-12)

Five tests were written for the caption rule. All five made the counter tick by SCROLLING. On the
device the first thing that counted after the user changed surface was the TAB TAP — a click. Same
rule, same code path downstream, different entry point, and every test was blind to it.

This is not a coverage-percentage problem; the line coverage was fine. It is an INPUT-SHAPE problem.
When a rule is reachable through several entry points (click and scroll; window-state-changed and
content-changed; app and web), write at least one test per entry point or state plainly which ones
are untested and why. A suite that only ever pulls one lever is measuring one lever.

## A fix can reintroduce the bug class it was written to fix (2026-09-12)

Issue #28 is "the user gets re-blocked though they never left the app". The fix added a sitting
model. A review round then hardened it with `resetSitting()` on `onServiceConnected`, reasoning that
a bind is the start of observation so nothing before it can be trusted.

That reasoning is correct about the away CLOCK and wrong about the GRANT — and `docs/BACKLOG.md`
already recorded that this service churns and reconnects on the bench device under memory pressure.
So the hardening turned every reconnect into a re-block of a user who never left: the original bug,
wearing the fix's clothes, two rounds later.

**When hardening a fix, re-read the bug it fixes and ask whether the hardening can produce that
symptom.** Ask it specifically about the failure DIRECTION: this subsystem has always chosen "miss a
revoke rather than interrupt someone mid-use", and the hardening silently chose the opposite. A
direction that is stated in the docs should be quoted in the review of anything that changes it.

## Rule out a hypothesis with the device, then write the ruling-out down (2026-09-12)

The reported second-overlay bug came with a detailed and plausible picture-in-picture theory. Two
scripted runs (one with a 150-second dwell) showed: one block, `pictureInPicture=false` throughout,
the PiP set never changing, and no sitting end past the return window. PiP, the tab tap and the
return window were all eliminated in about six minutes of device time, which is what left the rebind
as the only candidate.

The capture is committed WITH the three ruled-out hypotheses in its header. A fixture that records
only what did happen invites the next person to re-derive the same three dead ends.

## If you are writing a test to police a rule, the design is wrong (2026-09-12)

The home-screen widgets needed refreshing when the data they display changed. The first design added a
`WidgetRefreshSignal` to `NudgePreferences` and `UsageRepository` and expected every writer to call
`requestRefresh()`. It was defaulted to a no-op constant so the screens that construct those classes by
hand kept compiling. Four bugs came out of that one decision, and every value in the app was correct
throughout:

- `recordProtectionCheck` and `setStrictModeEnabled` never called it. The Protection widget is
  `updatePeriodMillis="0"`, push-only, so **"protection has stopped" - the one state that widget exists to
  announce - was the one state it could never receive.** A widget placed while healthy stayed
  healthy-looking indefinitely. Only device QA could see it.
- `applyImportedSettings` had the same gap and had not even been suspected.
- The fix to `setStrictModeEnabled` was then **unreachable**: `SettingsScreen` owns the Strict Mode toggle
  and built its own `NudgePreferences`, so the write went through an instance carrying the no-op. Correct
  code, tested code, on the one path every user takes.

Then came the mistake worth naming. The response was a source-scanning contract test that discovered which
preferences the widgets read, resolved each to its storage key, and required every assigning function to
push - a regex parser simulating what a compiler should be guaranteeing. It was good of its kind and it
caught the import path. It was also a signal that the design was wrong, and that signal was ignored for a
round.

**The actual fix was to delete the rule.** `NudgeWidgetUpdater` now COLLECTS the sources of truth - the
preference flows the widgets read, and a `MAX(id)` change signal over `usage_events`. Any write, through any
instance, from any layer, by any future caller, propagates, because it is the same DataStore and the same
Room table. The signal interface, its no-op constant, the `@Binds` module, the entry point added to route
around the hand-built instance, and the contract test policing all of it were deleted together.

- **A default argument on a collaborator with invisible side effects converts a compile error into a silent
  runtime no-op.** Take that trade only when the no-op is harmless for *every* caller, not just the ones you
  had in mind.
- **A comment is not a gate.** The no-op constant's own KDoc predicted this bug almost word for word - "if a
  future caller writes a widget-visible value through a hand-built instance, it shows up in the diff as a
  word rather than as nothing at all". It did. Nobody looked.
- **A test that enumerates the callers of a rule is a design smell, not a safety net.** Ask what the
  consumer actually depends on and subscribe to *that*. Correctness by construction needs no enumeration, and
  the enumeration is what rots: it can only ever pin the writers that exist today.
- **Deleting a test whose defect has become unwritable is the goal.** Deleting one because it is inconvenient
  is not. Four assertions went when the rule did; the one that survived pins that the observed set still
  covers the rendered set, which is the only part construction cannot guarantee on its own.
- Same shape as the watchdog lesson above: **an alarm nobody can observe is not an alarm.**

## On a surface you cannot observe, instrument FIRST - three device rounds proved it (2026-09-12)

A widget showed a stale value. Three device rounds went into it, and the first two were spent arguing
because there was nothing to read:

1. **Round one** produced "it fails" and nothing else. I hypothesised the app process was being frozen
   while backgrounded, which was plausible and wrong.
2. **Round two** added one log line per refresh. That killed the freezer theory outright (`isFrozen=false`,
   `curProcState=FOREGROUND_SERVICE`) and proved the refresh *did* fire, 138 ms after the tap. My fix had
   addressed a cause that did not exist.
3. **Round three** added a second line logging what each render actually READ. That located the failure
   precisely: `updateAll` is called and `provideGlance` frequently never runs.

The three candidate explanations - **the refresh never ran**, **it ran and read stale values**, **it ran,
read correctly, and the render was lost** - are indistinguishable from outside a widget, and they have
nothing in common as bugs. Two rounds of reasoning could not separate them. Two log lines did it in one.

- **A subsystem with no screen to fail on earns permanent diagnostic logging**, at debug level, from the
  day it is written. `docs/architecture/widgets.md` opens by saying every failure here is silent; the code
  should have been written to answer "did it even try?" from the start.
- **Log the VALUE the code acted on, not just that it acted.** "Refresh fired" narrowed nothing. "Refresh
  fired, and it read `enabled=true`" is the whole finding.
- **A confident mechanism with no instrument behind it is a guess wearing a lab coat.** Both of my wrong
  hypotheses were coherent, specific, and consistent with everything I knew - which is exactly what makes
  this failure mode expensive rather than obvious.
- **Before reverting a change that "broke" something, check whether the old code passed the same test.**
  The instinct was to drop the commit. Checking `28bedc7` showed the identical user-visible failure there,
  for a different reason - so reverting would have kept the bug and thrown away four real fixes. "It fails
  now" is not the same as "this change broke it", and the difference is one `git show` away.
- **When the remaining unknown is in a dependency, READ THE DEPENDENCY.** I originally wrote this bullet as
  "stop and file it", and that was half right: stopping the *device rounds* was correct, but the conclusion
  should have been to decompile the library, not to hand the bug over. `javap -c` on the Glance AAR found the
  mechanism in about ten minutes, after three device rounds had failed to. A third-party jar is not a black
  box; it is source you have not read yet. Behaviour that makes no sense against your own code is the
  strongest possible signal that the library is doing something you assumed it did not.

  What it turned out to be, and it is worth knowing for any Glance widget: **`provideGlance` runs once per
  SESSION, not once per update.** `AppWidgetSession.processEvent` handles `UpdateGlanceState` by refreshing a
  `MutableState` from the `GlanceStateDefinition` and recomposing; it never calls `provideGlance`. Sessions
  live 45s (`initialTimeout`, with `idleTimeout` 5s). So `val snapshot = read()` above `provideContent` is
  frozen for the session, and every refresh inside that window re-renders it - running, logging, throwing
  nothing, changing nothing. The timeouts are what made it intermittent, and intermittent is what made three
  rounds of black-box testing useless.


## A walk-away writes TWO `wasBlocked` rows, so a DB-count QA assertion is easy to get wrong (2026-09-14)

Device QA for #26 was briefed to expect `wasBlocked` to rise by exactly 10 across 10 "I changed my mind"
attempts. That brief was wrong, and the wrong number turned a passing run into a reported FAIL that cost a
round of investigation.

One walk-away writes **two** rows carrying `wasBlocked = true`: the block itself from `handleDecision`, and
the walk-away row from `RecordWalkAwayUseCase`, which sets `wasBlocked = true` **and** `userChangedMind =
true` so it is counted by both the "Blocked" and "Walked Away" tiles. That deliberate double-count is what
`InsightsCalculator.overlaysFromAllTimeCounts` subtracts back out, and it is documented on the use case
itself. So the correct expectation for N walk-aways is `wasBlocked` **+2N**, `userChangedMind` **+N**.

The run in question measured +25 over 10 attempts. Against the wrong baseline of 10 that looks like a 2.5x
inflation; against the real baseline of 20 it is 20 by design plus 5 genuine duplicates, which is what it
turned out to be. **Before writing a DB-delta assertion into a QA brief, read the writers of that column,
not just the one you are testing.** Here that is two files, and `grep -rn "wasBlocked = true" app/src/main`
finds both in one command.

## `shouldClearForOwnPackageEvent` can never return true in production (2026-09-14)

`NudgeAccessibilityService.shouldClearForOwnPackageEvent` tests `className?.startsWith(ownPackageName)`. The
applicationId is `dev.astraedus.nudge`; the classes are `com.astraedus.nudge.*`. The prefix never matches, so
`isOwnAppWindowEvent` is always false and the `clearOverlays(packageName, "own_app_window")` branch inside the
`ForegroundSignal.OwnUi` handler is unreachable. Its unit tests pass because they pass a matching
`ownPackageName` that production never supplies (`PassthroughTest` uses `OWN_PACKAGE = "com.astraedus.nudge"`).

Found while looking for a way to detect "our overlay's window is on screen" from the event stream; that need
was solved instead by having `BlockOverlayActivity.onResume` report itself (see
`docs/architecture/foreground-detection.md`, "A second overlay for one entry"). The defect above is NOT fixed
and wants its own ticket. **The general trap: a test that supplies its own value for a production constant
cannot see that the production value breaks the predicate.** Where a test hardcodes an identity like a
package name, it is worth one assertion tying it to the real `applicationId`.

## The gate that decides whether to SHOW is not the gate that decides whether to COUNT (2026-09-20, issue #36)

A user reported 2500 interventions in one day against a usual couple of hundred, on v1.17.1, the release
that had just fixed two over-counting bugs. It was not a third double-count. It was a loop, and the reason a
loop could exist at all is that `handleDecision` wrote a `UsageEvent` for **every launch `BlockLaunchGate`
allowed**. Four separate gates in this subsystem are careful about whether an overlay may be shown, and all
four are correct about that; none of them was ever about counting. So the count silently inherited "one row
per launch". Every mechanism that can put the overlay back up by itself then wrote a row too, once per
iteration, for as long as it ran: the overlay stopped and finished by a screen-off or a re-fronting app, a
re-delivery through `onNewIntent`, a dying instance clearing the live one's state, the walk-away fail-safe
popping the app back.

**The general trap: when one piece of state answers a question nobody asked it, the second question inherits
the first one's rule and nothing type-checks the difference.** Ask what the NUMBER on the user's screen is
supposed to mean, in the user's words, before deciding where to gate it. Here it is *"how many times did I
run into a block"*, which is a fact about the user's ARRIVAL, and arrival is not a fact any of the existing
gates held.

Two corollaries worth keeping:

- **A cap that evicts is not an invariant.** The arrival first remembered "the last 32 confrontation keys",
  which bounds memory and leaves the rows unbounded: an evicted key looks new again the next time round. A
  loop that manufactured distinct keys would have been back at 2500 a day with a tidier data structure. The
  cap only became a guarantee when it became a refusal. The test that caught this was the one asserting the
  ceiling as a NUMBER: it failed on the first run. Writing it as "and there is a cap" would have passed.
- **An invariant that silences a symptom owes a diagnostic.** Once the count is safe whatever loops, the
  loop stops announcing itself and the next report arrives as nothing at all. `launchBlockOverlay` therefore
  counts launch ATTEMPTS (not launches: a run the gate keeps dropping writes no rows and is exactly where the
  next one hides) and logs one `w` line per storm. One line, not one per event, or the loop fills logcat with
  the evidence of itself and pushes out everything that explains it.

The "is this Nudge's own app in front?" question here is asked **positively, by exact class**
(`isOwnMainAppWindowEvent`), never as "Nudge and not the block overlay": the overlay TASK's first window
arrives ~600ms early carrying the framework class `android.widget.FrameLayout`, so a negative test would have
classified it as the app and ended the very arrival the overlay belongs to. The still-unfixed
`shouldClearForOwnPackageEvent` defect below is a second reason not to reuse that predicate.

## The bench Pixel must carry a RELEASE-signed Nudge, never a plain debug build (2026-09-21)

The #36 device QA hit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` installing the CI-built `main-latest` dev APK: the installed v1.17.1 had been seeded from a local `assembleDebug` (generic Android debug key) while every CI artifact, release or rolling, is signed with the real release key. A signature mismatch cannot be flagged past; the tester had to `run-as`-export the rules, uninstall, reinstall and re-import, losing the device's usage history. The device now carries the release cert. Rule: seed the Pixel only from a CI APK (`gh release download main-latest` or a `v*` release), and when a debuggable build is genuinely needed, sign debug with the release key as the 2026-08-20 entry describes, so the next CI build still installs over it.

## A correction that lives on ONE screen is a bug on every other screen (2026-09-21)

Device QA on the 1.17.2 dev build: the home dashboard's Blocked tile rose by **2** per walk-away, reading
210 all-time where the user had faced 181 confrontations. Nothing was miscounted: a walk-away legitimately
writes TWO rows (the overlay-shown row plus `wasBlocked=1, userChangedMind=1`), and the tile faithfully
counted rows. The Interventions screen, two taps away, read 181, because `InsightsCalculator` classified its
rows and `overlaysFromAllTimeCounts` subtracted the duplicates back out.

So the app had ONE quantity with TWO definitions, and the correct one was on the screen nobody opens first.
It had also been seen before and written down as expected: `RecordWalkAwayUseCaseTest`'s KDoc recorded "the
home screen read Blocked 9 / Walked Away 4 off exactly those rows" in August, and
`docs/architecture/widgets.md` said the Today widget shows "the RAW day counts the Home tiles show" **on
purpose**, to agree with the tile. Agreement with a wrong number is how a local bug becomes a global one.

What generalises:

- **A correction belongs at the source of the number, not on the screens that display it.** The fix is one
  predicate, `UsageEvent.isShownConfrontation` (`wasBlocked && !userChangedMind`), with the DAO's count
  queries as its SQL mirror (`AND userChangedMind = 0`), and the raw `getBlockedCount` /
  `getAllTimeBlockedCount` readers DELETED so the old question cannot be asked. `blocked - changedMind`
  arithmetic would have produced the same numbers today and needed re-remembering on every future screen,
  and it under-reports the moment a walk-away row outlives its paired show row.
- **"Deliberately raw, to match the other surface" is a smell, not a decision.** Two surfaces agreeing is
  worth nothing on its own; what they must share is the QUERY. If a doc has to explain why a screen shows
  the less correct number, that is the bug asking to be fixed.
- **Fix the class, then grep for the rest of it.** The report was about the home tile. The same raw count
  also drove the Today widget, the weekly trend bars (drawing each walk-away in BOTH series of one bar),
  App Detail's per-day/total counts and its block-mode breakdown. Five surfaces, one predicate.
- **When the bug is WHICH question a screen asks, only a source-level test can see it** (fourth sighting in
  this repo). There is no SQLite on this test target, so `@Query` text is never executed by any JVM test;
  `BlockedCountSemanticsContractTest` reads it as text, forbids a `COUNT` over `wasBlocked` without the
  exclusion, and walks every file under `ui/` asserting none touches the raw column, discovered rather than
  hand-listed so the sixth surface is covered the day it is written. The export path is explicitly excluded
  and separately pinned: a BACKUP carries rows, not tiles, and both rows must survive a round trip.
- **A test whose numbers encode the bug must be re-stated, not deleted.** `HomeChartsBuilderTest` asserted
  `weekBlocked == 3` over a fixture containing one walk-away. It was green throughout, and it was the bug
  written down as a contract.

## 1552 green tests aimed at the wrong layer: thirteen "coding mistakes" were testing-strategy failures (2026-09-21)

The owner asked the right question after a run of shipped defects: *"a lot of these bugs, I swear, shouldn't
our tests catch this? how are we actually testing this, are we doing tests wrong, are we missing something?"*
The suite was evaluated against the last 18 defects. It is not theatre and it is not wrong. It is ~1550 tests
aimed almost entirely at **one layer** — pure JVM logic — while the bug history sits in three places that
layer cannot reach as written: lifecycle ordering, real-device event timing, and fixtures that agree with the
code instead of with the device.

The number that settles it: **75% of the test budget sits at the layer that would have caught 3 of 18**, while
the layer that would have caught the most (**5** — replaying a recorded device event stream) holds **1.9%**.
Writing more tests was never the fix. Every one of those 1552 could have been doubled and the same bugs ship.

**The lesson is that "write a test for it" is an incomplete instruction.** The complete one is *"write a test
at the layer this bug lives at"*, and the author is systematically the worst judge of that in the moment: #36
shipped under 49 exhaustive, correct tests of the gates deciding whether to SHOW an overlay, because nobody
asked which layer the COUNT question lived at. Exhaustive tests of the wrong question produce confidence, not
safety. Hence the rule now in `docs/testing-strategy.md` and `CLAUDE.md`: **every bug-fix PR body names its
layer (L1–L6) and puts the test there**, or says why that layer is not worth building for this bug.

**Thirteen entries already in this file are this same failure wearing a coding-mistake costume** — "every test
exercising the same trigger will miss a bug reached by a different one", "the gate that decides whether to
SHOW is not the gate that decides whether to COUNT", "if you are writing a test to police a rule, the design
is wrong", "a correction that lives on ONE screen is a bug on every other screen", and nine more. Each was
filed as a bug lesson. Read together they are one lesson about aim, and that is only visible when you count
them, which is the argument for doing this kind of evaluation periodically rather than trusting the running
total of green tests.

Three specifics that came out of it and are now doctrine:

- **A fixture that hand-types an identity constant agrees with its author, not with the device.**
  `PassthroughTest` supplies `ownPackageName = "com.astraedus.nudge"` to *both* sides of every comparison —
  that is the `namespace`, while `applicationId` is `dev.astraedus.nudge`, so `isOwnAppWindowEvent` has been
  dead in production for months under a green suite (#33). Same shape in `NudgeDatabaseMigrationTest`'s
  hand-kept `currentVersion` and `SettingsExportTest`'s hand-typed list of block modes inside a test named
  *"every real block mode is accepted"*. Derive from `BuildConfig`, the annotation, the enum.
- **A capture is cheaper than a theory.** #28a shipped because the model ("a foreign package fired a window
  event, so the user left") was false and no recorded stream existed to contradict it. Any accessibility
  report now owes a committed capture with an oracle *before* the fix is designed.
- **Do not buy a layer because a survey says it is standard.** Robolectric and an emulator suite are the
  textbook answers and are both deliberately deferred, with the trigger that would reverse each written down.
  The two practices funded instead — replay with a counterfactual, and fixture honesty — cover 8 of the 18
  defects, need no new dependency, and make the tests already here honest.

## A backup that FILTERS is data loss the file format cannot express (2026-09-21, issue #43)

`ExportRulesUseCase` read `repository.getEnabledRules()`. Its own KDoc said "all enabled rules", so the
filter read as intentional for a year, but the file format had carried an `enabled` flag per rule the whole
time, and the importer had always restored it. The format was describing a state the exporter could never
produce. A rule the user had switched off was simply absent from their backup, and a restore onto a wiped
phone lost it with no error, no count, and nothing in the UI to notice.

- **On a backup path, "which rows do we collect" is a correctness question, not a filter.** Every other
  failure mode here is loud (per-entry skips are counted, a wrong-shaped envelope fails the import). A
  narrowed *query* is the one data loss that reports success. When a use case says it exports a thing, check
  which query feeds it, not just what it does with the rows.
- **A field the format carries but the writer can never vary is the tell.** `enabled` was serialized,
  parsed, defaulted (`optBoolean("enabled", true)`) and applied to the inserted `BlockRule`, four places
  handling a value that was constant by construction. That asymmetry is visible from the data classes alone.
- **The regression test asserts the COLLECTION, not the output.** `ExportRulesUseCaseTest` leaves
  `getEnabledRules` deliberately UNSTUBBED on the mockk repository, so reaching for it again fails loudly
  instead of quietly exporting less. Asserting "2 rules came out" would pass for a fixture where both happen
  to be enabled.
- **Backward compatibility rode on the default, not on a version bump.** A pre-#43 file names no `enabled`
  key *and only ever contained enabled rules*, so the importer's `true` default is exactly right and must
  stay; reading that silence as "off" would restore a phone that blocks nothing. Envelope stays version 1 in
  both directions, for the same reason `history` and `settings` did.

## Robolectric was tried, and the price was not the one on the tin (2026-09-21)

The lifecycle-ordering layer (#8, #15, #26, two of #36's four mechanisms) was first built with
Robolectric, because that is the textbook answer and the research said so. It was reverted, and the
reasons are worth keeping so nobody pays the same hour twice:

- **It did not run.** Robolectric 4.17 (the current stable, SDK 37) fails on this machine's JDK 21 with
  `RuntimeException at AndroidInterceptors.java:88 -> IllegalAccessException at Reflection.java:394`,
  *with* the documented `--add-opens` set already applied. That is a boot-time failure, before a single
  assertion runs. Anyone reviving this owes a JDK-version answer first, and CI's JDK is not this one.
- **The sticker price was the wrong number to look at.** The dependency line is two entries; the real
  cost is a per-SDK `android-all` runtime download and a permanent tax on every `./gradlew test` run,
  paid by ~1550 tests that do not need it, to reach decisions that turned out to need no dependency at
  all.
- **What reached the same bugs instead**: the decisions were extracted out of `BlockOverlayActivity`
  into the pure `domain/block/OverlayLifecycle` — lifecycle callbacks in, an ordered list of
  `Effect`s out — and driven against the REAL `BlockLaunchGuard` in `OverlayLifecycleGuardTest`, in the orderings
  the platform actually produces. 36 JVM tests, no new dependency, no measurable change to the suite's
  wall time.
- **Extracting the decisions costs the spelling-pinning contract tests, and that is an upgrade, not
  damage.** Six assertions across four `*ContractTest` files were greps for `walkedAway.compareAndSet`,
  `++renderToken`, a `finish()` inside `navigateHome`, and a regex that read
  `WALK_AWAY_FINISH_FAILSAFE_MS` out of a source file. Each was re-homed: the ORDER of the walk-away's
  effects and the fail-safe's re-arm-before-finish are now VALUE assertions, the two timing constants
  are compared as numbers, and what stays source-level is only what a value test cannot reach — that
  the adapter still forwards each callback. Re-home them; do not delete them to go green.

## An OR locator cannot name a row that shares its id with every sibling (2026-09-24)

The Following steer picks Instagram's "Following" row out of the title dropdown. `NodeLocator` ORs its
three handles (ids, texts, contentDescriptions), which is right for the Reels tab — match `clips_tab`
OR the description "Reels", whichever this build exposes — and wrong here, in both directions at once:

- **Id alone is ambiguous.** Every row in that menu carries `context_menu_item_label`, so an id-first
  lookup returns whichever row is first in the tree. The committed `logo-menu` dump holds both
  "Following" and "Favorites", so a coin decided which one Nudge tapped inside somebody else's app.
- **Text alone is dangerous.** `findAccessibilityNodeInfosByText` is a case-insensitive SUBSTRING search
  over the whole tree, so a feed caption containing "following" matches, and the clickable ancestor of
  a caption is the post.

Only both together identify the row, so `NodeLocator` gained an opt-in `scopedLabel` (id AND label) that
`HostNodeFinder` honours **with no fallback to a text search** — falling back would reintroduce the
caption case at exactly the moment we are most likely to be on the feed.

- **The flag is DECLARED, never derived, and that is the whole lesson.** The first version computed it as
  "has ids AND has labels", which silently swept up the Reels tab locator and turned its deliberate
  locale fallback into a requirement: the cover would have stopped appearing for every user whose
  content-description is not the English string. Inferring intent from which fields happen to be
  populated is not a shortcut, it is a second meaning for the same data.
- **The counterfactual is what makes the flag provably load-bearing.** A test asserting "the finder
  returns Following" passed under OR too, because the first OR match in that dump happens to be a
  Following-labelled node. The test that earns its keep runs production's own `matches` over the same
  dump and asserts it hits MORE than one row, one of them Favorites.
- **One matching rule, one function.** The scoped/unscoped branch was briefly written out twice, once in
  `HostNodeFinder` for real nodes and once in the fixture reader for dumps. Two copies is how a fixture
  agrees with its author instead of with the device: the reader would select "Following" under OR while
  production selected whichever row came first, green about the wrong thing. Both now call
  `NodeLocator.matchesNode`.

## A JVM test cannot see anything an android CONSTRUCTOR assigns (2026-09-24)

Two `TabCoverOverlayManagerTest` cases failed with `expected:<216> but was:<0>` and "the cover must never
steal input focus". The window was built with `WindowManager.LayoutParams(w, h, type, flags, format)` —
and that five-argument constructor is the only thing those five assignments go through. In this module's
unit-test target an android constructor body is a no-op stub, so `width`, `height`, `type`, `flags` and
`format` all read back as 0, while the `x`/`y` writes in the trailing `apply` block survived because plain
field writes need no android implementation. So exactly the flag set that IS the feature was the part no
test could assert.

The fix is a production change, not a test workaround: assign every field individually in the `apply`
block. Behaviour is identical because the constructor was only doing those assignments, and the window's
whole contract becomes assertable. **The two tempting shortcuts are both traps:**
`unitTests.isReturnDefaultValues = true` would turn every unmocked android call into a silent 0/null
under ~1550 existing tests, and Robolectric was already tried and reverted here (see the 2026-09-21
entry). Weakening the assertions was never an option — touchable, not focusable, laid out in screen
coordinates is the feature.

Same family, same day: `@Database`'s `version` cannot be read by reflection either. Room declares the
annotation with BINARY retention, so `NudgeDatabase::class.java.getAnnotation(Database::class.java)`
returns null and a migration test "deriving" the version NPEs. The answer is not a hand-kept constant in
the test (that is the fixture-honesty failure this file records for `PassthroughTest`) but ONE
declaration both readers share: `NUDGE_DB_VERSION`, which the annotation itself is built from.

## Parallel agents in ONE worktree must serialise gradle (2026-09-24)

Four lanes sharing a worktree ran gradle concurrently and corrupted each other: `Unable to delete
directory ... transformDebugClassesWithAsm ... a process is still writing to the target directory`, then
`NoClassDefFoundError` on classes that were plainly on disk, then a "full suite" run reporting 40 FAILED
lines whose own result XML said `failures="0"` and which had written only 9 of 148 result files.

**A test failure you cannot reproduce under a lock is not a test failure.** Every gradle invocation goes
behind `flock /tmp/nudge-gradle.lock`, and a `NoClassDefFoundError` on an existing class means
`rm -rf app/build/intermediates/classes` and rerun, not a hunt through the source. Two further notes for
whoever automates this: `./gradlew test --tests '<glob>'` is rejected outright ("Unknown command-line
option '--tests'") because `test` is the aggregate task — the variant task `testDebugUnitTest` is the one
that accepts the filter — and a bare `./gradlew test` as a workaround makes the contention much worse.

## A correct state machine, an unreachable branch: the CADENCE was the bug (2026-09-24)

The Following steer clicked Instagram's dropdown and then never clicked "Following". The menu hung
open over the user's feed until they pressed back. `FollowingSteer` was not wrong — all 26 of its
tests passed, including the one asserting that a pending attempt yields `ClickFollowing` the moment
the menu appears. Two things outside it were:

- **The dropdown is a POPUP in its own window.** We searched `rootInActiveWindow` only, so the menu
  was invisible to us while plainly on screen. `rootInActiveWindow` is not "the screen"; it is one
  window, and which one it is during a popup is not ours to assume. The recorded dump of that exact
  moment contained the popup ALONE, with none of the activity's chrome — the evidence was sitting in
  the fixture the whole time and was read as "the menu classifies as UNKNOWN" instead of "these are
  two different windows".
- **The observation was gated behind a debounce LONGER than the timeout it was racing.** Pending
  attempts were resolved from the 2 s-debounced feature path against a 1.5 s timeout, so the one look
  the debounce permitted always arrived after the attempt had died. Unreachable by construction, on
  every device, always.

**The general trap: a unit test that calls the decision function directly cannot see the rate at
which production calls it.** Every test here supplied its own timestamps, so the 1.5 s-vs-2 s
relationship — two constants, two files, never named together — was invisible to all of them. When a
decision is time-bounded, something must pin the bound against the CADENCE of its real caller, and it
must read both numbers out of production rather than restating either.

Two corollaries worth keeping:

- **Giving up on an ACTION is not the same as giving up on its SIDE EFFECTS.** "Fail silently, never
  retry" was the right policy for the steer and the wrong one for the menu: we opened that dropdown,
  so we close it. A half-performed interaction inside someone else's app is worse than not acting.
  Any code that performs a multi-step interaction elsewhere owes an undo for the steps it completed.
- **Our own synthetic action comes back as a user event.** `performAction(ACTION_CLICK)` is reported
  as a real `TYPE_VIEW_CLICKED` carrying the host app's package, and the interaction counter dutifully
  counted the blocker's own taps as the user's. That is issue #28 from the other direction — there
  the phantom user was autoplaying video, here it is us. Anything that acts on another app must mark
  its own actions before performing them, and the suppression window must be consumed by ONE event,
  because over-suppressing silently under-counts the user and that is the worse failure.

## A signal's MEANING can be right while its VERDICT is premature (2026-09-25, issue #54)

The screen-off receiver carried its own justification in a comment: *"a screen-off is not a timer, it
is an observation that the user stopped using the phone, which is exactly the evidence the grant's
lifetime needs."* That sentence is persuasive, it is written at the call site, and it was wrong in one
word. A screen-off IS evidence the user stopped **touching** the phone. Android's display timeout runs
on input, not attention, and the Pixel default is 30 seconds, so a user reading a client chat inside a
blocked app produced the identical broadcast as a user putting the phone in a pocket. The model then
charged a full 60-second hold for the first one.

**The tell was in the model itself, not on the device.** `SCREEN_OFF` was the only one of three end
causes with no grace window, while the model already ruled that another app holding the foreground for
110 seconds was NOT a departure. A blank screen returning to the SAME app is strictly less of a
departure than that. An asymmetry like that is a question worth asking before a user has to report it:
**when one member of a small enumeration is handled differently from its siblings, either the reason
is written down or it is a bug.** Here the reason was written down and it was the bug.

Three things to carry forward:

- **A broadcast that two different user intentions both produce cannot be a verdict on its own.** A
  lock and a display timeout are the same `ACTION_SCREEN_OFF`; only the LENGTH of the absence separates
  them, so the length has to be what decides. The fix was not a new signal or a new list. It was
  routing an existing signal into the clock the model already had.
- **Fixing enforcement without fixing counting splits one definition into two.** The sitting's
  "departure" and `BlockLaunchGuard`'s arrival both meant *the user left*. Giving only the sitting a
  return window left the counter charging a second `wasBlocked` row for a screen that blanked while the
  user sat in front of an overlay they had not finished. That is #36's own invariant failing on #54's
  definition. **When you change what a word means, grep every consumer of that word.** The departure
  now hangs off `SittingEvent.Ended(cause = SCREEN_OFF)`, so there is one verdict and two readers of it.
- **Some bugs cannot be captured, and that has to be stated rather than skipped.** `docs/testing-strategy.md`
  rule (c) wants an `a11y-capture.sh` recording before the fix is designed. `ACTION_SCREEN_OFF` is a
  broadcast and never enters the accessibility stream, so no capture could ever have contained this
  bug. That is also exactly why every existing replay test stayed green through it. The honest move is
  to say why the capture is absent, not to produce one that proves nothing.

Also worth remembering from the device QA on this fix: **a device FAIL is not automatically a
regression.** The bench run failed a case (Home should revoke instantly) whose code path this change
does not touch, and the agent's proposed mechanism was contradicted by the source and by passing unit
tests. The cheap settling move is an A/B against the previous build, not a revert on the strength of a
plausible story.

## A crash in `onDestroy` is not a crash, it is a silent uninstall (2026-09-25, issue #57)

`stopForegroundTimeTicker` read `lateinit var foregroundClock`, which only `onServiceConnected`
assigns, and `onDestroy` called it unconditionally. A service created and destroyed without a
completed connect therefore threw out of `onDestroy` — and that is a special crash: Android wraps
it as *"Unable to stop service"*, files the component in `mCrashedServices`, and **never retries the
bind**. Every rule stopped enforcing, permanently, with nothing on screen to say so. It had been
shipping since at least 1.18.0.

- **Weight a crash by what it kills, not by how it looks.** An uninitialised-`lateinit` read is the
  kind of thing a reviewer waves through as a null-safety nit. In a teardown callback of the one
  component that enforces everything, it is the worst outcome this app has: total feature loss that
  looks like nothing at all. Ask "who never comes back if this throws" before deciding a throw is
  cheap.
- **Fixing the reported field would have shipped the same bug a second time.** There were two
  unguarded reads, and the second hid behind `if (activeWebSessionKey == null && !webClock.isRunning)`
  — which evaluates the clock precisely when the first half is TRUE. `&&` short-circuits in the
  unhelpful direction there. The issue predicted this ("a hand-list will rot; prefer a test that
  discovers them") and it was right: the guard is now an invariant that parses the file, computes
  `onDestroy`'s call closure and demands a guard for every `lateinit` it can reach. Write the rule
  over the CLASS, and it finds the instance you did not look for.
- **`dumpsys dropbox --print` is the device-free repro, and it is dated.** The traces carry the
  versionCode, so one read proved this was present on v53 as well as v54 and was NOT a #54
  regression. That is what kept it from being chased through unrelated code a second time; during
  #54's QA, before anyone looked at the dropbox, it presented as "the block screen just did not
  appear". Logcat had already rotated. Reach for the dropbox before theorising.
- **Teardown is the one place where catching everything is unambiguously correct.** Nothing after a
  teardown failure can observe it, so a throw there buys nothing and costs two things: the crashed
  state above, and every step BELOW the throw — here `serviceScope` was never cancelled and all
  three overlay managers kept a dead service as their context. `Throwable`, not `Exception`: a
  `NoSuchMethodError` from an API-level mistake leaves the service equally dead. Containment then
  makes ORDER the only remaining variable, so the two orderings that matter (instance cleared first,
  scope cancelled last) are pinned by test rather than by comment.
- **Check the alarm before assuming you need one.** The issue's second half asked whether anything
  notices a crashed service or whether it reads as healthy. It already notices, because
  `ProtectionStatus` reads the OS *bound* list and treats the settings string as intent only — the
  design the `mCrashedServices` retraction in `service-lifecycle-and-watchdog.md` produced. Verifying
  that cost one grep; building a second alarm would have cost a day.

## 2026-09-25, issue #64: a clock nothing could stop (hold re-prompts with the screen on)

The #35/#54 reporter came back the day after we shipped #54: the hold re-prompts minutes later with
the screen ON, while he is still inside the app, in three different apps. Not #54, and not a
duplicate of it.

- **A field named for a duration is a claim about what it MEASURES. Check that the thing which
  cancels it can actually arrive.** `SittingTracker.awaySinceMs` was documented as "how long the
  user has been away", but it was started by one foreign window event and cancellable only by
  another `AppWindow` for the sitting's own package. `AppWindow` means *a window transition
  happened*, not *the user is here* -- so for a user scrolling a feed, reading, or typing, **no
  cancelling event existed at all**. It was not a clock, it was a fuse: armed by any sub-flow, and
  defusable only by an in-app navigation inside the window. The class doc, the tests and the issue
  history all described the intended quantity; nothing checked that it was measurable. Every test
  fed it window events, which is the one input under which the name is true.
- **When one subsystem's answer contradicts another's about the SAME question, that is the bug,
  before any repro.** While the fuse burned, `InteractionCounter` was counting that user's taps and
  scrolls as *what he did inside app X* and feeding them to auto-kick. Two subsystems, one question
  (*is the user in app X?*), opposite answers. #54 was the same shape one cause over
  (enforcement forgiving a display timeout while counting still charged for it), and finding it
  cost a grep, not a device. **Ask what else in the app already knows the answer** -- here the
  evidence the sitting model needed was already flowing past it, on the same thread, in the same
  `when`.
- **Fix it with new EVIDENCE, not by flipping a documented judgement call.** The tempting fix was to
  require *proven* absence (`lastAwayEvidence - awaySince >= window`), which would have broken
  `returning after longer than the return window restarts the sitting` -- a test whose docstring
  says exactly why the ambiguity is resolved the way it is. A test that pins a deliberate choice is
  not in the way; it is telling you the change belongs somewhere else. Adding `onInteraction` left
  every existing assertion untouched.
- **Route new evidence through the EXISTING branch, not a rule of its own.** An interaction runs the
  same "the user came back" path a window event runs, so it cancels a short absence AND ends a long
  one. A second rule ("interactions only ever cancel") would have read as more cautious and would
  have opened a bypass: a stray event from a backgrounded app could have resurrected a grant. One
  fact, one branch, one direction of error.
- **A pure-model fix nobody calls is a silent no-op with a green suite**, and here the wiring was
  invisible by construction: a click classifies as `NotForeground`, so it reaches no branch that
  touches the sitting. What caught it in advance was an existing DISCOVERY test --
  `every sitting-model call from the service uses the monotonic clock` reads `PassthroughManager`'s
  signatures and fails if a clock-taking method has no call site. Written for #54's clock, it gated
  #64's wiring for free. Discovery tests keep paying; a hand-list pins yesterday's bug.
- **The reporter ruling a cause out is data, not noise.** "The screen does not turn off between
  these two holds" is what stopped this being filed as a #54 duplicate and closed. Quote a user
  verbatim in the issue: his numbered steps are the test plan, and "sometimes after a few minutes"
  is the detail that says *armed at an unpredictable moment*, which is what a one-shot timestamp
  with no expiry looks like from outside.
