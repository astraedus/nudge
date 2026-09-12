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
| `updatePeriodMillis` | `1800000` | `1800000` | `1800000` — was `0`; see the known bug below |

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

## Staying fresh: OBSERVE the data, never ask writers to announce themselves

`updatePeriodMillis` is clamped to 30 minutes by the platform. That is a fine backstop for a
screen-time number and useless for "I just walked away from Instagram", which is the moment the widget is
worth having. The Protection widget used to set `updatePeriodMillis="0"`, on the reasoning that polling a toggle
every half hour is worse than useless. That assumed the push always lands. It does not - see the known
bug below - so it now takes the same 30-minute backstop as the others. A backstop is not a fix; it is the
difference between wrong-for-half-an-hour and wrong-indefinitely.

### The design that failed, because it is worth knowing why

The first version exposed a `requestRefresh()` and expected every writer of widget-visible data to call it.
Three of the four writers never did. The consequences, all found on a device and none catchable by any test
of any value:

- `recordProtectionCheck` wrote the degraded flag and pushed nothing, so **"protection has stopped" - the
  single state the Protection widget exists to announce - was the one state it could never receive.** A
  widget placed while healthy stayed healthy-looking indefinitely.
- `setStrictModeEnabled` pushed nothing, so the widget kept drawing the unlocked TOGGLE affordance after the
  lock went on. The tap was then correctly refused by the guard in `ToggleProtectionAction`, which read as
  "the first tap does nothing".
- `applyImportedSettings` had the same gap and nobody had suspected it.
- And when the second of those was fixed, the fix was **unreachable**: `SettingsScreen` built its own
  `NudgePreferences`, whose signal parameter defaulted to a no-op. Correct code on the one path every user
  takes.

A source-scanning test was then written to police the rule. That is a regex parser standing in for something
the compiler should be guaranteeing, and it was the tell that the design was wrong.

### What it does now

`NudgeWidgetUpdater` collects the **sources of truth** and nothing tells it anything:

- the preference flows the widgets read (`isGlobalEnabled`, `protectionDegraded`, `isStrictModeEnabled`),
  combined and `distinctUntilChanged` - DataStore re-emits the whole preferences object on any write, so
  without that every unrelated setting would refresh the widgets;
- `UsageRepository.observeLatestEventId()`, a `SELECT MAX(id) FROM usage_events` flow. Room invalidates on
  any write to that table, and `MAX(id)` over the primary key is the cheapest question that re-emits. It is a
  change *signal*: the widgets re-read their real data themselves.

Any write, through any instance, from any layer, by any future caller, propagates - because it is the same
DataStore and the same Room table. Freshness is a property of the wiring rather than of everyone's
discipline, and `NudgePreferences` and `UsageRepository` went back to knowing nothing about widgets at all.
`WidgetRefreshSignal`, its `NONE` constant, the `@Binds` module, `PreferencesEntryPoint` and the contract
test policing the rule are all deleted.

**It is started from `NudgeApp.onCreate`**, the one callback that runs on every process start, for the same
reason the watchdog is armed there. Somebody has to hold those collectors open and a widget's own process is
far too short-lived to be that somebody.

### Two sources, two policies: coalesce what bursts, never defer what matters

`WidgetRefreshCoalescer` wraps a **conflated channel** consumed by one loop that refreshes and then waits
out a 10-second cooldown, so a burst of N changes produces two refreshes: one immediately and one carrying
the final state. It replaced a leading-edge debounce that got the rate limit right and correctness wrong -
a request inside the window returned `false` and scheduled *nothing*, so a change landing there was simply
discarded. It is a conflated channel rather than a `MutableSharedFlow(replay = 0)` because the latter
buffers nothing until a subscriber exists, so a request racing the loop's start would vanish.

**It is applied to the `usage_events` stream ONLY, and that distinction is the whole lesson.** Rate-limiting
was applied uniformly at first, and device QA showed why that is wrong: a deferred refresh runs *later*, and
later is usually after the user has left the app. The observed failure was the master toggle switched off
about five seconds after a block event - so it landed inside the cooldown that event had opened, and the
widget went on claiming "Blocking on".

The two sources are not alike, and only one of them ever justified a rate limit:

| Source | Bursts? | What it carries | Policy |
|---|---|---|---|
| `usage_events` | Yes - a user hitting a wall of blocks writes several rows a second | Counts and a leaderboard. Nice to have fresh. | Coalesced, 10s cooldown |
| Preferences | No - flipping the master toggle or Strict Mode is human-paced, one write | **Whether protection is on.** The safety-critical state this widget exists to show. | Refresh immediately, no window |

Generalising: **a deferral only ever buys something against a source that actually bursts, and it always
costs the chance that the refresh never happens at all.** For anything safety-relevant, that trade is never
worth making. `request()` is non-suspending either way, so no observer is ever blocked.

Every refresh logs one `Log.d` line naming its reason (`protection` or `events`). A subsystem whose failures
are all silent earns that: it costs nothing in normal use, and it is the difference between "the collector
never fired" and "it fired and the widget is still wrong", which are completely different bugs.

### KNOWN BUG: a protection change shortly after a block event may not reach the widget

**Reproduced on a Pixel 3, not fixed, and not a regression** - it fails the same way before the refresh
rewrite, for a different reason. Documented here with its evidence so the next person starts where this
stopped rather than re-deriving it.

