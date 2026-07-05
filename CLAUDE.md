# Nudge — Open-Source Android App Blocker

Privacy-first app blocker with delay-to-open (breathing exercises before opening distracting apps), per-app daily time budgets, app groups, schedule-based rules, in-app feature blocking (YouTube Shorts, Instagram Reels, TikTok), and grayscale mode. Zero internet permission. All data local.

- GitHub: https://github.com/astraedus/nudge
- F-Droid MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38398
- v1.6.0 (current)
- See CHANGELOG.md for release history

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug                    # Build debug APK
./gradlew assembleRelease                  # Build release APK (needs keystore.properties)
./gradlew test                             # Unit tests (JVM)
./gradlew connectedAndroidTest             # Instrumented tests (needs device)
adb install -r app/build/outputs/apk/debug/app-debug.apk  # Install on device
```

Test device: Pixel 3 on ADB at `192.168.1.68:5555` (Android 12, API 31).

**Gradle version: stay on 8.x.** Do NOT upgrade to Gradle 9.x -- it removed `JvmVendorSpec.IBM_SEMERU` which the React Native / Android Gradle plugins still reference. Gradle 8.13 is the latest compatible version. Currently on 8.7.

## Releasing

Two paths: fast (instant) or CI (verified).

**Fast path** -- release is live in seconds, CI verifies in the background:
```bash
# 1. Bump version in app/build.gradle.kts (versionCode + versionName)
# 2. Build locally: ./gradlew test && ./gradlew assembleRelease
# 3. Commit, tag, push
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.4.0"
git tag v1.4.0
git push origin main --tags
# 4. Create release immediately with local release APK
cp app/build/outputs/apk/release/app-release.apk nudge-v1.4.0.apk
gh release create v1.4.0 nudge-v1.4.0.apk --title "v1.4.0" --generate-notes
```

**CI-only path** -- just tag and push, wait ~4 min for GitHub Actions:
```bash
git tag v1.4.0
git push origin main --tags
# GitHub Action builds release APK, tests, creates release automatically
```

CI runs on every tag push (`.github/workflows/release.yml`). Builds `assembleRelease` (APK) **and `bundleRelease` (AAB)** using secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`); both are attached to the GitHub Release (APK for direct download + F-Droid; AAB for Google Play). Also exposes `workflow_dispatch` so a Play-ready AAB can be rebuilt from `main` without re-tagging. If a release already exists (fast path), CI updates it.

**Every push to `main`** (added 2026-07-05) also auto-builds the signed APK (tests-gated by `./gradlew test`) and publishes/refreshes a rolling **`main-latest` PRERELEASE** (`…/releases/tag/main-latest`, always the newest main) — an installable dev build per merge, asset `nudge-main.apk`. NOT a versioned release; `v*` tags remain the real releases. Watch: `gh run list --repo astraedus/nudge --branch main`.

### Google Play release (catch Play up after a GitHub release)

GitHub releases are automatic; **Google Play is a separate, deliberate step** that runs **from the laptop, not CI**:

```bash
# Safe verify: upload as a production DRAFT (no users affected).
scripts/publish-to-play.sh 1.7.0
# Go live to a 20% staged rollout (halt-able from Play Console).
STATUS=inProgress ROLLOUT=0.2 scripts/publish-to-play.sh 1.7.0
# Full rollout once confident.
STATUS=completed ROLLOUT=1.0 scripts/publish-to-play.sh 1.7.0
```

The script pulls the CI-built AAB from the GitHub Release (or `SOURCE=run` for a `workflow_dispatch` artifact), runs `gplay preflight` (offline secret/compliance scan), then `gplay release` to the chosen track with release notes auto-extracted from `CHANGELOG.md`.

**Why local, not CI (open-source security):** the repo is PUBLIC, so we never put the Google Play API credential in GitHub Actions — a malicious PR or compromised action could exfiltrate it. CI only ever holds the **upload key** (`KEYSTORE_BASE64`), and because Nudge is enrolled in **Play App Signing** (mandatory for apps first published after Aug 2021), Google holds the real app-signing key — a leaked upload key can be rotated in Play Console without bricking installed users. The powerful `gplay` admin service-account key stays on the laptop (chmod 600, gitignored). To go fully tag-triggered later, create a **dedicated, least-privilege** Play service account (Nudge-only, "release manager" — never the account admin key) and store it as a GitHub secret; only then is CI-side Play upload acceptable. Ref: `~/ops/references/play-console-cli.md`.

> Play track state is queryable: `gplay status --package dev.astraedus.nudge --pretty`. As of the AAB-pipeline addition, Play production was on 1.5.6 (versionCode 27) while the repo was at 1.7.0 (versionCode 31) — i.e. Play had drifted 4 versions behind because the push was manual. This script closes that gap.

