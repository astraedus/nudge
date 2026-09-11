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
ForegroundSignal   AppWindow | Home | SystemSurface | OwnUi | Transient | PipOnly | NotForeground
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

## What a "sitting" is, and what it is not

`domain/sitting/SittingTracker` owns one question: *is the user still in a sitting with app X?* A
sitting ends for three reasons and no others.

| Cause | Passthrough grant | Interaction count / time baseline |
|---|---|---|
| `WENT_HOME` | revoked | **untouched** — a trip home must not refill a time budget |
| `SCREEN_OFF` | revoked | **untouched**, same reason |
| `ANOTHER_APP_HELD_FOREGROUND` | revoked | reset once `InteractionTracker.SESSION_EXPIRY_MS` also passes |

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
| `SittingTrackerTest` | every branch of the model; one test per reported sub-flow; that no non-app signal can end a sitting |
| `EventClassifierTest` | every `ForegroundSignal` is reachable; the PiP gate is ahead of everything; an empty launcher set classifies nothing as Home |
| `InteractionCounterTest` | every counting branch, the primary-source election, the mode rules |
| `A11yCaptureReplayTest` | the real device streams, each against its own oracle — plus counterfactuals showing the OLD rule really does fail each capture, so a passing fixture cannot be passing by luck |
| `AccessibilityEventCodecTest` | encoder/decoder round trip, escaping, forward compatibility |
| `EventDispatchOrderContractTest` | classify-once and sitting-once above every early return; no branch re-derives the classification; `evaluateForegroundPackage` never clears the passthrough again |
| `HomeScreenPassthroughContractTest` | the Home / system-surface distinction, the Strict Mode guard's position, the global toggle's position |

The counterfactual tests are worth the extra lines. `the old event-rate rule really would have
over-counted this capture` and `the old rule really would have revoked the grant on this capture`
assert that each fixture still CONTAINS the defect it was captured for. Without them, a fixture that
quietly stopped reproducing would leave a green test asserting nothing.

One gap, recorded honestly: the node-tree label path (`resolveLabelIfUnknown` -> `detectFeature`) is
not JVM-reachable without a real `AccessibilityNodeInfo`, and there is no Robolectric in this module.
It affects the counter's LABEL only, never its number.
