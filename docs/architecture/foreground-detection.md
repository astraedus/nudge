# Foreground detection and passthrough, what "the user is in app P" means

Covers every rule the accessibility service uses to decide whether an event really means a package is the
foreground app, and when a completed delay's passthrough grant is revoked: transient windows (keyboards,
paste popups), the Home/launcher path, the content-change app-switch fallback, and picture-in-picture.
**Read before touching the event dispatch in `NudgeAccessibilityService`, `PassthroughManager`, or anything that adds an early return to the hot path, the bugs in here were all ORDERING bugs, not logic bugs.**

> **SUPERSEDED IN PART, v1.16.0 — read `accessibility-event-pipeline.md` first.**
> Everything below is still the true history of how each rule got here, and the *reasons* are all
> still binding. What changed is the MODEL underneath them. "The user left app X" is no longer
> answered by "a package that is not X fired a window event, and is not in one of three hardcoded
> sets" — that was the root cause of [#28](https://github.com/astraedus/nudge/issues/28), and it is
> why each of those sets sprang in turn. A `SittingTracker` now owns the question, ending a sitting
> only on Home, screen-off, or another app holding the foreground past a two-minute return window
> (`SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS` -- deliberately NOT the interaction counter's
> five-minute session expiry; the two answer different questions).
> Where a section below describes `clearIfAppChanged` being called on the app-switch path, or
> `SYSTEM_PACKAGES` deciding whether the user left, that mechanism is gone; the requirement it was
> serving is not.

## Post-overlay passthrough

- **Post-overlay passthrough** — after delay/breathing completes, skip re-evaluation until user leaves app. Prevents infinite overlay loop.

## Transient-window handling (keyboard / paste-popup re-block) — v1.9.3

Fix for [#5](https://github.com/astraedus/nudge/issues/5): after completing a delay, `PassthroughManager` shields app X from re-blocking until a genuine app switch. The bug was that a soft keyboard **not** in the hardcoded list, or the `android` framework package (which hosts the paste / long-press popup toolbar + toasts), surfaced a *different* package on a window event; that reached `evaluateForegroundPackage → clearIfAppChanged`, which wiped X's passthrough, so tapping back into X re-triggered the delay. Gboard/Samsung keyboards were only shielded by being hardcoded in `SYSTEM_PACKAGES`; the reporter's **FUTO** keyboard was not, so it hit the bug.

- **`NudgeAccessibilityService.isTransientNonAppPackage(pkg, currentImePackage)`** (pure, `internal`, unit-tested) — true for the `FRAMEWORK_PACKAGE` (`"android"`), the static `IME_PACKAGES` fallback, or a **dynamic** match against `currentImePackage`. The active keyboard is read from `Settings.Secure.DEFAULT_INPUT_METHOD` (package half) and cached in `@Volatile currentImePackage`, kept fresh by a `ContentObserver` on that setting — so **every** keyboard is covered, not a hardcoded few.
- **`onAccessibilityEvent`**: after the own-package block, `if (isTransientNonAppPackage(pkg, currentImePackage)) return` — ignore the event entirely (no `clearOverlays`, no passthrough clear, no `lastPackage` move; the real app underneath hasn't changed). The 3 IME packages were **moved out** of `SYSTEM_PACKAGES` into `IME_PACKAGES` (they're now caught earlier by this return).
- **`isOverlayBypassedByForeground`** gained an optional `currentImePackage` param and now also excludes transients, so a keyboard/`android` window surfacing over a live block overlay isn't mistaken for the user re-entering the app.
- **Tests**: `TransientWindowTest` (FUTO-as-active-IME, hardcoded IME, `android`, real-app-never-transient, and the overlay-bypass regression guard). Device-verified on the Pixel 3: keyboard + paste-popup no longer re-block (logcat `skip evaluation … reason=passthrough` on return); a genuine app switch still re-blocks (logcat confirms `foreground evaluation` re-fires on real returns).

## Going HOME re-arms the delay — the launcher is not "just another system package"

Found in device QA (2026-08-27, reproduced 3x on the Pixel 3): **once a user completed a delay for an app, the passthrough grant never cleared via the Home path.** Pass YouTube's delay → press Home → reopen YouTube → no delay, indefinitely; only opening a DIFFERENT non-system app in between re-armed it. That is the most common exit path there is, so the delay was effectively one-shot per app.

Root cause is an **ordering** one, not a logic one: `SYSTEM_PACKAGES` contains the stock launchers, and `onAccessibilityEvent`'s `if (packageName in SYSTEM_PACKAGES) { clearOverlays(); return }` fires ~200 lines before `evaluateForegroundPackage` reaches `PassthroughManager.clearIfAppChanged`. The launcher event cleared the awareness overlays and moved `lastPackage`, but the passthrough grant survived untouched. (A THIRD-PARTY launcher like Nova was never affected — it isn't in `SYSTEM_PACKAGES`, so it fell through to the normal app-switch clear. The bug was parity loss for the stock ones.)

