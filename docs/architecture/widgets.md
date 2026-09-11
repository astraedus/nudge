# Home-screen widgets

Covers the three Jetpack Glance widgets, the one-shot read model behind them, how they are kept fresh from
the accessibility hot path, the deep-link mechanism they share with the protection alert, and the Strict Mode
rule that stops the Protection widget being a one-tap bypass of the commitment lock.
**Read before touching `ui/widget/`, `domain/widget/WidgetRefreshSignal`, `MainActivity`'s intent handling, the
`NudgeNavGraph` signature, or the widget receivers in `AndroidManifest.xml`.**

The organising fact of this subsystem: **a widget's failures are all silent.** It runs in the launcher's
process, on a surface nobody is looking at during a code review, and there is no screen to show an error on.
A missing manifest attribute makes it never appear; a thrown exception makes it render the framework's grey
error box; a stale read makes it confidently show yesterday's number. None of those produce a log line anyone
will see. Almost every invariant below exists because the alternative fails quietly.

## Why Glance, and why 1.2.0

`androidx.glance:glance-appwidget:1.2.0` + `androidx.glance:glance-material3:1.2.0`, plain dependency strings
in `app/build.gradle.kts` (this repo has no version catalog).

- **Glance over classic `RemoteViews` + `AppWidgetProvider`.** The team writes Compose; the update model is
  coroutine-native, which matters because every read here is suspending; and `GlanceTheme` consumes the app's
  existing Material 3 `ColorScheme` directly, so the widgets cannot drift into a second palette. Its
  requirements were already met — compileSdk ≥ 35 (we are on 36), minSdk ≥ 23 (we are on 26), and the Compose
  compiler plugin, which this module already applies.
- **1.2.0, not 1.3.0-alpha02.** 1.3.0 exists and is an alpha. An alpha does not belong in code that runs inside
  another app's process, on a surface the user cannot force-refresh.
- **`glance-appwidget-testing` is deliberately NOT added.** It is Robolectric-backed and Robolectric is not on
  this project's classpath. Pulling a whole test runtime in for one subsystem is a separate decision from
  shipping the subsystem, so instead the composables are kept dumb and every rule they consult is a pure
  function with its own JVM tests (see "What the tests pin").
- `isMinifyEnabled = false` on release, so no R8 keep rules are owed for the reflectively-constructed
  receivers.
- **Glance has no `Canvas`.** That is why there is no chart in any widget and why the leaderboard's
  proportional bar is built from sized `Spacer`s rather than drawn (see "Layout without a Canvas").

## Read once, before `provideContent` — never a subscription

Every widget's `provideGlance` does exactly one suspending read, maps it to an immutable snapshot, and only
then composes:

```kotlin
val snapshot = runCatching { WidgetReads.today(NudgeWidgetEntryPoint.from(context)) }
    .getOrElse { WidgetSnapshot.Today.EMPTY }
provideContent { NudgeGlanceTheme { TodayContent(snapshot) } }
```

- **A long-lived `Flow` collect is wrong here, and wrong in a way that looks right.** The widget composes inside
  a Glance session the platform tears down as soon as the `RemoteViews` are handed to the launcher. A
  `collectAsState` over a Room flow would therefore emit once and be cancelled — the *same* value `.first()`
  gives — while reading, to the next person in this file, like something that keeps itself up to date. It would
  then be relied on as if it did. Freshness is not a property a widget can hold; it is pushed in (next section).
- **`.first()` on the repository's existing `Flow`, not a new suspend DAO method.** A Room flow delivers its
  query result immediately and the collection is cancelled straight after, so this *is* the one-shot read — and
  it is the same query the dashboard observes. A parallel suspend query would have been a second way to count
  the same rows, which is this package's standing defect class.
- **`runCatching { … }.getOrElse { EMPTY }` on every read.** An exception escaping `provideGlance` is the user's
  home screen breaking, and they have no retry. Each `EMPTY` is chosen to be honest rather than merely safe:
  `Today.EMPTY` reports no usage permission and zero counts; `Protection.EMPTY` is `enabled = true,
  strictModeEnabled = true`, so a failed read can never render an inviting one-tap OFF.
