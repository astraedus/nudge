# Privacy Policy

**Last updated: 2026-05-17**

Nudge is a privacy-first, open-source Android app blocker. This policy explains what data Nudge handles and how.

## The short version

Nudge has **no internet permission**. It physically cannot send data anywhere. Everything stays on your device.

## Data collected

Nudge stores the following data **locally on your device only**, using Room (SQLite) and Android DataStore:

- **Block rules** -- which apps you've configured to block and how (hard block, delay, breathing exercise)
- **App groups** -- groups you've created (e.g. "Social Media") and their members
- **Usage events** -- timestamps of when blocked apps were opened, how long you used them, and whether you walked away or continued
- **Preferences** -- your settings (delay duration, theme, etc.)

## Accessibility Service

Nudge uses Android's Accessibility Service for two things:

1. **Foreground app detection** -- knowing which app you opened (e.g. `com.instagram.android`) to evaluate block rules.
2. **In-app feature detection** -- identifying specific screens within an app (YouTube Shorts, Instagram Reels/Explore) so Nudge can block addictive feeds without blocking the entire app.

### What it reads

For foreground detection, Nudge receives the **package name** of the active app. For in-app detection, `canRetrieveWindowContent` is set to `true`, which allows Nudge to inspect the accessibility tree. Specifically, it reads:

- **UI element resource IDs** (e.g. `reel_recycler`, `clips_tab`) to identify which screen you're on
- **Element selection state** (e.g. which navigation tab is active)
- **Specific navigation labels** (e.g. the text "Shorts") as a fallback when resource IDs aren't available

### What it does NOT read

- Arbitrary text on your screen (messages, posts, search queries)
- Keystrokes or text input
- Notification content
- Any content beyond navigation elements needed for feature detection

### How this data is handled

- Processed in real-time, in memory only. Screen structure is never written to disk.
- Only the **result** is stored: which app/feature was detected, and whether it was blocked (as a usage event log entry).
- No internet permission means none of this can leave your device regardless.

## What Nudge does NOT do

- **No internet access.** The `INTERNET` permission is not declared in the manifest. Nudge cannot connect to any server, ever.
- **No analytics or telemetry.** No Firebase, no Mixpanel, no crash reporting, no usage tracking of any kind.
- **No third-party SDKs.** Nudge has zero dependencies that phone home.
- **No accounts.** No sign-up, no login, no email collection, no cloud sync.
- **No ads.** No ad networks, no tracking pixels, no monetization of your data.

## Data storage and deletion

All data is stored in your device's app-private storage. No other app can access it.

To delete all Nudge data:
- **Uninstall the app**, or
- Go to Settings > Apps > Nudge > Storage > Clear Data

There is nothing to delete on any server because no server exists.

## Open source

Nudge is open source under the [GPL-3.0 license](LICENSE). You can read every line of code at [github.com/astraedus/nudge](https://github.com/astraedus/nudge). If you don't trust this policy, trust the code.

## Contact

Questions or concerns: [theagentthatcould@gmail.com](mailto:theagentthatcould@gmail.com)
