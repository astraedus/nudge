# The "Bro. wtf." bounce check-in (v1.21.0)

Covers the opt-in notification that fires when the user hits a Nudge wall, hops through other apps,
and hits a wall again. **Read before touching `domain/bounce/`, `service/BounceCheckIn*`, the
`bounceCheckIn` lines in `NudgeAccessibilityService`, `AutoKickExecutor`'s `onKicked`, or the
"Check-ins" section of Settings.**

## Why it exists

The owner's own pattern, in his words: *"when I hit the walls I just open up other apps like Discord,
then go back to Instagram (which I'm blocked from for a few more minutes), and I just bounce around a
lot. I want to catch myself midway through that bouncing."* A block stops one app. It says nothing
about the restless loop around it, and that loop is often the more honest signal that the person
wanted a break, not a different feed. So: notice the loop and say so, once, in the voice he asked for.

## The model

All of it lives in `domain/bounce/BounceDetector.kt`, pure Kotlin, every threshold a constant there.

| Term | Meaning |
|---|---|
| **Wall** | Nudge stopped an app: any block overlay the launch gate allows (every `BlockMode`, Nuke, the auto-kick cooldown overlay, the daily-limit block, a website block) or an auto-kick (either trigger). |
| **Armed** | The first wall arms a streak. Nothing is tracked before it. |
| **Window** | `WINDOW_MS` = 5 min, measured from the MOST RECENT wall, so a streak that keeps hitting walls keeps being tracked. When it passes with no new wall the streak is dropped, all of it. |
| **App opened** | A classifier `ForegroundSignal.AppWindow` while armed. The walled app counts (the user opened it). Each package counts once. |
| **Site** | A blocked website, carried on its own identity (`web:<normalised domain>`, `WebSessionKey`), so three different sites in one browser are three walls, not one browser wall. `www.`/`m.` spellings are one site. |
| **Fire** | Inside one streak: walls ≥ `MIN_WALLS` (2) AND distinct apps ≥ `MIN_APPS` (4), OR distinct blocked sites ≥ `MIN_SITES` (3). The app rule is checked first, so a mixed streak reads as apps. |
| **Cooldown** | `COOLDOWN_MS` = 30 min after a fire, shared by both rules. Nothing is fed or held during it; the next wall after it starts fresh. |

The notification: title **"Bro. wtf."**, body *"You've bounced through N apps in M minutes. Wanna
take a break?"* — or **N sites** when the streak fired on the site rule. N is the distinct app (or
site) count. M is first wall to fire, rounded UP (a 40-second bounce
reads "1 minute", never "0 minutes"); singular "minute" at 1. The copy is in `BounceAlert` so the
numbers in it are unit-tested.

### What counts as a SECOND wall

"They came back and hit a wall again" is a new confrontation, not the same overlay being put up
twice. `block-overlay-lifecycle.md` ("ONE CONFRONTATION PER ARRIVAL", #36) lists four legitimate
mechanisms that re-launch an overlay over an app the user never left. Counting each as a wall would
fire this on someone sitting still with two apps in the count. So a wall for the SAME package as the
previous wall counts only if the user has demonstrably been elsewhere in between: another app
(`onAppOpened`) or Home (`onWentHome`). A wall for a different package always counts. A re-launch for
the same app still slides the window: the wall is still in their face.

A **site** wall follows the same rule one level down: the same site re-blocked while the user has not
left the browser is one wall, a different site is always a new one, and the browser is still what
decides whether the user "left". Navigating from one blocked site to another is not leaving Chrome.

### What is NOT an app

The detector is fed only from `ForegroundSignal.AppWindow`, so the launcher (`Home`), keyboards
(`Transient`), the shade and `SYSTEM_PACKAGES` (`SystemSurface`), every Nudge window (`OwnUi`,
`AwarenessOverlay`), PiP bubbles and content changes never count. That is the classifier's job and
it is reused, not re-derived. `BounceDetector.neverCountedPackages` adds a short remainder the
classifier deliberately does not know about: Nudge itself, `android`, System UI, and both permission
controllers (the Pixel's `com.google.android.permissioncontroller` is an ordinary app package to the
classifier). Excluded packages neither count nor count as LEAVING, so a permission dialog over the
walled app does not turn the next re-launch into a second wall.

A package list is the wrong answer to "has the user left the app" (`tasks/lessons.md`, 2026-09-11)
and it is not being asked that here: the list only holds packages that are never an app anywhere.

### Only an app counts (#181, after v1.30.0)

Up to 1.30.0 every `AppWindow` counted, and this doc called a share sheet or photo picker counting
as an app an accepted miss. It is not a miss the user accepts: issue #181 (1.29.0, Android 17,
Pixel 10 Pro, Delay mode) got "bounced through 4 apps" after opening two. A system sheet (the share
sheet `com.android.intentresolver`, the photo picker, an account picker or passkey prompt in
`com.google.android.gms`, the documents UI) is an activity in its own package with a focused
application window, so the classifier is RIGHT to call it the app in front, and no classifier fix
belongs here. What the check-in asks is a different question: is this an app the user opened?