## Release Signing

Keystore: `nudge-release.keystore` (PKCS12, alias `nudge`, 2048-bit RSA, 10000-day validity).
Config: `keystore.properties` (gitignored). CI uses GitHub secrets.
**Always use `assembleRelease`** for distribution. Debug APKs use machine-specific keys and cause "App not installed" when users try to update from a different build.

## Architecture

Clean Architecture in a single module with package boundaries:

```
com.astraedus.nudge/
├── data/           # Room DB, DAOs, repositories, DataStore preferences
├── domain/         # Pure Kotlin models, BlockEngine, use cases (NO Android deps)
├── service/        # AccessibilityService, ForegroundService, GrayscaleManager
├── ui/             # Jetpack Compose screens, navigation, overlay, theme
└── di/             # Hilt modules
```

**Dependency direction**: `ui` -> `domain` <- `data`, `service` -> `domain`. Domain has no Android imports.

### Core flow

```
AccessibilityService: TYPE_WINDOW_STATE_CHANGED
  -> BlockEngine.evaluate(packageName, time, usage)
  -> BlockDecision: ALLOW | HARD_BLOCK | DELAY | BREATHING
  -> Launch BlockOverlayActivity if not ALLOW
```

## Stack

- Kotlin, Jetpack Compose, Material 3
- Hilt (DI), Room (DB), DataStore (preferences)
- Coroutines + Flow
- Min SDK 26, Target SDK 34, Compile SDK 34

## Key conventions

- Domain layer is pure Kotlin — no `android.*` imports. Fully unit-testable on JVM.
- Single Activity architecture (MainActivity) + Compose Navigation.
- BlockOverlayActivity is a separate activity with `singleInstance` launch mode, `excludeFromRecents`, empty `taskAffinity`.
- AccessibilityService handles foreground app detection. ForegroundService keeps monitoring alive.
- All entities use Room `@Entity` annotations. DAOs return `Flow<>` for reactive queries.
- ViewModels use `@HiltViewModel` and inject use cases/repositories.
- No internet permission. No analytics. No telemetry.

## Block modes

- `HARD_BLOCK` — cannot open the app at all
- `DELAY` — configurable countdown (5/15/30/60s) before app opens
- `BREATHING` — guided breathing exercise before app opens (the signature feature)

## v1.1 Features

- **Schedule-based rules** — day-of-week + time-of-day, overnight schedule support (spans midnight)
- **In-app feature blocking** — YouTube Shorts, Instagram Reels/Explore, TikTok detection via AccessibilityService
- **Grayscale mode** — force screen to grayscale (requires ADB: `adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`). Grayscale guide in Settings.
- **Rotating motivational messages** — shown on overlay screens when blocks trigger. **User-editable (v1.6.0)**: defaults live in `ui/overlay/NudgeMessages.kt` (delayTitles/delaySubtitles/hardBlockMessages); users override via Settings → Personalize → "Edit block messages" (`ui/screens/settings/MessagesEditorScreen.kt`), stored as 3 multiline strings in `NudgePreferences` (`customDelayTitles`/`customDelaySubtitles`/`customHardBlockMessages`, one message per line, empty = defaults). `NudgeMessages.resolvePool(customRaw, default)` is the pure resolver; `BlockOverlayActivity` reads the prefs once via `runBlocking{ first() }` before `setContent` (avoids a default→custom flash) and passes resolved pools into the overlay composables (which still `remember { pool.random() }`).
- **"Walked Away" tracking** — counts when user taps "I changed my mind" instead of waiting
- **2x2 dashboard stats** — Screen Time, Active Rules (tappable), Blocked, Walked Away
- **Floating interaction counter** — centered touch-through overlay (40sp counter, 16sp label, 13sp daily) showing reels/shorts scrolled or taps per session. Escalating colors: white (0-9), orange (10-19), deep orange (20-29), red with red background tint (30+). TYPE_ACCESSIBILITY_OVERLAY from service, no extra permission. Per-rule `showCounter` toggle (default ON for new rules).
- **Time remaining overlay** — per-rule opt-in (`showTimeRemaining`). Displays "42m left" or "1h 12m left" below counter, color-coded: green (>50% remaining), orange (25-50%), red (<25%). Uses UsageStatsManager for actual foreground time. Requires daily limit to be set.
- **Auto-kick** — optional per-rule feature: sends user to home screen after N scrolls/taps in one session. Configurable threshold 5-100 (step 5, default 30). Session counter resets after kick. Stored as `autoKickAfter` on BlockRule.
- **Auto-kick cooldown** — configurable per-rule (0-300 seconds, default 60s). After auto-kick, returning to the app forces a DELAY overlay for the remaining cooldown. Session counter preserved during cooldown. Stored as `autoKickCooldownSeconds` on BlockRule.
- **Instagram home feed detection** — `InAppDetector` now detects Instagram's home feed (when Home tab is selected, no other tabs active) and treats it as REELS-equivalent. Home feed scrolling counts toward interaction counter and auto-kick the same as the Reels tab.
- **Post-overlay passthrough** — after delay/breathing completes, skip re-evaluation until user leaves app. Prevents infinite overlay loop.
- **Web domain blocking (Chrome v1)** — blocks websites in Chrome that match app rules. When Chrome is foregrounded, reads URL bar via accessibility tree (`WebDomainDetector`), extracts domain (`WebDomainMatcher`), matches against rules' `webDomains` field. Same overlay modes (HARD_BLOCK/DELAY/BREATHING). Passthrough prevents re-blocking same domain. Only Chrome for v1 (extensible via `BROWSER_PACKAGES`). UI toggle "Block on web too" auto-populates known domains (Instagram, YouTube, TikTok) or allows custom entry.
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
- **Settings** — version links to GitHub repo, source code & feedback link