- **Hilt reaches the widgets through an `@EntryPoint`.** A `GlanceAppWidget` is built by its
  `AppWidgetProvider`, which the framework constructs reflectively, so there is no constructor to inject and
  `@AndroidEntryPoint` does not apply. `NudgeWidgetEntryPoint.from(context)` pulls the same
  `SingletonComponent` bindings the app uses — the point being that a widget must never open its own Room
  instance or its own DataStore, which is how a widget comes to disagree with the screen it links to.
- **All of it on `Dispatchers.IO`.** `getWeeklyUsage` is a binder call plus a walk over a week of usage events;
  `resolveIcon` hits PackageManager.

## The three widgets

| | **Today at a glance** | **Blocked most** | **Protection** |
|---|---|---|---|
| Size mode | `Responsive` 2x2 `DpSize(110,110)` + 4x2 `DpSize(250,110)` | `Responsive` 4x2 `DpSize(250,110)` + 4x3 `DpSize(250,180)` | `Single`, 2x1 |
| Provider XML | `today_widget_info.xml` | `top_blocked_widget_info.xml` | `protection_widget_info.xml` |
| Reads | `getWeeklyUsage(dayStart).totalOn(dayStart)`, plus the day's blocked / walked-away counts | `InsightsCalculator.topBlockedApps` over the trailing week, plus `resolveAppName` / `resolveIcon` | `isGlobalEnabled`, `protectionDegraded`, `isStrictModeEnabled` |
| Tap | whole surface opens Stats | header opens Interventions; a row opens that app's AppDetail | see "Strict Mode" |
| `updatePeriodMillis` | `1800000` | `1800000` | `0` — pushed, never polled |

- **Today is the widget that exists because everything on it is already on the dashboard**, and the dashboard is
  two taps away on a phone whose whole problem is that two taps is too many. 2x2 leads with screen time and puts
  both counts on one line beneath it; 4x2 has room for three labelled columns. Both render from **one** read, so
  the two size variants cannot disagree — a per-size read would be the two-computations defect wearing a
  responsive-layout costume.
- **Today shows the RAW day counts the Home tiles show**, not the de-duplicated `overlaysFromAllTimeCounts`
  figure. The careful version of "how many confrontations" already has a screen. Here, agreement with the tile
  the user will compare against is worth more than statistical purity, and the choice is written down in
  `WidgetSnapshot.Today`'s KDoc so it reads as a decision rather than an oversight.
- **Blocked most reads through `InsightsCalculator.topBlockedApps`, the ONE per-app aggregation in the app**,
  over the same `startOfDayDaysBefore(today, WEEK_DAYS - 1)` boundary the dashboard's week card subscribes with.
  It reads the taller variant's five rows once and lets the composable trim to three; reading per size would be
  two reads of one week.
- **Protection is the only widget that acts**, and it is also where the app's worst silent failure surfaces:
  `protectionDegraded` means the OS killed the accessibility service, so Nudge looks enabled and blocks nothing.
  A `degraded` flag is suppressed when the master toggle is off — a switched-off Nudge is not a broken Nudge,
  and reporting one as the other trains the user to ignore the single message that means their phone killed us.

### Layout without a Canvas

Glance offers `fillMaxWidth()` but no fractional-width modifier and no `Canvas`, so the leaderboard's
proportional bar is an absolute `Dp` computed from `LocalSize.current`: the track is the widget width minus the
icon, gaps and the fixed count column, and the fill is `barPercent` of it. `barPercent` is a whole number
0..100 in the snapshot rather than a float, so the value the test asserts on is the value the layout multiplies.

App icons are decoded at a fixed 64 px (`WidgetReads.ICON_PX`) because a `RemoteViews` payload is IPC and has a
ceiling. A row whose icon fails to load or whose app has been uninstalled renders its **rank number** — never an
emoji, and never a blank gap that would knock the bars out of alignment.

