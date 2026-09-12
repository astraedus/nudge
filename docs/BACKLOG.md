# Nudge backlog

Known issues, in-progress work and future ideas. Moved out of `CLAUDE.md` so it is not loaded every session.
Entries marked `[x]` shipped, the version is noted so the history stays readable. Cross-check `CHANGELOG.md`.

## Known issues — surfaced incidentally during v1.9.0 device QA (pre-existing, NOT from the 1-min-pass feature; each needs its own investigation)
- [x] ~~**Genuine app-switch may not re-block when the interaction counter overlay is active**~~ (surfaced 2026-07-27 during #5 QA) — **RESOLVED in v1.9.4**: this was the same defect users reported as [#7](https://github.com/astraedus/nudge/issues/7). The counter overlay was a red herring; the real cause is that a re-entry delivering only `TYPE_WINDOW_CONTENT_CHANGED` never reached `evaluateForegroundPackage` for non-`SUPPORTED_PACKAGES`. Confirmed with real recents-overview taps (not `monkey`): 5 of 6 re-entries missed on the pre-fix build. See "Content-change app-switch fallback". Likely related to the existing "counter doesn't increment on YouTube swipes under a whole-app rule" item below.
- [ ] **Per-domain web time is not measured across a day — daily budgets and stats can't see the web** (design note, split out of the v1.15.2 web-enforcement fix; the ENFORCEMENT half shipped, this is the data half). Every screen-time number in Nudge comes from `UsageStatsManager`, which has no concept of a website: only the browser package emits `ACTIVITY_RESUMED`/`PAUSED`. Two consequences, both currently honest-but-absent rather than wrong:
  - **A daily budget on a web rule is only spent by APP time.** `EvaluateBlockUseCase.dailyUsageMs(trackingPackage)` reads the rule's app. The reverse direction works and should stay: once the app's budget is exhausted, `BlockEngine`'s `time_budget_exceeded` branch hard-blocks the website too. What is missing is web time *feeding* the budget. The rule editor now says this in the Daily Time Limit info rather than presenting a control that silently does nothing.
  - **Instagram-web never appears in stats/charts** — that time is attributed to Chrome, because that is genuinely what Android measured.
  - **Why it was NOT half-built in v1.15.2**: a daily total has to survive a service restart, so it needs persisted per-domain day records. An in-memory daily total would silently reset — a budget that stops blocking without saying so, the exact failure class issue #21 exists to prevent. What v1.15.2 DID ship is the *session*-scoped clock (`WebSessionUsageProvider` + `web:<domain>` keys), which needs no persistence because the app path's session clock is in-memory too, and which makes `autoKickAfterMinutes` + the auto-kick cooldown work on websites.
  - **Sketch if picked up**: a `web_usage(domain, dayStart, ms)` table (PK on both, DB migration 10→11), written from the existing 30s web tick and on session end, split at the day boundary with `TimeTracker.startOfDayDaysBefore` like every other day-scoped number. Then `dailyUsageMs` for a web rule becomes `appDaily + webDailyForItsDomains`, `showTimeRemaining` becomes expressible on web, and the stats layer gains a per-domain series. Decisions owed before writing any of it: (a) does the backup carry it (`docs/architecture/export-import.md` — history is exported, this is history), (b) retention, given `UsageRepository.cleanup` still has no call site (see below), (c) whether a domain appears in the charts as itself or is folded into its rule's app, which is a product call, not a technical one. **Do not build the write path without the read path** — a table nothing displays is the same silent-no-op defect from the other side.
- [x] ~~**Interaction counter doesn't increment on YouTube swipes under a whole-app rule**~~ (v1.9.2 QA) — **ROOT-CAUSED in v1.16.0, and half of it FIXED.** Original note kept below because the symptom was real and the guesses in it were wrong.
  - **Cause 1 (fixed):** the scroll path required the package to be in `InAppDetector.SUPPORTED_PACKAGES` **and** for detection to recognise a feature on that surface; the home feed usually resolves to no feature, so the scroll counted nothing, and `handleViewClicked` returned early for supported packages so taps counted nothing either. Detection held a veto over counting. It now supplies the LABEL only, and scrolls/taps count in every counter-enabled package — see `docs/architecture/accessibility-event-pipeline.md`.
  - **Cause 2 (NOT fixed, and now measured rather than suspected):** **YouTube Shorts emits no `TYPE_VIEW_SCROLLED` at all.** Screenshot-verified on the Pixel 3, 2026-09-11: five real full-screen Shorts flicks produced 66 accessibility events, every one a `TYPE_WINDOW_CONTENT_CHANGED`, zero scroll events. The home FEED does emit them (`vid=com.google.android.youtube:id/results`); the Shorts pager does not. So no scroll-derived counter can see Shorts consumption on this app version, and none could before either.
  - **What is owed:** a signal for paged video surfaces that emit only content changes. Do NOT reinstate the old content-change-per-second proxy — that is the bug issue #28 reports (it counts autoplaying video as user input and feeds auto-kick). Candidates worth capturing first: `TYPE_VIEW_CONTENT_DESCRIPTION_CHANGED` / the `contentChangeTypes` bitmask on the player node, or the video title node's text changing once per Short. Capture with `scripts/a11y-capture.sh` and write the failing replay test before writing any of it.
  - Original 2026-07-27 note, for the record: *"per-session count never moved across 10+ real taps/swipes (Shorts AND home feed), yet a daily total incremented once per app-open… so on YouTube 'after N interactions' effectively behaves as 'after N app-opens'. Suspect only the open/window event reaches `InteractionTracker` for this app shape."* The suspicion was wrong; the events arrive, the counter refused them.

- [ ] **The grant is three parallel nullable fields, and nothing structurally forces a fourth into the revoke path.** `PassthroughManager` opens `lastPackage` / `lastFeature` / `lastDomain` (plus `lastTime`) in `grant()` and closes them in `clear()`, by hand. That is exactly how the web axis came to survive Home before v1.15.2: a second piece of state meant a second thing to remember to clear, and the remembering is the only thing keeping them in step. The axes are already granted together and revoked together, so a `Grant` value type -- one nullable field holding package + feature + domain + earned-at, replaced wholesale or nulled -- would make the omission unrepresentable rather than merely currently-absent, and `clear()` would stop being a checklist. Low risk, touches `BlockOverlayActivity` and the three `isGranted`/`shouldSkip*` readers. Worth doing next time this file is open for another reason.

- [ ] **A grant survives an absence that happens entirely inside a service outage.** Since v1.16.0 a rebind calls `PassthroughManager.onObservationResumed()`, which discards the away clock and KEEPS the sitting -- deliberately, because revoking on every reconnect re-blocks users mid-session on a device that reconnects under memory pressure (issue #28 FAIL 1). The residual hole: if the user leaves the granted app, spends ten minutes elsewhere, returns, and the service only reconnects once they are back, nothing observed the absence and the grant survives. Cost is one skipped delay, in the failure direction this subsystem deliberately chooses ("miss a revoke rather than interrupt someone mid-use"), and the screen-off revoke does not cover it because that receiver is unregistered while the service is dead.
  **How to close it properly, when someone is in here anyway:** at reconnect, ask `UsageStatsManager` whether any OTHER package was foreground between `PassthroughManager.lastTime` and now. The app already holds `PACKAGE_USAGE_STATS` for the time-budget clock and already has `ScreenTimeProvider.getPerAppSessionStats` to read it, so this is a question the platform can answer exactly rather than a timer guessing at it -- the same shape as the `getTaskRootPackageName()` note above. Not done here because it needs its own device verification and this PR is already blocking a merge.

- [ ] **The passthrough return window is a timer standing in for a question the platform can answer outright.** `SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS` (2 minutes) decides whether another app in front is a sub-flow or a real switch. It is a judgement call, and it is wrong at both ends: a user who spends three minutes picking a photo loses a pass they earned, and a user who checks another app for ninety seconds keeps one they should not. `UsageEvents.Event.getTaskRootPackageName()` (API 29+) answers it outright -- a picker or share sheet launched from the granted app carries that app as its task root, a genuine switch does not -- which would demote the timer to a fallback for API 26-28 rather than the mechanism. Needs `PACKAGE_USAGE_STATS`, which Nudge already holds for the time-budget clock. Capture the two cases with `scripts/a11y-capture.sh` first and confirm the task root really does differ on the bench device before building anything.

- [ ] **A horizontal pager is unrecognisable on API 26-27 when it reports no scroll range.** `InteractionCounter.isHorizontalOnly` prefers `scrollDeltaX/Y` (API 28+) and falls back to `maxScrollX/maxScrollY`, which is populated at every API level. When a view reports neither, orientation is genuinely unknowable, the event reaches the index path, and a `ViewPager`'s `currentItemIndex` turns each sideways swipe into one consumed item -- so ~30 tab switches could auto-kick the user. Bounded and rare (tab switches are dwarfed by feed scrolling), and the alternative -- treating "unreadable" as "horizontal" -- would silently refuse to count real feeds, which is worse. Pinned by `InteractionCounterTest."with neither deltas nor a scroll range orientation is unknowable and the event counts"`. If it ever matters, the fix is a bounded `getSource()` read for `isScrollable` plus the node's bounds aspect, not another heuristic over the same fields.

- [ ] **`dailyTotal` still mixes units across a mode promotion.** `InteractionTracker.recordInteractions` resets the SESSION count when a tap-counting session is promoted to `CountMode.ITEMS` (so the session number means one thing), but the taps recorded before the promotion stay in the daily total, which then reads as "taps + items". Bounded to the handful of taps before the first scroll of a sitting, and it never reaches enforcement -- auto-kick reads `sessionCount`, not the daily -- so this is a display imprecision, not a bypass. Fixing it properly means making the daily total mode-scoped per package, which is a bigger change than it is worth until someone notices the number.

- [ ] **The counter counts ITEMS, not gestures, and on a free-scrolling feed those differ** (noted with the v1.16.0 fix, deliberate, flagged in case it reads as a bug). One swipe on the YouTube home feed moves the adapter two positions, so it counts 2; on a paged surface (reels, shorts) one swipe is one item and they coincide. "Items consumed" is the honest unit for an awareness counter and for an `autoKickAfter` threshold, but the rule editor's copy still says "scrolls/taps". Either the copy should say items, or the threshold's felt magnitude should be re-checked against real feeds. Product call, not a defect.
- [ ] **Block overlay briefly re-renders after tapping the daily pass** (v1.9.2 QA, cosmetic, pre-existing — identical under the old per-app design) — grant/lockout/expiry all correct; the overlay flashes once before the app becomes usable. Likely the next foreground event re-launching the overlay before `isPassActive` is consulted. Cosmetic polish only.
- [ ] **Accessibility service instability under sustained load** — during a ~90min automated torture test (many app launches, force-stops, PIP overlays) the a11y service churned/reconnected repeatedly and was found OS-unregistered once. Could NOT be reproduced in a normal cycle (process held 63min uptime, single PID, zero idle reconnects), so likely OS memory-pressure kill on the 3GB Pixel 3 rather than a code defect — but worth a foreground-service/`onUnbind` hardening pass + a repro on a clean device. Root-cause before assuming it's environmental. **Partly addressed**: the app can now *notice* this. `ProtectionWatchdogWorker` checks every 15 minutes that our component is still in `ENABLED_ACCESSIBILITY_SERVICES` and that the foreground service is alive, restarts a dead FGS, and pushes a notification when protection has genuinely stopped (`docs/architecture/service-lifecycle-and-watchdog.md`). What is still owed here is the *prevention* half — `onUnbind` handling and the `CoroutineExceptionHandler` on `NudgeAccessibilityService.serviceScope` (audit F7: one unhandled Room/binder exception in the app's highest-traffic scope kills the process and the a11y service with it).
- [ ] **Browsers bypass whole-app block rules** — a DELAY/HARD_BLOCK rule on Chrome never fires via the whole-app pipeline because browser packages route straight to per-URL web-domain evaluation. This is *by design* per the web-domain architecture (whole-app blocking a browser would nuke all browsing), but the UX is surprising — a rule silently does nothing unless "Block on web too" + a domain rule exist. Consider surfacing this in the rule editor when the target is a known browser.
- [ ] **System permission dialog can render over `BlockOverlayActivity`** — e.g. Camera's location-permission prompt kept re-appearing on top of the block overlay, leaving the blocked app's UI visible underneath even though the decision was correctly `HARD_BLOCK`. Overlay z-order / re-assert on `TYPE_WINDOW_STATE_CHANGED` for the permission-controller package.
- [x] ~~**"I changed my mind" can leave the user inside the blocked app**~~ — **RESOLVED in v1.13.0** (2026-08-20). `navigateHome()` did `startActivity(HOME)` then `finish()`; this activity is singleInstance in its own task with an empty taskAffinity, so finishing pops back to the task underneath — the blocked app — and whichever the system reached first decided where the user landed. Now prefers `GLOBAL_ACTION_HOME`. See "The walk-away path".
- [ ] **Two of the three overlay launch paths write no "overlay shown" `UsageEvent`** (found 2026-08-20 while root-causing the walk-away report; DB-verified on device). Only `handleDecision` (the rule-block path) logs the shown row — the **auto-kick cooldown** DELAY overlay (`NudgeAccessibilityService.evaluateForegroundPackage`) and the **daily-limit** HARD_BLOCK overlay (`TimeRemainingHandler`) log nothing. So those blocks are invisible to the "Blocked" tile and the insight pages, and a walk-away from one lands as a LONE `userChangedMind=1` row with no matching shown row. `InsightsCalculator` survives it by construction (`attempts = max(shown, walkAways)` is exactly this guard), but the tiles under-report real blocks. Decide deliberately whether those paths should log — it changes historical stat semantics.
- [ ] **Export/Import is hard to discover** (v1.12.0 QA, 2026-08-20) — backup/restore lives only in the Active Rules screen's overflow menu, and Active Rules itself is reachable only by tapping the "Active Apps" stat card on Home. A trained QA agent doing an exhaustive search concluded the feature didn't exist (checked Quick Actions, Manage Apps, Settings, rule editor). Cheap fixes: mirror Export/Import as Settings items, and/or add the overflow menu to Manage Apps too. Data-portability is exactly what users reach for before wiping/switching devices — it shouldn't be findable only via a stat card.
- [ ] **Export cannot save the backup to the device — only "send" it somewhere** (found 2026-08-20 while device-verifying history export). Export fires `ACTION_SEND`, and "Save to Files"/"Save to device" is NOT an `ACTION_SEND` target on Android: it only appears for `ACTION_CREATE_DOCUMENT`. Enumerated on the Pixel 3, the entire share sheet was Drive, Gmail, KDE Connect, Telegram, Bitwarden, Discord — every one of them sends the file to a *cloud or another device*. So a user of a zero-internet-permission privacy app has no way to put their own backup in their own Downloads folder, and the file the app writes lives in `cacheDir` where the system may evict it. Import already uses `ACTION_OPEN_DOCUMENT`, so the symmetric fix is small: `ACTION_CREATE_DOCUMENT` ("Save backup") writing through the returned `Uri`, keeping Share as a second option. This matters more now that the file carries the user's whole block history, not just rules.
- [ ] **Strict Mode does not gate the Content Filter toggles in Settings** (found while building the settings export, 2026-08-27; pre-existing). `SettingsWeakening.LockedToggle` covers only `STRICT_MODE` and `EMERGENCY_PASS`, so a user under Strict Mode can walk into Settings and switch "Block restricted websites" (or its strict-keyword sub-toggle) straight off with no challenge — a protection-weakening flip the lock is supposed to bite on. **Importing** those same settings IS gated (`ImportedSettingsWeakening` treats the content filter as an axis), so the import path is deliberately STRICTER than the screen; being stricter is never a vulnerability, but it is an inconsistency, and the screen is the easier of the two to reach. The fix is small — two more `LockedToggle` members and the same `requiresUnlock` call the escape-hatch toggle already makes — but it changes existing UX (turning the filter off would start costing a challenge), so it is a product call rather than a bug fix. Decide deliberately.
- [x] ~~**Settings screen shows stale Accessibility Service state**~~ (v1.12.0 QA) — **RESOLVED**: the three permission rows read `remember { mutableStateOf(...) }`, i.e. once at first composition, so the screen could show a green tick over a dead service. Now a `ContentObserver` on `ENABLED_ACCESSIBILITY_SERVICES` plus an `ON_RESUME` recheck, both pinned by `LivePermissionStateContractTest`. See `docs/architecture/service-lifecycle-and-watchdog.md`. **CORRECTED 2026-09-06:** the original title and note blamed "after an in-place APK update Android disables the a11y service", that is FALSE. AOSP `onPackageUpdateFinished()` clears the crashed set and rebinds, and never touches `mEnabledServices` (verified across Android 13/14/15/master). QA had misread our OWN stale UI as evidence of the OS acting, and that wrong note went on to mislead two later investigations. The real killer: the a11y binding uses `BIND_FOREGROUND_SERVICE_WHILE_AWAKE`, so oom_adj protection lapses with the screen off; once LMK reaps the process, `binderDied()` adds it to `mCrashedServices` and AOSP never rebinds, while the component stays in `ENABLED_ACCESSIBILITY_SERVICES`, so every settings-string check reports "enabled" over a dead service. Liveness must come from `getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`.
- [ ] **Content Filter over-promises vs. what a URL-bar architecture can deliver** (surfaced 2026-07-07, Anti tested it and it failed on Google Images). The accessibility URL-bar filter can catch known porn *domains* + keyword-in-URL navigations, but it structurally CANNOT block image-search results: (a) it only sees the URL, never the images on the page; the explicit thumbnails come from Google's own CDN (encrypted-tbn*.gstatic.com) which can't be blocked without blocking Google; (b) Firefox drops `q=` from the URL bar on the Images tab (`udm=2`), so the query is invisible; (c) keyword tuning is whack-a-mole and can't cover arbitrary explicit phrasings. The ONLY robust mechanism is DNS-level SafeSearch enforcement — **device-wide Private DNS → `family-filter-dns.cleanbrowsing.org`** (CleanBrowsing Family) or `family.adguard-dns.com` (AdGuard Family), both force-lock Google/Bing/YouTube SafeSearch AND NXDOMAIN porn domains, survive incognito, work in every browser. **Device-verified on the Pixel 3 2026-07-07** (pornhub.com → ERR_NAME_NOT_RESOLVED; Google Images "porn" → "SafeSearch is locked by your network or device", no explicit thumbnails). Cheap honest fix so users don't hit the wall and 1-star it: in the Content Filter settings, add a one-tap "Block adult content device-wide" that deep-links to Private DNS + pre-fills the hostname (`Settings.ACTION_PRIVATE_DNS_SETTINGS` where available), framed honestly as device-level; keep the in-app URL-bar filter as a light domain/keyword catch, don't imply it does image search. Strict Mode could additionally guard the Private DNS settings screen from being toggled off (same escape-route-guard pattern already used for the a11y settings page). Decision on whether to build this (wizard vs. bundled VpnService filter vs. leave as-is) left to Anti — he leaned "that's as fair as Nudge can go" / handle it via DNS himself.

## Known-unverified device paths (2026-09-07 post-merge QA)

Both are documented limitations of what a bench can reach, not open defects. Each says what IS
pinned, so nobody re-investigates from scratch.

- **Auto-kick cooldown on a deleted rule (`CooldownGate`) has no end-to-end device proof.** QA could
  not drive the device into the state: a passthrough grant masked all further evaluation
  (`skip evaluation ... reason=passthrough`), so the cooldown never armed and `CooldownGate.isStale`
  was never reached. The LOGIC is pinned by `CooldownGateTest` plus the contract assertion that both
  call sites (app path and web path) are gated; what is unverified is only the on-device path. To
  retry: needs a rule shape that arms a cooldown without first triggering a whole-app block, since
  the block's own passthrough grant is what suppresses the follow-up evaluation.
- **`ACCESSIBILITY_CRASHED` has never been observed firing on a device.** On an idle Pixel 3 the
  accessibility service rebinds 150ms-3s after `am crash` and `Crashed services` empties with it,
  which is faster than an `am broadcast` round trip, so the debug trigger cannot observe
  `connected=false` at the moment it evaluates (8 attempts, several strategies, including a 50ms
  on-device poll). The state itself is real and reproducible via `dumpsys accessibility`; the
  posting pipeline is proven by the sibling `ACCESSIBILITY_DISABLED` and `MONITOR_SERVICE_DEAD`
  faults firing through the identical `notify()` body. What is unverified is one `when` branch
  selecting one string pair, and `ProtectionAlertCopyTest` pins that mapping over the whole
  `ProtectionFault` enum. The field cause is a low-memory kill with the screen off, which the bench
  cannot simulate; `bmgr` is no longer an option since `allowBackup="false"` excludes the package.

## Service-resilience audit 2026-09-06 — remaining lanes (NOT built with the watchdog)

Full audit with `file:line` evidence and a fix order lives outside this repo:
`~/ops/routes/nudge/research/service-resilience-audit-2026-09-06.md`, plus the OEM research at
`~/ops/routes/nudge/research/oem-background-kill-2026-09-06.md`. The lifecycle + watchdog lane
(audit F1/F2, and F9's manifest property) shipped — see
`docs/architecture/service-lifecycle-and-watchdog.md`. What is still open, in the audit's order:

- [ ] **F7 — no `CoroutineExceptionHandler` on `NudgeAccessibilityService.serviceScope`** (`:67`). Every block evaluation, `UsageEvent` write and DataStore collect runs there. `SupervisorJob` stops sibling cancellation; it does NOT stop an unhandled exception reaching the thread's default handler, which kills the process and the a11y service with it. `RecordWalkAwayUseCase.kt:50` already documents this exact incident and has the handler; the app's highest-traffic scope does not. **Trivial fix, copy that pattern.**
- [ ] **F4 — onboarding can be completed with zero permissions granted.** `OnboardingScreen`'s Next/Get Started gate only on the pager page; the permission buttons fire Intents with no `registerForActivityResult` and no re-check. A user can swipe past everything and land on a working-looking home screen with an app that cannot block. (The watchdog now catches this after the fact; gating onboarding catches it before.)
- [ ] **F3 — every block is a background `startActivity`, and the OS drops those silently.** Our only documented exemption is `SYSTEM_ALERT_WINDOW`, which F4 lets the user skip. Worse, `markOverlayActive()` and the `wasBlocked = true` `UsageEvent` are both written BEFORE the launch, so a dropped launch leaves a stale overlay flag and records a block the user never saw. Detect cheaply (`Settings.canDrawOverlays` before every block); hardening means a `TYPE_APPLICATION_OVERLAY` fallback, for which the `CounterOverlayManager` machinery already exists.
- [x] ~~**F5 — a completed delay's passthrough survives screen-off.**~~ **RESOLVED in v1.16.0**, as part of the issue #28 sitting model. A `SittingTracker` now owns "has the user left", and a screen-off ends a sitting (`SittingEndCause.SCREEN_OFF`) exactly as Home does — revoking the grant, leaving session counts and the cooldown alone. Registered at runtime because `ACTION_SCREEN_OFF` is a protected broadcast; a failed registration degrades to the old behaviour, never to a false revoke. As the audit required, the fix is on screen-off and not on a timer, so #5 is not reintroduced: the GRANT still has no expiry, the SITTING does. See `docs/architecture/accessibility-event-pipeline.md`.
- [ ] **F6 — the `isOverlayActive` gate can swallow a content-change-only re-entry**, defeating the issue-#7 fallback. `isOverlayBypassedByForeground` is hardcoded to `TYPE_WINDOW_STATE_CHANGED`, so a re-entry that delivers only `TYPE_WINDOW_CONTENT_CHANGED` under a stale flag takes the `else` branch and returns. `ContentChangeAppSwitchTest` and `PassthroughTest` each pin their own pure function in isolation; nothing tests that the second is ever REACHED when the first's precondition holds. Owed a dispatch-ordering contract test, not another unit test of a pure function. **Partly addressed in v1.16.0**: `EventDispatchOrderContractTest` is that dispatch-ordering test and it now exists, and the gate reads the single classified `ForegroundSignal` instead of re-deriving package sets, so it can no longer disagree with the rest of the file about what a window is. The `TYPE_WINDOW_STATE_CHANGED` restriction itself is UNCHANGED and still open: lifting it means hoisting the issue-#7 active-window verification (a binder read) above this gate, which changes the cost profile of the hottest path while an overlay is up — a deliberate decision, not an oversight, and out of scope for #28.
- [ ] **F8 — we cannot observe any of this and neither can the user.** No crash reporting, no telemetry, no `INTERNET` permission (deliberately). `NudgeLogger` writes to Logcat only, gated off in release builds behind seven taps on the version number. A persisted local event log (service connected/disconnected, block launched, block suppressed) plus an in-app Diagnostics screen with a share button needs no new permission and converts every future report from anecdote into evidence. **Half-addressed on the DEVELOPER side in v1.16.0**: `AccessibilityEventTrace` + `scripts/a11y-capture.sh` make the raw event stream recordable and replayable as a test fixture, which is what finally root-caused #28 and the four-version-old YouTube counter mystery. That is a bench tool and needs adb. What F8 still asks for — something a USER can send us from their own device, with no cable — is untouched, and the trace's JSONL format is the obvious thing for it to emit.
- [ ] **F10 — OEM/battery handling.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (real Play-policy risk — this app has been rejected once on the a11y gate — and issue #23 is direct evidence it would not have been sufficient anyway), manufacturer-aware onboarding, and autostart deep links (`com.miui.securitycenter`, Samsung "never sleeping apps", …). Exact intents and the fallback chain are in the OEM research §3.1. Note the only OEM strings in the codebase today run the *opposite* way: `StrictModeEscapeGuard` knows every OEM security centre and uses that only to guard them, never to walk the user in to whitelist us.

### v1.2 in progress
- [x] Time remaining overlay (code-complete, verified on device)
- [x] Auto-kick cooldown (code-complete, verified on device)
- [x] Rule name on block overlays (code-complete, verified on device)
- [x] Export/Import rules — device-QA'd on the shipped release build in v1.13.0/v1.14.0; the format has since grown history (v1.14.0) and settings (v1.15.0). See `docs/architecture/export-import.md`.
- [x] Enhanced stats visualizations — shipped and device-QA'd across v1.13.0 (insight pages) and v1.15.0 (day selection, one screen-time source, home dashboard charts). See `docs/architecture/stats-and-charts.md`.
- [x] Dynamic version display from BuildConfig
- [x] Tag-triggered GitHub Actions release pipeline (`.github/workflows/release.yml`)
- [x] Instagram home feed detection — **superseded in v1.11.0**, which detects Instagram's reel player directly rather than inferring it from which bottom-nav tab is selected, so the blocked `selected`-state tree-walk described below is no longer the path. It also fixed the real user-facing gap (a reel opened from a DM). Original note, kept for context: code written but AccessibilityService API doesn't expose child node `selected` state through `findAccessibilityNodeInfosByText/ViewId`. Needs tree-walk approach: traverse from `rootInActiveWindow`, find ImageView nodes with `selected=true` in bottom nav, match to parent tab. See InAppDetector.kt.
- [ ] On-device QA for all v1.2 features — largely absorbed by the per-release device-QA gate (see the post-feature checklist in `CLAUDE.md`); no single outstanding sweep, but the two items below are still owed.
- [ ] YouTube Shorts verification on device

### v1.3+
- [ ] **QR code unlock** -- physical friction for bypassing blocks. User generates a QR code in settings (random secret encoded via ZXing), prints/places it somewhere inconvenient. Per-rule toggle `requireQrUnlock`. Block overlay gets "Scan QR to unlock" button that opens camera (ML Kit barcode scanner), verifies against stored secret, grants passthrough. Adds camera permission (only one we'd need beyond accessibility). Twist: multiple QR codes with different unlock durations (e.g. "bedroom QR" = 10min, "office QR" = 1hr). Could also give QR to a friend for accountability. Low implementation complexity, high user-perceived value.
- [ ] **Advanced data visualization** -- expand beyond current charts: per-app weekly breakdown, comparison vs previous week, export charts as image for sharing/accountability. **Partially shipped**: per-app weekly breakdown landed with App Detail's bars (v1.15.0) and the insight pages (v1.13.0) cover week-over-week trend; comparison-vs-previous-week and chart image export are still open.
- [ ] Discord in-app detection: count server/channel switches as "taps" for counter + auto-kick. Discord uses React Native so TYPE_VIEW_CLICKED doesn't fire. Would need to detect server/channel navigation via accessibility tree changes. Low priority.
- [ ] NFC tag unlock -- same concept as QR but tap phone to NFC tag. No extra permissions needed (hardware feature). User writes unlock token to a cheap NFC tag ($1), places it somewhere. Lower priority than QR since fewer people have NFC tags lying around.
- [x] ~~Widgets (home screen quick stats, toggle rules)~~ — **SHIPPED in v1.17.0**: three Jetpack Glance widgets (Today at a glance, Blocked most this week, Protection toggle). The twelve ideas that were considered, and the reasoning behind each cut, are in `docs/architecture/widgets.md`; the ones worth revisiting are listed under "Widget ideas not built" below.
- [ ] Contextual triggers (location-based, time-of-day auto-enable)
- [x] Release signing key (v1.3.2 -- PKCS12 keystore, CI via GitHub secrets)

## API-level crashes on Android 8-9 — FIXED in v1.17.0, with the gate that lets them ship still worth reading

Found by running `./gradlew :app:lintDebug` during the v1.17.0 widget work (the widgets themselves were
clean). **`NewApi`, error severity, four occurrences, all pre-existing.** Nudge declares `minSdk = 26`, so
it installs on Android 8.0/8.1/9, where calling a method the platform class does not have is a
`NoSuchMethodError` at the call site — not a no-op, not a wrong answer, a crash.

| Site | Method | Added in | Consequence on those devices | Status |
|---|---|---|---|---|
| `service/AccessibilityEventRecordFactory.kt` | `AccessibilityEvent.getScrollDeltaX/Y()` | **28** | `toRecord()` runs for **every** accessibility event, so the service died on the first one. Blocking never worked at all. | Fixed on the #28 branch (`31282b8`) |
| `data/repository/ScreenTimeProvider.kt` | `AppOpsManager.unsafeCheckOpNoThrow()` | **29** | the Home dashboard's screen-time read threw | Fixed v1.17.0 |
| `ui/screens/settings/SettingsScreen.kt` | same, a byte-identical copy | **29** | Settings' usage-access row threw | Fixed v1.17.0 |

- **The AppOps pair are now one `util/UsageAccess.kt`** behind one `SDK_INT >= Q` check, falling back to
  `checkOpNoThrow` (deprecated, but present since API 19, identical semantics). The duplication is what
  made it two bugs instead of one — that is the reusable lesson, not the API number.
- **Why nobody noticed for so long**: CI ran `test`, `assembleRelease` and `bundleRelease` — **not `lint`**.
  `NewApi` is precisely the defect class no JVM test can see (no Android runtime) and no device we own can
  reproduce (the bench Pixel 3 is API 31), so the one check that catches it was the one not in the gate.
  **`./gradlew lintDebug` is now a CI step** with `abortOnError = true`, and it was mutation-checked:
  deleting the guard fails the build with the exact `NewApi` error. See `docs/TESTING.md`.
- **[ ] Still owed — nothing has ever RUN this app on an API 26-27 device.** Lint proves we do not call a
  missing method; it cannot prove the app is usable on Android 8. Worth one emulator pass
  (`sdkmanager "system-images;android-26;google_apis;x86"`) to find out what else is broken down there, or
  a deliberate decision to raise `minSdk` and stop claiming support we have never verified.

## [ ] The widget refresh debounce is leading-edge only, so a burst drops its final state (found 2026-09-12)

`WidgetRefreshDebouncer.tryAcquire` runs the first call in a window and returns `false` for the rest,
**scheduling nothing**. Correct for `UsageRepository.logEvent` (rate-limit a hot path; another event will push
again soon), wrong for a state change, where the last write is the one that matters.

Observed once in device QA of v1.17.0: immediately after unlocking Strict Mode the Protection widget rendered
a state belonging to neither mode - "Not blocking" in red over the "Turn off in app" locked hint - and held it
for roughly a minute. The unlock path writes several preferences in quick succession, so the leading push ran
against a mid-burst snapshot and every later push inside the 10 s window was discarded. `ProtectionWatchdogWorker`
is on a 15-minute period, so it was not what eventually corrected the widget; some unrelated later push was.

- **Severity: low, but not zero.** It self-corrects, and it fails toward a false alarm rather than a falsely
  healthy widget. The reason it still matters is that `WidgetSnapshotMapper.protection` goes out of its way to
  suppress "degraded" over a deliberately switched-off Nudge, specifically so the user is never trained to
  ignore the one message that means the OS killed the service. A spurious red dot spends that credibility.
- **Fix shape**: on a rejected acquire, schedule ONE trailing run at `last + windowMs`, single-flight, so a
  burst of N writes yields one leading and one trailing update rather than N or one. `WidgetRefreshDebouncer`
  is pure and already JVM-tested, so the rule is cheap to test; the scheduling lives in `NudgeWidgetUpdater`,
  which is not JVM-testable and is why this wants a device pass rather than a quick patch.
- **Not bundled into v1.17.0 deliberately**: it modifies the updater reached from the accessibility hot path,
  and the verification it needs (a burst produces exactly two updates, not a storm) is a device cycle this
  change did not have left. Everything else in that release was device-verified.
- **How to reproduce**: place the Protection widget, turn Strict Mode on, then unlock it, and watch the widget
  across the following minute. A distinguishing check for anyone picking this up - log each `updateAll` and
  confirm whether the burst produced exactly one push, which is the prediction above, or whether the stale
  frame came from somewhere else entirely.

## [ ] Widget deep links are hand-rolled; Navigation-Compose already does this (noted 2026-09-12)

`WidgetDeepLink` + `EXTRA_ROUTE` + `MainActivity.onNewIntent` + a `LaunchedEffect` in the nav graph is a
hand-built version of what Navigation-Compose provides as `navDeepLink` / `NavController.handleDeepLink`,
which additionally survives **process-death restoration** - ours does not, because the route lives in a
`mutableStateOf` plus an Intent extra we strip on consumption.

The interim fix shipped in v1.17.0: consuming a deep link now removes the extra from the Intent, because
`onCreate` re-reads it on every creation and a configuration change recreates the Activity with the same
Intent - so a rotation used to throw the user back to the widget's target, repeatedly, for the life of the
task. `WidgetObservationContractTest` pins that every extra `routeFrom` reads is also cleared on consumption,
discovered from the reader so a third mechanism cannot be added with no matching removal.

Worth replacing wholesale next time this area is open: register the routes as `navDeepLink`s and let the
library own restoration. Not urgent - the shipped behaviour is correct for every path a user can currently
take - and not free, since `EXTRA_OPEN_SETTINGS` has `PendingIntent`s already sitting inside protection
alerts on real phones and has to keep working.

## [ ] Three naming/duplication follow-ups from the v1.17.0 review round (2026-09-12)

Each found while fixing something else, each deliberately left alone because the fix is wider than the
change that surfaced it.

- **`rememberAppIcon(icon, sizeDp)` is owed, and it is a visual change, not a cleanup.** The
  drawable-to-bitmap conversion is hand-rolled at five composable sites, and **every one passes a literal
  pixel size that does not match its own dp box**: `AppListItem` 48px into 40dp, `WillpowerScreen` 32px/32dp,
  `InterventionsScreen` 64px/32dp, `ActiveRulesScreen` 48px/40dp. A shared helper rasterising at real device
  density would make all four sharper (on a Pixel 3, 40dp is 110px against today's 48px) and cost roughly 5x
  the bitmap bytes per icon. That is probably an improvement and it changes rendering on four screens, so it
  wants its own device-QA'd task. `WidgetReads` is NOT in scope - Glance needs a raw `Bitmap`, which is a
  different thing.
- **`widget_top_blocked_title` / `_open` / `_range` are now read from a non-widget screen.** The dashboard
  card reuses them so the card and the widget cannot drift apart in wording, which is right, but the prefix
  now under-describes them. Rename to `top_blocked_*` next time `ui/widget/` is open. Purely cosmetic; the
  reuse is the part that matters.
- **"Last 7 days" has a third definition.** `StatsDateLabels.range` produces the phrase in Kotlin, alongside
  the `widget_top_blocked_range` string resource. Not a live bug - they agree today - but it is the same
  shape as every "two spellings of one fact" defect in `docs/architecture/stats-and-charts.md`, and the
  Kotlin one is the odd man out because it is the only one a translator cannot reach.
- **Contract-test scaffolding is copy-pasted across three files.** `source()` / `mainSources()` now exists in
  `HomeTileAffordanceContractTest`, `InterventionsDelegationContractTest`, `WidgetObservationContractTest`,
  `WidgetStrictModeContractTest` and `ProportionalBarTest`, each with its own comment-stripping regex. They
  agree today. One shared test helper would be better, and would also stop the next author re-deriving the
  two parsing traps that cost a cycle each this round.

## [ ] OPEN: `updateAll` can return without `provideGlance` running, so a widget keeps a stale frame (2026-09-12)

Reproduced twice on the Pixel 3, root cause NOT found, **not a regression** - the same user-visible failure
exists before the v1.17.0 refresh rewrite, for a different reason (there the refresh was dropped outright by
a leading-edge debounce; now it is dispatched and the render does not happen). Written up in full so the next
person starts where this stopped.

**Symptom.** Toggle the master switch within ~10 s of a block event, with the Protection widget on the
launcher: the widget keeps showing the pre-toggle state. Still wrong at 63 s. An isolated toggle works, and
the event-driven Today / Top-blocked path works from the launcher with the app never opened.

**The log is the finding** (`adb logcat -s NudgeWidgetUpdater:V`):

```
11:07:37.669  refreshing widgets (events)
11:07:38.136  protection read: enabled=true degraded=false strict=false
11:07:42.212  refreshing widgets (protection)     <- the OFF toggle. NO read follows.
11:07:47.768  refreshing widgets (events)         <- NO read follows.
11:09:20.816  refreshing widgets (protection)
11:09:21.259  protection read: enabled=true ...   <- this one DID read
```

A refresh is dispatched and `updateAll` is called, but `provideGlance` frequently never runs.

**Ruled out, each with evidence rather than argument:**

| Hypothesis | Evidence against |
|---|---|
| Process frozen or cached while backgrounded | `dumpsys`: `isFrozen=false`, `cached=false`, `curProcState=FOREGROUND_SERVICE`, `oom adj=100` |
| The refresh is never requested | fires 138 ms after the toggle tap |
| A stale preference read | every read that DOES happen reports correct values; the failure is an ABSENT read |
| An exception being swallowed | `updateAll` is in `runCatching` with a `Log.w` on failure; no failure line appears |
| A missed tap | true app state confirmed `checked="false"` by UI dump each time |

**Where to pick it up.** The remaining unknown is inside Glance's own update machinery, so it wants the
1.2.0 source, not another device round. Concretely: log at the very top of `provideGlance`, before the read,
to separate "never invoked" from "invoked but did not reach the read"; check what
`GlanceAppWidgetManager.getGlanceIds` returns for the receiver at that moment; and check whether a session
already in flight for the same id causes a later `updateAll` to be dropped rather than queued. The
`protection read:` log line exists for exactly this and should stay until the bug is closed.

**Mitigations in place, neither of which is a fix:**
- `pushAll` is serialised behind a `Mutex` so two `updateAll` calls for one widget id cannot overlap. That is
  defensible on its own merits, but **its effect on this bug was not demonstrated** and it should not be
  described as the fix.
- The Protection widget now carries the 30-minute platform backstop (`updatePeriodMillis`) instead of `0`.
  The original `0` assumed the push always lands; it does not, and with no timer there was no second chance.
  This bounds the staleness rather than removing it - wrong for at most half an hour instead of wrong
  indefinitely - which for the one widget whose job is announcing that blocking died is worth a redraw every
  30 minutes.

**User-visible impact, stated plainly:** a protection-state change made within ~10 s of a block event may not
appear on the widget for up to 30 minutes. It never shows the *wrong direction* on its own; it shows the
*previous* state. The in-app state is always correct, and Strict Mode enforcement is unaffected.

## Widget ideas not built (considered and cut for v1.17.0 — reasoning in `docs/architecture/widgets.md`)

Three widgets shipped. These were the rest of the brainstorm, kept because the reasoning for the cut is
also the reasoning for what would have to be true to pick one up.

- [ ] **Single-app "time left today"** — the most-requested shape of budget widget. Needs a configuration Activity so the user can pick which app, which is real work (`AppWidgetProviderInfo.configure`, a config flow, per-widget-id state) for low first-release value. Pick up when someone asks for it by name.
- [ ] **Streak counter** — cheap: `StatsCalculator.calculateStreak` already exists. The honest version is a footer line on "Today at a glance" rather than a fourth widget competing for a home-screen cell.
- [ ] **Walk-away rate ring** — needs a runtime-generated `Bitmap`, because **Glance has no `Canvas`**. "Today at a glance" already carries the number; the ring is presentation, not information.
- [ ] **Weekly screen-time bar chart** — same constraint, same answer: feasible via `Image(ImageProvider(bitmap))` with a bitmap drawn at refresh time. Worth doing only once some widget genuinely needs the bitmap machinery, then both this and the ring get it at once.
- [ ] **Hourly heatmap strip** — dense bitmap, illegible at 4x1. Would need a 4x2 minimum and would still read worse than the in-app heatmap.
- [ ] **"Next scheduled block starts in…"** — needs schedule evaluation off the accessibility hot path (a pure "when does the next window open" function over `BlockRule` schedules). That function would be useful in the app too, which is the argument for building it eventually.
- [ ] **Quick "start a focus block now"** — rejected for now because there is no such domain concept: Nudge blocks per-rule, not per-session. This is a product feature that would then get a widget, not a widget.
- [ ] ~~Grayscale toggle shortcut~~ — **rejected, not deferred.** Grayscale needs `WRITE_SECURE_SETTINGS`, which is ADB-granted and absent on almost every install. A home-screen control that silently does nothing is worse than no control.
- [ ] ~~Emergency-pass "burn one now"~~ — **rejected, not deferred.** A one-tap bypass on the home screen is a hole straight through the product's purpose, and it is the same class of mistake the Protection widget's Strict Mode branch exists to prevent.

## Noted 2026-08-31 (v1.15.1 QA): stay-awake devices legitimately show near-24h days
Device QA of the screentime fix on the Pixel 3 (which has "stay awake while charging" on and lives on AC) showed ~17h "today" and several ~24h historical days. This is CORRECT: dumpsys usagestats confirmed the app genuinely was foreground with the screen on the whole time (no screen-off events ever fire on that device). Digital Wellbeing counts the same way. Do NOT "fix" this by distrusting long inherited sessions, that would under-count real long sessions (overnight video, navigation, charging docks). If it ever bothers users, the only defensible improvement is annotating, not clamping.
