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
| **Fire** | Inside one streak: walls ≥ `MIN_WALLS` (2) AND distinct apps ≥ `MIN_APPS` (4). |
| **Cooldown** | `COOLDOWN_MS` = 30 min after a fire. Nothing is fed or held during it; the next wall after it starts fresh. |

The notification: title **"Bro. wtf."**, body *"You've bounced through N apps in M minutes. Wanna
take a break?"*. N is the distinct app count. M is first wall to fire, rounded UP (a 40-second bounce
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
and it is not being asked that here. It only bounds what an over-count costs, and the cost of a miss
(a share sheet or a photo picker counting as an app) is one extra toward a soft notification. That
failure direction is what makes a short list acceptable here and nowhere near enforcement.

## Where it is fed (and why only there)

| Feed | Site | Why there |
|---|---|---|
| Apps / Home | `applyForegroundSignal` → `bounceCheckIn.onForegroundSignal(signal)` | The one place every classified signal passes, above every early return. The fourth consumer next to the launch guard, the sitting and the tab cover. |
| Overlay walls | `launchBlockOverlay`, after the gate's refusal return, before `startActivity` | Every overlay in the service passes through here once per ALLOWED launch. A dropped launch is no wall the user saw. Reports `targetPackage` (the browser, for a website block: the app they were in). |
| Kick walls | `AutoKickExecutor.kick` → `onKicked(key)` | The single kick path for both triggers. A `web:<domain>` key is mapped back to the browser the user was in. |
| Reset | `onGlobalDisabled` | Nudge off behaves as if uninstalled, a half-finished streak included. |

Pinned by `BounceCheckInWiringContractTest` (source level: the four sites above, one wall report per
launch, after the gate, and the event path never reads the preference).

## Cost

- **Off (the default):** `BounceCheckIn.enabled` is a cached `@Volatile` boolean, kept in step by a
  collector on `NudgePreferences.bounceCheckInEnabled` (with `distinctUntilChanged`, #63). Off, both
  feeds return on their first line. No DataStore read on the event path, ever.
- **On, not armed:** one volatile read plus one uncontended lock and a null check per event.
- **On, armed:** a constant-time update of a `LinkedHashSet` bounded at `MAX_TRACKED_APPS` (64).
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
nudge when you bounce between apps after hitting a wall."*, default OFF. Not a protection setting, so
Strict Mode does not gate it either way. Turning it ON on Android 13+ without the notification grant
asks for `POST_NOTIFICATIONS` there and then. While it is ON and notifications cannot be posted
(app notifications off, or this channel blocked), the subtitle says so in the error colour and a tap
on the row opens Nudge's notification settings; the state is re-read on resume. Off never mentions
notifications. The copy decision is the pure `bounceCheckInRowCopy` (`BounceCheckInRowCopyTest`).

The preference is device-local and NOT carried by a backup (it is a per-phone convenience, and the
export settings set is pinned by `ImportedSettingsWriteContractTest`).

## Tests

- `BounceDetectorTest` (L1): no fire on one wall or on two walls with too few apps; exact-threshold
  fire with real N/M; second wall crossing via a different package; minutes from the first wall,
  rounded up; same-overlay re-launches are one wall; Home-and-straight-back is a second wall (with the
  counterfactual on the same config); the same app twice counts once; exclusions neither count nor
  count as leaving; window expiry disarms and leaves nothing behind; the window edge; the sliding
  window; a backwards clock; cooldown suppresses then resets; `reset`; the bounded set; config
  validation; the copy.
- `BounceCheckInTest` (L1): the owner's sequence through real `ForegroundSignal`s; the toggle-OFF path
  feeds nothing; default off; switch-off mid-streak drops it; only `AppWindow` counts; cooldown across
  the adapter; the master-toggle reset; the auto-kick reports its key.
- `BounceCheckInWiringContractTest` (source level, the residue no value test can see).
- `BounceCheckInRowCopyTest`: the row is never ON-and-silent.
- **L6 (device), 2026-10-05, v1.21.0 vc60 release APK on the bench Pixel 3:** YouTube DELAY wall, Home, three apps, YouTube wall posted `apps=4 minutes=1` (notification id 3 on `nudge_bounce_checkins` with the exact copy); an immediate repeat inside the cooldown posted nothing; switched off, the same sequence posted nothing; switched back on, it fired again; the 1.20.0 redirect bubble and picker still worked; no crashes. Screenshots `~/Pictures/screenshots/nudge-browtf-*.png`.