### Theming

`NudgeGlanceColors = ColorProviders(light = LightColors, dark = DarkColors)`, built from the app's own two
Material 3 schemes. `LightColors` / `DarkColors` in `ui/theme/Theme.kt` were promoted from `private` to
`internal` precisely so this file can read them; a widget with its own teal would drift from the app's the first
time either was touched, and the drift would only ever be visible on a home screen.

The app uses Material You dynamic colour on API 31+ and the widgets deliberately do not. A `ColorProviders` is
resolved per-configuration, not per-composition, and these are read against the launcher's wallpaper scrim,
which a stable brand colour survives better than one extracted from that same wallpaper.

## Staying fresh: the platform clamp, the chokepoint push, and the debounce

`updatePeriodMillis` is clamped to 30 minutes by the platform. That is a fine backstop for a screen-time number
and useless for "I just walked away from Instagram" — which is the moment the widget is worth having. So:

- **One chokepoint: `UsageRepository.logEvent`.** Every block decision and every walk-away already passes
  through it (`RecordWalkAwayUseCase`, and both write sites in `NudgeAccessibilityService`). **No WorkManager
  job** — the 15-minute `ProtectionWatchdogWorker`, the 30-minute platform tick and these pushes already cover
  every case, and a fourth scheduler would be a fourth thing to keep alive.

### The Protection widget is push-ONLY, so a missed push is a frozen widget

`protection_widget_info.xml` sets `updatePeriodMillis="0"` on purpose: polling a toggle every half hour is
worse than useless. That makes the push the **entire** update mechanism for this widget, and it turns a
forgotten push from a latency problem into a correctness one.

Device QA found exactly that, twice over, and it is worth stating plainly because the bug was invisible in
every other way: **the single state the Protection widget exists to announce — "protection has stopped" — was
the one state it could never receive.** `recordProtectionCheck` wrote the degraded flag and pushed nothing, so
a widget placed while healthy stayed healthy-looking indefinitely; only a *freshly placed* instance ever showed
the truth. An alarm nobody can observe is not an alarm. `setStrictModeEnabled` had the same gap, so after
enabling Strict Mode the widget kept drawing the unlocked TOGGLE affordance; the tap was then correctly refused
by the guard in `ToggleProtectionAction`, which read to the tester as "the first tap does nothing".

So the rule is not "push from `setGlobalEnabled`", it is:

> **Every preference a widget renders pushes a refresh on every write path, after the write lands.**

Four writers qualify today: `setGlobalEnabled`, `setStrictModeEnabled`, `recordProtectionCheck`, and
`applyImportedSettings` (restoring a backup can flip Strict Mode, and a restore is precisely when a placed
widget is most likely to be stale).

`WidgetRefreshCoverageContractTest` enforces it by **discovery, not by a list**: it reads `WidgetReads.kt` to
find which preference flows the widget layer actually consumes, resolves each to its `Keys.*` constant, finds
every function that assigns that key, and requires each one to push — and to push *after* its own
`dataStore.edit`, since a refresh that runs first reads the value it is replacing and renders one state behind,
which looks identical to no push at all. A fifth write path, or a fourth widget-visible preference, fails the
test until it is wired. Hand-listing the three known writers would have pinned yesterday's bug and missed the
import path, which is exactly what happened before the test was written.
- **`logEvent` is on the accessibility hot path, so the push must cost nothing there.**
  `WidgetRefreshSignal.requestRefresh()` is **not** suspending, does one atomic compare-and-set on the caller's
  thread, and hands off to an application-scoped `SupervisorJob` coroutine. A user hitting a wall of blocks
  produces several events a second, and each `updateAll` is a real `RemoteViews` build plus a binder call into
  the launcher. Adding that to an event dispatch would mean the thing deciding whether to block someone waits on
  a home screen nobody is looking at.
