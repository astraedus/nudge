# Nudge — Open-Source Android App Blocker

Privacy-first app blocker with delay-to-open (breathing exercises before opening distracting apps), per-app daily time budgets, app groups, schedule-based rules, in-app feature blocking (YouTube Shorts, Instagram Reels, TikTok), and grayscale mode. Zero internet permission. All data local.

- GitHub: https://github.com/astraedus/nudge
- F-Droid MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38398
- v1.1.4 (current), working toward v1.2.0
- See CHANGELOG.md for release history

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug                    # Build debug APK
./gradlew test                             # Unit tests (JVM)
./gradlew connectedAndroidTest             # Instrumented tests (needs device)
adb install -r app/build/outputs/apk/debug/app-debug.apk  # Install on device
```

Test device: Pixel 3 on ADB at `192.168.1.73:5555` (Android 12, API 31).

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
- **Rotating motivational messages** — shown on overlay screens when blocks trigger
- **"Walked Away" tracking** — counts when user taps "I changed my mind" instead of waiting
- **2x2 dashboard stats** — Screen Time, Active Rules (tappable), Blocked, Walked Away
- **Floating interaction counter** — centered touch-through overlay (40sp counter, 16sp label, 13sp daily) showing reels/shorts scrolled or taps per session. Escalating colors: white (0-9), orange (10-19), deep orange (20-29), red with red background tint (30+). TYPE_ACCESSIBILITY_OVERLAY from service, no extra permission. Per-rule `showCounter` toggle (default ON for new rules).
- **Time remaining overlay** — per-rule opt-in (`showTimeRemaining`). Displays "42m left" or "1h 12m left" below counter, color-coded: green (>50% remaining), orange (25-50%), red (<25%). Uses UsageStatsManager for actual foreground time. Requires daily limit to be set.
- **Auto-kick** — optional per-rule feature: sends user to home screen after N scrolls/taps in one session. Configurable threshold 5-100 (step 5, default 30). Session counter resets after kick. Stored as `autoKickAfter` on BlockRule.
- **Auto-kick cooldown** — configurable per-rule (0-300 seconds, default 60s). After auto-kick, returning to the app forces a DELAY overlay for the remaining cooldown. Session counter preserved during cooldown. Stored as `autoKickCooldownSeconds` on BlockRule.
- **Instagram home feed detection** — `InAppDetector` now detects Instagram's home feed (when Home tab is selected, no other tabs active) and treats it as REELS-equivalent. Home feed scrolling counts toward interaction counter and auto-kick the same as the Reels tab.
- **Post-overlay passthrough** — after delay/breathing completes, skip re-evaluation until user leaves app. Prevents infinite overlay loop.
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
- **Settings** — version links to GitHub repo, source code & feedback link

## Database

Room DB version 6. Migrations: 1->2 (schedule/inapp/grayscale), 2->3 (userChangedMind), 3->4 (showCounter), 4->5 (autoKickAfter), 5->6 (showTimeRemaining, autoKickCooldownSeconds).

## Counter overlay architecture

- `InteractionTracker` (@Singleton): in-memory session/daily counts per package. No DB writes per interaction. Also tracks cooldown state per package after auto-kick.
- `CounterOverlayManager` (@Singleton): WindowManager overlay using service context (required for TYPE_ACCESSIBILITY_OVERLAY token). `setServiceContext()` called in `onServiceConnected()`. Centered on screen with escalating colors (white -> orange -> deep orange -> red) based on session count. Also displays time-remaining line with color escalation (green/orange/red based on % remaining).
- `activeReelLabel`: once Shorts/Reels feature detected, skip tree inspection on subsequent scrolls. Reset on app switch.
- Counter-enabled packages cached every 10s via `CounterCacheRefresher` (Map<String, CounterCacheEntry> with autoKickAfter, showTimeRemaining, dailyLimitMinutes, autoKickCooldownSeconds per package).
- Auto-kick: optional per-rule threshold (`autoKickAfter`). When session count hits threshold, sends ACTION_MAIN/CATEGORY_HOME intent, sets cooldown, and resets session counter.
- Auto-kick cooldown: configurable per-rule (default 60s). After auto-kick, re-opening the app shows a DELAY overlay for the remaining cooldown. Session counter NOT reset during cooldown.
- Time remaining overlay: optional per-rule (`showTimeRemaining`). Uses UsageStatsManager to get actual foreground time, displays remaining daily limit as color-coded overlay line. Updated every 30 seconds.

## Store listing

Assets at `store-listing/` — feature graphic, screenshots, listing copy, batch config (`screenshots.json`).

## Post-feature checklist

After any feature addition or significant change:
1. Update CHANGELOG.md (under `[Unreleased]` section)
2. Update this CLAUDE.md (architecture docs, feature descriptions)
3. Run `./gradlew test` and verify all pass
4. Build debug APK and install on Pixel 3 via ADB
5. Test golden path + edge cases on real device
6. Update store listing copy if user-facing
7. Commit with descriptive message

## Backlog

### v1.2 in progress
- [x] Time remaining overlay (code-complete, verified on device)
- [x] Auto-kick cooldown (code-complete, verified on device)
- [x] Rule name on block overlays (code-complete, verified on device)
- [ ] Instagram home feed detection -- code written but AccessibilityService API doesn't expose child node `selected` state through `findAccessibilityNodeInfosByText/ViewId`. Needs tree-walk approach: traverse from `rootInActiveWindow`, find ImageView nodes with `selected=true` in bottom nav, match to parent tab. See InAppDetector.kt.
- [ ] On-device QA for all v1.2 features after home feed fix
- [ ] YouTube Shorts verification on device

### v1.3+
- [ ] Anti-bypass, NFC/QR unlock, widgets, contextual triggers
- [ ] Release signing key (currently distributing debug APK)
