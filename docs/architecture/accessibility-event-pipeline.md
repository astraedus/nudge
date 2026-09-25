# The accessibility event pipeline: one classification, one sitting, one counter

What happens to an accessibility event between `onAccessibilityEvent` and a decision, why it is
shaped this way, and how to turn the next bug report into a failing test before writing a fix.
**Read before touching `NudgeAccessibilityService.onAccessibilityEvent`, `EventClassifier`,
`SittingTracker`, `InteractionCounter`, `InteractionHandler`, or `PassthroughManager`.**

Companion docs: `foreground-detection.md` (the history of what "the user is in app P" has meant) and
`counter-overlay-and-autokick.md` (what the counter feeds).

## The bug family this exists to end

Five reports, five fixes, one shape:

| Issue | Symptom | What was actually wrong |
|---|---|---|
| [#5](https://github.com/astraedus/nudge/issues/5) | keyboard re-blocks the app | a keyboard's window event reached the passthrough clear |
| Home path (v1.13) | the delay never re-armed after Home | the `SYSTEM_PACKAGES` return fired ~200 lines before the clear |
| [#7](https://github.com/astraedus/nudge/issues/7) | timer does not start on re-entry | a content-change-only re-entry returned before evaluation |
| [#19](https://github.com/astraedus/nudge/issues/19) | 9 re-blocks in 5 minutes from a PiP bubble | the gate was attached to one branch instead of the pipeline |
| [#28](https://github.com/astraedus/nudge/issues/28) | a picker re-blocks the app; the counter lies | any foreign package's event reached the passthrough clear |
| [#41](https://github.com/astraedus/nudge/issues/41) | a daily limit stops blocking while our own counter is up | OUR OWN overlay's event was read as "Nudge is in front" |

Every one of them was an **ordering** bug, not a logic bug, and every fix was a new hardcoded set or
a new early return in a method that derived "what is on screen" independently in six places. That
structure guarantees a sixth report. The pipeline below removes the structure, not just the fifth
bug.

## The shape

```
AccessibilityEvent  (framework object, android.*)
  |
  |  AccessibilityEventRecordFactory.toRecord()        <- the ONLY place that reads a live event
  v
AccessibilityEventRecord  (pure data, no android imports, serialisable)
  |
  |-- AccessibilityEventTrace.record()                 <- debug builds: one JSON line to logcat
  |
  |  EventClassifier.classify()                        <- ONE answer to "what is on screen?"
  v
ForegroundSignal   AppWindow | Home | SystemSurface | OwnUi | AwarenessOverlay
                   | Transient | PipOnly | NotForeground
  |
  |  PassthroughManager.onForegroundSignal()  ->  SittingTracker
  v
SittingEvent       Unchanged | Started | Ended(WENT_HOME | SCREEN_OFF | ANOTHER_APP_HELD_FOREGROUND)
  |
  |  ...then the existing dispatch: block evaluation, overlays, web domains, auto-kick
  v
InteractionCounter (for VIEW_SCROLLED / VIEW_CLICKED)  ->  CountResult(count, mode)
```

Two rules hold the whole thing together, and both are pinned by `EventDispatchOrderContractTest`
because neither is visible to a value-level test:

1. **Classify once, above every early return.** `onAccessibilityEvent` may return before the
   classification for exactly three reasons, all of them "there is nothing to classify": a null
   event, a null package, and the PiP-explainer modal.
2. **Update the sitting once, immediately after.** This is only safe — and it is what makes a sixth
   bug of this family unavailable — because `SittingTracker` makes every signal except `AppWindow`
   and `Home` *structurally* incapable of ending a sitting. There is nothing below the update that a
   future early return could skip.

Everything downstream reads the signal. Re-deriving `packageName in SYSTEM_PACKAGES` inside a branch
is how the Pixel's `com.google.android.permissioncontroller` came to be classified two different
ways in one file; the contract test now forbids it by name.

## Our own windows are three different things ([#41](https://github.com/astraedus/nudge/issues/41), [#33](https://github.com/astraedus/nudge/issues/33))

> Read this before touching anything that asks "is this Nudge?".

Nudge emits four shapes of window event under one package name, and they do not mean the same thing:

| What it is | Class name it carries | What it means about where the user is |
|---|---|---|
| The main app | `com.astraedus.nudge.MainActivity` | the user is **in Nudge**; they left whatever they were in |
| The block overlay | `com.astraedus.nudge.ui.overlay.BlockOverlayActivity` | Nudge is **in front of** the user |
| The overlay TASK's first window | `android.widget.FrameLayout` | nothing yet — it arrives ~600ms before the overlay itself |
| An awareness overlay | `com.astraedus.nudge.service.AwarenessOverlayWindow` | Nudge drew **on top of** the app the user never left |

For three releases all four were `ForegroundSignal.OwnUi`, and `BlockLaunchGate.foregroundAfter`
moves the foreground to Nudge for `OwnUi`. For the first two that is deliberate and is half of
issue #31's fix. For the last it is the bug.

**#41, measured.** Bench Pixel 3, v1.17.1, scenario S3 of the #36 investigation: Hard Block plus a
1-minute daily limit on Keep, the limit exceeded, Keep visibly in front. Every 30 seconds:

```
block overlay launch dropped target=com.google.android.keep attributed=com.google.android.keep
  reason=DROP_FOREGROUND_MOVED foreground=dev.astraedus.nudge
```

The interaction counter and the time-remaining pill are `TYPE_ACCESSIBILITY_OVERLAY` windows we
own, so adding one — or setting a `TextView`'s text inside one — emits an event carrying
our package. The foreground moved to Nudge and **nothing moved it back**, because a user sitting
still in an app fires no window event for the app they are already in. Every subsequent tick was
refused. It self-corrected on the next real Keep window event, which on a phone left face-up is
indefinitely, and in the meantime someone past their daily limit was not being blocked.

**The fix is a separate signal, identified positively.** `ForegroundSignal.AwarenessOverlay`
carries no foreground claim, ends no walk-away, no arrival and no storm, and moves no sitting: it
sits with `SystemSurface` and `Transient` in every exhaustive `when`. It is recognised by the
accessibility class name the overlay's views report — never as "Nudge and not the block
overlay", because the framework class name on row three of that table is what a negative test
misfiles, which is the same reason `BlockLaunchGate.isOwnMainAppWindow` is positive (see
`block-overlay-lifecycle.md`, issue #36).

Two details that are load-bearing rather than tidy:

- **Every view in an awareness overlay wears the identity, not just the root.** A
  `TYPE_WINDOW_CONTENT_CHANGED` is sourced from the view that changed, or from their common
  ancestor once `ViewRootImpl` coalesces a burst. Our own package is classified *ahead of* the
  window-change test, so content changes reach `foregroundAfter` too — identifying only the
  root would leave the counter's own text updates claiming the foreground on every interaction it
  counts.
- **`EventClassifier`'s `awarenessOverlayClassNames` has no default.** A forgotten argument would
  not fail; it would silently reclassify both overlays as `OwnUi` with the whole suite green.

`AwarenessOverlayContractTest` DISCOVERS the rule rather than listing it: any framework widget
constructed in either overlay manager fails it, including one nobody has added yet. Verified by
reverting one view to a bare `TextView`.

**#33, the same question asked one field over.** `NudgeAccessibilityService.shouldClearForOwnPackageEvent`
tested `className.startsWith(ownPackageName)` where `ownPackageName` was the **applicationId**:

| | value | appears as |
|---|---|---|
| `applicationId` | `dev.astraedus.nudge` | `event.packageName` |
| `namespace` | `com.astraedus.nudge` | `event.className`, every class we ship |

The prefix is therefore false for every event this app can emit, so `clearOverlays(packageName,
"own_app_window")` was dead in production for months — the awareness overlays stopped hiding
when the user opened Nudge. **Its three unit tests passed throughout**, because they built the
predicate's constant out of the literal `"com.astraedus.nudge"` and fed it the same literal back.
`tasks/lessons.md` (2026-09-14) records the general form: *a test that supplies its own value for a
production constant cannot see that the production value breaks the predicate.*

"Is this class ours" is now ONE predicate, `BlockLaunchGate.isOwnNudgeClass`, shared with
`isOwnMainAppWindow`; the service derives the namespace from a real class
(`BuildConfig::class.java.packageName`) rather than naming it; and `NudgeIdentity` gives the test
tree one derived source for both identities, with `OwnClassNamespaceContractTest` asserting they
are **different strings**. Four suites had been feeding the namespace in as the app's package;
they now read the real values. The restored clear cannot double-fire on the overlays themselves:
they classify as `AwarenessOverlay` and return above the `OwnUi` branch, so a pill can no longer
order itself hidden.

## What a "sitting" is, and what it is not

`domain/sitting/SittingTracker` owns one question: *is the user still in a sitting with app X?* A
sitting ends for three reasons and no others.

| Cause | Ends on | Passthrough grant | Interaction count / time baseline |
|---|---|---|---|
| `WENT_HOME` | the gesture itself, at once | revoked | **untouched** — a trip home must not refill a time budget |
| `SCREEN_OFF` | the screen having been off for ≥ the return window when the user comes back | revoked | reset on return |
| `ANOTHER_APP_HELD_FOREGROUND` | that app holding the foreground for ≥ the return window | revoked | reset once `InteractionTracker.SESSION_EXPIRY_MS` also passes |

**Two windows, not one.** The sitting's return window (`SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS`,
2 minutes) is deliberately NOT `InteractionTracker.SESSION_EXPIRY_MS` (5 minutes), though sharing one
constant reads tidier and was the first thing tried. They answer different questions: the session
expiry asks *"should the time budget refill?"*, where generous is safe because the failure mode is a
user farming a fresh budget; the return window asks *"did the user leave?"* and governs permission to
skip a delay, where generous is a bypass. At five minutes a four-minute excursion keeps the pass, and
ping-ponging under the window keeps it alive indefinitely. Two minutes is long enough to browse a
gallery, short enough that using another app costs a fresh delay.

**An unknown package is never evidence the user left.** That single sentence is the fix for #28. A
photo picker, a share sheet, a permission dialog, a custom tab, a notification hop and an OEM volume
panel are all ordinary app packages, and they are sub-flows of the sitting by construction: another
app in front for a few seconds is not five minutes. Nothing has to recognise them, which is what
makes this different in kind from the four fixes before it — each of those added a name to a list,
and every one of those lists has since sprung.

`SCREEN_OFF` is deliberate and load-bearing. The return window makes a brief excursion cheaper than
it used to be; a locked phone (backlog F5: *complete Instagram's delay, lock, unlock hours later
straight back into Instagram, no delay*) now costs a fresh delay where it used to cost nothing. Net,
the model is stricter where it matters and looser only where it was wrong.

**A screen-off is an ABSENCE, not a departure (issue #54).** `SCREEN_OFF` used to end the sitting the
instant the broadcast arrived, with no return window at all, on the premise written into the receiver
that raises it: *a screen-off is not a timer, it is an observation that the user stopped using the
phone*. The user who asked for HOLD mode (#35) falsified that by email on 2026-09-24 — he was holding
sixty seconds, getting in, and paying again a minute later while still in the app reading client
chats. Android blanks the display on lack of **input**, not lack of attention, and the Pixel default
is 30 seconds, so reading inside the blocked app timed the screen out and unlocking straight back
into it read as a brand-new arrival.

It was the one end cause with no grace, and it was the *inconsistent* one: this model already rules
that another app holding the foreground for 110 seconds is not a departure (the whole of #28), and a
screen that blanked for 40 seconds and came back to the SAME app is strictly less of a departure than
that. So a screen-off now starts the away clock exactly as a foreign app window does, and the same
`PASSTHROUGH_RETURN_WINDOW_MS` decides on the user's return. F5 is untouched, because *hours* is far
past the window; what changed is only the case where nobody went anywhere. The bug predates HOLD by
every release the sitting model has shipped — a DELAY always had it — and went unreported because
paying a 15-second countdown again reads as annoying where paying 60 seconds of sustained attention
again reads as broken.

It is a clock **start** rather than an end for a second reason: there is no `ACTION_SCREEN_ON` in this
model and none is needed. `awaySinceMs` is read on the next app window, and its clock
(`SystemClock.elapsedRealtime()`) counts while the device sleeps, so the absence measures itself.
Whichever absence starts the clock also owns it: the **first** evidence wins, both the timestamp and
the reported cause, so a user who switches apps and then lets the screen time out left when they
switched, and a phone that blanks every thirty seconds cannot extend one departure forever.

**The counting side shares that verdict.** `BlockLaunchGuard.onDeparture("screen_off")` used to fire
unconditionally from the screen-off receiver, on the same premise. Leaving it there would have split
this app's definition of *the user left* in two — enforcement forgiving a display timeout while
counting still charged for it — and the split is visible on a block the user has **not** completed:
no grant protects them, so the overlay comes back either way, and #36's invariant (*no `wasBlocked`
row without a genuine departure*) then failed on #54's own definition. The departure is now raised
from `NudgeAccessibilityService.onSittingEnded`, gated on the sitting having ended with cause
`SCREEN_OFF`, which is the moment the verdict exists. `WENT_HOME` and `ANOTHER_APP_HELD_FOREGROUND`
deliberately do not route there: both arrive as ordinary `ForegroundSignal`s and
`BlockLaunchGate.arrivalAfterSignal` already ends the arrival on them, on the very signal that feeds
the sitting. Their windows also differ on purpose — the arrival ends the moment another app is in
front while the sitting holds for two minutes, because *is this block worth a row* and *is the user
still in this app* have different safe directions.

**An absence is cancelled by PRESENCE, not only by a navigation (issue [#64](https://github.com/astraedus/nudge/issues/64)).**
The away clock is evidence of absence, and until 1.18.2 the only evidence of PRESENCE the model would
accept was another `AppWindow` — which means *a window transition happened*, not *the user is here*
(`EventClassifier` returns `NotForeground` for everything that is not a window change). A user
actively using their app without changing windows — scrolling a feed, reading, typing — therefore had
no way to contradict an absence they were not having.

So one ordinary sub-flow armed a **fuse with no expiry**. A Chrome Custom Tab opened from Reddit, an
"open with" chooser from Files, a share sheet, a permission dialog: all of them start the clock by
design, on the premise (#28's) that the RETURN cancels it. When the return did not arrive as a window
event — #58 measured that exact delivery flakiness on the bench Pixel 3, same gesture, same build —
the clock kept burning, and the user's next in-app navigation, minutes later, was read as a return
from a two-minute absence. The grant went and the block came back while they sat exactly where they
had been. Reported by the #35 / #54 user on 2026-09-25 in Reddit, Gemini and Files, paying a fresh
sixty-second HOLD each time, screen never off.

The app was also contradicting itself for the whole of that window: `InteractionCounter` was counting
those same taps and scrolls as *what the user did inside app X* and feeding them to auto-kick. One
question, two opposite answers — the same split #54 found between enforcement and counting.

`SittingTracker.onInteraction` closes it, and routes through the **same** "the user came back" branch
`onAppWindow` uses rather than getting a rule of its own:

| The interaction is | and the away clock is | so |
|---|---|---|
| in the sitting's app | running, under the window | cancelled, the sitting survives — the reported bug |
| in the sitting's app | running, past the window | the sitting ENDS exactly as a window event would end it — no bypass is opened |
| in another app, or there is no sitting | anything | nothing at all |

A stray event from a backgrounded app can therefore only ever revoke **sooner**, never grant. An
interaction in a foreign app is deliberately inert: it is weaker evidence than a window about what is
in FRONT (a question this model has never owned), and the failure direction here is always to miss a
revoke rather than interrupt someone mid-use. A foreign app that really is in front says so with a
window event.

**What this does not close.** If the fuse is armed spuriously and the user then goes past the return
window without touching the app at all, their next touch or navigation still ends the sitting. The
complete fix is to verify the foreign app is *still* in front instead of inferring it from one event
— `docs/BACKLOG.md` records the mechanism (`UsageEvents.Event.getTaskRootPackageName()`, API 29+,
which tells a sub-flow from a real switch outright).

What the sitting does **not** do: decide whether to block. A foreign app window is still evaluated
immediately and blocked on its own merits — opening a blocked app from a picker blocks at once, not
five minutes later. The sitting governs only whose *grant* is alive.

`SYSTEM_PACKAGES` survives, narrowed to the one question it can honestly answer: *should this be
evaluated, and should the awareness overlays hide?* It is no longer allowed anywhere near *did the
user leave*. That grouped-constant trap has now sprung three times — on the passthrough grant, on the
foreground-time clock, and here.

## What the counter counts

`domain/interaction/InteractionCounter` counts **item transitions**, not events.

`TYPE_VIEW_SCROLLED` carries `fromIndex`, `toIndex`, `currentItemIndex`, `itemCount`, and on API 28+
`scrollDeltaX/Y`. None of it was read. The old rule was one count per event past a 500ms debounce,
plus — for any package outside `InAppDetector.SUPPORTED_PACKAGES` — one count per second of
`TYPE_WINDOW_CONTENT_CHANGED` standing in for taps.

Measured on a Pixel 3:

- **One slow 2.5-second drag emits TEN scroll events** and moves the index once
  (`aosp-list-slow-scroll.jsonl`). The old rule scores that 2-3, and more for a longer gesture. That
  is the reported "scrolling slowly between two posts counts as 5-6".
- **An untouched phone on the Instagram feed emits scroll events anyway**
  (`instagram-idle-no-input.jsonl`), five in 75 seconds, indices never moving. The old rule ticked on
  them, and fed auto-kick, so it could eject a user for doing nothing.
- **Three gestures in a list emit twenty-one events** and move the index eight times
  (`yt-list-scroll-3-gestures.jsonl`).

The rules, in order:

- an item transition is one interaction, **forward only** (scrolling back up consumes nothing, and a
  "jump to top" must not count forty items), capped per event as a guard against an adapter reset;
- accumulated vertical distance against a screen height is the fallback **only** where a source
  reports no indices at all;
- horizontal-only movement is never an item — that is a carousel or a tab strip;
- a source's first event establishes its position and counts zero. Counting it is how opening a sheet
  used to register as a tap;
- **debounces remain as guards against duplicate delivery, never as the counting mechanism.** The
  difference is the whole fix: a debounce is a rate limit, and rate was never a measurement of what
  the user did.

Clicks count everywhere now. The old code took them only from packages *outside* the supported set,
so a tap in Instagram counted nothing while a re-render in Discord counted one.

**A session counts one unit.** Reels watched and buttons tapped are different things, and adding
them silently is how "opening the comments counted as a tap" returns under a new name — the overlay
would read "7 reels" after five swipes and two taps, and auto-kick would fire two items early.
`CountMode.ITEMS` wins: the first item promotes a tap-counting session and resets it, so one stray
tap on entering an app cannot silence the reel counter for the whole sitting.

### Multiple scroll sources

A screen often has more than one scrollable thing. Instagram's feed screen has two in ONE window:
`android:id/list` and `com.instagram.android:id/swipeable_tab_view_pager`. Only the PRIMARY source
counts, where primary means *the source that has most recently been producing item transitions*, and
a newcomer may take over only after the incumbent has been silent past `handoverMs`. A sheet opened
over a live feed therefore counts nothing; a list the user genuinely moves to and stays in does take
over, because at that point they really are consuming it.

The source key is `(windowId, className, viewIdResourceName)`. The view id is needed because a sheet
is commonly another `RecyclerView` in the same window as the feed, and without it the two share a key
and their indices interleave into phantom counts.

It costs a binder round trip and is resolved **once per scroll event**, in every build including
release. It was briefly cached under a 500ms throttle keyed on `(windowId, className)` -- which is
self-defeating, since those are precisely the two fields that are identical for a sheet and the feed
it opened over, so the sheet was handed the feed's id and counted as the feed. A cache keyed on the
ambiguity it is disambiguating cannot work. The cost is ~10 IPC/second while the user is actively
scrolling and nothing at rest, which is cheaper than the bounded 800-node tree walks this service
already performs on a 1-second debounce, and `AccessibilityEventTrace` logs a periodic `COST` line
during a capture so the number can be checked rather than argued about. While tracing, the factory
has already put the id on the record and the handler reuses it rather than paying twice.

**Known limits, measured rather than assumed** (see `docs/BACKLOG.md` for the follow-ups):

- YouTube's **Shorts** pager emits no `TYPE_VIEW_SCROLLED` at all — screenshot-verified, 66 events
  from five real flicks, every one a content change. The counter sees nothing there. It saw nothing
  before this change either; what is new is knowing why.
- On YouTube the comments list and the home feed share the SAME `viewIdResourceName`
  (`com.google.android.youtube:id/results`) and class, so there they cannot be told apart and the
  counter simply counts whichever list is being scrolled. That is the honest answer for that app.
- **Items are not gestures.** One swipe on a free-scrolling feed can pass two posts, and the counter
  says two, because two is what was consumed. On a paged surface (reels, shorts) the two coincide.

`InAppDetector.SUPPORTED_PACKAGES` no longer gates counting at all. It went back to meaning what its
name says — the apps whose in-app FEATURES we can recognise — and that recognition now supplies the
counter's LABEL and nothing else. Detection holding a veto over counting is exactly why
`docs/BACKLOG.md`'s "counter doesn't increment on YouTube swipes under a whole-app rule" was possible.

The content-change proxy is **removed, not tightened**. A package we cannot measure now reads zero,
which is honest; the old number was fabricated and wore the word "taps".

## Capturing a device session

This is the part to reach for first on the next report, before reading any code.

```bash
# 1. Install a debug build (or enable debug logging: tap the version number in Settings 7 times).
#    adb install -r DROPS the accessibility grant — re-enable it or you capture nothing:
adb -s "$DEVICE" shell settings put secure enabled_accessibility_services \
  dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService
adb -s "$DEVICE" shell settings put secure accessibility_enabled 1

# 2. Record while reproducing:
scripts/a11y-capture.sh instagram-comments-overcount 30

# 3. It writes app/src/test/resources/a11y-captures/<name>.jsonl. FILL IN THE HEADER — the
#    EXPECTED line is the test oracle, and a fixture with no oracle cannot fail.

# 4. Add the failing assertion to A11yCaptureReplayTest, THEN fix.
```

`AccessibilityEventCodec` holds the encoder and the decoder in one object on purpose: they are the
contract between the device and the test suite. If they drifted, every committed capture would replay
as a different stream than the device produced, and the tests would stay green against the wrong
data — the worst failure a fixture suite can have.

The trace costs one boolean test per event in a release build with debug logging off. The single
expensive field, `viewIdResourceName`, is a binder read with two callers on different budgets: the
factory reads it into the RECORD only while tracing, but `InteractionHandler` reads it in every build
including release, once per scroll event, because telling a sheet from the feed behind it is a
production decision. The trace reports the measured cost periodically, so "can we afford this?" is
answered with numbers rather than opinion.

### Probes that need no gestures

`tools/qa/subflow-passthrough-probe.sh` and `tools/qa/passive-observe.sh` reproduce issue #28 using
only `am start`, `monkey -c LAUNCHER` and `cmd media_session` — no taps, no swipes, no keyevents.
Two things make that possible: the DELAY overlay auto-completes on its own timer (`DelayContent`'s
`LaunchedEffect`), so a passthrough can be EARNED with no input; and the excursions are real system
intents, which is what the reported sub-flows actually are.

That matters beyond convenience. **Automation must never drive Instagram or TikTok over adb** —
account-ban risk to the device's real logins, a hard rule with no QA carve-out, recorded in
`tools/qa/ig-walkthrough-capture.sh`. Launching them and watching is fine, and is how
`instagram-idle-no-input.jsonl` was taken. Everything that needs a real gesture is captured on
YouTube or a stock AOSP list instead, which is no loss: **the counter must decide from generic event
fields, never from per-app knowledge**, so a stock `androidx` RecyclerView is the honest control.

### Two traps that cost time in this repo already

- **`mCurrentFocus` does not tell you which SCREEN you are on** inside a single-activity app. Three
  YouTube captures were taken on a video player while every focus check said "YouTube". Screenshot
  and look.
- **Relaunching a backgrounded YouTube puts it in picture-in-picture** when the block overlay
  backgrounds it, and #19's PiP gate then swallows its events, so the gestures land on the launcher
  instead. `tools/qa/scroll-capture.sh` force-stops first for this reason; 5 of 8 launches landed
  wrong without it.

## What the tests pin

| Test | Pins |
|---|---|
| `SittingTrackerTest` | every branch of the model; one test per reported sub-flow; that no non-app signal can end a sitting; that an interaction inside the app cancels an absence (#64) while one past the window still ends the sitting — with the counterfactual that the pre-fix path really did leave the clock burning |
| `EventClassifierTest` | every `ForegroundSignal` is reachable; the PiP gate is ahead of everything; an empty launcher set classifies nothing as Home; an awareness overlay is not `OwnUi` and every OTHER window of ours still is |
| `AwarenessOverlayContractTest` | the overlays are built only from views that carry the identity, and the identity is derived from a real class |
| `OwnClassNamespaceContractTest` | the applicationId and the namespace are different strings, production derives the namespace, and comparing a class name against the applicationId (issue #33) still cannot match |
| `BlockLaunchGuardReplayTest` | issue #41 replayed through the real classifier and guard — the tick after an awareness overlay still LAUNCHes, with the counterfactual that the pre-fix classification DROPs it |
| `InteractionCounterTest` | every counting branch, the primary-source election, the mode rules |
| `A11yCaptureReplayTest` | the real device streams, each against its own oracle — plus counterfactuals showing the OLD rule really does fail each capture, so a passing fixture cannot be passing by luck |
| `AccessibilityEventCodecTest` | encoder/decoder round trip, escaping, forward compatibility |
| `EventDispatchOrderContractTest` | classify-once and sitting-once above every early return; no branch re-derives the classification; `evaluateForegroundPackage` never clears the passthrough again; a click or a scroll actually REACHES the sitting model (#64), under the synthetic-click guard and before the counter is told — a pure-model fix nobody calls is a silent no-op with a green suite |
| `HomeScreenPassthroughContractTest` | the Home / system-surface distinction, the Strict Mode guard's position, the global toggle's position |

The counterfactual tests are worth the extra lines. `the old event-rate rule really would have
over-counted this capture` and `the old rule really would have revoked the grant on this capture`
assert that each fixture still CONTAINS the defect it was captured for. Without them, a fixture that
quietly stopped reproducing would leave a green test asserting nothing.

One gap, recorded honestly: the node-tree label path (`resolveLabelIfUnknown` -> `detectFeature`) is
not JVM-reachable without a real `AccessibilityNodeInfo`, and there is no Robolectric in this module.
It affects the counter's LABEL only, never its number.