## Database

Room DB version 7. Migrations: 1->2 (schedule/inapp/grayscale), 2->3 (userChangedMind), 3->4 (showCounter), 4->5 (autoKickAfter), 5->6 (showTimeRemaining, autoKickCooldownSeconds), 6->7 (webDomains).

## Counter overlay architecture

- `InteractionTracker` (@Singleton): in-memory session/daily counts per package. No DB writes per interaction. Also tracks cooldown state per package after auto-kick.
- `CounterOverlayManager` (@Singleton): WindowManager overlay using service context (required for TYPE_ACCESSIBILITY_OVERLAY token). `setServiceContext()` called in `onServiceConnected()`. Centered on screen with escalating colors (white -> orange -> deep orange -> red) based on session count.
- `TimeRemainingOverlayManager` (@Singleton): Standalone floating overlay in top-right corner. Shows "Xm left" with color-coded text (green >50%, orange 25-50%, red <25%) and increasingly opaque background. Separate from counter overlay so both can show independently.
- `activeReelLabel`: once Shorts/Reels feature detected, skip tree inspection on subsequent scrolls. Reset on app switch.
- Counter-enabled packages cached every 10s via `CounterCacheRefresher` (Map<String, CounterCacheEntry> with autoKickAfter, showTimeRemaining, dailyLimitMinutes, autoKickCooldownSeconds per package).
- Auto-kick: optional per-rule threshold (`autoKickAfter`). When session count hits threshold, sends ACTION_MAIN/CATEGORY_HOME intent, sets cooldown, and resets session counter.
- Auto-kick cooldown: configurable per-rule (default 60s). After auto-kick, re-opening the app shows a DELAY overlay for the remaining cooldown. Session counter NOT reset during cooldown.
- Time remaining overlay: optional per-rule (`showTimeRemaining`). Uses UsageStatsManager to get actual foreground time, displays remaining daily limit as color-coded overlay line. Updated every 30 seconds.

## Web domain blocking architecture

- `domain/WebDomainMatcher.kt` — pure Kotlin (no Android deps). `extractDomain(urlBarText)` strips protocol/path/port, normalizes subdomains (www, m, mobile, l, lm). `matches(urlBarText, webDomains)` checks extracted domain against comma-separated rule domains.
- `service/WebDomainDetector.kt` — `@Singleton`, two-strategy URL-bar read. Multi-browser: `BROWSER_URL_BAR_IDS` maps each package to ordered candidate view-id suffixes (Chrome/Brave/Edge/Kiwi `url_bar`+`omnibox_url_text`, Firefox/Fenix `ADDRESSBAR_URL_BOX`+`mozac_browser_toolbar_url_view`, Samsung Internet `location_bar_edit_text`, Opera `url_field`, DuckDuckGo `omnibarTextInput`). **Strategy 1 (fast path)**: `findAccessibilityNodeInfosByViewId()` over fully-qualified `pkg:id/suffix` ids (Chromium family, Samsung, Opera, DDG). **Strategy 2 (traversal fallback)**: when the fast path finds nothing, `findNodeByViewId()` does a bounded (≤600 nodes) DFS matching `node.viewIdResourceName` against the BARE suffixes — needed for modern Firefox, whose Compose toolbar exposes the URL bar as a bare testTag `ADDRESSBAR_URL_BOX` (no `pkg:id/` prefix) that `findAccessibilityNodeInfosByViewId` will NOT match at runtime, with the URL in `contentDescription` (not `text`). `readUrlRaw(node)` reads text→contentDescription; `cleanAddressBarText()` strips Firefox's localized "…. Search or enter address" hint by cutting at the first `\.\s` (locale-agnostic; URLs never contain period-space). `urlBarViewIdsFor`/`qualifiedUrlBarViewIdsFor`/`bareUrlBarViewIdsFor` are pure, unit-tested. `isBrowser(pkg)` checks map membership. Findings/rationale: `docs/firefox-webblock-findings.md`.
- Integration in `NudgeAccessibilityService`: on `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` for browser packages, calls `evaluateWebDomain()` which reads URL bar, extracts domain, queries `EvaluateBlockUseCase.evaluateWebDomain()`.
- `EvaluateBlockUseCase.evaluateWebDomain()` finds all enabled rules with matching `webDomains`, converts to `ActiveRule` list, passes through `BlockEngine`.
- Passthrough: `lastBlockedDomain` tracks currently-blocked domain. Same domain won't re-trigger until user navigates away. Clears on app switch away from browser.
- UI: "Block on web too" toggle in `UnifiedAppConfigScreen`, auto-populates known domains per `DEFAULT_WEB_DOMAINS` map.

