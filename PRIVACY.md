# Privacy Policy

**Last updated: 2026-07-27**

Nudge is a privacy-first, open-source app blocker — an Android app and a Chrome extension. This policy explains what data Nudge handles and how. The sections below cover the Android app; see [Chrome Extension](#chrome-extension) for the extension.

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
2. **In-app feature detection** -- identifying specific screens within an app (YouTube Shorts, Instagram Reels) so Nudge can block addictive feeds without blocking the entire app.

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

## Chrome Extension

Nudge for Chrome is a separate, sibling build to the Android app on this page, built on the
same principle: **zero network requests, no account, nothing leaves your device.** This section
covers what's specific to the extension.

### Data collected

Nudge for Chrome stores the following **locally in your browser only**, using Chrome's
`storage` API:

- **Block rules** — which sites you've configured to block and how (Hard Block, Delay,
  Breathing), including any daily time limits and schedules
- **YouTube settings** — Shorts mode, channel whitelist/blacklist entries (channel handles/IDs
  you've added), gray-screen mode, and the Unhook-style hide toggles
- **Usage rollups** — per-site daily active seconds, block counts, and walked-away counts,
  bucketed by hour, used to render the Dashboard's stats
- **Preferences** — delay durations, temporary-access length, custom block messages, Commitment
  Lock settings

### What syncs, and what never does

Chrome's built-in `storage.sync` lets your **settings** (rules, YouTube config, preferences —
never usage data) follow you to your other devices signed into the same Chrome account, the
same way your bookmarks or browsing history sync. This is Google's own Chrome Sync
infrastructure, tied to your Google account, not a Nudge server — Nudge has no server, and the
developer has no access to synced data.

**Usage rollups (screen time, block/walked-away counts) are stored in `storage.local` only and
are never eligible for sync, under any setting.** This is a fixed architectural choice, not a
toggle: usage data is written to `storage.local` exclusively.

### Permissions, explained

- **`declarativeNetRequest` + `host_permissions: <all_urls>`** — the blocking mechanism. Nudge
  matches the site you're navigating to against your rules and, if it matches, redirects to
  Nudge's own interstitial page. It does not read, log, or transmit the destination URL beyond
  this real-time match — the URL is compared to your rules and discarded.
- **`tabs`** — lets the toolbar popup know which site is open in your active tab (so it can
  offer "Block this site"), and lets Nudge redirect any open tab on a site the instant a Daily
  Time Limit is reached.
- **`storage` / `unlimitedStorage`** — where everything above is saved. `unlimitedStorage`
  exists because weeks of daily usage rollups can exceed Chrome's default storage quota; it
  does not grant access to anything beyond Nudge's own data.
- **`alarms`** — schedules the midnight reset of daily limits, the expiry of temporary access
  after a completed pause, and the daily reset of the Escape Hatch pass. No data leaves the
  device to do this.
- **`idle`** — detects when you've stepped away so screen-time stats reflect active use, not an
  idle tab left open. Nudge does not read what you were doing before going idle.
- **`scripting`** — registers one static CSS file (Gray-screen mode). No JavaScript is injected
  into any page, and no page content is read.

### What Nudge for Chrome does NOT do

- **No network requests, ever.** Not for updates, not for "anonymous" analytics, not for
  anything. If you don't trust this claim, read the code — every line is public.
- **No accounts.** No sign-up, no login, no email collection.
- **No third-party SDKs, no ad networks, no tracking pixels.**
- **No sale or transfer of data to anyone** — trivially true, since nothing is collected off
  your device in the first place.

### Data deletion

- **Remove the extension** (`chrome://extensions` → Remove), or
- Reset it in place: `chrome://extensions` → Nudge → **Extension options** → clear rules from
  the Dashboard, or use `chrome.storage.local.clear()` via the browser's own extension
  storage inspector.

There is nothing to delete on any server, because no server exists.

### Open source

Nudge for Chrome is open source under the same [GPL-3.0 license](LICENSE) as the Android app.
Read every line at [github.com/astraedus/nudge](https://github.com/astraedus/nudge)
(`extension/` directory). If you don't trust this policy, trust the code.

## Contact

Questions or concerns: [theagentthatcould@gmail.com](mailto:theagentthatcould@gmail.com)