- **`WidgetRefreshDebouncer` coalesces to one run per 10 s**, and is a separate pure class rather than an inline
  `if` because the updater itself cannot be JVM-tested (it needs a real `Context` and AppWidgetManager) while
  the rule that decides whether it runs can be, exactly. This repo has been burned by a silent early return in a
  loop with no test and no log; that is the shape being avoided.
- **The clock is `SystemClock.elapsedRealtime()`, monotonic.** A wall-clock reading can jump backwards — a
  timezone change, an NTP correction, the user setting the date — and a backwards jump would lock refreshes out
  for however far it went, with the symptom being a widget that quietly stops updating, which looks exactly like
  a widget with nothing to report.
- **It fails OPEN.** The first call always runs, a rejected call never extends the window, and the failure
  direction of a debounce bug is "refreshed more often than needed", never "stopped refreshing".
- **KNOWN LIMITATION: the debounce is LEADING-EDGE ONLY, so the last write of a burst can be dropped.**
  `tryAcquire` runs the first call and returns `false` for anything inside the window, scheduling nothing. That
  is exactly right for `logEvent`, where the point is to rate-limit a hot path and the next event will push
  again shortly. It is wrong for a state change, where **the LAST write is the one that carries the truth**.
  Device QA saw the consequence once: right after unlocking Strict Mode the widget rendered a combination
  belonging to neither state (`Not blocking` in red, over the `Turn off in app` locked hint), and stayed that
  way for about a minute. The unlock path writes several preferences in quick succession; the first push ran
  against a mid-burst snapshot and every later push in that 10 s window was discarded with no trailing run, so
  the widget kept the half-updated frame until some unrelated push came along. The 15-minute watchdog is far
  too slow to have been the thing that corrected it.
  It self-corrects and it fails toward a FALSE ALARM rather than a falsely-healthy widget, which is the safe
  direction — but only just: `WidgetSnapshotMapper.protection` deliberately suppresses "degraded" over a
  switched-off Nudge precisely so the user is never trained to ignore the one message that means their phone
  killed us, and a spurious red dot works against that.
  **The fix is a trailing edge**: on a rejected acquire, schedule exactly one run at `last + windowMs`,
  single-flight so a burst of N produces one leading and one trailing update rather than N. Deliberately not
  done in v1.17.0 — it touches the updater that sits on the accessibility hot path, and it wants its own device
  pass to confirm a burst really does produce two updates and not a refresh storm. Filed in `docs/BACKLOG.md`.
- **The steady-state path is silent; a failure is logged.** `Log.w` on a failed `updateAll`, because "the
  widgets are stale" and "the widgets had nothing new to show" must not look identical in logcat. It uses
  `android.util.Log` rather than the injected `NudgeLog` for a hard reason: `NudgeLogger` depends on
  `NudgePreferences`, which now depends on this class, so injecting the logger would be a Dagger cycle.

### Layering: why `WidgetRefreshSignal` lives in `domain`

`UsageRepository` and `NudgePreferences` are `data`, and the dependency rule in this module is
`ui -> domain <- data`, so neither may import a Glance class. `domain/widget/WidgetRefreshSignal.kt` is a pure
`fun interface` with one non-suspending method; `ui.widget.NudgeWidgetUpdater` implements it and a `@Binds`
module inside `ui/widget/` wires them. This is the same pattern as `service.UsageProvider` and
`service.GlobalEnabledProvider` — the consumer names the capability, the implementing layer supplies it.

Both constructor parameters default to **`WidgetRefreshSignal.NONE`**, a named constant rather than an empty
lambda. Two screens (`SettingsScreen`, `MessagesEditorScreen`) build a `NudgePreferences` by hand to *read*
preferences, and neither writes the master toggle; the Hilt binding in `di/RepositoryModule` passes the live
pusher to every writer. The constant is named so that if a future caller ever writes a widget-visible value
through a hand-built instance, it shows up in the diff as a word rather than as nothing at all.

## Strict Mode: the Protection widget must not be a one-tap bypass