## Content filter architecture (generic "restricted content" web blocker)

Generic, opt-in master switch that blocks websites against a large bundled blocklist + keyword list across supported browsers. **Framing constraint: NOTHING user-facing reveals the adult/restricted-content purpose** — the blocklist and keywords live only in code/assets. UI strings say "Block restricted websites" / overlay rule name "Restricted content".

- `app/src/main/assets/content_filter_domains.txt` — ~274k newline-separated lowercased base domains (4.5MB). Packaged into the APK.
- `data/repository/ContentFilterRepository.kt` (`@Singleton`, impl of `ContentFilter` interface) — lazily loads the asset into an in-memory `HashSet<String>` on **first** `isBlocked()` call (not at app/service start), on `Dispatchers.IO`, guarded by a `Mutex` so concurrent callers load once. Fails open to empty set if the asset is unreadable. The `ContentFilter` interface lets `EvaluateBlockUseCase` be unit-tested without loading the asset.
- `domain/ContentFilterMatcher.kt` — pure Kotlin. `matchesDomain(url, blocklist)` extracts the base domain via `WebDomainMatcher.extractDomain` then checks it + progressively-stripped parent domains against the set (subdomains of a blocked base match). `matchesKeyword(url, keywords)` does case-insensitive substring matching of `DEFAULT_KEYWORDS` against the raw URL (catches search queries + unknown domains). Keyword list deliberately avoids short ambiguous tokens (no bare "sex"/"anal") to prevent false positives like sussex/essex/analysis.
- **Query-scoped matching (v1.8.0)** — for ambiguous shorthand tokens (`AMBIGUOUS_QUERY_KEYWORDS`, e.g. "bbc") that would be dangerous as raw substrings (would block bbc.com news), `matchesQueryKeyword(url, keywords)` matches them as WHOLE WORDS inside the URL's SEARCH QUERY only (never the host). `extractSearchQuery(url)` is a hand-rolled (NOT android.net.Uri, stays JVM-pure) parser that pulls decoded search terms from common param names (`q`/`query`/`search_query`/`p`/`text`/`wd`/`k`/`kw`/`kp`) and path styles (`/search/…`, `/s/…`), `+`/`%XX`-decoding best-effort. This catches Google/Bing/DDG image searches too (the `q=` is in the URL). Gated behind opt-in pref `contentFilterStrictKeywords` (default false) so the general userbase isn't hit by news-search false positives. **Firefox caveat (device-verified v1.8.0):** the block fires on the initial search navigation (URL has `q=<term>`), but once on the Google Images tab (`udm=2`) Firefox's URL-bar contentDescription DROPS the `q=` param, so a direct Images-tab load without `q` won't re-fire. URL-bar architecture cannot see inline images on a benign-URL page — only the URL. `DEFAULT_KEYWORDS` also expanded with coined/compound low-collision tokens (redgifs, porngif, stripchat, …) — never bare "gif".
- `EvaluateBlockUseCase.evaluateWebDomain()` — after the per-rule `webDomains` check finds no match, falls through to `evaluateContentFilter()`: if `contentFilterEnabled` and `ContentFilter.isBlocked(url)`, builds an `ActiveRule` with the configured `contentFilterMode` (tracking package `"web"`, ruleName "Restricted content") and runs it through `BlockEngine`. Reuses the existing overlay/passthrough path (HARD_BLOCK never sets passthrough, so it always re-blocks).
- Prefs: `NudgePreferences.contentFilterEnabled` (default **false**, opt-in) + `contentFilterMode` (default `"HARD_BLOCK"`) + `contentFilterStrictKeywords` (default **false**, gates the query-scoped ambiguous-slang matching). All DataStore — no Room migration.
- `ContentFilter.isBlocked(url, strictKeywords)` OR-chains `matchesDomain || matchesKeyword(DEFAULT_KEYWORDS)` plus, when `strictKeywords`, `matchesQueryKeyword(AMBIGUOUS_QUERY_KEYWORDS)`. `EvaluateBlockUseCase.evaluateContentFilter` reads the strict pref and threads it through.
- UI: "Content Filter" section in `SettingsScreen.kt` — "Block restricted websites" master switch + "Strict keyword matching" sub-toggle (both direct-`NudgePreferences`, no ViewModel).
- Tests: `ContentFilterMatcherTest` (domain/keyword/false-positive guards), `WebDomainDetectorTest` (multi-browser id resolution + mockk node reads), `EvaluateBlockContentFilterTest` (enabled/disabled/mode wiring, repo mocked — never loads the asset).

