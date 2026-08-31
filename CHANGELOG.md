# Changelog

All notable changes to Nudge are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed
- **Blocked websites are no longer a one-shot delay.** Waiting out the delay on a blocked site used to buy you the site for as long as you stayed on it, with nothing watching and nothing counting. Now a blocked site has a session of its own: if the rule has Auto-kick's time trigger switched on, the minutes you spend on that site are measured and it sends you home the same way it does inside the app, with the same cooldown before you can go back. Each site gets its own timer and its own cooldown, so being kicked off one site never locks the rest of your browsing.
- **Turning around at a website's delay now actually turns you around.** Nudge marked the site as "passed" the moment it showed you the countdown, so tapping "I changed my mind" — or simply tabbing away before the timer finished — let the site straight through anyway. The pass is earned by finishing the exercise now, exactly like it always has been for apps.
- **Waiting out a website's delay no longer unlocks the app behind it.** Sitting through the countdown for instagram.com in your browser was granting a free pass to the Instagram app.
- **You'll no longer be blocked again in the middle of a page you already waited for.** If Nudge couldn't read a real address out of the browser's address bar for a moment — a page title, a search box, a half-typed URL — it decided you had navigated away and re-armed the block under you. It now leaves things alone when it can't tell where you are, and waits for the next reliable reading.
- **The escape hatch works on websites.** Using the 2-minute daily pass on a blocked site opened the pass on the wrong app, so the site blocked you again seconds later.

- **Auto-kick's timer now actually goes off.** "Send me home after N minutes" could sit well past its threshold and do nothing, in an app or on a website. Three things were wrong: a notification arriving (or any system pop-up) silently stopped the timer for the rest of the session, a single hiccup inside the timer ended it permanently, and the reading it measured against could jump by hours after a dropped Android event, which is also why a cooldown occasionally appeared out of nowhere. The timer now survives notifications and hiccups, and every screen-time number in Nudge is finally read the same way.

### Changed
- The rule editor is now explicit about what a Daily Time Limit covers when "Block on web too" is on: the budget counts time in the app, and once it runs out the websites are hard-blocked as well, but time on the sites doesn't count towards it yet — Auto-kick's time trigger is what limits the sites. Auto-kick says the same thing from its side: the time trigger covers the websites, the scrolls/taps trigger is app-only.

## [1.15.1] - 2026-08-31

### Fixed
- **Screen time no longer counts hours you never spent.** Today's total could reach seventeen hours by lunchtime. Nudge works out how long each app was open from Android's usage events, and when an app's "left this app" event never arrived — a crashed or killed app, an event the system simply didn't send — that app carried on counting, right through to the present moment. Several apps could be counting at once, each billed for the same minutes, so a day's bars added up to more time than the day contained. Now only one app is ever on screen at a time: the moment you open the next app is the moment the last one stopped. The screen turning off, the phone locking, and the phone shutting down all end the session too, so a phone asleep in your pocket accrues nothing. A session Nudge can only see the *start* of is capped instead of being billed to the present, so one missing event can no longer be worth a whole day.
- **The same correction reaches every screen-time number**, not just the weekly bars: the hourly heatmap, each app's detail page, and the "time reclaimed" estimate on the Willpower page were all computed the same way and were all inflated in the same direction.
- Your history is recalculated from Android's own records every time you open the stats, never stored, so the past week's charts correct themselves the moment you update — nothing to wipe, and no wrong numbers left behind.

## [1.15.0] - 2026-08-27

### Added
- **Backups now carry your settings too.** Exporting takes your custom block messages and your protection settings — the content filter, Strict Mode and its difficulty, the daily pass — along with your rules and history, so restoring gives you back the Nudge you actually set up rather than a blank one. Old backup files still import exactly as before, and new files still open in older versions of Nudge. Restoring settings that would weaken your protection asks for the Strict Mode unlock first, so a backup file can't be a way around your own commitment.
- **Your last 7 days, right on the dashboard.** Two small charts on the home screen — screen time per day, and blocks vs. walk-aways — with the week's total. Tap them to jump into the full stats page. The Screen Time tile is tappable now too.

