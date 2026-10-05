# Rule capabilities and in-app feature detection

Covers what a *rule* can express beyond a plain block: schedules, in-app feature blocking
(Shorts/Reels/TikTok), grayscale, the user-editable overlay message pools, and the rule editor.
**Read before touching `domain/` rule models, `InAppDetector`, `NudgeMessages`, the rule editor, or the Settings screen.**

## Capabilities

- **Schedule-based rules** — day-of-week + time-of-day, overnight schedule support (spans midnight)
- **In-app feature blocking** — YouTube Shorts, Instagram Reels/Explore, TikTok detection via AccessibilityService
- **Grayscale mode** — force screen to grayscale (requires ADB: `adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`). Grayscale guide in Settings.
- **Rotating motivational messages** — shown on overlay screens when blocks trigger. **User-editable (v1.6.0)**: defaults live in `ui/overlay/NudgeMessages.kt` (delayTitles/delaySubtitles/hardBlockMessages); users override via Settings → Personalize → "Edit block messages" (`ui/screens/settings/MessagesEditorScreen.kt`), stored as 3 multiline strings in `NudgePreferences` (`customDelayTitles`/`customDelaySubtitles`/`customHardBlockMessages`, one message per line, empty = defaults). `NudgeMessages.resolvePool(customRaw, default)` is the pure resolver; `BlockOverlayActivity` reads the prefs once via `runBlocking{ first() }` before `setContent` (avoids a default→custom flash) and passes resolved pools into the overlay composables (which still `remember { pool.random() }`).
- **Instagram home feed detection** — `InAppDetector` now detects Instagram's home feed (when Home tab is selected, no other tabs active) and treats it as REELS-equivalent. Home feed scrolling counts toward interaction counter and auto-kick the same as the Reels tab.
- **Redirect app (v1.20.0)** — ONE global "better app" (a to-do list, Wikipedia) offered as a bubble on every
  block screen; a tap is a walk-away into that app. Set from the bubble (long-press to change/remove) or from
  Settings → Personalize → "Redirect app". Never an app with an enabled rule or on the Nuke list. Design, the
  picker's back-gesture safety and the stale-choice fallback: `block-overlay-lifecycle.md`, "The redirect app".
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
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