## Strict Mode (commitment lock) architecture — v1.7.0

Opt-in lock that gates every protection-WEAKENING action behind a typed unlock challenge. Strengthening is never gated. Two layers: in-app gate + OS escape-route guard.

- **Prefs** (`NudgePreferences`): `strictModeEnabled` (default false) + `strictModeChallengeLength` (default 24; Easy 12 / Medium 24 / Hard 48). Same `Flow` + setter pattern as `globalEnabled`.
- **`domain/lock/StrictModeChallenge.kt`** — pure Kotlin. `generate(length)` (unambiguous charset, excludes 0/O/1/l/I), `forDisplay` (dash-grouped chunks of 5), `normalize`/`rawLength` (dash- + whitespace-strip), `verify(input, target)` (case-sensitive, dash-insensitive **both** directions). The dialog counter and `verify` share `normalize`, so "x/y" can never disagree with what's compared.
- **`domain/lock/RuleWeakening.kt`** — pure `isWeakening(old, new)`: disable, mode softening (HARD_BLOCK>DELAY>BREATHING>none), shorter delay, lowered/removed daily limit.
- **`ui/lock/StrictModeGate.kt`** — ViewModel-side helper: `run(prompt, action)` runs immediately if Strict Mode off, else defers the action and emits a `ChallengeState` the screen renders. Used by `HomeViewModel` (global toggle ON→OFF only), `ActiveRulesViewModel` (rule disable), `UnifiedAppConfigViewModel` (weakening save / delete). Settings gates Strict-Mode-OFF with its own local challenge.
- **`ui/components/ChallengeDialog.kt`** — the unlock UI. Paste/copy suppressed via a no-op `LocalTextToolbar`; `imeAction=Done` clears focus to dismiss the keyboard. Fresh target per open.
- **Escape-route guard** (the OS-bypass layer):
  - `domain/lock/StrictModeEscapeGuard.kt` — pure `shouldGuardSettingsScreen(foregroundPkg, windowText, appLabel, strictEnabled, withinGrace)`. Fails CLOSED (blank/empty/exception → no guard); biased to fewer false positives (requires a settings package AND the app label AND a strong escape signal).
  - **Detection signatures** (tuned against live AOSP Settings on the Pixel 3, Android 12; app label "Nudge - App Blocker"): a11y **detail** page (`com.android.settings/.SubSettings`) keys on the label + **"shortcut"** / **"use <label>"** — NOT the bare word "accessibility", because the a11y **list** page also shows our label (that was the false-positive to avoid). App Info page (`.applications.InstalledAppDetails`) keys on label + **"Force stop"** + **"Uninstall"**.
  - `service/StrictModeEscapeManager.kt` (`@Singleton`) — in-memory 60s grace window (modeled on `PassthroughManager`); while in grace the service short-circuits so a committed user can complete their toggle/uninstall.
  - `ui/lock/StrictModeGuardActivity.kt` — full-screen overlay reusing `ChallengeDialog`; unlock → `grantGrace()` + finish (back to Settings); cancel/back/dismiss → reliable `GLOBAL_ACTION_HOME` (HOME-intent fallback). Registered in manifest like `BlockOverlayActivity` (singleInstance, excludeFromRecents, empty taskAffinity).
  - `NudgeAccessibilityService` guards in `onAccessibilityEvent` before the `SYSTEM_PACKAGES` early-return; bounded node-text harvest (≤800 nodes); Strict Mode flags cached off-main so the hot path never blocks on DataStore. `accessibility_service_config.xml` gained `flagRetrieveInteractiveWindows`.
  - **OEM/locale caveat**: detection is best-effort, verified only on AOSP/English. Other settings packages are tolerated in `SETTINGS_PACKAGES` but unverified; an untuned OEM/locale screen simply isn't guarded (a miss, never a trap). Safety invariant: the lock can never hard-trap the user — cancel always goes home, the challenge is always solvable, Strict Mode off disables all guarding.