### Fixed
- **Tapping a day in the weekly charts now actually shows you that day.** Selecting a bar used to highlight it and change nothing else; now the screen-time total, the hourly breakdown, the per-app list and the blocked/walked-away numbers all follow the day you picked, the header says which day you're looking at, and a "Back to today" chip appears whenever you've wandered off today. Same fix on each app's detail page.
- **The weekly bars and the day view can no longer disagree.** They were computed from two different Android sources, so a bar could look tall while its own day view read "0s". Both now come from one reading of the same usage data — the bar for a day and the numbers behind it are literally the same value. As a bonus, a session that runs across midnight is now counted in both days it touched instead of vanishing from both.
- **Leaving an app by pressing Home now re-arms its delay.** Completing a delay used to grant that app a pass that only ended if you opened some other app first — Home → reopen skipped the delay entirely, forever. Going home now ends the pass (pulling down the notification shade or opening the keyboard inside the app does not — those never meant you left).
- The dashboard's idea of "today" now rolls over correctly at midnight everywhere on the screen, and weekly windows land on true local midnight even across a daylight-saving change.

## [1.14.0] - 2026-08-20

### Added
- **Backups now carry your history, not just your rules.** Exporting takes your whole record — every block and every walk-away — along with your rules, so moving to a new phone (or restoring after a reset) keeps your streaks, your stats, and both insight pages intact instead of starting you back at zero. Importing merges: restoring on a phone that already has history adds only what's missing, restoring the same file twice changes nothing, and nothing is ever overwritten or doubled. Old backup files still import exactly as before, and new files still open in older versions of Nudge (they just ignore the history). One honest limit: Screen Time comes from Android itself, so that number stays with each device.

## [1.13.0] - 2026-08-20

### Added
- **Tap "Blocked" or "Walked Away" on the dashboard to see the story behind the numbers.** Two new pages, built from the block history Nudge already keeps on your phone:
  - **Walked Away → your willpower, visualized.** A ring showing how often you walk away instead of pushing through, an estimate of the time those walk-aways reclaimed, a 24-hour clock revealing when your resolve is strongest and weakest, a per-app leaderboard of what you resist vs. what still wins, and a week-over-week trend showing whether you're getting stronger.
  - **Blocked → know your temptation patterns.** Totals with a two-week sparkline, blocks by hour of day with your "danger hour" highlighted, worst days of the week, your most-blocked apps with how each is blocked, and a week-by-hour heatmap — your temptation fingerprint.
  - Both pages switch between the last 7 and 30 days. Everything is computed on your phone from data that never leaves it, like everything else in Nudge.
- The new pages also count more honestly than the dashboard tiles did: walking away used to be recorded in a way that inflated the raw blocked count, and the insight pages correct for that.

### Fixed
- **"I changed my mind" now reliably takes you home.** Tapping it raced the block screen's own dismissal, so you could land back inside the app you had just decided to walk away from. It now uses the same reliable go-home action as the rest of Nudge, records your walk-away exactly once — under a countdown, a breathing exercise, a hard block, or a cooldown — and a rare storage hiccup during that moment can no longer crash the app.

## [1.12.0] - 2026-08-20

