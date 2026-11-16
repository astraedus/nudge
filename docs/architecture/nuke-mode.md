# Nuke Mode — a second list, a physical key, and no daily pass

Covers what Nuke is, how it is stored, where it joins enforcement, what it overrides, the gates that
keep it from being theatre, and — honestly — what it cannot stop.
**Read before touching `domain/nuke/`, `ui/nuke/`, `ui/screens/nuke/`, `EssentialApps`, the Nuke
lines in `NudgeAccessibilityService`, `NukeBlockContent`, or any gate on the master toggle.**

## What it is (Anti, 2026-09-29)

> "A nuke option you can click, like a focus mode. When activated it's a DIFFERENT list — separate
> from the usual apps you block in Nudge. When nuke is on, every app in the nuke list you just cannot
> open. Pair it with toggling nuke via a QR code — scan the QR code to turn it on AND off. And an
> emergency option: you can disable the nuke, but you have to type a long thing. Just make it
> annoying."

So Nuke is three things:

1. **A list** of packages, independent of `BlockRule`s and groups. A rule-less app can be nuked; a
   nuked app's own rules are simply overridden while Nuke is on and come back untouched when it ends.
2. **A key**: a QR Nudge generates (`nudge-nuke:` + 32 `SecureRandom` bytes, base64url), or any
   QR/barcode the user already has. Scanning it while Nuke is off turns it ON; scanning it while on
   turns it OFF. A plain "Nuke now" button also turns it on, because strengthening is always free.
3. **An emergency way out**: a fresh random 64-character code, typed by hand, case-sensitive, paste
   suppressed. Annoying on purpose.

## The model