`HomeViewModel.toggleGlobalEnabled` runs ON→OFF through `StrictModeGate`; ON is free. A widget that called
`setGlobalEnabled(false)` itself would be a **one-tap bypass of the commitment lock, sitting on the launcher,
reachable without unlocking anything** — the exact hole `docs/architecture/strict-mode.md` exists to close, and
one that no value-level unit test would notice, because every individual line of it looks correct.

- **The branch is a VALUE, not a runtime `if`.** `WidgetSnapshot.Protection.togglesInWidget` is
  `!(enabled && strictModeEnabled)` — one place, unit-tested over all eight input combinations.
- **The locked case is a DIFFERENT COMPOSABLE, chosen at compose time from the snapshot**, whose tap target is
  `actionStartActivity` into `MainActivity` at the Home route, so the user meets the real challenge dialog.
  It renders "Turn off in app" beneath the status so a tap that opens the app does not read as the widget
  failing to toggle.
- **This is not a style preference.** An `ActionCallback` runs from a broadcast, and starting an Activity from
  there is subject to the API 31+ background-activity-start restriction: it would silently do nothing on any
  modern phone. A locked widget would read as a *dead* widget, and the tap that was supposed to raise the
  challenge would simply be lost. Hence the rule: **never start an Activity from inside an `ActionCallback`;
  express the branch as a composable chosen from the snapshot.**
- **`ToggleProtectionAction` re-reads the preferences rather than trusting the rendered snapshot**, which can be
  minutes old. "What it looked like when I drew it" is not a safe basis for writing a protection setting.
- **The one weakening write carries its guard on the same source line**:
  `if (!strictModeEnabled) preferences.setGlobalEnabled(false)`. Same-line, deliberately — a guard on the
  preceding line can be separated from what it guards by an innocent-looking refactor, and the resulting failure
  would be silent and unreachable from any JVM test.

## Deep links: one mechanism, not two

`MainActivity` already carried a bespoke `EXTRA_OPEN_SETTINGS` boolean for the protection alert. Adding a second
path for widgets would have meant a boolean per destination forever, and one of the two mechanisms always rots.

- **`WidgetDeepLink`** (pure, no `android.*`) maps an extra to a NavGraph route and returns **null** for anything
  unrecognised. Null is the important answer: an unknown string must not reach `navigate()` (it throws), and it
  must not be coerced to Home either — a widget that opens the wrong screen is a bug report, while an app that
  opens at its start destination is the platform's own default.
- **A package containing `/` cannot forge a route.** `app_detail/<pkg>` is only produced and only accepted when
  the package matches dot-separated ASCII segments, so `app_detail/x/../settings` is not a string this function
  will ever hand back. Deliberately stricter than the platform's own parser: rejecting an exotic-but-legal name
  costs one leaderboard row its detail screen, while accepting a crafted one navigates somewhere nobody asked.
- **`EXTRA_OPEN_SETTINGS` is translated, not replaced.** Its `PendingIntent` is sitting inside protection alerts
  already posted on real phones, so the constant keeps working — `MainActivity.routeFrom` reads it and returns
  `ROUTE_SETTINGS`. One reader, two inputs.
- **`MainActivity` is `launchMode="singleTop"`** with an `onNewIntent` override. Without it, tapping a widget
  while the app is open stacks a second `MainActivity`, so back lands the user on a stale copy of their own
  dashboard. The pending route is held in `mutableStateOf` and **cleared on consumption**, because
  `LaunchedEffect` keys on the value: without the clear, tapping the same widget twice would be swallowed as an
  unchanged key.
- **`NudgeNavGraph` takes `deepLinkRoute: String?` + `onDeepLinkConsumed`**, replacing `openSettingsOnLaunch:
  Boolean`. It keeps the original guard: a first-run user is never yanked out of onboarding, because the start
  destination reads `true` for one frame while the preference loads and that frame must not be enough to
  navigate away.