- **Tests**: `StrictModeChallengeTest` (charset/length/uniqueness, verify exact + dash/whitespace-insensitive both directions), `RuleWeakeningTest` (every axis both directions), `StrictModeGateTest` (off=immediate, on=deferred-then-run/cancel), `StrictModeEscapeGuardTest` (guard/no-guard matrix + list-page-not-trapped, fail-closed, OEM pkg), `StrictModeEscapeManagerTest` (grace open/expire/clear/re-grant).

## Daily 1-minute pass (emergency escape hatch) — v1.9.0

Opt-in, per-app escape hatch on the block overlays. One 60-second free window per blocked app, then a rolling 24h lockout. Hidden while Strict Mode is on (a commitment lock must not have a one-tap bypass) and behind a Settings master toggle.

- **`domain/emergency/EmergencyPass.kt`** — pure Kotlin. Ledger `parse`/`serialize` (format `pkg=epochMillis;pkg2=epochMillis`, fails soft to empty map on malformed input, never throws), `canUse(usage, pkg, now, cooldownMs)`, `nextAvailableMs(...)` (remaining lockout for the UI hint), `record(...)`. Constants `PASS_DURATION_MS=60_000`, `LOCKOUT_MS=86_400_000`. Fully unit-tested (`EmergencyPassTest`, 20 cases).
- **`service/EmergencyPassManager.kt`** (`@Singleton`) — modeled on `PassthroughManager`. In-memory `activeUntil: ConcurrentHashMap<pkg, Long>` (window end); per-package `kickJobs` so a fresh grant replaces the prior timer. `isPassActive(pkg)` is the non-blocking hot-path check. `usePass(pkg)` opens the window, persists the lockout (`prefs.recordEmergencyPassUsed`), and schedules `delay(PASS_DURATION_MS) → remove → kickHome()`. `kickHome()` prefers `NudgeAccessibilityService.requestGoHome()` (GLOBAL_ACTION_HOME) and falls back to a HOME intent from the app context (same "never fail to go home" pattern as the Strict Mode guard). The active window is in-memory only — a process restart just ends the window (fail-safe toward re-blocking); only the lockout is persisted.
- **Prefs** (`NudgePreferences`): `emergencyPassEnabled` (bool, default **true**) + `emergencyPassUsage` (serialized ledger string) + `recordEmergencyPassUsed(pkg, now)` convenience (read-modify-write via `EmergencyPass`, prunes entries older than `LOCKOUT_MS` so the string stays bounded).
- **Service integration** (`NudgeAccessibilityService`): `emergencyPassManager()` on the EntryPoint; in `evaluateForegroundPackage`, `if (isPassActive(pkg)) return` placed **before** the auto-kick-cooldown block so an active pass overrides cooldown too (genuinely free use). When the window expires the scheduled kick sends the user home AND the next foreground event re-blocks normally (backstop).
- **UI**: `ui/overlay/EmergencyPassAction.kt` — one shared composable (muted `TextButton` "Use for 1 minute · once a day" when available; muted "Daily pass used · next in Xh" when spent; nothing otherwise) rendered by all three overlays below the primary button. `BlockOverlayActivity` computes `canUseEmergencyPass`/`emergencyLocked`/`nextPassMs` inside the existing `runBlocking` alongside the message pools (so state is correct on first composition, no flash); skips the `"web"`/blank pseudo-package. Tap → `emergencyPassManager.usePass(pkg); finish()` (NOT navigateHome, NOT a "changed my mind" event — a deliberate escape). Strict-mode/feature-disabled → button hidden entirely. Settings master toggle under an "Escape Hatch" section (greyed while Strict Mode on).
- **Device-verified (v1.9.0, Pixel 3)**: button renders on delay + hard-block overlays; tap grants access; 60s auto-kick fires (~59.8s, logcat-confirmed); locked state + re-block after kick; master toggle + Strict Mode both hide it.

## Export/Import architecture

- `data/export/RuleExportData.kt` — data classes: `NudgeExport`, `ExportedRule`, `ExportedGroup`
- `data/export/RuleExporter.kt` — serialization/deserialization via `org.json` (no extra dependency). Handles null fields, version validation, block mode validation. `@Singleton` with `@Inject`.
- `domain/usecase/ExportRulesUseCase.kt` — collects enabled rules + groups + members, delegates to RuleExporter
- `domain/usecase/ImportRulesUseCase.kt` — `preview()` returns count, `execute()` inserts with duplicate detection (packageName + mode + schedule match). Creates groups by name if missing.
- UI: three-dot overflow menu in `ActiveRulesScreen` with "Export Rules" (share intent via FileProvider) and "Import Rules" (ACTION_OPEN_DOCUMENT file picker). Confirmation dialog before importing.
- Export format: pretty-printed JSON, version 1, human-readable. Groups referenced by name (not ID).