| Piece | Where | Notes |
|---|---|---|
| `NukeState(active, packages, keyHash, keyKind)` | `domain/nuke/NukeState.kt` | The whole persisted state as one value. `packages` is RAW: it may hold a floor package, so nothing enforces off it directly. It is the ONE list a running Nuke reads; profiles (below) only ever copy into it while Nuke is off. `timer` is the running timer, read only while `active`. |
| Profiles (#183) | `domain/nuke/NukeProfile.kt` (`NukeProfiles`), `data/preferences/NukeProfilesCodec.kt` | Named presets of the list. Pure edits (`save` updates a same-named profile in place, `rename`, `delete`, `sanitize`, `matching`) returning `Done` or a `Refusal`; names trimmed, case-insensitively unique, at most 30 chars, at most 10 profiles, never an empty list. Stored as one JSON string under `NUKE_PROFILES`; a corrupt value (or entry) is dropped, never a crash. |
| Timer (#184) | `domain/nuke/NukeTimer.kt`, `NukeClock` port → `service/AndroidNukeClock.kt` | See "The timer" below. `NukePolicy.keyWaitMs(state, now)` is the one question every gate asks. |
| Key generation, hashing, matching | `domain/nuke/NukeKey.kt` | Only the lowercase-hex SHA-256 of the trimmed payload is stored. Constant-time compare (`MessageDigest.isEqual`). Blank and >4096-char payloads are never keys. |
| Every decision | `domain/nuke/NukePolicy.kt` | `isNuked`, the safety floor, `armBlocker`/`canArm`, `isWeakening(old, new)`, `masterToggleOffRequiresUnlock`. |
| Scan meaning | `domain/nuke/NukeScan.kt` | `resolveToggle` → Arm / Disarm / WrongKey / NoKey / CannotArm / Cancelled. `NukeEmergencyCode` (length 64, `SecureRandom`) reuses `StrictModeChallenge` wholesale. |
| Persistence | `NudgePreferences` (`NUKE_*` keys) | DataStore, so it survives reboot and service restarts. Dumb writes, except ONE invariant: never on without a key (`setNukeActive(true)` with no key stays off; `clearNukeKey()` also turns Nuke off). |
| Device safety floor | `service/EssentialApps.kt` | The one essentials list (spec 5.4), which group and All apps coverage read too. The static floor plus this phone's launcher(s), default dialer (`TelecomManager`), keyboards (`InputMethodManager`), default SMS app (`Telephony.Sms`), clock (`AlarmClock.ACTION_SHOW_ALARMS` resolvers), emergency apps (the `EMERGENCY_ASSISTANCE` resolver plus `com.android.emergency` / `com.google.android.apps.safetyhub`), enabled accessibility services and installed autofill services, and its own package. One labelled `EssentialSource` per query; a source that throws drops only itself. Binder calls, cached 5 minutes, off-main only. |
| Evaluation hook | `NukeEnforcement` (interface) → `service/NukeEnforcementSource.kt` | What `EvaluateBlockUseCase` asks. |

**Why SHA-256 and not bcrypt.** A password hash slows down guessing a low-entropy secret. A
generated key is 256 random bits. A product barcode is low-entropy, but the attacker in this threat
model is the user holding the phone, who can walk to the cupboard or type the emergency code far
faster than they can brute-force a digest. The hash protects the payload from being READ (a backup,
a debug dump, a prefs file), not from being guessed.

## Where it joins enforcement

### The pure step
`BlockEngine.evaluate(..., nuked = true)` returns `BlockEngine.NUKE_DECISION` as its FIRST step: a
`HARD_BLOCK` flagged `nuke = true`, with no delay, no daily-limit fields, no grayscale and no tab
cover. It overrides every rule and every limit rather than competing with them (`BlockEngineNukeTest`
runs every `BlockMode` × limit spent/unspent × feature/no feature and asserts the WHOLE decision).

`EvaluateBlockUseCase.invoke` asks `NukeEnforcement.isNuked(pkg)` before any rule or usage read. Its
constructor takes the enforcement with NO default: a defaulted "never nuked" is the silent no-op that
would let every nuked app open. Every app-level caller goes through `invoke` — the foreground event,
the issue #7 content-change fallback, in-app feature detection, the Reels tab cover, the daily-limit
clock (`enforceExhaustedBudget`) — so there is one place Nuke joins evaluation.

It is a `HARD_BLOCK` on purpose, not a new `BlockMode`: the stats, the walk-away row, `KNOWN_MODES`,
the interventions chart and `enforceExhaustedBudget` all already understand a hard block, and a
sixth mode would have meant the four hand-edited mode lists in `block-overlay-lifecycle.md` all over
again. The `nuke` flag is what the overlay and the launch fingerprint read.

### The service (the hot path)
`NudgeAccessibilityService` caches `nukeActiveCached` + `nukedPackagesCached` (the ENFORCEABLE set,
device floor already removed) off-main from `NudgePreferences.nukeState`, exactly like Strict Mode
and the master toggle. `isNukedNow` re-applies the static floor on top, so a stale cache still cannot
nuke the dialer. The event path never blocks on DataStore or PackageManager.

Every site it touches:

| Site | What changed | Why |
|---|---|---|
| `evaluateForegroundPackage` | The daily pass, the auto-kick cooldown overlay, the time-remaining overlay and the passthrough skip were extracted into `grantLetsThrough`, and a nuked package never consults it: `if (!nuked && grantLetsThrough(...)) return`. | These are all ways INTO an app a rule gates. None is a way into a nuked app. Everything above (grayscale, web session end, counter, clock) still runs. |
| same, browser branch | `if (!nuked && isBrowser(pkg))` | A nuked browser is blocked as an APP, whatever site is open. |
| `handleWindowContentChanged` | A nuked package outside `InAppDetector.SUPPORTED_PACKAGES` takes the verified #7 switch check instead of the URL-bar read. | Otherwise a nuked browser re-entered via content changes only could reach `evaluateWebDomain`, which can only block a SITE. Supported packages already re-evaluate the whole app on every content change. |
| `detectAndEvaluateFeature` | Stands down for a nuked app. | The foreground path already blocked it whole; a feature evaluation on top would launch the same block again under a different confrontation key. |
| `onServiceConnected` collector | On apps becoming nuked (Nuke ON, or apps added while on), `reevaluateForegroundForNuke` reads the live active window and, if it is a newly nuked app, spends the debounce and evaluates it. | A grant open when Nuke starts (a completed delay, a daily pass, a cooldown) must not keep the app on screen until the next window event. Usually the user armed Nuke from inside Nudge, so this is a no-op. |
| `handleDecision` | `EXTRA_NUKE`; fingerprint mode `"NUKE"` | A pending RULE hard block for the same app must not swallow the Nuke block as a duplicate (issue #50's shape). The ROW is still `blockMode = HARD_BLOCK`. |
| `maybeGuardSettingsEscape` | Engages while Nuke is on, at `max(64, strict length)`. | See the escape route below. |

**Untouched on purpose:** the Home/SystemSurface early return (launcher, SystemUI, Settings are on the
floor and can never be nuked, so nothing below it concerns Nuke), the PiP / own-UI / transient
returns (they are not the user being in an app), and the global master-toggle gate (Nuke lives UNDER
it — see the master toggle gate below).

### The overlay
`BlockOverlayActivity` renders `NukeBlockContent` for a `HARD_BLOCK` carrying `EXTRA_NUKE`: "Nuked.
[App] is off until you end Nuke. Scan your Nuke code to end it." and ONE button, "Go home", wired to
the same `navigateHome()` every block uses — the walk-away row, `GLOBAL_ACTION_HOME`, the #26
departure window. `NukeBlockContent` takes no emergency-pass parameters at all, so the daily pass
cannot be put on it by any caller (`NukeWiringContractTest`). There is no completion path, like any
hard block.

## Why there is no daily 2-minute pass

The daily pass exists so that a RULE, which the user set in a calm moment, has a cheap, rationed
exit for the moment the rule is wrong. Nuke is the opposite commitment: the user has chosen,
deliberately, a mode with no cheap exit, and has moved the exit somewhere physical. A 2-minute pass
on a nuked app would make Nuke a rule with extra steps. The exits are the key and the 64-character
code; both live in the app, never on the block.

## The gates (else Nuke is theatre)

`NukePolicy.isWeakening(old, new)` is the policy; `ui/nuke/NukeGate` is the ViewModel-side gate
(same shape as `StrictModeGate`: run now, or stash behind a dialog); `ui/nuke/NukeUnlockHost` is the
dialog (scan, or type a fresh 64-char code via the reused `ChallengeDialog`).

| Action | Nuke off | Nuke on |
|---|---|---|
| Turn Nuke on (scan or "Nuke now") | needs a key + a non-empty enforceable list | — |
| Turn Nuke off | — | key scan or emergency code |
| Add an app | free | free |
| Remove an app | free | key or code |
| Pair the first key | free | — (a key exists whenever Nuke is on) |
| Replace / remove the key | free | key or code (removing also ends Nuke) |
| Master toggle OFF (Home) | Strict Mode's gate only | Nuke's gate, THEN Strict Mode's |
| Master toggle OFF (Protection widget) | allowed unless Strict Mode | refused in the widget; opens the app |
| Import a backup | cannot touch Nuke | cannot touch Nuke |
| Save / rename / delete a profile (#183) | free | free (writes only `NUKE_PROFILES`; the running list is untouched) |
| Switch the list to a profile, or its Undo (#183) | free (replaces the list) | refused: "End Nuke to switch profiles." |
| Pick "Lock code for" (#184) | free (used by the next start) | not offered |
| Any "key or code" row above, while the timer runs (#184) | — | the key is refused with the time left; the emergency code works |

Every unlock re-reads the LIVE key (a re-pair between opening the dialog and scanning counts), and
every trip into typing mode generates a NEW code.

**Pairing a generated QR requires scanning it back once** before it is saved. That proves the user
kept it somewhere a camera can see; pairing a code they never saved would leave the emergency code as
the only way out. A phone with no camera cannot pair at all, and the screen says so rather than
letting every scan silently return nothing.

### The master toggle gate
Every enforcement path sits behind `globalEnabled`, so with Nuke on the master switch is the obvious
one-tap way around it. `HomeViewModel.toggleGlobalEnabled` routes ON→OFF through `NukeGate` first
(when `masterToggleOffRequiresUnlock`) and then through the existing Strict Mode gate. Turning it ON
is never gated. Passing the gate turns the master toggle off but leaves Nuke's state as it was, so
turning Nudge back on resumes Nuke; the global toggle means "behave as if uninstalled", and that
includes Nuke.

The Protection widget's `togglesInWidget` is `!(enabled && (strictModeEnabled || nukeActive))`, and
`ToggleProtectionAction` carries `!strictModeEnabled && !nukeActive` on the same line as the write
(`WidgetStrictModeContractTest`, `WidgetSnapshotMapperTest` over all sixteen inputs). The updater
collects `nukeState.active`, so the widget re-renders when Nuke changes.

### The OS escape route
While Nuke is on the Strict Mode escape-route guard (`StrictModeGuardActivity` over the Nudge
accessibility toggle and App Info / Force stop / Uninstall) engages as if Strict Mode were on, with
the challenge at `max(64, Strict Mode length)` and Nuke copy (`EXTRA_NUKE`). Solving it opens the
usual 60-second grace window. This reuses the one guard rather than building a second: its safety
invariant — cancel always goes home, the challenge is always solvable — is the same code path it
always was. It does NOT end Nuke; it lets the user do the OS action they committed to (after which
enforcement stops because the service stops, and Nuke resumes if they turn it back on).

## Safety floor

Nuke must never block what a phone cannot be without, or the ways out of Nudge: the dialer and
in-call UI, emergency apps, the launcher, SystemUI, Settings (every OEM settings package the escape
guard knows), the installer/permission dialogs, every keyboard (the emergency code is TYPED), and
Nudge itself. `NukePolicy.STATIC_FLOOR` is the static half; `EssentialApps` adds THIS phone's
launcher, dialer and keyboards, and (since spec PR 1) the default SMS app, every app that shows
alarms, the emergency-assistance app, every enabled accessibility service (a screen reader must
never be blocked) and every installed autofill service (a password manager unlocks itself inside the
app it is filling). Each of those is one isolated query: one that throws costs only its own
packages, never the rest of the list and never the static half. Camera, Maps, browsers and banking
apps are deliberately NOT on it (owner decision, spec section 8). A browser a device source names
anyway is taken back off: Firefox declares an autofill service, so until PR 4 Nuke could not nuke
Firefox. The browsers removed are the ones Nudge reads URLs from plus every app that opens an
ordinary https link; the static half is never trimmed.

The same list is the floor for group and All apps rules (spec 4.2): neither ever covers an essential
app, whatever its allowed list says. An explicit rule on that one app still applies. The Nuke screen says so in one
sentence: "Your phone, messages, clock, launcher, keyboard and Settings are never on this list."
The floor is applied in three places: the picker never offers a floor
package, `NukePolicy.isNuked` refuses one even if stored, and the service's cache is built through
`NukePolicy.enforceable` with `isNukedNow` re-checking the static half. "Arm" counts only enforceable
packages, so a list of only floor apps cannot be armed.

**One count.** "Nuke is on · N apps" appears on the dashboard card and on the Nuke screen, and both
are `NukePolicy.nukedCount(state, essentialApps.packages())`: the apps Nuke actually blocks. A stored
app that is now essential (Messages listed before spec PR 1 made it essential) is left out of BOTH,
but stays in storage, so it counts again if this phone stops treating it as essential. Before this,
the card used only the static floor and said "3 apps" next to a screen that said "1 app" (found on
the bench in spec PR 1; pinned by `NukeCountParityTest`).

## Profiles (#183)

A profile is a saved copy of the list, nothing more. The screen shows them as a row of FilterChips
under the "never on this list" caption (nothing at all until the first "Save as profile", so with no
profiles the section looks exactly as it did before #183); the chip whose apps equal the current list
shows as selected, by fill alone (no check mark, so a chip never changes width). Tapping another chip
while Nuke is off makes the list exactly that profile's apps, with an "Undo" snackbar holding the list
from before. Tapping the selected chip, or any chip while Nuke is on, opens Rename / Delete. "Save as
profile" is a text link on the header row (`NukeUiState.canSaveProfile`): offered only when the list
is non-empty and is not already exactly a saved profile, since saving then would only duplicate it.
It is a clickable Text rather than a TextButton so the header keeps its height; Compose's hit testing
still gives it a 48dp touch target.

Why a preset and not a live link: the running Nuke reads `NUKE_PACKAGES` and nothing else, so no
profile edit can weaken a running Nuke (deleting the profile it came from changes nothing), and the
safety floor applies exactly as before (`NukePolicy.enforceable`), since a profile only ever lands in
the same raw list. Switching profiles while Nuke is on would be "remove apps" in disguise, so
`replaceNukePackagesWhileOff` refuses it inside the same DataStore transaction that reads `active`
(an Undo that lands after Nuke started is refused too).

## The timer (#184)

Before starting, the user may lock the code for a while: the "Lock code for" row in the off card
(None, 15, 30, 45 min, 1 or 2 hours; remembered, offered only when a key is paired and only while Nuke
is off). The words are about the CODE on purpose: on the bench "Timer" read as "Nuke turns off after
15 min", which is the opposite of what it does. The ON card says "Code locked · X left" while it runs
and nothing once it has run out (the plain ON card). While it runs, the key unlocks nothing: every
"key or code" row in the gate table answers "Your code is locked for X more min. Scan again after
that." from
`NukeGate` (and `NukeScan.resolveToggle` → `TimerRunning` for scan-to-toggle). Adding apps stays free.
The emergency code ignores the timer, always. When the timer runs out NOTHING happens by itself: no
auto-unlock, Nuke stays on until the key or the code ends it, and ending Nuke clears the timer.
Starting again while on never replaces a running timer (`armNuke` is a no-op when already active).

**The clock model.** Time is phone-on time on the monotonic clock (`SystemClock.elapsedRealtime`)
plus `Settings.Global.BOOT_COUNT`, counted in one segment per boot: counted = banked + (now − segment
start) on the same boot. The wall clock is never read, so changing the date, time or zone (Settings is
reachable during Nuke) moves nothing. The app banks the running segment on screen-off, on
`ACTION_SHUTDOWN` (`goAsync`) and when the Nuke screen opens.
- **A reboot** carries the banked time forward and counts the new boot's uptime; the old boot's
  unbanked tail is lost, so the timer can only run LONGER than asked, never end early.
- **Time with the phone off does not count.** A timer is "N minutes of phone time", by design:
  otherwise switching the phone off would be the way to wait it out.
- **No boot count** (a phone that will not say): a clock behind the segment start means a reboot, one
  ahead is treated as the same boot. Both can only under-count.
- **A corrupt stored timer** (impossible duration, longer than 24 h, missing start) reads as no timer:
  Nuke stays on and the key works, so a bad value can never trap the key.

Limits: a hard power-off or crash loses the time since the last checkpoint (the timer runs longer);
the timer is enforced by the app, so safe mode, `adb` or a reset bypass it like everything else here;
and the emergency code always works, by the product rule.

## Export / import

Nuke state is DEVICE-LOCAL, like the emergency-pass ledger. `ExportedSettings` has no Nuke field, an
export never mentions it, and `applyImportedSettings` cannot write a `NUKE_*` key: a hand-edited
backup saying `"nukeActive": false` would otherwise end Nuke without the key, and one carrying a
`nukeKeyHash` would swap it. Profiles, the timer and the timer choice are device-local too (a
backup carrying a short timer must not replace a running one). Pinned by `NukeExportExclusionTest` (the real exporter over a hostile
file) and `ImportedSettingsWriteContractTest`'s device-local list.

## Threat model, and its honest limits

Nuke is a commitment device against the user's own impulses, not a security boundary against an
adversary. What it closes: every in-app path (list edits, key swaps, master toggle, widget, import),
every grant (pass, cooldown, passthrough), the browser-as-website path, and — best-effort — the OS
screens that switch Nudge off.

What it cannot close, and does not pretend to:
- **Safe mode, `adb`, a factory reset, or a second device** all bypass any app-level blocker.
- **The escape-route guard is best-effort detection**, tuned on AOSP/English (see `strict-mode.md`).
  An OEM settings screen or locale it does not recognise is simply not guarded — a miss, never a
  trap.
- **A reboot before the service binds**: Nuke state persists and is re-read on bind, but between
  boot and the accessibility service connecting, nothing is enforced (true of every block).
- **The key is only as far away as the user put it.** A barcode on the fridge is a short walk.
- **The emergency code is deliberately always solvable.** Sixty-four characters is friction, not a
  lock; the product rule is that Nudge can never hard-trap anyone.

## Tests

| Layer | Test | Pins |
|---|---|---|
| L1 | `NukeKeyTest` | token format, the injected random source, SHA-256 vector, whitespace, blank/oversize refusal, right/wrong key, no-key, hash never contains the payload |
| L1 | `NukePolicyTest` | enforcement truth table, the static + device floor (every floor entry), arming, `isWeakening` both directions on every axis, master toggle |
| L1 | `NukeScanTest` | every toggle outcome, the 64-char code (length, charset, freshness, exact/dash/case), `MAX_LENGTH` unchanged |
| L1 | `BlockEngineNukeTest` | Nuke overrides every mode × limit × feature, and the non-nuked answer is byte-for-byte the old one |
| L1 | `EvaluateBlockNukeTest` | through the real use case: nuked → Nuke decision with no rule/usage read; not listed / off / floor → the rule's answer |
| L1 | `NukeGateTest` | key, wrong key, cancelled scan, live re-read, fresh emergency code, exact typing, cancel |
| L1 | `EssentialAppsTest`, `EnabledAccessibilityServicesTest` | every essentials source lands on the list, a throwing source drops only itself (and is logged by label), all throwing still leaves the static floor, the production source labels are exactly the pinned set, the 5-minute cache, no camera, a browser named by any source is not essential (also when the browser query throws), the static floor is never trimmed; the accessibility-settings package parse |
| L3 | `EvaluateBlockAllAppsTest` | a nuked app that an All apps rule allows is still nuked |
| L1 | `NukeViewModelTest` | arm needs key + list, key toggles, wrong key does not disarm, emergency disarms, remove/replace/unpair gated only while on, add free, QR saved only after the same code is scanned back, the picker never offers the floor |
| L1 | `NukeCountParityTest` | the dashboard card and the Nuke screen show the same count through both real ViewModels: stored essentials left out of both, an app that stops being essential counts again on both, the static floor left out of both |
| L1 | `HomeNukeToggleGateTest` | master toggle OFF waits for the key or the code while Nuke is on; during a timer the key is refused and the code still works |
| L1 | `NukeTimerTest` | the clock model: same boot, reboot carries banked time, a reboot never ends it early, a power cut only lengthens it, no boot count either way, a clock that went backwards, clamps, choices, `fromStored` fails soft, `keyWaitMs` |
| L1 | `NukeProfilesTest`, `NukeProfilesCodecTest` | name rules and bounds, update-in-place, rename/delete, `matching`, `sanitize`; the stored JSON round-trips and every corrupt shape reads as nothing (same on Android's and the desktop org.json) |
| L1 | `NukeTimerCopyTest` | every code-lock line: rounds up, refusal (minutes and hours), status line gone once run out, picker labels, and no line calls it a "timer" |
| L1 | `NukeGateTest`, `NukeScanTest` (timer cases) | the right key refused with the time left (flagged `errorIsCodeLock`, so the dialog shows it as plain text, not the error colour), works after; wrong key still wrong (and an error again); the emergency code at any time; a leftover timer on an off Nuke holds nothing |
| L1 | `NukeViewModelTimerProfilesTest` | through the real VM + gate + preferences: the picked timer starts with both starts; EVERY key-gated weakening refused during the timer; adding free; emergency ends and clears; reboot never ends early; time zone moves nothing; upgrade from the old keys; profile save/apply/Undo/refusals; a running Nuke's list survives every profile edit |
| L4 | `NukePreferencesTimerProfilesTest` | the real preferences: upgrade from old on-disk keys, arm never without a key, never replaces a running timer, end/unpair clear it, checkpoint writes only when it should, corrupt timer/profiles fail soft, profile edits touch only `NUKE_PROFILES`, switch only while off |
| L4 | `NukeExportExclusionTest`, `ImportedSettingsWriteContractTest` | nothing about Nuke (profiles and timer included) leaves or enters through a backup; every `NUKE_*` key is absent from the export/import code |
| contract | `NukeWiringContractTest` | every grant sits in `grantLetsThrough`, a nuked app never consults it, nuked browser, content-change + feature paths, re-evaluate on arm, overlay flag + fingerprint, escape guard, the Nuke overlay has no pass and walks away through `navigateHome`; the timer's checkpoints (screen-off, shutdown receiver registered/torn down, screen open) and no wall-clock read on its path |
| contract | `WidgetStrictModeContractTest`, `WidgetSnapshotMapperTest` | the widget cannot turn Nudge off during Nuke |
| L6 | device case list in the Nuke PR | the overlay on a real app, no pass, reboot persistence, the Settings guard, the camera flows |
