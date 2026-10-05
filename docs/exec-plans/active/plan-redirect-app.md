# Plan: "Redirect app" bubble on every block screen (v1.20.0 / vc59)

Anti's request: a single, user-chosen "better app" (Wikipedia, a to-do list) offered on every
block screen; tapping it leaves the blocked app for that one.

## Shape
- `domain/redirect/RedirectAppPolicy.kt` (pure): rule targets (direct + group), the excluded set
  (Nudge + rule targets + Nuke list + the block's own packages), picker rows (filter + search +
  sort), and `resolve(saved, launchable, excluded)` -> the package to show or null (empty state).
- `data/preferences/RedirectAppPref.kt`: key + read/write over `Preferences` (pure, JVM-tested);
  `NudgePreferences.redirectAppPackage` / `setRedirectAppPackage` delegate to it. Device-local,
  not in the backup format.
- `data/repository/RedirectAppRepository.kt`: prefs + rules + groups + Nuke list + PackageManager
  launch intent; the only place the policy meets Android.
- `OverlayLifecycle.onWalkAwayRequested(..., redirectPackage)` -> same effect list, with
  `LaunchRedirectApp(pkg)` in place of `GoHome`. One walk-away writer, one row pair, armed window
  before the launch, fail-safe unchanged. Launch failure falls back to `goHome()`.
- UI: `ui/redirect/RedirectAppController` (state holder shared by overlay + Settings),
  `RedirectAppBubble` (empty/set, tap, ~600ms long-press + haptic), `RedirectAppPickerSheet`
  (M3 ModalBottomSheet: own dialog window, own back dispatcher -> back closes the picker, never
  walks away / finishes). `PauseWhile` caps the overlay content's LocalLifecycleOwner at STARTED
  while the picker is open so countdowns/breathing/hold do not progress unseen (#8 rule).
- Every overlay body gets a `redirect` slot between its message and its primary button:
  Delay, Hold, Breathing, HardBlock (incl. daily limit + cooldown via Delay), Nuke.
- Settings > Personalize: "Redirect app" row -> same picker sheet.

## Tests
RedirectAppPolicyTest, RedirectAppPrefTest, OverlayLifecycleTest (redirect variant + once-only),
contract test: every overlay body renders the slot, the bubble launches only via navigateHome.

## Ship
lintDebug + scoped tests, device QA (device-tester), version 1.20.0/59, store notes, PR, merge,
tag, Play 100%.