`AppCountability` (`domain/bounce/`) answers it from facts, never a list:

| Answer | When | Counts? | Leaving the walled app? |
|---|---|---|---|
| `COUNTS` | cached `PackageFact.launchable` (a launcher entry) and not a home screen | yes | yes |
| `NOT_AN_APP` | installed, no launcher entry: a sheet, picker or prompt host | no | no (like an excluded package) |
| `HOME` | in the service's launcher set, OR the package's own `homeScreen` fact (it handles `CATEGORY_HOME`) | no | yes, it is going home |
| `UNKNOWN` | facts not cached yet | not until they land | judged at the next wall, on the answer known then |

- **Never a binder call on the event thread.** Every answer is a cache read under the check-in's
  lock. A miss answers UNKNOWN, records the package, and asks the service to read its facts on
  `serviceScope` (IO) once; when the read lands, `BounceCheckIn.onFactsLanded()` re-checks the
  streak through `BounceDetector.reevaluate`, which obeys the same window and cooldown. A failed
  read leaves the package UNKNOWN for good (no retry loop): under-counting is the safe direction for
  a nudge, and the read takes milliseconds while the user's next switch takes seconds.
- **Home is known twice.** `launcherPackagesCached` is empty until it first resolves and can be
  minutes stale after the user picks a new launcher, so a home screen is also recognised by its own
  per-package `CATEGORY_HOME` fact. Neither has to be right on its own; an unresolved package is
  UNKNOWN, never countable. The fact goes through the launcher set's own rule
  (`sanitizeLauncherPackages`, via `isHomeScreenPackage` in `AndroidPackageFacts.kt`) before the
  query: AOSP Settings declares `Settings$FallbackHome` with `CATEGORY_HOME`, so a raw query reads
  Settings as a home screen on every stock phone. (Settings is also in `SYSTEM_PACKAGES`, so today
  it reaches the check-in as a `SystemSurface`, but the fact must not depend on that.) Cost: one
  extra package-scoped `queryIntentActivities` on the first read of each package (cached until that
  package changes), skipped for every excluded package; estimated well under a millisecond each,
  not measured.
- **The arithmetic candidate.** The Android 12 bench did not reproduce #181 in any variant
  (captures below), and the only packages that came to the front in all three were the two apps, the
  Nudge overlay and the Pixel launcher: 2 + 1 + 1 = 4, the reporter's number. Nudge was already
  excluded, so the remaining way to that count is a launcher reaching the check-in as an
  `AppWindow` (for example a launcher outside `SYSTEM_PACKAGES` with the launcher set empty or
  stale). The `HOME` answer closes that one too, whichever of the two it was on the Pixel 10 Pro.

**Debug trace** (tag `NudgeA11yTrace`, written only with debug logging on, never a domain):
`BOUNCE {"unit":"apps","count":N,"minutes":M,"walls":W,"apps":[...]}` when a check-in fires (the
packages it counted; a sites check-in has `"apps":[]`), and `BOUNCE-SKIP {"pkg":"..","why":".."}`
once per package per streak for each package left out (`not_launchable` or `home`). These lines are
not part of a capture's event stream.

**Not covered (H2):** a surface that is launchable but incidental still counts: Chrome Custom Tabs
(it is Chrome), an in-app Play review sheet (Play Store), the Google app's overlays (Gemini, Circle
to Search). None appeared on the bench, and no dwell-time rule was added without a capture that
demands one. If a report names one, capture it first.

## Where it is fed (and why only there)