- **Glance builds the `PendingIntent`**, with the mutability flags API 31+ demands and a unique data URI per
  clickable view — which is why five leaderboard rows carrying five different routes do not collapse onto one
  cached PendingIntent. Hand-building one would forfeit both. Glance copies each `ActionParameters` key's name
  into the Intent extras verbatim, so `WidgetRouteKey` and the extra `MainActivity` reads are one constant.

## Getting a widget onto a device for QA

**Honest finding: a widget cannot be placed on the launcher from adb alone.** There is no `cmd appwidget add`;
`adb shell appwidget grantbind` only grants a launcher the bind permission, which Pixel Launcher already holds.
The two real paths are a human dragging one out of the picker, or `AppWidgetManager.requestPinAppWidget()`,
which raises a system dialog that still needs a tap — but a tap is something a device agent can find *by text*
and perform.

So Settings gains an **"Add widgets to home screen"** row under Developer Options, guarded by
`BuildConfig.DEBUG` **on the composable** (not inside the handler) so the row does not exist in a release build.
It calls `requestPinAppWidget` for all three receivers, and degrades with a toast on a launcher that does not
support pinning.

The QA procedure, the resize check, the push-latency check and the Strict Mode assertion are in the spec's
§C8. The one that must never be skipped: **Strict Mode ON, tap the Protection widget, assert it opens the app
and shows the challenge dialog, and that blocking is still on.**

## Manifest and resources

Each receiver needs three things, and each of the three fails silently when missing:

- **`android:exported="true"`** — the launcher is a different process and cannot broadcast to a non-exported
  receiver, so the widget simply never updates, with no error anywhere. The receivers hold no data of their own
  and read through the same repositories the app does, so there is nothing here an exported receiver leaks.
- **an `APPWIDGET_UPDATE` intent-filter** — without it nothing ever asks the widget to draw.
- **an `android.appwidget.provider` meta-data** pointing at an `@xml/…` file that exists.

Provider XMLs carry `resizeMode`, `widgetCategory="home_screen"`, min sizes at the smallest supported size,
`maxResize*` at the largest, plus `targetCell*` / `previewLayout` / `description` (API 31+, harmlessly ignored
on 26-30 — lint reports these as `UnusedAttribute` warnings and that is expected). `initialLayout` and
`previewLayout` both point at **our own** `res/layout/widget_loading.xml`, a single centred `TextView`: guessing
a Glance-internal resource name would be a dependency on a private API.

All user-visible widget strings live in `res/values/strings.xml`. They are read in a process that is not ours,
where a missing resource is an exception rather than a blank.

## What the tests pin

50 tests across six JVM classes. The source-scanning classes use the `source()` helper that **strips
comments before scanning**, the same technique as `ScreenTimeSourceContractTest` — the widget files deliberately
document in prose the very calls they must not make, and scanning raw text would make writing that explanation
fail the test that protects it.

- **`WidgetDeepLinkTest`** (9) — every fixed route round-trips; null/empty/blank/unknown yield null rather than
  a fallback; `app_detail/<pkg>` carries the package through unmangled; the slash-forgery cases; seven rejected
  package shapes; and the `EXTRA_ROUTE` literal is pinned, because it is an over-the-wire intent-extra key and a
  rename silently breaks every already-pinned widget.
- **`WidgetSnapshotMapperTest`** (15) — the screen-time placeholder when Usage Access is missing while the
  database-sourced counts still pass through; bar percentages including the unsorted-input bound; label fallback
  for an uninstalled app; and one exhaustive table over all eight `protection(...)` inputs asserting `state` and
  `togglesInWidget`. This is the value-level half of the Strict Mode contract.
- **`NudgeWidgetUpdaterTest`** (8) — the debouncer: first call always runs, burst coalescing, the window
  restarting from the last *successful* acquire, a rejected call not extending the window, backwards-clock
  behaviour, and 200 threads racing one `nowMs` with exactly one winner.