### Fixed
- **Apps can no longer slip a video past a block by shrinking it into a floating window** ([#19](https://github.com/astraedus/nudge/issues/19), found by [@polubarev](https://github.com/polubarev)). When Nudge blocked YouTube, YouTube could drop the Short into a picture-in-picture bubble that floats above everything — including a full-screen block. Android gives no app a way to switch that off for another app, so Nudge now spots it happening and, once per app, shows you what is going on with a one-tap link to the setting that stops it (Special app access → Picture-in-picture). It won't ask twice.
- **Your blocked count no longer inflates on its own.** A video playing in one of those floating windows looked, to Nudge, exactly like you re-opening the app — so it re-blocked and counted a block over and over while you sat there watching. Those events are now recognised for what they are, and the picture-in-picture screen itself records nothing: it is neither a block you hit nor a walk-away.
- **Website blocking works with "Block the whole app" off** ([#21](https://github.com/astraedus/nudge/issues/21)). Websites were blocked using the app's own block mode, so a rule set to leave the app open, the setting that blocks just Shorts or Reels, quietly stopped blocking the websites too. "Block on web too" was simply greyed out to hide it. Now a rule's websites have their own block mode: leave the app open and still hard-block, delay or breathe on the site. Existing rules are untouched, and any rule that was silently blocking nothing on the web is repaired to a delay you can change in the editor.

## [1.11.0] - 2026-08-19

### Added
- **"Block only the parts you choose"** (contributed by [@polubarev](https://github.com/polubarev)): a new "Block the whole app" switch lets a rule block just an app's features — YouTube Shorts, Instagram Reels — without blocking the app itself. Switch it off and the rule keeps carrying your daily limit, counters and overlays while leaving the app itself alone. Requested by a user who wanted Shorts/Reels-only blocking and got both apps blocked entirely. Strict Mode treats switching whole-app blocking off as a weakening, so it's challenge-gated.

### Fixed
- **Daily time limits now actually count your time** ([#14](https://github.com/astraedus/nudge/issues/14), fixed by @polubarev). The daily budget was read from a data column nothing ever wrote, so for most rules the limit could never trigger — it only worked if the rule also showed the time-remaining overlay. Every rule with a daily limit now counts real screen time.
- **Reels opened from DMs are now blocked** (@polubarev). Instagram's reel player is detected directly rather than by which tab is selected, so a reel opened from a DM no longer plays freely under a hard block.
- **Wrong timers on back-to-back blocks** ([#15](https://github.com/astraedus/nudge/issues/15), fixed by @polubarev). Switching between two blocked apps no longer shows the new app's name over the old app's countdown with a frozen progress ring — each block gets its own fresh screen. This also closes a real bypass: a nearly-finished countdown could previously grant entry to a freshly blocked app after about a second.
- **Backups can't be destroyed by the new mode** (found in review): importing a backup containing a "whole app off" rule no longer silently discards every rule in the file.
- The "Block on web too" toggle now explains itself and disables when whole-app blocking is off, instead of appearing to protect you while enforcing nothing.
- Daily-limit checks no longer scan device usage on every app switch when no rule has a daily limit — smoother on older phones.
- **"Block restricted websites" no longer over-blocks.** The filter shipped with a huge third-party blocklist that turned out to be badly wrong about ordinary sites: a US state government portal, several universities and an international standards body were all on it — and, because blocking a domain also blocks everything under it, entries for major hosting and blogging platforms quietly blocked *every* site hosted on them. It has been replaced with a hand-curated list — a few hundred entries instead of a few hundred thousand, each one individually justifiable. If a site you use was being blocked for no good reason, this is why.
- **Ordinary words are no longer matched inside web addresses.** The filter also looked for a list of words anywhere in a URL, and a few of them are perfectly normal English — the name of a car model, a music genre, and a dessert. Searching for any of those got you a block screen. Those words are gone; the unambiguous ones stay, so genuinely explicit addresses and searches are still caught.
- Government, university and military domains can now never appear in the filter's list — enforced by a test, along with a size ceiling so a bulk list can't be dropped back in.

### Changed
- The app is about 4.5MB smaller.

## [1.10.0] - 2026-07-27

### Added
- **Auto-close by time** (requested in [#6](https://github.com/astraedus/nudge/issues/6)): a rule can now send you home after N minutes in an app, not just after N scrolls or taps. It works while you watch passively — no interaction counter needed. Set either trigger or both; whichever fires first wins, and both share the same cooldown ("use an app 30 minutes, then lock it for 15" is now a real configuration).
- **Free-form cooldowns**: auto-kick cooldowns are now entered as minutes (up to 24h) instead of a 0–5 minute slider. Existing cooldowns are preserved exactly — values that aren't a whole number of minutes display rounded up but are never rewritten unless you actually edit the field.

### Changed
- **The daily 2-minute pass is now controlled by its own Settings toggle alone.** Strict Mode no longer hides it from block screens — if you left the escape hatch on, it stays available. The toggle is also no longer greyed out while Strict Mode is on: turning it back ON now requires the unlock challenge (it re-opens a one-tap bypass), while turning it OFF is always free, like every other action that strengthens your protection.
- **Strict Mode now recognises the auto-kick settings as protection**: raising or removing either kick threshold, or shortening the cooldown, requires the unlock challenge.
- Auto-close now uses the accessibility "go home" action (with the old home-intent as fallback), which lands reliably from inside apps that previously stayed in the foreground.
- Rules that only use the time-based kick no longer switch on the floating interaction counter.

### Fixed
- Editing a rule in the rule editor no longer wipes its "Block on web too" domains.
- The daily time limit now blocks on time during passive use (e.g. video playback) instead of waiting for your next tap.

## [1.9.4] - 2026-07-27

### Fixed
- **The delay can no longer be bypassed by tabbing out and waiting** (reported in [#8](https://github.com/astraedus/nudge/issues/8)): the countdown used to keep running invisibly after you pressed Home mid-delay — wait a few seconds on the launcher and the app would open with no delay at all, and a stale overlay could linger with the timer still counting. The countdown now only runs while the block screen is actually in front of you: leaving it (Home, switching apps, screen off) abandons the attempt entirely, and coming back always starts a fresh, full delay. Breathing exercises count only the time you actually spend watching them too.
- **Re-entering an app via Recents or a notification now reliably starts the timer/block** (reported in [#7](https://github.com/astraedus/nudge/issues/7)): Android sometimes delivers no app-switch event for those re-entries, so Nudge occasionally missed them — no delay, no counter, no time-remaining overlay. Nudge now detects these returns through a verified fallback (it confirms the app really is in the foreground before acting, so the [#5](https://github.com/astraedus/nudge/issues/5) keyboard fix can't regress).

## [1.9.3] - 2026-07-27

### Fixed
- **Typing or using a paste popup no longer re-triggers the delay** (reported in [#5](https://github.com/astraedus/nudge/issues/5)): after you'd waited out a delay and were using a blocked app, opening the keyboard, or dismissing a paste / long-press popup, could make Nudge think you were re-opening the app and show the delay again. Nudge now recognises any keyboard (matched against your active keyboard, so third-party keyboards like FUTO are covered too — not just a hardcoded few) and the system pop-up/toast windows as *not* an app switch, so your pass-through survives them. Genuinely switching to a different app still re-asserts the block as before.

## [1.9.2] - 2026-07-19

### Fixed
- **Reddit is no longer blocked by the content filter**: The optional "Block restricted websites" filter was wrongly treating reddit.com (and its media domains) as restricted, so plain Reddit browsing got blocked. Reddit, and other mainstream mixed-content platforms (X/Twitter, Imgur, Discord, Tumblr, Wikipedia), are now on a permanent never-block allowlist so a future blocklist update can't re-introduce them. Genuinely explicit URLs are still caught by keyword matching regardless of the site.
- **Turning Nudge off now actually turns everything off**: Toggling the master switch off on the home screen previously left some enforcement running — an app already in an auto-kick cooldown or over its time limit could still kick you out. The switch now suppresses *all* blocking, cooldowns, counters, and overlays instantly; with Nudge off it behaves as if uninstalled. (Strict Mode still guards turning the switch off.)

### Changed
- **Daily pass is now one 2-minute pass across all apps** (was one 1-minute pass per app). Using the escape pass on any block screen gives you 2 minutes in that app and then locks the pass for every app for 24 hours. On other block screens the pass shows as a greyed-out "Daily pass used" control so you can see it exists but is spent for the day. Still hidden while Strict Mode is on, still optional in Settings → Escape Hatch.

## [1.9.1] - 2026-07-13

### Fixed
- **Block screens can no longer be bypassed by leaving and re-opening the app**: Previously, if you hit a delay/breathing/hard-block screen and then switched to Home (or Recents) and re-opened the blocked app, Nudge would sometimes let you straight in without re-showing the block. The block overlay lives in its own task, so re-entering the app could orphan it while Nudge still thought it was on screen. Nudge now detects when a blocked app returns to the foreground and re-asserts the block. Completing a delay still grants normal passthrough, so this adds no extra nagging once you've genuinely waited it out.

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
