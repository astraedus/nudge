# Privacy Policy

**Last updated: 2026-05-16**

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

Nudge uses Android's Accessibility Service to detect which app is in the foreground. This is the only way Android allows an app to know what's currently on screen.

- Nudge requests `canRetrieveWindowContent = false` -- it **cannot** read your screen content, text, or keystrokes
- It only receives the **package name** of the foreground app (e.g. `com.instagram.android`)
- This data is processed in real-time to evaluate block rules. It is never stored beyond usage event logs.

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
