# Rule capabilities and in-app feature detection

Covers what a *rule* can express beyond a plain block: schedules, in-app feature blocking
(Shorts/Reels/TikTok), grayscale, the user-editable overlay message pools, and the rule editor.
**Read before touching `domain/` rule models, `InAppDetector`, `NudgeMessages`, the rule editor, or the Settings screen.**

## Capabilities

- **Schedule-based rules** — day-of-week + time-of-day, overnight schedule support (spans midnight), or "for N minutes after your first unlock" with the logical day starting at a configurable hour (default 4:00 AM)
- **In-app feature blocking** — YouTube Shorts, Instagram Reels, TikTok detection via AccessibilityService. Instagram Reels is detected by the reel player's container (`INSTAGRAM_CLIPS_VIEWER_IDS`), which covers every entry route. Home-feed and Explore detection (`Feature.EXPLORE`, the `feed_tab`/`search_tab` branches of `InAppDetector.detectInstagram`) read the bottom tab's selected state (the tab node, a child or a grandchild), so they depend on the device and Instagram build: they work where that state is exposed and never fire where every tab reads null (see the Instagram home feed entry below)
- **Grayscale mode** — force screen to grayscale (requires ADB: `adb shell pm grant dev.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`). Grayscale guide in Settings.
- **Rotating motivational messages** — shown on overlay screens when blocks trigger. **User-editable (v1.6.0)**: defaults live in `ui/overlay/NudgeMessages.kt` (delayTitles/delaySubtitles/hardBlockMessages); users override via Settings → Personalize → "Edit block messages" (`ui/screens/settings/MessagesEditorScreen.kt`), stored as 3 multiline strings in `NudgePreferences` (`customDelayTitles`/`customDelaySubtitles`/`customHardBlockMessages`, one message per line, empty = defaults). `NudgeMessages.resolvePool(customRaw, default)` is the pure resolver; `BlockOverlayActivity` reads the prefs once via `runBlocking{ first() }` before `setContent` (avoids a default→custom flash) and passes resolved pools into the overlay composables (which still `remember { pool.random() }`).
- **Instagram home feed / Explore detection (issue #18): works only where Instagram's tab state is readable.** `InAppDetector.detectInstagram` → `findActiveInstagramTab` → `isTabActive` checks `feed_tab`, `clips_tab`, `search_tab` and `profile_tab`, and counts a tab as active if the tab node, a child or a grandchild `isSelected`. In the committed `home.xml` / `coldstart.xml` fixtures the `feed_tab` FrameLayout is `selected=false` but its child `tab_icon` is `selected=true`, so detection works on that build. Observed on a bench Pixel 3 (Nudge v1.18.0, 2026-09-24): `instagram active tab: feed_tab` → `feature=REELS` → `HARD_BLOCK`. `search_tab` has the same one-child shape, so Explore (`Feature.EXPLORE`) is INFERRED to work from the shared mechanism; it has not been observed. `feed_tab` → REELS is deliberate (commit 8cd0054, CHANGELOG 1.2): scrolling the Home feed is treated as reels-equivalent. On a Galaxy S24 / Android 16 (PR #17, ~800 attempts) every tab read null, so tab detection (Explore, Home-as-Reels) never fires there; only the reel-player container check works. `InteractionHandler.noteDetectedFeature` maps EXPLORE to no label, so Explore has no per-feature handling beyond the block. Home-feed *identification* for the Following steer is separate: `InstagramSurfaces.classify` uses `title_logo` / `sticky_header_list`, not tab selection.
- **Redirect app (v1.20.0)** — ONE global "better app" (a to-do list, Wikipedia) offered as a bubble on every
  block screen; a tap is a walk-away into that app. Set from the bubble (long-press to change/remove) or from
  Settings → Personalize → "Redirect app". Never an app with an enabled rule or on the Nuke list. Design, the
  picker's back-gesture safety and the stale-choice fallback: `block-overlay-lifecycle.md`, "The redirect app".
- **Keep the pass on short trips (v1.23.0, issue [#110](https://github.com/astraedus/nudge/issues/110))**: a per-rule
  switch, off by default, shown in the app's rule editor only for the modes that grant a pass (Delay, Hold,
  Breathing; never Hard Block). On, a trip to the home screen or a gesture swipe through the launcher starts the
  2-minute away clock instead of ending the pass, so coming straight back opens the app without a second wait.
  `BlockRule.keepPassOnShortTrips` (DB 12->13), read off the rule that DECIDED the block like `tabVanish`, carried by
  the grant into the sitting. Off is the shipped behaviour exactly. Mechanism, the device capture and why it is
  per rule: `accessibility-event-pipeline.md`, "Per-rule opt-in: a launcher window as an ABSENCE".
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable.
  `UnifiedAppConfigScreen` is the only per-app editor (the old `RuleEditorScreen` was deleted). Its reusable, stateless
  pieces live in `ui/components/rule/`: `InfoButton`, `TimeSelector`, `BlockModeSegmentedRow` (+ `BLOCKING_MODES`),
  `WaitTimeChips`, `ScheduleSection` (whose days-and-times half is `DaysAndTimesFields`), `DailyLimitChips`, and
  `WhenSection` (the three-way When, with the first-unlock fields and the day-start row). The group and All apps
  editors reuse them; `DeadRuleEditorContractTest` pins it.
- **Groups (spec PR 2)**: a named set of apps blocked by ONE rule of its own (`BlockRule.groupId`, `packageName = null`,
  no `inAppFeatures`; `ownGroupRule` in `ui/screens/groups/GroupForm.kt` is the one definition of "the group's rule",
  shared by the editor, the Manage Apps switch and "Also covered by"). See "Groups" below.
- **Settings** — version links to GitHub repo, source code & feedback link
- **Tab Vanish (v1.18.0)** — while a rule covering a host app's feature resolves to a HARD block, Nudge draws a
  touchable cover exactly over that feature's bottom-nav tab, so the icon is not there and the tap does nothing.
  Per-rule, **default ON**. Instagram Reels is the one implementation. See "Tab Vanish and the platform-surface
  adapter" below.
- **Following steer (v1.18.0, experimental)** — per-rule opt-in, **default OFF**: on arrival at Instagram's home
  feed, Nudge clicks the title dropdown and then "Following", so the user lands on people they follow instead of
  suggested posts. The first thing Nudge does *inside* another app. See below.

## Tab Vanish and the platform-surface adapter

### The problem, and what is actually possible

An `AccessibilityService` cannot edit or remove another app's views — `AccessibilityNodeInfo` is immutable once
delivered. Every OSS Reels blocker therefore does detect-then-BACK, with a visible flicker; Nudge does
detect-then-overlay, which is better but still lands *after* the tap. What we CAN do is draw our own window over
the tab's screen rectangle. `TYPE_ACCESSIBILITY_OVERLAY` (the type the awareness overlays already use) needs no
extra permission and is a *trusted* window — Android 12's untrusted-touch blocking explicitly exempts
accessibility overlays. Drop `FLAG_NOT_TOUCHABLE` and it eats the tap; paint it the nav-bar colour and the icon
is gone.

It is deliberately NOT a complete wall. Reels still open from Explore tiles, profile tabs, DM shares and inline
feed cards, and the existing `clips_viewer_*` player detection stays exactly as it was as the backstop. Tab Vanish
is an ADDITIONAL enforcement, never a replacement.

### Where each decision lives

| Question | Owner |
|---|---|
| Which nodes describe this app's surfaces | `domain/surfaces/PlatformSurfaces` + `InstagramSurfaces` (pure data) |
| Is a cover wanted right now | `BlockDecision.Block.tabVanish`, set from the rule that DECIDED the block |
| Where to draw it | `TabCoverPlacement.of(bounds)` (pure; null for degenerate bounds) |
| Show, move, hide or leave alone | `TabCoverDecider` (pure) |
| Is the cover still valid at all | `TabCoverPresence.shouldKeep(signal, coveredPackage)` (pure, exhaustive over `ForegroundSignal`) |
| The window itself | `service/TabCoverOverlayManager` |
| Turning a locator into a node | `service/HostNodeFinder` — the only place that does node lookups for this feature |

Three constraints that are not obvious and are each a bug already paid for elsewhere in this repo:

- **The cover's views are `AwarenessOverlayWindow` types, never bare ones.** A `TYPE_ACCESSIBILITY_OVERLAY` owned
  by Nudge fires window and content events carrying OUR package. Without the identity class name the
  `EventClassifier` reads them as `OwnUi`, `BlockLaunchGate` moves the foreground to Nudge, and every later block
  is dropped with `DROP_FOREGROUND_MOVED` — so a cover meant to add enforcement would silently remove it. That is
  [#41](https://github.com/astraedus/nudge/issues/41) with a new window; `AwarenessOverlayContractTest` covers the
  new manager for exactly that reason.
- **Teardown happens in ONE place — `applyForegroundSignal`.** The cover is that method's third consumer, beside
  the sitting and the launch guard, and for the same reason: it sits above every early return in
  `onAccessibilityEvent`. Hiding from the six branches that each mean "the user is somewhere else" is the shape
  that produced #5, #7, #19 and #28, each of which was a path that returned before something that had to happen.
  `TabCoverPresence` is exhaustive over `ForegroundSignal` with no `else`, so a new signal forces a decision.
  `AwarenessOverlay` must KEEP the cover: the cover is itself an awareness overlay, so tearing down on that
  signal would have it order itself away the moment it appeared.
- **A tap the cover eats is NOT a confrontation.** No `UsageEvent`, no `wasBlocked` row, no arrival claim. The
  user never saw a block screen, and counting it would inflate the Blocked count the way
  [#36](https://github.com/astraedus/nudge/issues/36) did. `TabCoverCountingContractTest` reads the manager's
  source and forbids the whole vocabulary.

### Which modes vanish the tab

Only a decision that resolves to `HARD_BLOCK`: an unconditional hard block, or a daily budget spent on a
`BlockMode.NONE` + `dailyLimitMinutes` rule — Anti's headline behaviour, *"once you've done your daily timer it'll
just disappear"*. DELAY, HOLD and BREATHING deliberately do NOT vanish the tab: those modes are friction with a
choice at the end of it, and the tab has to stay tappable to reach the interstitial.

The flag is read off the rule that DECIDED the block, not `applicableRules.any { it.tabVanish }`. With `any {}` a
user who switched the cover off on their Reels rule would still get it from some unrelated rule still carrying the
default `true`.

## Following steer (experimental)

Instagram has a real chronological Following feed, but it resets to Home on every cold start and Meta has said it
will never be the default. We cannot filter the algorithmic feed — no view removal, and covering scrolling cards
is not viable — so the steer navigates: click the title dropdown, then "Following".

Measured on Instagram 447.0.0.55.81 (Pixel 3, fixtures in `app/src/test/resources/surface-fixtures/`):

- `title_logo` is NOT clickable; its clickable parent `action_bar_title_view` is. `HostNodeFinder.findClickable`
  walks up to the nearest clickable ancestor for exactly this reason, and the same walk handles the menu item,
  whose label TextView sits inside the clickable `context_menu_item` Button.
- Following is a **separate full-screen activity**, not a filter on the Home tab: the header becomes
  `action_bar_title` reading "Following", and `tab_bar` / `clips_tab` are gone from the tree entirely. A useful
  side effect — the Reels tab is unreachable from the Following screen — and the reason the cover must hide
  whenever `clips_tab` is not found.
- **Deep links are dead.** `https://www.instagram.com/?variant=following` is claimed by the app but the parameter
  is ignored, and `instagram://feed?variant=following` does not resolve at all. The `variant-link.xml` and
  `scheme-link.xml` fixtures are the recorded proof, asserted as landing on `HOME_FEED`. That is what makes the
  click sequence the only option, not a preference.

`FollowingSteer` is a pure state machine with the clock injected. One attempt per home-feed arrival, a bounded
wait for the menu, then give up silently. Observing the Following screen marks the arrival attempted, so a user
who backs out of Following to Home on purpose is NOT steered again until they leave Instagram or visit another
tab. Anything unrecognised inside Instagram (the reel player, a story, a DM thread) changes nothing at all.

### The visit lifecycle: two resets, and only two

The steer's "already steered this arrival" memory is cleared by exactly two things, and which is which matters.

1. **Another tab**, inside Instagram, decided by the steer itself from `HostSurface.OTHER_TAB`. A real
   navigation away from the feed; the next return to Home is a fresh arrival. The sitting never hears about
   it — it is one app throughout.
2. **A departure**, decided by nobody in this file. `NudgeAccessibilityService.onSittingEnded` calls
   `resetHostAppActuation`, so the steer's verdict is literally the same object's verdict that revokes a
   completed delay: `SittingTracker`'s. Home ends a visit at once; another app in front, or a dark screen, ends
   it only past `PASSTHROUGH_RETURN_WINDOW_MS`.

The reset used to live in `hideAllOverlays`, which the `ACTION_SCREEN_OFF` receiver calls
([#56](https://github.com/astraedus/nudge/issues/56)). That is the premise
[#54](https://github.com/astraedus/nudge/issues/54) had already removed from the sitting and the arrival:
Android blanks the display on lack of INPUT, not lack of attention, and the Pixel default is 30 seconds — so
reading the Home feed you deliberately switched back to, letting it time out and tapping it back on cost you two
synthetic taps out of that feed. Only the LENGTH of an absence can tell a display timeout from putting the phone
down, and one object already measures it.

**There is deliberately no third rule and no second timer.** #28, #36, #54 and #56 were all one subsystem
holding two definitions of "the user left", so a new reason to forget a visit has to become a new
`SittingEndCause` rather than a new listener. `FollowingSteerVisitWiringContractTest` pins the set of reasons;
`FollowingSteerVisitLifecycleTest` drives the real `SittingTracker` against the real `FollowingSteer` and carries
the pre-fix wire as a counterfactual. Neither can see the device, and there is still no `a11y-capture` of a steer
session — recording one needs a bench device with Instagram signed in.

`SyntheticClickWindow` (the "was that click ours?" suppressor for the counter) is cleared alongside the steer,
purely because it is the same kind of state — a fact about what Nudge just did inside someone else's app. Its
window is one second and expires on its own, so where it is reset changes nothing observable.

## Adding another app

`PlatformSurfacesRegistry.all` is the one registration point. An app needs: its package name, a
`vanishableTabs` entry per feature, a `SteerRecipe` if it has a following-style feed, a nav-bar colour per night
mode, and a `classify` that maps node presence to a `HostSurface`. Nothing in `service/` may name a host app's
view ids — `InstagramIdsLiveInOneFileTest` enforces that, with `InAppDetector` grandfathered as the one legacy
holder.

**Roadmap — YouTube Shorts tab vanish + Subscriptions steer: same adapter, needs YT selectors.** The Shorts tab
node and the Subscriptions tab are both stable enough; what is missing is a bench capture of the Shorts tab's
bounds and the Subscriptions nav item, plus a decision on the Shorts shelf inside Subscriptions (it scrolls, so
the cover trick does not apply there). TikTok is a third candidate, unmeasured.

## Groups (spec PR 2)

Spec: `docs/exec-plans/active/groups-categories-allowlist.md`, sections 3.3, 3.6, 3.7, 5.5, 5.6.

| Piece | Where | What it owns |
|---|---|---|
| Editor | `ui/screens/groups/GroupEditorScreen` + `GroupEditorViewModel` (route `group_editor?groupId=`; none or <= 0 = new) | Name, apps, then "Block these apps" heading How (mode, wait, daily limit for each app) and When (All the time / Days and times). Off folds How and When away and saves the rule with `enabled = false`, so the values come back. Back and the top bar's arrow both go through `requestLeave`: "Discard changes?" (Discard / Keep editing) only when `GroupEditorState.isDirty`, i.e. the draft differs from the `baseline` the editor opened with (the saved group, or the blank new-group form), so an edit undone by hand leaves without asking (`GroupEditorDiscardTest`). "Allowed during" came in PR 4, category chips in PR 6 (see "Category groups" below). |
| The form | `ui/screens/groups/GroupForm.kt` (pure) | `GroupRuleForm.from(rule)` / `toRule(existing)`: `toRule` COPIES the saved row, so fields the editor does not show (auto-kick, counter, reminder...) survive a save. `GroupMembersDraft.afterPick` keeps members the picker could not show (essential, uninstalled): a member is only ever removed where the user can see it go (`membersAfterPick` does the same for the All apps editor's allowed apps). |
| Save / delete | `BlockRuleRepository.saveGroup` / `deleteGroupWithRules` -> `AppGroupDao` `@Transaction`s | Group, member set and own rule in one write; delete removes rules, members, then the group. |
| Picker | `ui/components/AppPicker` | Full-screen multi-select over installed apps minus the essentials (spec 5.4). |
| Manage Apps | `buildGroupRows` (pure, `ManageAppsSections.kt`) + `AppListScreen` | "Groups" rows above the apps: `Social · 9 apps`, the rule's summary in the same words as an app's (`RuleSummary.groupSummaryText`), and a switch. Since PR 3 Manage Apps is the one list; see "Manage Apps: one list (spec PR 3)" below. "Block several apps" opens a new group (hidden while searching). Search matches group names. **Everything above the apps is ONE list row** (`ManageAppsRow.Top`, key `"top"`, always first; `manageAppsRows` is pure and pinned by `ManageAppsRowsTest`): the LazyColumn keeps its first visible row in place by key, and the groups arrive after the first frame, so when each group was its own row they were inserted above "Block several apps" and so above the viewport, and Manage Apps opened with no Groups section (PR 2 device QA, from Home and after a save). Late groups now grow the top row in place. The list also waits for the first state that holds the groups (`isLoading`), and a group just saved in the editor scrolls back to the top (`AppListViewModel.newlyAddedGroupId`); an ordinary open never scrolls. Never split the top section back into separate lazy items. |
| "Also covered by" | `ui/screens/config/CoveringGroups.kt` (pure) + `UnifiedAppConfigViewModel.coveredBy` | On App config, under Enabled, only when a group containing the app has its own rule ON. Read-only; a row opens the group. |
| Rule name on the block screen | `buildRuleName` | A group rule's block names the group. |
| Browsers | engine (spec 5.5) | A browser covered by a group is evaluated as an app first; see `web-domain-blocking.md`. |

Strict Mode for all of this is in `strict-mode.md`, "Groups". There is no migration: groups and `BlockRule.groupId` have
existed since DB v1, the engine already honoured them, and only the UI was missing. The old `GroupScreen` (unreachable
from any navigation) is deleted.

## Category groups (spec PR 6)

Spec sections 3.3, 5.2, 5.5, 5.6. A group can be built from Android's app categories as well as by hand.

**Membership is resolved, never stored** (spec 5.2):

```
member(p, g) = !essential(p) && !(p in g.excludes)
               && (p in g.includes || (!browser(p) && category(p) in g.categories))
```

Precedence: an essential is never a member; "Removed by you" beats a category; an app added by hand or in a checked
category is a member. `AppCategory.UNDEFINED` never matches (a category-less app joins only by hand). A browser never
joins by category, only by hand (below). Nothing is written per installed app, so an app installed later joins by
itself, and its first open is blocked.

| Piece | Where | What it owns |
|---|---|---|
| Definition | `domain/groups/GroupDefinition` (`id`, `categories`, `includes`, `excludes`), `OFFERED_CATEGORIES`, `AppCategoryCsv` | The group as the user wrote it. The categories offered are SOCIAL, VIDEO, GAME, NEWS, AUDIO, IMAGE, MAPS, PRODUCTIVITY, in that order; never UNDEFINED. `AppCategoryCsv` is the one reader/writer of `app_groups.categories` (unknown names and UNDEFINED skipped, written in offered order, NULL for none). |
| Rows | `app_groups.categories` (TEXT NULL), `app_group_members.excluded` (0 = "Add apps", 1 = "Removed by you"), DB 14 -> 15 | The PK `(groupId, packageName)` means an app is never both. `AppGroupDao.saveGroupWithRule(groupId, name, categories, includes, excludes, rule)` diffs rows with `memberRowChanges` (a flip is one write, not delete + add). `getGroupsForPackage` reads include rows only. |
| The one answer | `domain/groups/GroupMembershipResolver`, built from rows by `groupResolverOf` (`data/repository/GroupDefinitions.kt`) | Every reader asks it: the engine (through `RuleSnapshot.membership`), the browser check, Manage Apps counts and "In Social" lines, App config's "Also covered by", the All apps editor's counts, Home's "N apps blocked", the redirect-app exclusions, the group editor, Strict Mode and the backup. A member row is not a member, so nothing lists rows as members any more. `groupIdsOf(pkg, category, browser)` does not apply essentials: `RuleCoverage` applies them per rule kind. Off the engine, `data/repository/DeviceGroupMembers` is the one place that resolves groups against this phone (installed apps, essentials, browsers, read only when a group checks a category); the redirect app and the All apps block screen both use it. |
| Hot path | `EvaluateBlockUseCase.coverageFacts`, `NudgeAccessibilityService.isCoveredWholeNow` | The category is read only when an enabled rule targets a category group (`RuleCoverage.categoryNeeded`), from the cached `PackageFacts`, and the same fact gives launchable. `browser` is `pkg in EssentialPackages.browsers()` (cached with the essentials). The synchronous browser check (`isCoveredWholeNow`) asks about a browser, so it passes `browser = true` and no category: only a hand-added row can cover it, and it reads no category at all. No PackageManager call is added to the accessibility path. |
| Editor | `ui/screens/groups/GroupMembersDraft` (pure) + `GroupEditorScreen` | Under "Apps · N": FilterChips for EVERY offered category, titled by Nudge's own `categoryTitle` (`ui/components/CategoryTitle.kt`, pure and exhaustive: "Social & Communication", "Music & Audio", ...; Android's `getCategoryTitle` said "Music and audio", the odd one out) with the count of apps it brings in (installed, minus essentials, minus browsers, minus removed). A category with nothing installed shows 0 and is still offered, so "Games" can be checked before the first game is installed (the first build hid empty chips; device QA showed that made pre-blocking impossible and made a chip vanish once all its apps were removed). "New apps in these categories join automatically." only while one is checked. A category member shows its category's SHORT name as trailing text (`shortCategoryTitle`: "Social & Communication" is "Social"; the full title ellipsised on every row). Its ✕ moves it to "Removed by you" (an app in by hand only, a browser included, is just dropped); that section is collapsed by default, lists only removed apps a checked category would bring in, and has "Add back" per row. Picking a removed app under "Add apps" restores it. Save needs a name and at least a category or a member. |
| Strict Mode | `groupSaveWeakens` / `groupDeleteWeakens` over `GroupDefinition`s | Judged on the members RESOLVED on this device, so unchecking a category with 0 apps here is free and one with 5 is five apps leaving. See `strict-mode.md`. |
| Package changes | the one runtime receiver in `AndroidPackageFacts` | Invalidates the package's fact and refreshes `InstalledAppsRepository`, so counts and the editor follow installs and removals. No second receiver. See `rule-snapshot.md`. |

**What a category is.** `ApplicationInfo.category`, set by the developer in the manifest; Play-installed apps carry it.
On the bench Pixel 3 (48 launchable apps): Social 11, Productivity 17, none 14, Image 2, Video 2, Audio 1, Maps 1. The
apps with none were sideloaded, dev or system apps, so there is no hand-kept table of known apps (it would be a second
source of truth that rots). Some apps file themselves surprisingly: Chrome, Gmail and Contacts say Social; LinkedIn and
Firefox say Productivity. "Removed by you" is the answer for the apps.

**A browser never joins by category.** A browser covered by a blocking group is blocked whole, as an app, on every site
(spec 5.5), so a category match on one would make checking "Social" (Chrome) or "Productivity" (Firefox) silently
hard-block the browser. `GroupMembershipResolver.matchesCategory` is false for a browser; adding it by hand under "Add
apps" still works exactly as before. "Browser" is the one list, `EssentialPackages.browsers()` = `EssentialApps`'s
`KNOWN_BROWSERS` (the URL-bar table) plus the apps that open a web link on this phone: the same set that keeps browsers
out of the essentials, never a second list. Tests: `GroupMembershipResolverTest` (L1),
`EvaluateBlockCategoryBrowserTest` (L3, through the real use case), `GroupMembersDraftTest` (chips, ✕).

**The All apps block screen lists what the engine allows.** `AllAppsBlock.allowedPackages` is what the engine can name
without the device (the rule's own apps, then each allowed group's hand-added apps); the service also sends
`AllAppsBlock.exceptGroupIds`, and the overlay fills in the category members through `DeviceGroupMembers` and the same
`AllAppsBlock.allowedPackagesOf`, off the main thread, after the first frame. A failure keeps the first frame's icons and
never touches the block. `AllAppsBlockTest` cross-checks the list against `RuleCoverage`: listed exactly when not covered.

## First unlock (spec PR 5b, section 5.3)

A rule can be on for N minutes after the first unlock of the user's logical day, instead of on clock times. It is the
third choice under "When" (`ui/components/rule/WhenSection.kt`, `ScheduleKind`), next to "All the time" and "Days and
times", and it appears in all three editors: the group editor, the All apps editor and App config's Scheduled Override
(where it is the second segment of `[Days and times | After first unlock]`). The days row is shared and means the
logical day's weekday. Morning Lock is one such rule: all apps except Spotify and Maps, hard block, 30 minutes.

| Piece | Where | What it owns |
|---|---|---|
| `FirstUnlockWindow` | `domain/engine/FirstUnlockWindow.kt` | The whole mechanism, pure: `logicalDayStart`, `logicalDayIso`, `isActive`, `endsAt`, `shouldStamp`, `startsAtNextUnlock`. `end = min(stamp + minutes, next logical day start)`, so "Until" agrees with when the window really shuts. |
| The anchor | `NudgeAccessibilityService` | `ACTION_USER_PRESENT`, runtime-registered beside `screenOffReceiver`, plus the fallback: the first `ForegroundSignal.AppWindow` of the logical day while `KeyguardManager.isDeviceLocked` is false. Whichever comes first stamps, through `FirstUnlockStateCache.record` (in memory first, DataStore after, because the app opened straight after an unlock is the one the window is for). Both sites ask `shouldStamp`, so a second unlock cannot restart the window. PR 5a was the log-only spike; the log line is still there (`first-unlock evidence source=... screenOn=... keyguardLocked=... secureLock=... sinceBootMs=...`). |
| Data model | `BlockRule.firstUnlockMinutes` / `armedAt` (DB 15->16), `NudgePreferences.day_start_minute` (240) / `first_unlock_at` | `firstUnlockMinutes` non-null makes it a first-unlock rule and ignores `scheduleStartMinute`/`scheduleEndMinute`. `armedAt` is wall-clock ms of the last enabled write; the repository stamps it on every save and switch-on, so a rule saved at 08:05 waits for tomorrow instead of locking the phone mid-setup (owner decision 2). `first_unlock_at` is device-local and never exported. |
| The day start | `SetDayStartMinuteUseCase` + `DayStartRow` | One global setting, shown only where a first-unlock rule is made, always the same value. Any change is a Strict Mode challenge while an enabled first-unlock rule exists (spec 5.6). Stored 0..1439. |
| The sentence | `startsAtNextUnlock` in each editor's state | "Starts at your next first unlock." appears when today's unlock is already recorded, because saving arms after it. |
| Summary | `RuleSummary.scheduleText` | "Hard Block 30min after first unlock on Mon–Fri": duration first, days last, no clock times (the window starts at the user's own unlock). |

Deliberate readings: a stamp in the future (the clock moved back) is discarded and replaced on the next unlock; a
clock jump forward ends the window early; a time-zone change mid-window keeps the absolute end but can make the stamp
stale in the new zone's logical day, which allows apps rather than blocks them; a window opened before the day start
ends at the boundary, because the new day has no unlock yet. The full matrix, with explicit `ZoneId`s, is
`FirstUnlockWindowTest` and `ScheduleEvaluatorFirstUnlockTest`.

## Manage Apps: one list (spec PR 3)

Spec: `docs/exec-plans/active/groups-categories-allowlist.md`, sections 3.1, 3.2. Active Rules and Manage Apps were two
lists for one job, with switches that meant different things; Active Rules is deleted and Manage Apps is the list.

| Piece | Where | What it owns |
|---|---|---|
| Sections | `buildManageAppsSections` (pure, `ui/screens/apps/ManageAppsSections.kt`) | In order, each only when non-empty: Groups, the "Block several apps" row (hidden while searching), "Apps with rules" (apps with a DIRECT rule, `packageName != null && groupId == null`, by name), then the rest under "Other apps", or under "Apps" on a phone with no groups and no app rules. Search (trimmed, case-insensitive) filters every section: groups by name, apps by name or package. The heading is decided by what exists, not by what the search left, so it does not flip mid-search. "No apps or groups match" shows only when `nothingMatches` (no group and no app), not when the app sections alone are empty: the groups live inside the top row. A group's rule never lands on an app row: a group member with no rule of its own is an "other" app, whose second line names the groups that block it (`RuleSummary.coveredByText`: `In Social`, `In Social, Work`, `In 3 groups`), from `blockingGroupsOf` (`ui/screens/config/CoveringGroups.kt`), the same resolver as App config's "Also covered by": member, and the group's own rule is on. Its switch still means "give this app a rule of its own". **"All apps" (PR 4)**: its rows sit in the same top row, above Groups; see "All apps in the screens" below. Pinned by `ManageAppsSectionsTest` (empty, typical, heavy, search by group, search by app). |
| Rows | `manageAppsRows` (pure, `ManageAppsRows.kt`) | `Top` (key `"top"`, always first) + a header and the apps per section. Header keys contain no dot, so they can never equal a package name (`ManageAppsRowsTest`). |
| Rules for apps not on the launcher | `AppListViewModel.extraApps` | A rule whose package the launcher list lacks (uninstalled, or launcher-less) is still listed, named by `resolveAppName`, so deleting Active Rules hides no rule the engine applies. |
| One summary format | `ui/components/rule/RuleSummary` (pure) | `buildSummaryText`: per rule, mode (a `NONE` rule with a limit says only the limit), then `Nmin limit`; the rule's schedule text follows its FIRST piece with a plain space (`Hard Block Sat, Sun`, `30min limit Sat, Sun`), so ` · ` only ever separates rules and limits and a schedule can never read as a rule of its own (`Delay 30s · Hard Block Sat, Sun`, not `Delay 30s · Hard Block · Sat, Sun`); unscheduled app rules first, then scheduled, then feature rules (`Reels: Hard Block`), joined ` · `. `scheduleText`: day runs of 3+ compress (`Mon–Fri`), shorter sets list (`Sat, Sun`), times `9:00–17:00`, a time with no days reads `Every day 9:00–17:00`, and all the time adds nothing. A half-set window (start without end) is not a window, the same as `ScheduleEvaluator`. Used by app rows, group rows and "Also covered by" (`BuildSummaryTextTest`, `ScheduleTextTest`). |
| Switches | `AppListViewModel.toggleApp` / `toggleGroup` | An app row is on when ANY of its direct rules is on. Off is a weakening: Strict Mode gated from every section ("Turn off blocking for this app"), then every rule naming the package off (`setEnabledForPackage`). On is free. A row with no rule creates Delay 15s, once (a double tap does not make two rules), and **stays where it is** for the rest of the visit: `AppListViewModel.switchedOnHere` feeds `keepInPlace`, so the row keeps its place among the other apps and just gains its summary; it moves up to "Apps with rules" the next time Manage Apps opens. Moving it at once slid the next app under the finger, and a quick second tap switched that app on (device QA, PR 3). A rule made anywhere else still moves up at once. Pinned by `AppListViewModelStrictModeTest`, `SwitchedOnStaysInPlaceTest` and `ManageAppsSectionsTest`. |
| Home tile | `ActiveAppsCount.kt` + `domain/engine/CoveredApps` | "Active Apps" opens Manage Apps; the number is the apps an enabled rule covers, via the engine's resolver (see `stats-and-charts.md`). |

Backup is reached from Settings only (`BackupEntryPointsContractTest`).

## All apps in the screens (spec PR 4, sections 3.2, 3.3, 3.7, 3.8)

An "All apps except" rule (`BlockRule.allApps`) is one more kind of row on the screens that already list groups.
Every screen builds its row from ONE pure builder, `allAppsRuleItems` (`ui/components/rule/AllAppsRuleItems.kt`), so a
rule never reads two ways: the title is `RuleSummary.allAppsTitle` (allowed groups first, then installed allowed apps;
`All apps`, `All apps except Spotify`, `... except Work, Spotify +3`), the second line is `RuleSummary.allAppsSummaryText`
(mode, then schedule, the same words as every rule).

| Piece | Where | What it owns |
|---|---|---|
| Manage Apps "All apps" | `ManageAppsSections.allApps` + `AppListScreen` | Header "All apps" above "Groups", inside the one stable `ManageAppsRow.Top` row (PR 2's key-anchoring lesson: the section appearing must not re-key the list), only when an All apps rule exists. A row is an `Apps` icon in the 40dp `TonalIcon`, the title, the summary and a switch. Search filters by title. Off is a weakening: Strict Mode gated like a group row (`AppListViewModel.toggleAllApps`, "Turn off blocking for these apps"); on is free. A phone with an All apps rule is no longer "first run", so the app list is headed "Other apps". |
| "Block several apps" sheet | `BlockSeveralSheet` (`BlockSeveralOption`) | Replaces the direct jump to a new group: a bottom sheet titled "Block several apps" with "A group of apps" (-> new group) and "All apps except a few" (-> new All apps rule, null id). Row targets are `BlockSeveralOption.open`, pure. `AppListScreen(onOpenAllAppsRule: (Long?) -> Unit)`. |
| "Also covered by" | `coveringAllApps` (`CoveringGroups.kt`) + `UnifiedAppConfigViewModel.coveredByAllApps` | An All apps rule is listed on an app's config when `RuleCoverage.coveringRules` says it covers the app, with real `CoverageFacts` (essential, launchable) from `EssentialPackages` / `PackageFacts`: the one coverage answer, so an essential app, an allowed app, an app in an allowed group and a launcher-less app never show a rule that would not block them. A schedule is not applied (a weekend rule still covers the app). Group rows come first. |
| "Allowed during" | `GroupEditorViewModel.allowedDuring` (`allAppsRulesAllowingGroup`) | On the group editor, after When and before Delete group, only when non-empty: the All apps rules (ON OR OFF) whose `exceptGroupIds` contains the group. A rule that is off still lists the group, for when it is on. A new group is in none. |
| Home lock card | `ui/screens/home/LockCard.kt` + `HomeViewModel.lockCard` | See below. |

### The Home lock card (spec 3.8)

Under the Nuke card, in the same `surfaceVariant` card: `<title> are blocked`, then `Until 8:42 AM` (device 12/24h via
`DateFormat.getTimeFormat`; omitted for a rule that is on all the time), and a chevron that opens the rule. Shown only
while an enabled All apps rule is in its window right now.

- `lockCardFor(rules, now, appLabels)` (pure) picks the rule: enabled, `RuleTarget.AllApps`, active at `now` per
  `ScheduleEvaluator.isActiveAt`. Several active: the one that ends last (`windowEndAt`; no end = all the time = last);
  ties go to the oldest rule so the card does not flip. The end comes from `ScheduleEvaluator.windowEndAt`, the same
  walk the block screen's "Until" uses.
- `HomeViewModel.lockCard` is its OWN flow (like `nukeSummary`), not a field of `uiState`. With no enabled scheduled All
  apps rule (`lockCardNeedsClock`) it emits once per rule change and **never polls**. With one, it re-derives at the top
  of every minute (`millisToNextMinute`, the schedule's resolution), so the card disappears in the minute the window ends.
  Rules are read inside the flow body, so nothing is read until the card is collected.
- Essentials are not part of the card's decision: it is about the RULE being on; an essential app is still never
  covered (`RuleCoverage`).

Pinned by `LockCardTest`, `AllAppsRuleItemsTest`, `ManageAppsAllAppsTest`, `CoveringAllAppsTest` and
`AppListViewModelStrictModeTest` (All apps switch).

