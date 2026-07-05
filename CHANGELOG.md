# Changelog

All notable changes to Nudge are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [1.9.0] - 2026-07-05

### Added
- **Daily 1-minute pass (emergency escape hatch)**: On any block screen (delay, breathing, or hard block) a subtle **"Use for 1 minute · once a day"** button now lets you into a blocked app for a single 60-second window. When the minute is up, Nudge sends you back to the home screen and normal blocking resumes. Each blocked app gets its own pass, and after you use one it's locked for a full 24 hours — the button is replaced by a muted "Daily pass used · next in Xh" note. Built for the genuine "I need this app for one thing right now" moment without giving up your protection for the day.
- **Respects Strict Mode & fully optional**: The pass never appears while **Strict Mode** is on (a commitment lock shouldn't have a one-tap escape). You can also turn it off entirely for everyone in **Settings → Escape Hatch → "Daily 1-minute pass"** (on by default). Everything stays on-device.

## [1.8.0] - 2026-07-02

### Added
- **Stronger content filter matching**: The optional **Settings → Content Filter → "Block restricted websites"** filter now recognises many more sites out of the box, and catches flagged terms inside search queries (including image searches) rather than only whole domains — so restricted results are blocked at the search stage across supported browsers, Firefox included.
- **Strict keyword matching**: A new opt-in sub-toggle (**Settings → Content Filter → "Strict keyword matching"**, off by default) also blocks ambiguous shorthand terms when they appear as whole words in a search query — while leaving normal websites reachable (e.g. searching a flagged term is blocked, but visiting a legitimate site that merely shares the name is not). Everything stays on-device; no browsing data leaves the phone.

## [1.7.0] - 2026-06-25

### Added
- **Strict Mode (commitment lock)**: A new **Settings → Commitment Lock → "Lock my settings"** switch turns Nudge into a real commitment device. While Strict Mode is on, any action that *weakens* your protection requires typing a randomly generated unlock code first — turning Nudge off, disabling or deleting a rule, shortening a delay, softening a block mode, or even turning Strict Mode itself back off. Strengthening your protection (adding apps, longer delays, harder modes) is never gated. Pick a difficulty (Easy 12 / Medium 24 / Hard 48 characters); the code can't be pasted.
- **Escape-route guarding**: Strict Mode also closes the obvious bypasses. While locked, opening Nudge's **Accessibility settings page** (to switch the service off) or its **App Info page** (Force stop / Uninstall) brings up the same unlock challenge over the system screen. Solve it and you get a 60-second window to make your change; back out and you're returned home. The accessibility *list* page is deliberately left alone so you can still browse your other services.

## [1.6.0] - 2026-06-20

### Added
- **Edit your own block messages**: The motivational messages shown on the block, delay, and breathing overlays are now fully customizable. A new **Settings → Personalize → "Edit block messages"** screen lets you rewrite the delay title, delay subtitle, and hard-block message — one message per line (a random one is shown each time a block fires, as before). Leave a field empty (or tap "Reset to defaults") to fall back to the built-in messages. Stored locally on-device like everything else.

### Fixed
- **Web blocking now works in Firefox**: Domain blocking ("Block on web too") previously did nothing in Firefox — modern Firefox switched to a Compose-based toolbar that exposes the URL differently (as a bare test tag, with the address in the accessibility content description rather than the text). Nudge now reads the Firefox address bar via a tree traversal and correctly blocks matching domains. Chrome and other Chromium browsers are unaffected.

## [1.5.8] - 2026-06-16

### Fixed
- **Faster screen navigation**: Opening Active Rules, Manage Apps, app config, and stats screens no longer stutters. Loading installed-app names and icons (a heavy PackageManager operation) was running on the main/UI thread and recomputing on every navigation. It now runs off the main thread (`Dispatchers.IO`) and is cached in the installed-apps repository, so the first visit pays the cost once and every later visit is instant. Active Rules also no longer re-fetches the app list on every rule change.

## [1.5.7] - 2026-06-05

### Fixed
- **Web domain HARD_BLOCK now re-blocks on return**: Previously, visiting a hard-blocked domain (e.g. instagram.com) in Chrome would show the block overlay once, but returning to Chrome after dismissing it would silently let the user through. The `lastBlockedDomain` passthrough was incorrectly set for HARD_BLOCK mode, which has no "completed" state. Now only DELAY and BREATHING modes set the passthrough (after the user completes the exercise), while HARD_BLOCK always re-evaluates the domain.

## [1.5.6] - 2026-05-26

### Fixed
- **Google Play policy compliance**: Added prominent disclosure dialog for Accessibility Service usage, shown before requesting the permission. Dialog explains what data is accessed (foreground app names only), why it's needed (to trigger block rules), and how it's used (locally, never sent anywhere). Two-button consent (I Understand / Not Now) as required by Google Play policy.

## [1.5.5] - 2026-05-22

### Added
- **Day-by-day navigation on stats screens**: Browse previous days' usage data with back/forward arrows on both the overall Usage Stats screen and per-app detail screen. Forward arrow disabled when viewing today. Weekly chart and hourly pattern shift to the selected date's window.

## [1.5.4] - 2026-05-21

### Added
- **Interactive charts**: Tap any bar/cell in the weekly chart, blocked trend chart, or hourly heatmap to see exact numbers. Selected elements highlight while others dim. Tap again to deselect.
- **Per-app detail screen**: Tap any app in the Usage Stats "App Usage" list to see a deep-dive view with weekly screen time, hourly usage pattern, nudge effectiveness trend, blocked/walked-away counts (today + all time), and block mode breakdown.

### Fixed
- **App names showing as package names in stats**: Usage Stats now shows human-readable app names (e.g. "Settings" instead of "com.android.settings") for all apps including system apps. Previously only launcher-category apps had proper names.

## [1.5.3] - 2026-05-20

### Fixed
- **Rules no longer deleted when toggled off**: Disabling a rule from Manage Apps previously deleted it permanently, losing all configuration. Now it disables the rule (preserving settings) so it can be re-enabled later. Disabled rules appear dimmed in Active Rules.
- **Removed stray APK from git repo**: Release APKs now only live in GitHub Releases, not committed to the repo.

## [1.5.2] - 2026-05-20

### Changed
- **Active Rules collapsed to one card per app**: Previously showed separate cards for each internal rule (whole-app, feature overrides, schedule) with individual enable/disable toggles. Now shows one card per app with icon, name, summary text (e.g. "Delay 15s · Reels: Hard Block"), and a single app-level toggle. Tapping navigates to the unified config screen.
- **Home screen "Active Rules" renamed to "Active Apps"**: Count now reflects distinct apps being blocked, not internal rule count.
- **Home screen shows Today + All Time stats**: Blocked and Walked Away counts now displayed in two sections ("Today" and "All Time") instead of just today's numbers. Resolves confusion where stats appeared to "reset" on app update -- they were always per-day.

### Fixed
- **Stale migration test**: `NudgeDatabaseMigrationTest` now covers all migrations through DB version 7.

## [1.5.1] - 2026-05-18

### Fixed
- **Screen time showing "0s"**: Switched from `queryUsageStats(INTERVAL_DAILY)` to event-based `queryEvents()` for both total and per-app screen time. The daily interval query returns stale pre-aggregated buckets on Android 12+ that often read zero. Event-based calculation (ACTIVITY_RESUMED/PAUSED pairs) gives accurate real-time data.
- **Screen time permission handling**: Home screen and stats page now show "--" with "Tap to enable" when Usage Access permission is missing (tappable to open Settings), and "< 1m" for sub-minute values instead of the confusing "0s".
- **Tap counter resets on app close/reopen**: Session counter now persists for 5 minutes after leaving an app. Previously, closing Discord and reopening it reset the tap count to 0, letting users game auto-kick. Counter only resets after being away > 5 minutes or after auto-kick cooldown expires.
- **Counter overlay showing "0" on app entry**: The floating counter no longer appears with a "0" count when first entering an app. It now only shows after the first interaction.
- **Time remaining overlay showing "0s left"**: Overlay now hides instead of displaying "0s left" when daily time runs out.

## [1.4.3] - 2026-05-17

### Changed
- **Refactored NudgeAccessibilityService** (583 -> 401 lines): Extracted `PassthroughManager` (@Singleton, 41 lines), `TimeRemainingHandler` (93 lines), and `InteractionHandler` (99 lines). Service is now a thin event router. Passthrough state moved from static companion fields to injectable singleton -- testable and no global mutable state. BlockOverlayActivity uses injected PassthroughManager via Hilt.

### Fixed
- **Daily limit enforcement during passthrough**: When "time remaining" shows 0s, the app now hard-blocks immediately by clearing passthrough and launching the block overlay.

## [1.4.2] - 2026-05-17

### Changed
- **Time remaining is now a separate corner overlay**: Moved from a text line inside the centered counter pill to its own standalone floating pill in the top-right corner. Shows independently of the interaction counter -- works even when the counter is disabled. 15sp bold text, pill-shaped dark background, color-coded (green/orange/red). Background opacity increases as time runs low.

## [1.4.1] - 2026-05-17

### Fixed
- **YouTube Shorts detected from home feed**: Tapping a Short from the YouTube home page now correctly triggers Shorts-specific rules. Previously only worked when navigating via the Shorts tab. Uses resource ID detection (`reel_recycler`, `reel_player_page_container`) as fallback when tab-based detection fails.
- **Time remaining overlay now shows after delay passthrough**: The floating time-remaining overlay was never visible because the passthrough early-return blocked the overlay code. Moved awareness overlay logic before passthrough check so counter and timer show even after delay completes.

## [1.4.0] - 2026-05-17

### Added
- **Unified App Config screen**: Replaces the per-rule editor with a single configuration page per app. All settings for an app live on one screen: master enable toggle, daily time limit, interaction counter, grayscale, block mode with delay duration, auto-kick, per-feature overrides (Reels/Explore/Shorts/Feed), and scheduled time-based overrides. No more confusing rule conflicts.
- **Per-feature override cards**: For Instagram, YouTube, and TikTok -- each detected feature (Reels, Explore, Shorts, Feed) gets its own card with mode selection (Inherit/Block/Delay/Breathing), delay duration, and auto-kick settings independent of the app-level defaults.
- **Scheduled override**: Apply a different block mode during specific time windows (e.g. hard block 6am-9am, delay otherwise). Supports day-of-week selection and independent feature overrides within the schedule.
- **"Remove All Rules" action**: One-tap removal of all rules for an app with confirmation dialog.
- **`deleteByPackageName` DAO method**: Bulk delete for clean-slate save in the unified config.

### Changed
- Navigation from Manage Apps and Active Rules now routes to the unified config screen instead of the per-rule editor.

## [1.3.4] - 2026-05-16

### Fixed
- **Time remaining now visible**: The floating time-remaining overlay now appears immediately when opening an app with `showTimeRemaining` enabled, instead of only after scroll/tap interactions. Previously the overlay was coupled to the interaction counter and never showed unless you scrolled reels or tapped.
- **App name + daily budget on nudge screens**: Delay, breathing, and hard-block overlay screens now show the human-readable app name (e.g. "Instagram") and color-coded daily time remaining ("42m left today") when a daily limit is configured. Color coding: primary (>50%), tertiary (25-50%), error (<25%).
- **Hard block shows "Daily limit reached"**: When a hard block triggers because the daily time budget is exhausted, the overlay now says "Daily limit reached" instead of the generic "App Blocked".

## [1.3.1] - 2026-05-16

### Fixed
- **Removed ripple flash** -- no more white shine when tapping buttons and cards.
- **Smoother app list scrolling** -- icon bitmap conversion now cached instead of recalculated every frame.
- **Counter overlay allocations** -- overlay background no longer creates new objects on every scroll tick during Reels/Shorts.
- **Onboarding buttons clipped** -- Next/Get Started buttons were hidden behind the navigation bar on gesture-nav devices.

### Improved
- Flow collection stops when app is backgrounded (lifecycle-aware).
- All UI state classes marked `@Immutable` so Compose can skip unchanged recompositions.
- Constant lists in rule editor memoized to reduce garbage collection pressure.
- Tag-triggered GitHub Actions release pipeline replaces local release script.

## [1.3.0] - 2026-05-16

### Added
- **Time remaining overlay**: Per-rule opt-in (`showTimeRemaining` toggle in rule editor). Displays remaining daily time as a color-coded overlay line below the interaction counter. Green (>50% remaining), orange (25-50%), red (<25%). Uses Android UsageStatsManager for actual foreground time. Requires a daily limit to be set on the rule.
- **Auto-kick cooldown**: Configurable 0-300 second cooldown after auto-kick (default 60s). Returning to the app during cooldown forces a DELAY overlay for the remaining cooldown time. Session counter preserved during cooldown -- user doesn't get a fresh slate. New `autoKickCooldownSeconds` field in rule editor with slider.
- **Rule name on block overlays**: All three overlay screens (Hard Block, Delay, Breathing) now show which rule triggered the block at the bottom of the screen. Auto-generated descriptive labels from rule properties (e.g. "Reels - Delay (5 min/day)", "Hard Block", "Breathing (scheduled)").
- **Instagram home feed detection**: InAppDetector now detects Instagram's home feed and treats scrolling there as reels-equivalent. Home feed scrolls count toward the interaction counter and auto-kick threshold.
- **Export/Import rules**: Export active blocking rules to JSON (share intent), import rules from JSON file picker. Handles groups, duplicate detection, version validation. Three-dot overflow menu in Active Rules screen. FileProvider for secure file sharing. 19 unit tests.
- **Enhanced usage visualizations**: Stats screen redesigned with four custom Compose Canvas charts: 7-day bar chart (screen time per day), blocked vs walked-away trend chart, hourly usage heatmap (24-cell color-coded row), and streak counter (consecutive days with nudge interactions). All lightweight custom Canvas -- no external charting library. 12 unit tests.
- **Dynamic version display**: Settings screen now shows `BuildConfig.VERSION_NAME` instead of hardcoded string.
- **Release build script**: `scripts/release.sh` for version bumping (patch/minor/major), building, and optional device install.
- 52 new unit tests across export/import validation, stats calculations, and existing features.

### Changed
- Room database version 5 -> 6. Migration adds `showTimeRemaining` (BOOLEAN, default 0) and `autoKickCooldownSeconds` (INTEGER, default 60) to `block_rules`.
- `CounterOverlayManager` now supports a fourth line (time remaining) below the daily total.
- `CounterCacheRefresher` carries `showTimeRemaining`, `dailyLimitMinutes`, `autoKickCooldownSeconds` per package.
- `InteractionTracker` now tracks per-package cooldown state (in-memory).
- `BlockDecision.Block` and `ActiveRule` carry `ruleName` for overlay display.
- `BlockEngine` threads rule names through all decision paths.
- `RuleEvaluator.buildRuleName()` constructs descriptive labels from rule properties.
- Accessibility service config: added `flagReportViewIds` for resource-ID-based tab detection.

### Known Issues
- Instagram home feed detection (resource-ID-based tab detection via `findAccessibilityNodeInfosByViewId`) does not reliably detect the active tab from the AccessibilityService API. Needs tree-walk approach. Text-based fallback also fails because `findAccessibilityNodeInfosByText` returns node copies that don't expose children's `selected` state. **Status: in progress.**

### Files Changed
- `data/db/entity/BlockRule.kt` -- 2 new fields
- `data/db/NudgeDatabase.kt` -- version bump + MIGRATION_5_6
- `data/repository/UsageRepository.kt` -- `getDailyForegroundTimeMs()` via UsageStatsManager
- `di/DatabaseModule.kt` -- register migration
- `di/RepositoryModule.kt` -- pass ApplicationContext
- `domain/model/ActiveRule.kt` -- `ruleName` field
- `domain/model/BlockDecision.kt` -- `ruleName` field
- `domain/engine/BlockEngine.kt` -- threads ruleName
- `domain/engine/RuleEvaluator.kt` -- `buildRuleName()`
- `service/CounterOverlayManager.kt` -- time remaining display
- `service/CounterCacheRefresher.kt` -- new cache fields
- `service/InteractionTracker.kt` -- cooldown tracking
- `service/InAppDetector.kt` -- home feed + resource-ID detection
- `service/NudgeAccessibilityService.kt` -- cooldown enforcement, time remaining updates
- `ui/overlay/BlockOverlayActivity.kt` -- EXTRA_RULE_NAME
- `ui/overlay/HardBlockContent.kt` -- ruleName param + display
- `ui/overlay/DelayContent.kt` -- ruleName param + display
- `ui/overlay/BreathingContent.kt` -- ruleName param + display
- `ui/screens/rules/RuleEditorViewModel.kt` -- new state fields
- `ui/screens/rules/RuleEditorScreen.kt` -- new toggles/sliders
- `res/xml/accessibility_service_config.xml` -- flagReportViewIds

## [1.1.4] - 2026-05-16
- Version bump for F-Droid metadata fix

## [1.1.3] - 2026-05-15
- Fix: clarify rule scope copy
- Fix: keep counter overlay stable during reels

## [1.1.2] - 2026-05-15
- Fix: clear counter overlay when leaving app

## [1.1.1] - 2026-05-15
- Initial accessibility counter overlay

## [1.1.0] - 2026-05-15
- Schedule-based rules, in-app feature blocking, grayscale mode, interaction counter, auto-kick

## [1.0.0] - 2026-05-15
- Initial release: delay-to-open, breathing exercises, hard block, daily time budgets