## Stats visualization architecture

- `ui/screens/stats/StatsCalculator.kt` — pure Kotlin (no Android deps), injected via Hilt. Methods: `buildWeeklyData`, `buildTrendData`, `buildHourlyData`, `calculateStreak`. Fully unit-testable.
- `ui/screens/stats/charts/WeeklyBarChart.kt` — Canvas-based 7-day bar chart with rounded corners, day labels
- `ui/screens/stats/charts/BlockedTrendChart.kt` — dual chart: bars (blocked) + line with dots (walked away)
- `ui/screens/stats/charts/HourlyHeatmap.kt` — 24-cell row, color intensity from surfaceVariant to primary
- `ui/screens/stats/charts/StreakCounter.kt` — flame icon + streak count + "X days streak" label
- All charts use Material 3 colorScheme exclusively, handle empty states, no external dependencies.

## Google Play compliance

### AccessibilityService prominent disclosure (required by Google Play policy)
- `ui/components/AccessibilityDisclosureDialog.kt` — Material 3 AlertDialog shown BEFORE requesting Accessibility Service permission
- Two buttons: "I Understand" (accept) / "Not Now" (decline). Back/tap-outside = decline, NOT consent.
- Explains: WHY (detect foreground apps), WHAT data (package names only), HOW used (locally, never sent)
- Wired into: `OnboardingScreen.kt` (onboarding flow) and `SettingsScreen.kt` (settings page)
- Demo video (unlisted): https://youtube.com/shorts/0ZN77tEcFzQ — linked in Play Console Accessibility Services declaration
- If Google rejects again: check the specific reason. The dialog text, button labels, and dismiss behavior all matter. See https://support.google.com/googleplay/android-developer/answer/10964491 for full requirements.

## Store listing

Assets at `store-listing/` — feature graphic, screenshots, listing copy, batch config (`screenshots.json`).

## Testing Philosophy — Never Regress

**Every new feature MUST ship with tests that cover its core behavior.** The test suite is the safety net that lets us move fast without breaking existing functionality.

Principles:
- **Tests are not optional.** If you add a feature, you add tests. No exceptions.
- **Test the contract, not the implementation.** Domain logic (BlockEngine, use cases, StatsCalculator) gets unit tests. UI gets integration tests for navigation/state.
- **Run `./gradlew test` before every commit.** If tests fail, the feature isn't done.
- **Regression = bug.** If a new feature breaks existing behavior, that's a blocker — fix it before merging.
- **Domain layer is the priority.** Pure Kotlin with no Android deps = fast JVM tests. Test BlockEngine decisions, schedule evaluation, counter logic, export/import round-trips.
- **When fixing a bug, write a test that reproduces it first.** Then fix. The test proves the fix works and prevents re-introduction.

Test locations:
- `app/src/test/` — JVM unit tests (domain, data, use cases)
- `app/src/androidTest/` — instrumented tests (Room migrations, accessibility service behavior)

Coverage targets (aspirational, enforce on new code):
- Domain layer: >90% line coverage
- Data layer (repositories, DAOs): >70%
- UI ViewModels: key state transitions tested

## Post-feature checklist