- **The launcher is resolved, never hardcoded.** `resolveLauncherPackages()` reads `Intent.ACTION_MAIN` + `CATEGORY_HOME` via `resolveActivity` (the current default — the one Home actually goes to) **plus** `queryIntentActivities` (every installed home-capable app, so switching launchers is covered between refreshes). Cached in `@Volatile launcherPackagesCached` like the Strict-Mode / global-enabled flags, refreshed lazily at most every 5 min (`refreshLauncherPackagesIfStale`) and only from the system-package branch on a real foreground change — so the steady-state cost inside an app is **zero**, and the hot path is one set lookup.
- **`sanitizeLauncherPackages(resolved, ownPkg)`** (pure, unit-tested) drops what a `CATEGORY_HOME` query wrongly returns. The load-bearing exclusion is **`com.android.settings`**: AOSP declares `Settings$FallbackHome` with `CATEGORY_HOME` + `CATEGORY_DEFAULT` (the placeholder home before first unlock after a reboot), so a stock `queryIntentActivities` really does hand you Settings — and treating Settings as home would clear passthrough on every permission excursion. Also dropped: the `android` framework package (an unset default home resolves to the chooser/ResolverActivity), our own package, any IME, systemui and the two permission/installer surfaces.
- **`isHomeScreenForeground(eventType, pkg, launcherPackages, ownPkg, currentImePackage)`** (pure, `internal`, unit-tested) is the whole decision. Restricted to **`TYPE_WINDOW_STATE_CHANGED`** for exactly the reason `isOverlayBypassedByForeground` is: it is the only event type meaning "a new activity is in front", and launcher content-change churn (widgets, wallpaper, the icon grid redrawing behind a fullscreen app) is not evidence anything came forward.
- **Why an allowlist and not "clear for every system package".** The shade/SystemUI, the IME, a permission dialog and our own overlay all foreground *briefly without the user leaving the app*. Clearing on those would re-delay a user for pulling the notification shade — a regression users would hate more than the bug (it is issue #5 all over again). So an unresolvable or stale launcher set clears **nothing** and behaves exactly as the service did before; the failure direction is "miss a clear", never "clear falsely".
- **`clearPassthroughForHome`** drops the app-level grant (`clearIfAppChanged`) **and** `lastBlockedDomain` — the web passthrough is only ever cleared inside `evaluateForegroundPackage`, which this return skips, so a completed web delay survived Home identically. Same "the user left the app" semantics, applied consistently.
- **It touches NOTHING else, deliberately.** `InteractionTracker`'s 5-minute session expiry and the auto-kick cooldown treat a quick trip home as the SAME sitting on purpose (a tab-out-and-back must not refill a time budget). Leaving the app revokes permission to SKIP a delay; it does not end the session.
- **Accepted behaviour change**: on Pixel the recents overview is hosted *by the launcher*, so opening Overview and returning to the same app now costs a fresh delay. This matches how issue #8 already treats the overlay ("Home, a recents switch or screen-off dismiss the overlay… the next entry gets a fresh FULL delay"), and friction on re-entry is the feature.
- **`PassthroughManager` has no time-based expiry at all** — it records `lastTime` on `grant()` and *never reads it*; a grant lives until an app change or process death. Deliberately left alone here: a naive `now - lastTime > N` would re-block a user **mid-use**, still inside the app, which is the issue #5 failure class. A screen-off clear is the safer defence-in-depth candidate if this is ever revisited. **(v1.16.0: that candidate was taken.** The grant still has no timer — what expires is the SITTING, and only on evidence that the user stopped: Home, screen-off, or another app in front past the return window. Backlog F5 closed; see `accessibility-event-pipeline.md`.)
- **Tests**: `HomeScreenPassthroughTest` (24 — launcher clears; shade / active-IME (FUTO) / hardcoded IME / `android` / own overlay / permission dialog / Settings do not; content-change, windows-changed and view-clicked from the launcher do not; an unresolved launcher set clears nothing; the `sanitizeLauncherPackages` exclusion matrix; and cooldown + session counts untouched by the clear) and `HomeScreenPassthroughContractTest` (7, source-level in the spirit of `BlockOverlayWalkAwayContractTest` — the bug was *where an early return sat*, which no value-level test can see: the home check must run inside the system-package branch before it returns, passthrough must never be cleared unconditionally there, the Strict-Mode escape guard must still precede that branch, the global-toggle gate must still follow it, and the launcher set must be PackageManager-resolved and refreshed). Verified to fail against the pre-fix wiring.


## Content-change app-switch fallback (fixes #7)

Fix for [#7](https://github.com/astraedus/nudge/issues/7): "occasionally app timer does not start on re-entrance of app". Re-entering an app via the **recents overview** or a **notification tap** sometimes delivers only `TYPE_WINDOW_CONTENT_CHANGED`, with no `TYPE_WINDOW_STATE_CHANGED`. `handleWindowContentChanged()` only routed to `evaluateForegroundPackage()` for browsers and for `InAppDetector.SUPPORTED_PACKAGES`; for every other app such a re-entry produced **no evaluation at all** — no delay re-block, no counter session, no time-remaining overlay. This is the same defect previously logged in the backlog as "genuine app-switch may not re-block when the interaction counter overlay is active".

Edge-triggering on *every* content change would fix it and immediately reintroduce [#5](https://github.com/astraedus/nudge/issues/5) — content-change events also arrive from windows that are not in front, so a ghost app-switch would wipe post-delay passthrough and re-block the user. The fallback is therefore **state-verified**:

- **`NudgeAccessibilityService.shouldTreatContentChangeAsAppSwitch(packageName, lastPackage, ownPackageName, currentImePackage, activeWindowPackage)`** (pure, `internal`, unit-tested) — requires the event's package to also own the **real active window** (`rootInActiveWindow`), and rejects our own package, `SYSTEM_PACKAGES`, `FRAMEWORK_PACKAGE` and any IME (the active one matched dynamically) first. A null/unreadable active window is **never** a switch: unverifiable means do nothing, because a false positive costs the user their passthrough while a false negative just retries on the next event.
- `activeWindowPackage` is a **lambda**, invoked only after the cheap comparisons, so the node-tree read never runs for the app the user is already in. Additionally throttled per package (`SWITCH_CHECK_DEBOUNCE_MS = 500`) because `evaluateForegroundPackage` early-returns for an active emergency pass or live passthrough **without advancing `lastPackage`** — without the throttle the read would repeat on every content change for the whole of that window.
- The transient-window early-return in `onAccessibilityEvent` stays **upstream** of this path, and passthrough is still only ever cleared by `clearIfAppChanged` on a genuinely different foreground app.
- **`TYPE_WINDOWS_CHANGED` with a null package** (dropped at `event.packageName ?: return`) was investigated and deliberately **not** handled: on the Pixel 3 every recents re-entry was caught by the content-change path, so a null-package handler would add active-window reads with no behavioural gain.
- **Tests**: `ContentChangeAppSwitchTest` — verified re-entry evaluates; same package / unverified package / null active window / IME (incl. the FUTO case from #5) / framework / system / own package never do; plus a cost test asserting the active window is not read for cheaply-rejected events.
- **Device-verified with an explicit counterfactual** (Pixel 3, 15s DELAY rule on Keep, alternating Contacts ↔ Keep through the recents overview): on the pre-fix build **5 of 6 re-entries produced no `foreground evaluation` and no block** — the bug reproduced on demand; with the fix, **6 of 6** re-entries evaluated and blocked, every one of them routed through the new fallback. The #5 regression case (complete the delay, raise the keyboard with `mInputShown=true`, keep using the app) logs `ignoring transient non-app window …inputmethod.latin` and `skip evaluation … reason=passthrough` with **zero** re-blocks.

## Picture-in-picture escape — detect, explain, deep-link (fixes #19)

Fix for [#19](https://github.com/astraedus/nudge/issues/19), found by @polubarev during PR #17 QA: when the block overlay backgrounds YouTube, YouTube enters **picture-in-picture** and the Short keeps playing. `BlockOverlayActivity` is correctly fullscreen and `topResumedActivity` and **still loses** — a PiP window is always-on-top by design. This is platform behaviour, not an overlay bug, and there is **no public API** for one app to disable PiP for another. The only real remedy is the per-app PiP permission in Settings, which only the user can flip.

**The honest shape is therefore detect-and-deep-link, not detect-and-fight.** Nudge does not try to kill the PiP window.

### The v1.12.0 field failure — two root causes, both device-confirmed

The first version of this fix shipped in v1.12.0 and **failed device QA**: detection never fired once, and the stat inflation it was supposed to stop got *worse*. Both causes were found on the Pixel 3 (API 31) and both are worth remembering, because each is a trap the next person would fall into identically.

**1. SystemUI's PiP *menu* is also flagged PiP, and it sorts first.** `dumpsys accessibility` with a live YouTube bubble (the definitive probe — it prints the real `AccessibilityWindowInfo` list, including a `pictureInPicture=` field, with no build required):

```
title=Picture-in-Picture menu, type=TYPE_SYSTEM,      layer=2, pictureInPicture=true
title=YouTube,                 type=TYPE_APPLICATION, layer=1, pictureInPicture=true
title=Pixel Launcher,          type=TYPE_APPLICATION, layer=0, pictureInPicture=false  (active)
```

`isInPictureInPictureMode` works perfectly on API 31 — the bug was ours. The old code took the **first** flagged window, resolved its owner to `com.android.systemui`, compared that against the blocked package, got no match and returned false. Worse, its only log line sat *past* an early return, so the failure was completely silent and indistinguishable from "never detected". Hence: filter to `TYPE_APPLICATION`, return a **set** (so no other PiP-flagged window can shadow the app), and log unconditionally.

**2. The escape outlives the block — the deeper one.** The old check required a *live* block (`isOverlayActive` + `blockedPackage`). In the field the overlay dismisses and the **orphaned bubble keeps playing and keeps firing events**, each read as a fresh foreground entry → evaluate → block → `UsageEvent`. Measured: 9 re-blocks in 5 minutes, all-time Blocked +11 in one incident, firing while the tester was navigating **inside Nudge itself**. Reproduced here exactly: with only a bubble up and the launcher focused, Nudge started `BlockOverlayActivity` for YouTube.

**The general rule that replaced the special case.** Every evaluation path in this service assumes *"an event carrying package P means P is the foreground app"*. A PiP window breaks that assumption — P has a window and fires events while the user is somewhere else. So there is now **one gate ahead of the whole pipeline**: a package present only as a PiP window is not the foreground app and drives nothing (no evaluation, no block, no `UsageEvent`, no overlay bypass, no interaction counting). `isPipEscapeOfActiveBlock` and `blockedPackage` were deleted — the special case is subsumed.

### How it works now

- **`service/PipWindowProbe.kt`** — `packagesInPictureInPicture()` reads `AccessibilityService.getWindows()` (works because `accessibility_service_config.xml` already carries `flagRetrieveInteractiveWindows` for Strict Mode) and returns the owners of every **application** window in PiP. `pipPackages(windows)` and `pipOnlyPackages(pip, activeWindowPackage)` are pure companion functions, unit-tested against the **real captured Pixel 3 window list** above. The reader resolves a window's owner only for windows already flagged PiP (`getRoot()` is a binder read *per window*). A PiP window whose owner won't resolve is **skipped, never guessed** — callers compare against packages they are blocking, and a wrong match would suppress a real block.
- **`pipOnlyPackages`** subtracts the active window: an app in PiP that *is* the active window (the user expanding the bubble back to fullscreen) must re-block normally. A **null** active window still counts as PiP-only — a window in PiP is by definition not the fullscreen foreground app, and a missed block self-corrects the moment PiP ends, whereas guessing the other way resurrects the storm.
- **Cost.** `refreshPipOnlyPackages()` runs **only on window-change events** (a PiP window can only appear or vanish via one) with the probe throttled to 500ms; every other event pays a set lookup against `pipOnlyPackagesCached`. The active-window read happens only when something is actually in PiP. Entering PiP always involves a foreground change, so the cache is populated before any content-change from the bubble arrives.
- **Logging is bounded and unconditional.** The explainer decision and the log both run when the PiP set *changes*, not per event, and **both suppression reasons are logged** (`never_blocked_this_session`, `already_explained`). "Detection fired but stayed silent" vs "detection never fired" being indistinguishable is what cost the v1.12.0 cycle.
- **`markOverlayActive(pkg)` / `markOverlayInactive()`** (with `isOverlayActive` `private set`) are also where `blockedThisSession` is recorded — a launch site that set the flag but forgot the record would silently disable the explainer for that path. `BlockOverlayActivity` marks inside `render()`, not `onCreate`/`onNewIntent`, because those run before the intent is parsed.
- **The explainer is gated on "we blocked this app this session", not "a block is up right now"** — the reported repro reaches PiP minutes later via an emergency pass with no overlay on screen. An app floating in PiP is unremarkable; an app we were blocking floating in PiP is the escape.
- **Honest stats.** `PipEscapeActivity` writes no `UsageEvent` and grants no passthrough (a platform escape is neither a block the user hit nor a walk-away), and while `PipEscapeActivity.isActive` the service swallows events entirely — the explainer *stands in for* the block overlay, so re-evaluating behind it would relaunch the overlay on top of it and double-log.
- **`ui/overlay/PipEscapeActivity.kt` + `PipEscapeContent.kt`** — full-screen explainer registered like `StrictModeGuardActivity` (singleInstance, excludeFromRecents, empty taskAffinity), with the same `onStop → finish` discipline so no orphaned task lingers. Back / "Not now" just closes: unlike the Strict Mode guard it does **not** force the user home — the block overlay it replaced is already gone and there is nothing left to protect.
- **The deep link (device-probed, do not re-derive).** `Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS` is **NOT a public SDK constant** (it is `@hide` in AOSP) — referencing it will not compile. `ui/overlay/PipSettingsTarget.kt` holds the raw action string and an ordered candidate list, resolved at runtime via `resolveActivity` (the manifest's `QUERY_ALL_PACKAGES` makes this work on API 30+). Verified on the Pixel 3 / Android 12 with `cmd package query-activities`: action + `Uri.fromParts("package", pkg, null)` → `Settings$AppPictureInPictureSettingsActivity` (**the per-app toggle — the one that actually fixes it**); action alone → `Settings$PictureInPictureSettingsActivity` (the list). Both `exported=true`. Falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`, then to on-screen manual instructions — an unresolvable intent throws `ActivityNotFoundException` in the user's face, and the deep link is this screen's whole value.
- **Prompt-once**, via `domain/pip/PipEscapeLedger.kt` (pure; `;`-separated set, `MAX_ENTRIES = 200`, oldest dropped first, idempotent `record`) persisted as `NudgePreferences.pipEscapePromptedPackages` and cached off-main in the service like the other hot-path flags. This is one-shot **education about a platform limitation**, not an enforcement surface — repeating it would be pure nagging. `parse` fails soft to "nothing prompted yet": failing hard would crash the hot path, and failing soft the other way would silently kill the feature. The service marks its in-memory cache **before** the DataStore write so an event burst cannot stack explainers.
- **Tests**: `PipEscapeTest` (13, rewritten around the real captured Pixel 3 window list, SystemUI's PiP menu must not shadow the app, system-only PiP yields nothing, unresolved owners skipped, PiP-only vs active-window vs unreadable-active-window, the full live-device pipeline end to end, the throttle's read count + its first-question-at-clock-zero sentinel, and the session-block record outliving `markOverlayInactive`), `PipEscapeLedgerTest` (7), `PipSettingsTargetTest` (5, incl. the exact action-string literals).
- **Diagnosing this on a device, start with `adb shell dumpsys accessibility`.** It prints the exact `AccessibilityWindowInfo` list the service sees, `pictureInPicture=` flag and all, with **no build and no instrumentation**. Both root causes above were found with it in minutes, after a whole release cycle of guessing. Two gotchas: behind a fullscreen block overlay the list holds only ~3 windows (the blocked app is not in it), so you need the bubble actually floating; and `adb install -r` DROPS the accessibility grant, re-enable with `settings put secure enabled_accessibility_services dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService` + `accessibility_enabled 1`, or the next test silently exercises nothing.

## The passthrough has TWO axes, v1.15.2

`PassthroughManager` now holds the web-domain grant (`lastDomain`) alongside the app-level one. It
used to be a `lastBlockedDomain` field on the accessibility service, which had two consequences worth
remembering, both instances of patterns already documented above:

- **It was set at BLOCK time, not at completion time**, so abandoning a website's delay still let the
  site through. Every other grant is earned in `BlockOverlayActivity.onTimerComplete` (issue #8); this
  one now is too, carried on the overlay intent as `EXTRA_WEB_DOMAIN`.
- **A second piece of state means a second thing to remember to clear.** `clearPassthroughForHome`
  had to null the field explicitly precisely because the system-package early return skips
  `evaluateForegroundPackage`, exactly the ordering trap this whole document is about. With both
  axes in one manager, `clear()` and `clearIfAppChanged()` drop both, and `clearWebGrant()` exists for
  the one case that is genuinely web-only: the user navigated to a different domain without leaving
  the browser.

The overlay also carries `EXTRA_PASSTHROUGH_PACKAGE` now, because a web block has two packages and
they answer different questions: `EXTRA_PACKAGE_NAME` is what the block is ATTRIBUTED to (the rule's
app, its label on the overlay, its `UsageEvent`, its PiP session record) while the passthrough
package is the app the user is IN (the browser). Collapsing them is what made completing an
instagram.com delay in Chrome grant a free pass to the Instagram app. The emergency pass reads the
same extra, for the same reason: `isPassActive` is checked against the foreground package.

Going Home also ends the web foreground-time session (`endWebSession()`), which stops the clock but
deliberately does NOT reset the session, `InteractionTracker`'s 5-minute expiry still owns that, so
a quick trip home does not refill a website's time budget either. Same scope discipline as above.

## Late decisions land on the wrong screen: the block-launch gate ([#31](https://github.com/astraedus/nudge/issues/31))

Every launch of `BlockOverlayActivity` used to carry one assumption nothing checked: *the app a
decision is about is still the app in front by the time the decision is ready to show*. A block
decision is computed on the service's IO scope, a rule lookup, a usage read, sometimes a URL-bar
read, while foreground changes keep arriving on the main thread the whole time. The assumption is
false whenever the user leaves faster than the database answers, and nothing sat between the answer
coming back and `startActivity` to notice. The overlay could land on the launcher, on another app
entirely, or on Nudge's own screens, the most visible form of the report.

This is the same shape as [#19](https://github.com/astraedus/nudge/issues/19) one layer up. There, a
package had a *window* on screen while the user was somewhere else, and every evaluation path read
"P has a window" as "P is the foreground app". Here, a package genuinely *was* the foreground app
when the evaluation started and is not any more by the time it finishes. Both are the same silent
premise about staleness, so both get the same treatment: one gate ahead of the whole pipeline, not a
check bolted onto whichever branch happened to get reported.

### One launch site, not four

Four places in this service used to build a `BlockOverlayActivity` intent by hand and call
`startActivity` directly: the rule block in `handleDecision`, the auto-kick cooldown in
`evaluateForegroundPackage`, the web auto-kick cooldown in `enforceWebCooldown`, and the daily-limit
`HARD_BLOCK`, which lived in `TimeRemainingHandler` and owned its own `Context` to start the activity
itself, outside the service and therefore outside any gate at all, and the launch most likely to
land late, since it fires from a 30-second clock tick rather than from a foreground event.
`TimeRemainingHandler` now reports the limit through its `onTimeLimitExceeded` callback, wired in
`NudgeAccessibilityService.onServiceConnected`, and no longer holds a `Context` or references
`BlockOverlayActivity` at all.

All four now go through one private `launchBlockOverlay(targetPackage, attributedPackage, extras)`.
Four copies of "build an intent, mark the flag, start the activity" was four places to remember a
gate, and remembering it in three of four is exactly how #19's first fix shipped: it guarded the
branch that had been reported and missed the common case. `markOverlayActive` is asserted from
inside this one helper and nowhere else in the service, for the matching reason: a launch that starts
the activity without marking leaves `isOverlayActive` false under a live overlay, and a mark without a
launch swallows every subsequent event for an overlay that never appears.

### Why the target is the passthrough package, not the attributed package

`launchBlockOverlay` takes two package parameters and they answer different questions.
`attributedPackage` is what the block is recorded against: the overlay's app label, the
`UsageEvent`, the picture-in-picture session record. `targetPackage` is who the gate compares
against the foreground, and it is the app the user is actually **sitting in**. For a web block those
are different apps: the block is attributed to Instagram (its label, its stats) while the user is
sitting in Chrome. Gating on the attributed package would compare Instagram against the foreground
and find Instagram is never there, because instagram.com blocked inside a browser never puts the
Instagram app in front, and that would drop every web block this app has ever shown. `BlockLaunchGateTest`'s
web-block case pins this directly: the same target launches when it equals the browser and is
refused when it is swapped for the rule's app.

### The gate runs before the row

`handleDecision`'s call into `launchBlockOverlay` sits ahead of the grayscale enable and ahead of the
`UsageEvent` write, and `handleDecision` returns immediately when the launch is refused. Recording a
block nobody saw is exactly the stat inflation [#19](https://github.com/astraedus/nudge/issues/19)
measured, +11 to the all-time Blocked count in five minutes from an orphaned PiP bubble. A block
refused here now writes nothing: no grayscale, no row. `BlockOverlayLaunchContractTest` pins the
ordering at source level, because it is invisible to any value-level test: the decision object, the
package and the rule are all correct either way, and what is wrong is that the code got there.

### Which signals may move the target, and why the rest cannot

`BlockLaunchGuard` holds one field, the last package genuinely observed in front, fed from
`BlockLaunchGate.foregroundAfter` at the same single classification point that feeds the sitting
model, inside `applyForegroundSignal`. Only three of the seven `ForegroundSignal` cases move it:
`AppWindow`, `Home`, and `OwnUi`. The other four, `SystemSurface`, `Transient`, `PipOnly`, and
`NotForeground`, make no claim about which app is in front, and reading any of them as evidence the
user left would drop legitimate blocks whenever the notification shade, a keyboard, a permission
dialog, or a floating PiP bubble happened to land inside the few milliseconds a rule lookup takes.
That is `SYSTEM_PACKAGES` answering a question it cannot answer, the same grouped-constant trap this
document already records springing on the passthrough grant, the foreground-time clock, and the
sitting itself. `OwnUi` deliberately DOES move it: an overlay landing on top of Nudge's own screens is
the case the #31 reporter cared about most, and our own block overlay is also classified `OwnUi`,
which is correct rather than awkward, since a second decision arriving while an overlay is already up
has nothing to add.

A fresh `BlockLaunchGuard` starts with `foregroundPackage = null`, and `null` never suppresses a
launch. "We have observed nothing yet" is not evidence the user is elsewhere; the gate may only ever
WEAKEN enforcement on a positive claim, the same failure direction the launcher-package resolution
and the URL-bar read already take.

### The accepted false-drop

A genuine foreign app window landing in the gap between evaluation start and evaluation finish does
drop the pending block: the overlay would have covered that app's window anyway, and the user's
return to the originally-blocked app fires its own window event and evaluates fresh.
`BlockLaunchGuardReplayTest`'s sub-flow case pins this deliberately: a photo picker moves the target
and the decision is dropped, but the sitting itself is untouched, which is issue #28's own fix one
layer down, so no time budget or passthrough grant is disturbed by a drop here. The failure direction
is "miss a block, self-correcting on the next window event", never "show the block on the wrong
app": the same direction every fix in this document already chooses.

### A second overlay for one entry: the duplicate block

**What device QA measured.** On v1.17.1, ten "I changed my mind" attempts against a Google Keep
DELAY rule raised `userChangedMind` by exactly ten, which was correct, and `wasBlocked` by
twenty-five. Logcat showed `handling block package=com.google.android.keep` twice within about 40ms
of each other on five of the ten attempts, each writing its own `UsageEvent` row and its own
`ActivityTaskManager: START ... BlockOverlayActivity`. The trigger was the pre-existing line
`block overlay bypassed by foreground switch, re-evaluating package=...`, firing on ordinary clean
launches with no walk-away and no home-press race anywhere in sight.

**The mechanism, from a capture already in the repo.**
`app/src/test/resources/a11y-captures/picker-subflow-keeps-sitting.jsonl` times one clean launch of
Google Keep under a DELAY rule (offsets from the capture's first event):

```
 1252ms  keep   android.widget.FrameLayout            <- evaluate, launch the overlay
 1445ms  nudge  android.widget.FrameLayout            <- the overlay TASK's first window
 1736ms  keep   ...keep.ui.activities.BrowseActivity  <- keep STILL starting up
 2056ms  nudge  ...overlay.BlockOverlayActivity        <- the overlay actually reaches the screen
```

The event at 1736ms is Keep's own second window, 320ms before the overlay reaches the screen. It is
a `TYPE_WINDOW_STATE_CHANGED` for a real `ForegroundSignal.AppWindow`, so the old rule read it as the
user having got past the block, cleared `isOverlayActive` and re-evaluated. The 1000ms debounce
(`DEBOUNCE_MS` in `NudgeAccessibilityService`) did not absorb it either, because the overlay task's
own window at 1445ms had already run `clearOverlays` and moved `lastPackage` to Nudge's own package,
so the `packageName == lastPackage` test failed.

**The invariant.** `isOverlayActive` is set synchronously at `startActivity`, which is the right
moment for "stop evaluating" and the wrong moment for "the overlay is covering the app". An overlay
we have STARTED is not yet an overlay the user can be PAST. Until it reports itself on screen, the
target package's own window events are the app being covered, and nothing else. A DIFFERENT app
coming forward in that gap is still a real foreground change and still counts as a bypass.

**Why the activity has to report it, and the event stream cannot.** The overlay task's first window
arrives roughly 600ms early and carries a framework class name (`android.widget.FrameLayout`), so "a
Nudge window appeared" is not the same fact as "the overlay appeared". Matching on
`className.startsWith(ownPackageName)` cannot separate them either: this app's applicationId is
`dev.astraedus.nudge`, while its classes live under `com.astraedus.nudge.*`, so the prefix never
matches. This is also why the service's existing `shouldClearForOwnPackageEvent` predicate can never
return true in production, a separate latent defect worth its own look; it has NOT been fixed here,
only noticed in passing. `BlockOverlayActivity.onResume` is therefore the authoritative source that the
overlay is on screen, and `onDestroy` clears that state again once it is gone.

**The fail-safe.** `BlockLaunchGate.OVERLAY_SETTLE_MS` (3000ms) only matters when the overlay never
reports itself at all, a `startActivity` the platform dropped. The measured gap on the Pixel 3, from
the same capture, is 804ms (Keep's own window at 1252ms to the overlay's own window at 2056ms). Being
generous is the safe direction: a missed bypass self-corrects, because the overlay's own `onStop`
finishes it and clears the flag, while a bypass recognised too eagerly is this bug.

**The second, belt-and-braces condition.** `Decision.DROP_ALREADY_PENDING` refuses a launch for the
SAME package while an overlay for it is already pending, so a single entry can only ever write one
row even if some other path re-evaluates. Once the overlay is on screen, a fresh launch is allowed
again, because a re-block after a genuine bypass is a real, separate block.

**Provenance and scope.** This defect PREDATES the #26/#31 work: the capture proving it,
`picker-subflow-keeps-sitting.jsonl`, was committed in v1.16.0, and nothing in the #26/#31 change
touched `isOverlayBypassedByForeground`, `clearOverlays` or the debounce. It is fixed here because it
is the same gate asking the same question this whole section is about: is this decision still about
where the user is. The old companion function `isOverlayBypassedByForeground` was DELETED rather
than left unused, for the same reason a second copy of any rule in this document has always been the
trap, never the safety net. `PassthroughTest` and `TransientWindowTest` now exercise
`BlockLaunchGate.isGenuineBypass` directly with no pending overlay, where it reduces to exactly the
old rule.

### Tests

`BlockLaunchGateTest` covers `decide`, `foregroundAfter` and `walkAwayAfter` in isolation, every
branch and the combinations where the two conditions disagree, plus the `DROP_ALREADY_PENDING` cases
and `isGenuineBypass` branch by branch, including the blocked app's own window before the overlay
arrives (not a bypass), the same event once the overlay is on screen (a bypass), a different app
arriving mid-launch (still a bypass), and the settle window expiring. `BlockLaunchGuardReplayTest`
replays both reported sequences through the real `EventClassifier` and `SittingTracker`, the same
objects the service uses, plus a false-positive suite for the shade, the active keyboard, the
framework package and a permission dialog, and a counterfactual on every #31 case proving the pre-fix
rule really would have launched. Its "the blocked app still starting up under a launching overlay is
not a bypass" replays the capture above end to end, including the counterfactual that the pre-fix
rule really does read the 1736ms event as a bypass, and "a different app coming forward while the
overlay launches is still a bypass" pins the other half. `BlockOverlayLaunchContractTest` is the
source-level guard: exactly one `BlockOverlayActivity` construction in the whole service, the
daily-limit handler holding neither a `Context` nor a reference to the activity, the gate preceding
both the launch and the `UsageEvent`, the guard fed from exactly one place, the overlay-bypass rule
existing in exactly one place, and "the overlay reports when it actually reaches the screen" asserting
at the source level that `launchBlockOverlay` calls `guard.onOverlayLaunched(targetPackage)`, that
`BlockOverlayActivity.onResume` calls `blockLaunchGuard.onOverlayShown()`, and that `onDestroy` calls
`blockLaunchGuard.onOverlayDismissed()`.

**Device QA, both rounds.** The first round, against the build that had the launch gate but not the
bypass fix, is what found the duplicate block: ten "I changed my mind" attempts on a Keep DELAY rule
wrote twenty-five `wasBlocked` rows where twenty is correct (each walk-away legitimately writes two,
see `RecordWalkAwayUseCase`), with five attempts logging `handling block` twice. The second round,
against the fixed build, passed every case: ten walk-aways, ten tightened-timing home races and five
open-Nudge-immediately attempts, **exactly one `handling block` on every one of the twenty-five**, no
overlay over the launcher or over Nudge, no crashes.

Neither round ever logged `DROP_FOREGROUND_MOVED` or `DROP_WALK_AWAY_IN_FLIGHT`, and that is the
expected result rather than a wiring failure. The walk-away condition is now a BACKSTOP: since
`navigateHome` stopped calling `finish()` itself, the blocked app no longer resurfaces during the
transition, so there is normally nothing for it to catch, and it exists for the fail-safe finish and
for devices where `onStop` does not arrive. The foreground condition needs the user to leave inside
the few milliseconds a rule lookup takes, and on this device the lookup usually wins: of ten
plain-timing home races, none even reached evaluation before HOME landed, and of the tightened ones
that did, the decision had already completed while the app was still in front, where LAUNCH is the
correct answer. Both conditions are exercised by JVM fixtures instead, which is where a race this
narrow can actually be pinned.
