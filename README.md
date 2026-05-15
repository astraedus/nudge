# Nudge

**A privacy-first, open-source app blocker for Android.**

## Why

App blockers ask for Accessibility Service permissions — the most powerful permission on Android. It can see everything on your screen. Every keystroke, every notification, every app you open.

Most app blockers are closed-source. Some have been [acquired by data intelligence companies](https://sensortower.com/blog/actiondash-stayfree-acquisition-announcement). Their privacy policies are vague at best, alarming at worst. You're trusting a black box with root-level visibility into your digital life.

That's a problem.

Meanwhile, billions of dollars have been spent engineering the perfect attention trap. Every scroll, every notification, every autoplay video is designed to fragment your focus and keep you consuming. Instagram Reels, YouTube Shorts, TikTok — they're not features, they're retention machines built on decades of behavioral psychology research.

Nudge is a small act of fighting back. It's open source, requests zero internet permissions, and keeps all your data on your device. You can read every line of code. No analytics. No telemetry. No data leaving your phone. Ever.

## What it does

- **Delay-to-open** — Before opening a blocked app, you wait through a breathing exercise or countdown timer. Not a hard lock — a moment of friction that breaks the autopilot habit loop. This is the feature no other open-source blocker has.
- **Hard blocking** — Completely block apps you don't want to access at all.
- **Daily time budgets** — Allow 30 minutes of Instagram per day, then block. You choose the limit.
- **App groups** — Create a "Social Media" group, configure once, apply to all.
- **Usage stats** — See where your screen time actually goes.
- **Zero internet permission** — Declared in the manifest. Verifiable. Not a promise — a guarantee.

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
- [ ] In-app blocking (Reels, Shorts, Explore feeds)
- [ ] Schedule-based rules (block social media 9-5 on weekdays)
- [ ] Grayscale mode
- [ ] Anti-bypass protections
- [ ] NFC/QR unlock

## License

[GPL-3.0](LICENSE). The code stays open. Fork it, improve it, share it.

## Contributing

Issues and PRs welcome. If Instagram or YouTube changes their UI and breaks in-app detection rules, that's especially useful to report.
