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
