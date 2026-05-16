# Changelog

All notable changes to Nudge are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

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