- **`WidgetStrictModeContractTest`** (8) — the source-level half, and the decisive one. Every
  `setGlobalEnabled(false)` in `ui/widget/` sits on a line also carrying `!strictModeEnabled`, and there is
  **exactly one** such write (more means the decision was duplicated; zero would make the test pass vacuously);
  the guard is read fresh from preferences inside the callback; `ToggleProtectionAction` contains no
  `startActivity`, `actionStartActivity` or `Intent(`; the compose-time branch exists and consults
  `togglesInWidget`; nothing in the package writes any other preference; every widget wraps its read in
  `runCatching`/`getOrElse`; no widget file uses `.collect {`, `collectAsState` or `stateIn(`; and the hot-path
  refresh is non-suspending, debounced, monotonic and not routed through WorkManager.
- **`WidgetManifestContractTest`** (6) — the set of `GlanceAppWidgetReceiver` subclasses **discovered from
  source** equals the set declared in the manifest, in both directions, so a fourth widget cannot ship
  half-wired; exported + intent-filter + provider meta-data on each; every referenced `@xml` provider and its
  `initialLayout` exists; `MainActivity` is `singleTop`; every `@string` the widget XML names is defined; and each widget has its OWN debug pin row, with the requesting function iterating nothing (the launcher services one pin request at a time, so a loop drops every dialog but the last).

- **`WidgetRefreshCoverageContractTest`** (4) — added after device QA, and the only one here that **discovers
  its own inputs**. It reads `WidgetReads.kt` for the preference flows the widget layer consumes, resolves each
  to its `Keys.*` constant, finds every function that ASSIGNS that key, and requires each to push a refresh —
  after its own `dataStore.edit`, and never from a file that hand-builds a `NudgePreferences`. A fourth
  assertion fails if the discovery finds nothing, so a refactor that breaks the resolver fails loudly instead
  of passing vacuously. Hand-listing the writers would have pinned the two bugs QA had already found and missed
  the settings-import path entirely — which is exactly what happened before this test existed.

  Two parsing traps are worth knowing if you extend it: splitting the file on the `suspend fun` keyword
  misattributes a write to the function declared *above* the real one, and the marker `prefs[Keys.X]` matches
  the READ inside each `Flow` declaration as well as the write. Hence backward search plus brace matching, and
  the `] =` in the marker. A contract test that cries wolf gets deleted.

**Mutation-checked.** Removing the `!strictModeEnabled` guard, flipping one receiver to `exported="false"`,
deleting `launchMode="singleTop"`, reintroducing the pin-row loop, deleting the degraded-state push, and
restoring the hand-built `NudgePreferences` in `SettingsScreen` each fail their specific test. A source-scanning
test that passes on broken code is worse than no test, so every one of these was run rather than assumed.

## Deliberately not built (v1)

Kept here so the reasons survive; the shortlist is mirrored in `docs/BACKLOG.md`.

- **Single-app budget, "time left today"** — needs a configuration Activity to pick the app. Real work, low
  first-release value.
- **Streak counter** — cheap, `StatsCalculator.calculateStreak` already exists. Fold into Today as a footer line
  if it fits rather than spending a widget slot on it.
- **Walk-away rate ring** — needs a generated bitmap, and Today already carries the number.
- **Hourly heatmap strip** — a dense bitmap, and it reads poorly at 4x1.
- **"Next scheduled block starts in…"** — needs schedule evaluation off the hot path.
- **Quick "start a focus block now"** — there is no such domain concept yet. It would be a feature, not a widget.
- **Weekly screen-time bar chart** — feasible via a runtime `Bitmap` and `Image(ImageProvider(bitmap))`, since
  Glance has no `Canvas`. Deferred with the rest of the bitmap machinery.
- **Grayscale toggle shortcut — REJECTED.** `WRITE_SECURE_SETTINGS` is ADB-granted, so for almost every user the
  widget would silently do nothing. A control that no-ops is worse than no control.
- **Emergency-pass "burn one now" — REJECTED.** A one-tap bypass on the home screen is a hole straight through
  the product's purpose, and the same reasoning as the Strict Mode rule above.