**Symptom.** Toggle the master switch within ~10 s of a block event, with the Protection widget on the
launcher: the widget goes on showing the pre-toggle state. Observed still wrong at 63 s. An isolated toggle
(no recent block) works correctly, and the event-driven Today / Top-blocked path works from the launcher
with the app never opened.

**The captured log is the finding:**

```
11:07:37.669  refreshing widgets (events)
11:07:38.136  protection read: enabled=true degraded=false strict=false
11:07:42.212  refreshing widgets (protection)     <- the OFF toggle. NO read follows.
11:07:47.768  refreshing widgets (events)         <- NO read follows.
11:09:20.816  refreshing widgets (protection)
11:09:21.259  protection read: enabled=true ...   <- this one DID read
```

A refresh is dispatched and `updateAll` is called, but `provideGlance` frequently never runs, so the widget
keeps rendering its last composition. **What that rules out**, each with evidence rather than argument:

- *The process being frozen or cached.* `dumpsys` during the failing window: `isFrozen=false`,
  `cached=false`, `curProcState=FOREGROUND_SERVICE`, `oom adj=100`. The foreground service and the bound
  accessibility service keep it fully alive.
- *The refresh never being requested.* It fires 138 ms after the toggle tap.
- *A stale preference read.* Every read that DOES happen reports correct values; the failure is the absence
  of a read, not a wrong one.
- *An exception being swallowed.* `updateAll` is wrapped in `runCatching` with a `Log.w` on failure, and no
  failure line appears.
- *A missed tap.* The true app state was confirmed `checked="false"` by UI dump each time.

**Where to pick it up.** The remaining unknown is inside Glance's own update machinery, so it wants the
1.2.0 source rather than another device round. Concretely: log at the very top of `provideGlance`, before
the read, to separate "never invoked" from "invoked but did not reach the read"; check what
`GlanceAppWidgetManager.getGlanceIds` returns for the receiver at that moment; and check whether a session
already in flight for the same id causes a later `updateAll` to be dropped rather than queued.

**Mitigations already in place**, neither of which is a fix: `pushAll` is serialised behind a mutex so two
`updateAll` calls for one widget id cannot overlap (defensible on its own merits, but its effect on this bug
was NOT demonstrated), and the Protection widget now carries the 30-minute platform backstop so the
staleness is bounded rather than open-ended.

### Per-widget isolation

`pushAll` launches each widget's `updateAll` as **its own child coroutine with its own `runCatching`**, so
they are concurrent and one widget's failure cannot starve the others. The previous version ran all three
sequentially inside a single `runCatching` while its KDoc claimed the opposite, citing `SupervisorJob` -
which isolates across coroutines and does nothing whatsoever for three calls in a row inside one of them.
Failures are logged with the widget's NAME so they are attributable; the steady-state path is silent, because
"the widgets are stale" and "the widgets had nothing new to show" must never look the same in logcat.

`android.util.Log` rather than the injected `NudgeLog`: `NudgeLogger` depends on `NudgePreferences`, which
this class already depends on, so injecting it would be a Dagger cycle. Same precedent as `GrayscaleManager`.

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

52 tests across six JVM classes. The source-scanning classes use the `source()` helper that **strips
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
- **`WidgetRefreshCoalescerTest`** (7) - the coalescing policy on a `TestScope`'s virtual clock: the first
  request refreshes immediately, a burst of twenty inside one cooldown yields **exactly two** refreshes rather
  than one or twenty, a request arriving mid-refresh is served with the FINAL state, requests spaced beyond the
  cooldown each refresh, a request made *before* the loop starts is still served (which is why it is a conflated
  channel and not a `MutableSharedFlow(replay = 0)`), and a quiet period costs nothing. Every case is really
  asking the same question: can the last request ever be lost?
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

- **`WidgetObservationContractTest`** (6) - what is left to pin now that freshness is structural. It reads
  `WidgetReads.kt` to DISCOVER which preference flows the widgets render, then asserts `NudgeWidgetUpdater`
  collects every one of them, that it also observes `usage_events`, and that no data-layer file mentions
  `requestRefresh` or `WidgetRefreshSignal` any more - if the enumerate-every-writer design creeps back, so does
  the bug it produced. It also asserts each widget refreshes in its own child coroutine with its own
  `runCatching`, and that **every Intent extra `routeFrom` reads is removed on consumption**, both sets
  discovered from the code rather than listed, so a third deep-link mechanism cannot be added with no matching
  removal. A first case fails if the discovery finds nothing, so a refactor that breaks the regex fails loudly
  instead of passing vacuously.

**Mutation-checked.** Removing the `!strictModeEnabled` guard, flipping one receiver to `exported="false"`,
deleting `launchMode="singleTop"` and reintroducing the pin-row loop each fail their specific test. A
source-scanning test that passes on broken code is worse than no test, so every one of these was run rather
than assumed.

Two earlier mutation checks are gone along with the code they guarded - the missing degraded-state push and the
hand-built `NudgePreferences` in `SettingsScreen`. Both were checks on a rule that no longer exists, because
the updater observes the data instead of trusting writers to announce themselves. Deleting a test whose defect
has become unwritable is the point; deleting one because it is inconvenient is not.

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
