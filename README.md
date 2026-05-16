# Nudge

**A privacy-first, open-source app blocker for Android.**
Made by @[Antimatter543](https://github.com/Antimatter543).

Always free.
## Why

App blockers ask for Accessibility Service permissions, the most powerful permission on Android. It can see everything on your screen. Every keystroke, every notification, every app you open.

Most app blockers are closed-source. Some have been [acquired by data intelligence companies](https://sensortower.com/blog/actiondash-stayfree-acquisition-announcement). Their privacy policies are vague at best, alarming at worst. You're trusting a black box with root-level visibility into your digital life. I don't like that. I also **hate** spending money on apps that should be free.

Billions of dollars have been spent engineering the perfect attention trap. Every scroll, every notification, every autoplay video is designed to fragment your focus and keep you consuming. Instagram Reels, YouTube Shorts, TikTok  are all retention machines built on decades of behavioral psychology research.

Nudge is a 🖕 to all that. This is open source, requests zero internet permissions, and keeps all your data on your device. You can read every line of code. No analytics. No telemetry. No data leaving your phone. Ever. On principle.

## What it does

### Core

- **Delay-to-open** — Before opening a blocked app, you wait through a breathing exercise or countdown timer. Not a hard lock — a moment of friction that breaks the autopilot habit loop. This is the feature no other open-source blocker has.
- **Hard blocking** — Completely block apps you don't want to access at all.
- **Daily time budgets** — Allow 30 minutes of Instagram per day, then block. You choose the limit.
- **App groups** — Create a "Social Media" group, configure once, apply to all.
- **Motivational messages** — Rotating messages on overlay screens to reinforce your intent when a block triggers.
- **"Walked Away" tracking** — Every time you tap "I changed my mind" instead of waiting through the delay, Nudge counts it. See your wins on the dashboard.

### Schedule rules

- Block apps on specific days and times (e.g. block social media 9-5 on weekdays).
- Supports overnight schedules that span midnight.
- Per-rule day-of-week and time-of-day configuration.

### In-app feature blocking

- Block addictive feeds without blocking the whole app: **YouTube Shorts**, **Instagram Reels/Explore**, **TikTok For You**.
- Uses Accessibility Service to detect in-app navigation to these screens.

### Grayscale mode

- Force your screen to grayscale to make apps less appealing.
- Requires a one-time ADB permission grant: `adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`

### Dashboard

- 2x2 stats at a glance: **Screen Time**, **Active Rules**, **Blocked** (times blocked today), **Walked Away** (times you chose to leave).

### Privacy

- **Zero internet permission** — Declared in the manifest. Verifiable. Not a promise — a guarantee.
- All data stored locally (Room DB + DataStore). Nothing leaves your device.
- No analytics, no telemetry, no crash reporting, no third-party SDKs.
- Open source under GPL-3.0. Read the [privacy policy](PRIVACY.md).

## Install

### From GitHub Releases
Download the latest APK from [Releases](https://github.com/astraedus/nudge/releases) and sideload it.

### Build from source
```bash
git clone https://github.com/astraedus/nudge.git
cd nudge
export ANDROID_HOME=$HOME/Android/Sdk  # or your SDK path
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires: JDK 17+, Android SDK with platform 34 and build-tools 34.

## Permissions

Nudge requires three permissions. Here's exactly what each does and why:

| Permission | Why | What it sees |
|-----------|-----|-------------|
| **Accessibility Service** | Detects which app is in the foreground to trigger block rules | Package name of the foreground app. `canRetrieveWindowContent` is set to `false` — Nudge cannot read your screen content. |
| **Display Over Other Apps** | Shows the block/delay overlay on top of blocked apps | Nothing. It's a display permission. |
| **Usage Stats** | Tracks your daily screen time per app for time budgets | App usage durations. Stored locally in a Room database on your device. |

No internet permission. No camera. No contacts. No location. No storage.

## Tech stack

- Kotlin + Jetpack Compose + Material 3
- Hilt (dependency injection), Room (local database), DataStore (preferences)
- Coroutines + Flow for reactive data
- Clean Architecture: `domain/` has zero Android imports — fully unit-testable on JVM
- Min SDK 26 (Android 8.0), Target SDK 34

## How the blocking works

```
AccessibilityService detects foreground app change
  → BlockEngine evaluates rules for that app
  → Decision: ALLOW | HARD_BLOCK | DELAY | BREATHING
  → If blocked: full-screen overlay appears
  → User can wait through the timer, or go back home
```

The delay/breathing modes are the core idea. They don't lock you out — they insert a moment of intentional friction. Research shows this small pause is enough to break the automatic habit loop that drives most mindless phone usage.

## Running tests

```bash
./gradlew test                    # Unit tests (JVM, no device needed)
./gradlew connectedAndroidTest    # Instrumented tests (needs connected device)
```

## Roadmap

- [x] App blocking (hard block, delay, breathing)
- [x] Per-app daily time budgets
- [x] App groups
- [x] Usage stats dashboard
- [x] Rotating motivational messages
- [x] "Walked Away" tracking
- [x] In-app blocking (Reels, Shorts, Explore feeds)
- [x] Schedule-based rules (day-of-week + time-of-day, overnight support)
- [x] Grayscale mode (ADB permission required)
- [x] 2x2 dashboard stats
- [ ] Anti-bypass protections
- [ ] NFC/QR unlock
- [ ] Widget support
- [ ] Export/import settings

## License

[GPL-3.0](LICENSE). The code stays open. Fork it, improve it, share it.

## Contributing

Issues and PRs welcome. If Instagram or YouTube changes their UI and breaks in-app detection rules, that's especially useful to report.