After any feature addition or significant change:
1. Write tests covering the new behavior (unit + integration as appropriate)
2. Run `./gradlew test` and verify ALL tests pass (not just new ones)
3. Build debug APK: `./gradlew assembleDebug`
4. Install on Pixel 3: `adb -s 192.168.1.68:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
5. **QA on device** — spawn `device-tester` agent with specific test cases. PASS required before push.
6. If QA passes: bump `versionCode` + `versionName` (patch) in `app/build.gradle.kts`
7. Update CHANGELOG.md with version + date + changes
8. Update this CLAUDE.md (architecture docs, feature descriptions) if applicable
9. Commit all changes, tag, push: `git push origin main --tags`
10. Create GitHub release (fast path): `gh release create vX.Y.Z nudge-vX.Y.Z.apk --title "vX.Y.Z" --generate-notes`
11. **Publish to Google Play** — the standing default so Play never drifts behind GitHub again. After CI attaches the AAB, run `scripts/publish-to-play.sh X.Y.Z` (stages a production DRAFT to verify), then `STATUS=completed ROLLOUT=1.0 scripts/publish-to-play.sh X.Y.Z` for full rollout (or `STATUS=inProgress ROLLOUT=0.2 …` for a staged, halt-able rollout). Play credentials stay on the laptop — never in CI. See the **Releasing → Google Play** section for the security rationale.
12. Update store listing copy if user-facing

**This is the standard ship flow. Every change that touches user-facing behavior gets a device QA gate before push.**

**SHIP AUTONOMOUSLY — do NOT ask for permission once the device-QA gate passes.** This is a documented, reversible, owned release flow (own-the-last-mile rule). When QA is green: bump the version, update CHANGELOG/docs, commit, build `assembleRelease`, tag, `git push origin main --tags`, and `gh release create` — end to end, no confirmation step. Asking "should I push?" on a verified change is the exact anti-pattern this repo's flow exists to prevent. The ONLY things that still warrant a pause are the universal ones: money, real-world identity, known-contact email, ban-risk platform actions, or destructive/irreversible deletion — none of which a Nudge release involves.

## Backlog

### Known issues — surfaced incidentally during v1.9.0 device QA (pre-existing, NOT from the 1-min-pass feature; each needs its own investigation)
- [ ] **Accessibility service instability under sustained load** — during a ~90min automated torture test (many app launches, force-stops, PIP overlays) the a11y service churned/reconnected repeatedly and was found OS-unregistered once. Could NOT be reproduced in a normal cycle (process held 63min uptime, single PID, zero idle reconnects), so likely OS memory-pressure kill on the 3GB Pixel 3 rather than a code defect — but worth a foreground-service/`onUnbind` hardening pass + a repro on a clean device. Root-cause before assuming it's environmental.
- [ ] **Browsers bypass whole-app block rules** — a DELAY/HARD_BLOCK rule on Chrome never fires via the whole-app pipeline because browser packages route straight to per-URL web-domain evaluation. This is *by design* per the web-domain architecture (whole-app blocking a browser would nuke all browsing), but the UX is surprising — a rule silently does nothing unless "Block on web too" + a domain rule exist. Consider surfacing this in the rule editor when the target is a known browser.
- [ ] **System permission dialog can render over `BlockOverlayActivity`** — e.g. Camera's location-permission prompt kept re-appearing on top of the block overlay, leaving the blocked app's UI visible underneath even though the decision was correctly `HARD_BLOCK`. Overlay z-order / re-assert on `TYPE_WINDOW_STATE_CHANGED` for the permission-controller package.
- [ ] **"I changed my mind" can leave the user inside the blocked app** — reproduced on Calculator (post-lockout) and suspected on Chrome; `navigateHome()` should reliably land home, investigate the cases where it doesn't.

### v1.2 in progress
- [x] Time remaining overlay (code-complete, verified on device)
- [x] Auto-kick cooldown (code-complete, verified on device)
- [x] Rule name on block overlays (code-complete, verified on device)
- [x] Export/Import rules (code-complete, tests pass, needs on-device QA)
- [x] Enhanced stats visualizations (code-complete, tests pass, needs on-device QA)
- [x] Dynamic version display from BuildConfig
- [x] Tag-triggered GitHub Actions release pipeline (`.github/workflows/release.yml`)
- [ ] Instagram home feed detection -- code written but AccessibilityService API doesn't expose child node `selected` state through `findAccessibilityNodeInfosByText/ViewId`. Needs tree-walk approach: traverse from `rootInActiveWindow`, find ImageView nodes with `selected=true` in bottom nav, match to parent tab. See InAppDetector.kt.
- [ ] On-device QA for all v1.2 features
- [ ] YouTube Shorts verification on device

### v1.3+
- [ ] **QR code unlock** -- physical friction for bypassing blocks. User generates a QR code in settings (random secret encoded via ZXing), prints/places it somewhere inconvenient. Per-rule toggle `requireQrUnlock`. Block overlay gets "Scan QR to unlock" button that opens camera (ML Kit barcode scanner), verifies against stored secret, grants passthrough. Adds camera permission (only one we'd need beyond accessibility). Twist: multiple QR codes with different unlock durations (e.g. "bedroom QR" = 10min, "office QR" = 1hr). Could also give QR to a friend for accountability. Low implementation complexity, high user-perceived value.
- [ ] **Advanced data visualization** -- expand beyond current charts: per-app weekly breakdown, comparison vs previous week, export charts as image for sharing/accountability.
- [ ] Discord in-app detection: count server/channel switches as "taps" for counter + auto-kick. Discord uses React Native so TYPE_VIEW_CLICKED doesn't fire. Would need to detect server/channel navigation via accessibility tree changes. Low priority.
- [ ] NFC tag unlock -- same concept as QR but tap phone to NFC tag. No extra permissions needed (hardware feature). User writes unlock token to a cheap NFC tag ($1), places it somewhere. Lower priority than QR since fewer people have NFC tags lying around.
- [ ] Widgets (home screen quick stats, toggle rules)
- [ ] Contextual triggers (location-based, time-of-day auto-enable)
- [x] Release signing key (v1.3.2 -- PKCS12 keystore, CI via GitHub secrets)