| Feed | Site | Why there |
|---|---|---|
| Apps / Home | `applyForegroundSignal` → `bounceCheckIn.onForegroundSignal(signal)` | The one place every classified signal passes, above every early return. The fourth consumer next to the launch guard, the sitting and the tab cover. |
| Overlay walls | `launchBlockOverlay`, after the gate's refusal return, before `startActivity` | Every overlay in the service passes through here once per ALLOWED launch. A dropped launch is no wall the user saw. Reports `targetPackage` (the browser, for a website block: the app they were in) plus `webDomain` when it is a website. |
| Kick walls | `AutoKickExecutor.kick` → `onKicked(key)` | The single kick path for both triggers. A `web:<domain>` key is mapped back to the browser and its domain. |
| Reset | `onGlobalDisabled` | Nudge off behaves as if uninstalled, a half-finished streak included. |

Pinned by `BounceCheckInWiringContractTest` (source level: the four sites above, one wall report per
launch, after the gate, and the event path never reads the preference).

## Cost

- **Off (the default):** `BounceCheckIn.enabled` is a cached `@Volatile` boolean, kept in step by a
  collector on `NudgePreferences.bounceCheckInEnabled` (with `distinctUntilChanged`, #63). Off, both
  feeds return on their first line. No DataStore read on the event path, ever.
- **On, not armed:** one volatile read plus one uncontended lock and a null check per event.
- **On, armed:** a constant-time update of a `LinkedHashSet` bounded at `MAX_TRACKED_APPS` (64), plus
  the site set bounded at `MAX_TRACKED_SITES` (64).
- **No browsing history:** blocked domains live in one in-memory set for the length of a streak. They
  are never persisted, logged, exported or sent anywhere; a process death forgets them.
- **No** timer, alarm, WorkManager, wakelock, polling or I/O. Lapsing is checked lazily on the next
  event (a lapsed streak that nothing touches costs nothing and fires nothing). Time is
  `SystemClock.elapsedRealtime()`, read on an event already being handled, so a wall-clock change can
  neither stretch a streak nor end a cooldown early.
- **Threading:** signals arrive on main; overlay walls arrive from the IO coroutine that ran the rule
  lookup. `BounceCheckIn` serialises both with `synchronized`, held only for the update, never across
  the notification post.
- **Process death, or any accessibility-service rebind** (the service instance is recreated: memory pressure, a UI-tree dump during QA) forgets a streak and a cooldown, since both live in that instance's memory. Each reconnect also re-logs `bounce check-in enabled=...` from the new collector, which is why device logs show that line repeated next to `accessibility service connected`. That is the right
  direction for a nudge: the worst case is one check-in that would otherwise have been suppressed.

## The notification

`service/BounceCheckInNotifier`: its own channel `nudge_bounce_checkins` ("Bounce check-ins") at
`IMPORTANCE_DEFAULT`, so it actually pops without taking the heads-up slot the protection alert uses,
and can be muted without muting "blocking has stopped". Notification id 3, `PendingIntent` request
code 3 (uniqueness of both is enforced by `SharedNamespaceUniquenessTest`).

**Tap opens Nudge's home screen** (MainActivity, `CLEAR_TOP`), auto-cancel. Chosen over a plain
dismiss because the honest answer to "wanna take a break?" is often "show me the numbers", and the
home screen is where they are. It is a `PendingIntent` the user taps, never a `startActivity` from the
service (`MonitorServiceContractTest`). A posting failure (`SecurityException` with notifications
refused on some OEM builds) is swallowed: the accessibility thread must never die for a nudge.

## Settings

"Check-ins" section, one row (`ui/screens/settings/BounceCheckInRow.kt`): **"Bro. wtf."**, *"Get a
nudge when you bounce between apps or blocked sites after hitting a wall."*, default OFF. Not a protection setting, so
Strict Mode does not gate it either way. Turning it ON on Android 13+ without the notification grant
asks for `POST_NOTIFICATIONS` there and then. While it is ON and notifications cannot be posted
(app notifications off, or this channel blocked), the subtitle says so in the error colour and a tap
on the row opens Nudge's notification settings; the state is re-read on resume. Off never mentions
notifications. The copy decision is the pure `bounceCheckInRowCopy` (`BounceCheckInRowCopyTest`).

The preference is carried by a backup with the other settings. It is not a protection setting, so an
import that turns it on or off needs no Strict Mode challenge. It changes what Nudge says, never what
it blocks. The export set is pinned by `ImportedSettingsWriteContractTest`.

## Tests

- `BounceDetectorTest` (L1): no fire on one wall or on two walls with too few apps; exact-threshold
  fire with real N/M; second wall crossing via a different package; minutes from the first wall,
  rounded up; same-overlay re-launches are one wall; Home-and-straight-back is a second wall (with the
  counterfactual on the same config); the same app twice counts once; exclusions neither count nor
  count as leaving; window expiry disarms and leaves nothing behind; the window edge; the sliding
  window; a backwards clock; cooldown suppresses then resets; `reset`; the bounded set; config
  validation; the copy. Sites: three distinct sites in one browser fire on the third, two do not;
  the same site re-blocked is one wall (counterfactual: a second wall would fire); `www.`/`m.`
  spellings are one site; an app wall right after a site wall in that browser is a new wall; site
  and app counts never add up; a mixed streak still reads as apps; the one shared cooldown; the site
  streak lapses with the window; the site set bound; the site copy.
- `BounceCheckInTest` (L1): the owner's sequence through real `ForegroundSignal`s; the toggle-OFF path
  feeds nothing; default off; switch-off mid-streak drops it; only `AppWindow` counts; cooldown across
  the adapter; the master-toggle reset; the auto-kick reports its key; three blocked sites through
  the adapter post one site check-in.
- `BounceDetectorCountabilityTest` (L1): NOT_AN_APP neither counts nor is leaving; HOME is going
  home; UNKNOWN counts once its answer lands, through `reevaluate`; `reevaluate` is a no-op disarmed
  or lapsed; a left-out package is reported once per streak.
- `BounceNonAppWindowTest` (L2, #181): events go through the real `EventClassifier`, `BounceCheckIn`,
  `BounceDetector` and `AppCountability` (`BounceHarness`, injected clock, fake device packages).
  Every set of sheets around two Delay walls stays silent, every pair asserted rather than the first
  failure, with the pre-#181 rule shown to fire on the same sets; the photo picker from a device
  capture; sheets do not turn the overlay's return into a new wall, Home does; a cold cache, a late
  lookup that fires through the re-check, lookups that never land, a failed lookup not retried; a
  launcher outside `SYSTEM_PACKAGES` with an empty launcher set, before its facts land, and a stale
  launcher set.
- `BounceCaptureReplayTest` (L2): the three Android 12 bench captures
  (`a11y-captures/bounce-181-{delay-walkaway,delay-letthrough,autokick-cooldown}.jsonl`, walls from
  their `# WALL` headers) never fire; each plus two more apps and a wall fires with `MIN_APPS`; the
  arithmetic candidate (the launcher renamed out of the launcher set) stays silent, and the pre-#181
  rule fires on it.
- `AccessibilityEventTraceBounceTest`: the BOUNCE / BOUNCE-SKIP lines, no domain, nothing with debug
  logging off.
- `BounceCheckInWiringContractTest` (source level, the residue no value test can see).
- `BounceCheckInRowCopyTest`: the row is never ON-and-silent.
- **L6 (device), 2026-10-05, v1.21.0 vc60 release APK on the bench Pixel 3:** YouTube DELAY wall, Home, three apps, YouTube wall posted `apps=4 minutes=1` (notification id 3 on `nudge_bounce_checkins` with the exact copy); an immediate repeat inside the cooldown posted nothing; switched off, the same sequence posted nothing; switched back on, it fired again; the 1.20.0 redirect bubble and picker still worked; no crashes. Screenshots `~/Pictures/screenshots/nudge-browtf-*.png`.
- **L6 `bounce-non-apps` (`scripts/device-qa.sh`, group3, #181):** a YouTube Delay wall, two system
  sheets and one app around a second wall (`MIN_APPS` packages on `MIN_WALLS` walls, which pre-#181
  code would post on) must post nothing and log `not_launchable` for each sheet; then YouTube + 3
  apps on two walls must post once with exactly that set and `walls=2`, and one more app inside the
  cooldown must not post again. Constants come from the Kotlin sources; the apps and sheets are
  picked on the device and their launcher entries checked there; an unreadable answer is a FIXTURE
  failure. The switch is set by importing a settings-only backup and is turned back off after every
  outcome, and nothing dumps the UI once the streak is live (a dump can rebind the service).
  Device-free coverage in `scripts/test-device-qa.sh`. Only the Android 12 bench runs it; Android 17
  has not been run.
