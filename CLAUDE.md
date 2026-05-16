# Nudge — Open-Source Android App Blocker

Privacy-first app blocker with delay-to-open (breathing exercises before opening distracting apps), per-app daily time budgets, app groups, schedule-based rules, in-app feature blocking (YouTube Shorts, Instagram Reels, TikTok), and grayscale mode. Zero internet permission. All data local.

- GitHub: https://github.com/astraedus/nudge
- F-Droid MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38398
- v1.1.0 (current), v1.0.0 released

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
- **Floating interaction counter** — semi-transparent touch-through overlay showing reels/shorts scrolled or taps per session. TYPE_ACCESSIBILITY_OVERLAY from service, no extra permission. Per-rule `showCounter` toggle.
- **Post-overlay passthrough** — after delay/breathing completes, skip re-evaluation until user leaves app. Prevents infinite overlay loop.
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
- **Settings** — version links to GitHub repo, source code & feedback link

## Database

Room DB version 4. Migrations: 1->2 (schedule/inapp/grayscale), 2->3 (userChangedMind), 3->4 (showCounter).

## Counter overlay architecture

- `InteractionTracker` (@Singleton): in-memory session/daily counts per package. No DB writes per interaction.
- `CounterOverlayManager` (@Singleton): WindowManager overlay using service context (required for TYPE_ACCESSIBILITY_OVERLAY token). `setServiceContext()` called in `onServiceConnected()`.
- `activeReelLabel`: once Shorts/Reels feature detected, skip tree inspection on subsequent scrolls. Reset on app switch.
- Counter-enabled packages cached every 10s to avoid DB queries on every accessibility event.

## Store listing

Assets at `store-listing/` — feature graphic, screenshots, listing copy, batch config (`screenshots.json`).

## Backlog (next session)

### Testing debt (Codex task, HIGH PRIORITY)
- [ ] Unit tests for InteractionTracker (session/daily counts, onAppChanged reset, recordInteraction)
- [ ] Unit tests for passthrough logic (grantPassthrough, clear on app switch)
- [ ] Unit tests for counter cache (refreshCounterCacheIfNeeded)
- [ ] Unit tests for NudgeMessages (non-empty lists, randomness)
- [ ] Unit tests for DB migrations (MIGRATION_2_3, MIGRATION_3_4)

### Debug logging system (Codex task)
- [ ] NudgeLogger wrapper checking BuildConfig.DEBUG or DataStore toggle
- [ ] Logging in AccessibilityService, BlockEngine, CounterOverlayManager, InAppDetector
- [ ] Hidden developer options toggle (tap version 7x in Settings)

### v1.2 features
- [ ] Anti-bypass, NFC/QR unlock, widgets, contextual triggers
- [ ] Release signing key (currently distributing debug APK)
