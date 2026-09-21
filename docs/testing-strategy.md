# Testing strategy — which layer catches which bug

`docs/TESTING.md` says what a change owes in tests. This says **where that test goes**, and it is the
doc to read when a bug has just shipped. Source: the 2026-09-21 evaluation of the suite against the
last 18 defects (`~/ops/routes/nudge/research/test-suite-evaluation-2026-09-21.md`).

**The finding, in one paragraph.** The suite is not fake and it is not wrong. It is ~1550 tests aimed
almost entirely at one layer — pure JVM logic — while the bug history lives in three layers that
layer structurally cannot reach: Activity/Service lifecycle ordering, real-device event timing, and
fixtures that agree with the code instead of with the device. Of 18 ledger defects, **3** would have
been caught by a missing pure-unit test. 75% of the test budget sits at the layer that caught 3 of
18; the layer that would have caught the most (**5** — replayed device streams) holds 1.9% of it.
The problem was never test *quantity*. It was **altitude**.

The rest of this file is the seven layers (**L1–L7**), then the five rules (**(a)–(e)**), then what not
to do. The evaluation labels its layers with letters; the mapping is L1=(a), L2=(b), L3=(c), L4=(d),
L5=(f), L7=(e).

## Routing table — symptom to layer

| The report says | Layer |
|---|---|
| a number on a screen is wrong, a rule decided wrong | **L1** pure JVM unit |
| anything about the accessibility service, counters, re-blocks, spurious triggers | **L2** — replay a capture, always first |
| the overlay came back / stayed / skipped its countdown / needed two taps | **L3** Robolectric lifecycle |
| a query reads what nothing writes; DI or Room wiring; a real second app in front | **L4** instrumented emulator |
| the code is right and the shipped data or copy is wrong | **L5** data/asset gate |
| works on the bench Pixel, crashes on an older phone | **L6** lint |
| OEM launcher timing, PiP, the service dying overnight | **L7** bench device — confirm here, never discover here |

---

## L1 — Pure JVM unit: `app/src/test/`, no Android types

**Catches:** value and decision logic over inputs you hand it. Wrong arithmetic, wrong branch, a
missing case in a rule. ~75% of the suite; fast, and the right default for anything in `domain/`.

**Blind to:** ordering, timing, wiring, and every input shape nobody thought to construct.

**Worked example — #36 (2500 interventions in one day).** The four gates deciding whether to *show*
an overlay had 49 exhaustive, correct tests. Nothing ever asked the **count** question separately, so
a green show-gate suite gave zero signal about counting. The test that eventually caught it asserted
the ceiling **as a number** and failed on its first run; written as "and there is a cap" it would have
passed. See also **#31**, fixed by extracting the async foreground re-check into `BlockLaunchGate` —
once a decision is a pure object, this layer can hold it.

**How to add one:** extract the decision into a pure function or object under `domain/`, feed it
constructed inputs, and assert **the number the user's screen would show**, never which internal
branch ran. A test asserting "the bypass check returned false" passes on the buggy build too.

**Cost:** minutes to write, milliseconds to run, inside `./gradlew test`.

## L2 — Recorded-event replay with a counterfactual: `app/src/test/resources/a11y-captures/`

**Catches:** "the device does not produce the stream the code assumes" — the largest single bucket,
**5 of 18**. Wrong trigger, wrong event type, raw-versus-corrected metrics. This is the best thing in
this repo and it is funded at 1.9%.

**Worked examples.** **#26**, "I changed my mind" needing two taps: pure launcher timing that *the
bench Pixel never reproduced once*, closed by replaying the stream. **#28b**: one slow thumb scroll
counted as 5–6 — the tests asserted the number the *code* produced (one per event past a debounce),
never the number the *thumb* produced. **#41**: the same own-window rule, reached through the
awareness overlay instead of the block overlay, with no capture to contradict the model.

**How to add one:**

1. Reproduce on the device with `scripts/a11y-capture.sh <name> [seconds]` running (needs a debug
   build, or a release build with Settings → About → tap Version 7× → Debug Logging on).
2. Commit the `.jsonl` under `app/src/test/resources/a11y-captures/` with its `#` header filled in:
   `# device:`, `# HOW:`, and `# EXPECTED (test oracle):` — **a fixture with no oracle cannot fail**,
   and `A11yCaptureReplayTest` asserts every capture has one.
3. Write the assertion that **fails** on it. Then fix.
4. **Write the counterfactual in the same PR**: run the *pre-fix* rule over the same capture and
   assert it still fails (`A11yCaptureReplayTest.kt:309` is the pattern). Without it, a capture that
   quietly stops reproducing leaves a green test asserting nothing.

**Cost:** ~2 hours per report, nearly all of it on the device. Milliseconds forever after.

## L3 — Robolectric: Activity lifecycle and service hosting, still `app/src/test/`

**Catches:** lifecycle *ordering*. `onStop` vs a running countdown, `onNewIntent` re-entry into a
`singleInstance` activity, `finish()` racing an async `GLOBAL_ACTION_HOME`, and — via
`ShadowAccessibilityService` — the real service's binder-facing callbacks with a simulated window
list, which is where symmetric fixtures stop passing.

**Worked examples:** **#8** (tab out mid-delay, come back, delay skipped — the countdown was a plain
`LaunchedEffect` that kept ticking while backgrounded); **#15** (switching between two blocked apps
showed the new name over the old frozen ring); **#26** (the finish/home race).
`docs/TESTING.md` recorded "there is no Robolectric here" as a fact of nature. It was a **choice**,
and it cost roughly one (c)-class bug per release cycle.

**How to add one:** `@RunWith(RobolectricTestRunner::class)`, drive the activity with
`Robolectric.buildActivity(...).setup()` then `.stop()` / `.newIntent(...)`, and assert on **gate and
service state**, not just activity state. A `singleInstance` re-entry needs a second controller
against the first's live state. **A sibling lane is landing this layer** — read
`app/build.gradle.kts` and the existing Robolectric tests for the pinned version and SDK config
before adding the second one.

**Cost:** runs inside `./gradlew test`, seconds. Setup is one-time.

## L4 — Instrumented emulator scenarios: `app/src/androidTest/`

**Catches:** real SQLite, real DI graph, real WindowManager, a real second app in the foreground.
Today `androidTest/` is empty and CI runs only `test lintDebug`.

**Worked example — #14.** The daily limit never fired: `EvaluateBlockUseCase` read `getDailyUsage()`
= `SUM(durationMs)` over a column **nothing ever wrote**. Every test mocked the repository call, so
the dead wiring was invisible at every other layer. Only a real write path through real Room, or a
schema gate (L5) asserting no query reads a column no insert writes, can see that.

**How to add one:** a Gradle managed device, `@HiltAndroidTest`, real Room, UI Automator to bring a
real second app to the foreground. Keep it to a handful of scenarios — app launches, the service
binds, a write/read round-trips, the daily-limit path reads a column something writes. **A sibling
lane is landing this layer and defines the managed-device task name**; read `app/build.gradle.kts`.

**Cost:** the highest hours-per-bug on this list, plus minutes of CI wall-clock per run — but the
repo is public, so Actions minutes are free. It is also the only layer that can answer
`docs/TESTING.md`'s open question: nothing has ever run this app on an API 26–27 device.

## L5 — Data and asset gates

**Catches:** the code is right and the **data** is wrong. Nothing else can see this class.

**Worked example — the content filter.** A 274k-domain upstream blob blocked `virginia.gov`,
`purdue.edu` and, through parent-domain walking, every site on `wordpress.com`. *Every unit test was
green throughout, because they ran against a hand-written 2-entry fake blocklist.*
`ContentFilterAssetTest` now parses the **shipped** asset with the app's **own** parser and asserts
properties: entry ceiling, no `.gov`/`.edu`/`.mil`, no public suffix, no allowlist collision, plus a
~65-domain benign corpus that must match via neither layer. `ProtectionAlertCopyTest` does the same
shape for copy, iterating the real `ProtectionFault` enum against the real `strings.xml`.

**How to add one:** load the real artifact, parse it with production's parser, assert properties —
never sample it, never restate it in the test. **Delete a guard by hand once and confirm the gate
fails**; otherwise it is a green tick, not a gate.

**Cost:** about an hour, once.

## L6 — Lint: the API-level gate

**Catches:** the one class JVM tests are structurally blind to. `minSdk` is 26, so an API 28 call
compiles, passes every unit test, and works on the bench Pixel 3 (API 31). On a real Android 8 phone
it is a `NoSuchMethodError`.

**Worked example.** `AccessibilityEvent.getScrollDeltaX/Y()` (API 28) sat inside `toRecord()`, which
runs for **every** accessibility event — on API 26–27 the service died on the first one and blocking
never worked at all. Two more `AppOpsManager.unsafeCheckOpNoThrow()` calls (API 29) shipped as
byte-identical copies of each other; the duplication is what made it two bugs instead of one.

**How to keep it:** nothing to add. `lintDebug` gates CI with `abortOnError = true`. Every `android.*`
API above 26 goes behind a `Build.VERSION.SDK_INT` check, and behind **one shared helper** if it has
two callers (`util/UsageAccess.kt`). **Never add a `lint-baseline.xml` for errors** — a baseline for
correctness errors is a list of bugs nobody reads again.

**Cost:** seconds, already paid.

## L7 — Bench-device QA: the Pixel 3

**Catches:** what nothing else can. **#19** (SystemUI's own PiP *menu* window also carries
`pictureInPicture=true` and shadows the app window), **#23** (the service dying overnight while
Settings still showed a green tick — "enabled" and "actually bound" only diverge under real memory
pressure).

**This layer confirms a fix; it is a poor place to discover one.** #26 never reproduced here once.
Rules: the bench Pixel must carry a **release-signed** build (a plain debug build has different
signature and grant behaviour); **only one agent drives the device at a time**; QA is delegated to
`device-tester` with concrete cases and expected outcomes, never tap-walked by the author.

**Cost:** a device cycle, serialised. Never in CI.

---

## The rules

### (a) Every bug fix names its layer

The PR body carries one line: **"Layer L1–L7: here is the test at that layer, or here is why that
layer is not worth building for this bug."** This is the cheapest item on the list and it is exactly
what would have stopped #36 — the show-gates had 49 green tests and the author had every reason to
feel covered. The question that was never asked is *"what layer does the count question live at?"*

**A source-grep contract test is never the answer to an ordering bug.** This repo has already learned
that the expensive way (`tasks/lessons.md`, "If you are writing a test to police a rule, the design is
wrong"): a regex scanner was written to enforce that every writer calls `requestRefresh()`; the right
fix was to delete the rule and have the updater collect its own sources of truth. Ask first whether
the invariant can be made **unwritable**, then whether a behavioural test at L1–L4 can hold it, and
only then reach for source text.

### (b) Fixture honesty: derive identity constants, never retype them

**A test must never hand-type a value that production reads from `BuildConfig`, the manifest, a
`build.gradle.kts` field, a production `const`, or an enum.** Derive it or import it. A hand-typed
constant makes the test agree with the author instead of with the device, and both sides of the
comparison then agree on a value that exists nowhere.

Live offenders, all verifiable today:

| Offender | What it retypes | Why it is wrong |
|---|---|---|
| `service/PassthroughTest.kt:211` | `const val OWN_PACKAGE = "com.astraedus.nudge"`, fed to **both** sides of every comparison | `namespace` is `com.astraedus.nudge`; `applicationId` is `dev.astraedus.nudge`. This is **#33**: `isOwnAppWindowEvent` can never be true in production, and has been dead for months |
| `service/TransientWindowTest.kt:26`, `service/HomeScreenPassthroughTest.kt:29`, `service/ContentChangeAppSwitchTest.kt:34`, `data/repository/InstalledAppsRepositoryTest.kt:44` | the same wrong literal | six other files hand-typed the *correct* one, so this is drift, not convention. **No file in the suite imports `BuildConfig.APPLICATION_ID`** |
| `data/db/NudgeDatabaseMigrationTest.kt:187` | `val currentVersion = 10` | already bit us once: the test sat at 6 while the DB was at 7 (`tasks/lessons.md`, 2026-05-20). Read it from the `@Database` annotation |
| `data/export/SettingsExportTest.kt:387` | `listOf("NONE", "HARD_BLOCK", "DELAY", "BREATHING")` in a test named *"every real block mode is accepted"* | it claims exhaustiveness over an enum it does not read. `BlockMode.entries.map { it.name }` makes the claim true, and a new mode then fails the test instead of silently escaping it |
| `domain/sitting/SittingTrackerTest.kt:19` | a 5-minute `returnWindowMs` where production wires `PASSTHROUGH_RETURN_WINDOW_MS = 2 minutes` | no test verifies production wires the real constant |

**The repo already solved this once and did not generalise it:** `BlockOverlayLaunchContractTest.kt:357`
reads the namespace out of `build.gradle.kts` and asserts `MAIN_APP_ACTIVITY_CLASS` equals
`"$namespace.MainActivity"` — *"the same mismatch that left `shouldClearForOwnPackageEvent` dead for
months"*. That is the right fix, applied to exactly one constant. Generalise it.

### (c) An accessibility report gets a capture before a fix is designed

**Any issue about the accessibility service gets a committed capture with an oracle, recorded with
`scripts/a11y-capture.sh`, before the fix is designed.** Not after, not optionally. The harness, the
script, the oracle discipline and the protocol in
`docs/architecture/accessibility-event-pipeline.md` all already exist; the only thing missing was
that it was optional, and that is the bucket holding 5 of 18 defects.

`.github/ISSUE_TEMPLATE/bug_report.md` asks the reporter for the log; when they cannot produce one,
capture the closest local repro yourself. **Theorising about an event stream you have not recorded is
how #28a shipped** — the model ("a foreign package fired a window event → the user left") was simply
false, and no recorded stream existed to contradict it.

### (d) A fix that closes a race by ordering owes its reversed-order counterfactual

If the fix is "do A before B", the same PR asserts that **B-before-A still fails**. A test that only
proves the current order works cannot tell a reader whether the order is load-bearing, and the next
refactor reorders it back. This is the `A11yCaptureReplayTest` counterfactual pattern applied to code
order rather than to data, and it is the only thing that keeps an ordering assertion honest as the
file changes around it.

### (e) Source-grep assertions: check an absence or a discovered count, never a presence

This fell out of reading all 21 contract tests. The assertions that survive a faithful refactor check
either an **absence of a whole defect class** (`assertFalse(source.contains("CoroutineScope("))`, no
`mutableStateOf`, no `INTERVAL_DAILY`, no `onBackPressed` override) or a **count over a discovered
set** (exactly one `wasBlocked = true` writer; this set of preferences equals that set). The ones
that rot check the **presence of a specific spelling**. That is a sharper rule than "source-grep
tests are bad", and it tells an author which kind to write.

---

## What NOT to do

- **No blanket coverage target.** The `>90% domain` line in `docs/TESTING.md` stays aspirational and
  unenforced. The caption-rule bug had fine line coverage and five green tests; the defect was input
  *shape*. A coverage gate would reward writing more of the 75% that caught 3 of 18.
- **Do not mutation-test the suite.** Too slow here, yield too low. Keep the targeted version this
  repo already practises: when you add a **gate**, delete the guard once by hand and confirm the gate
  fails (done for `UsageAccess.kt` and the `NewApi` gate). Anything broader is a green tick.
- **Do not answer an ordering bug with another source-grep contract test.** See rule (a).
- **Do not write Compose UI screen tests.** The bugs are in lifecycle and wiring, not rendering.
  Robolectric on `BlockOverlayActivity` earns its hours; Espresso over the settings screens does not.
- **Do not chase #24** (Reddit scroll freeze). No root cause, no reproduction, reporter rebooted and
  closed it. It is in the ledger for completeness and stays closed.
- **Do not delete the counterfactual tests to save lines.** They are the reason the replay fixtures
  can be trusted at all.
- **Do not delete any contract test now.** The list below is a *when you next touch this file* list,
  not a cleanup task. Deleting them today buys maintenance relief and zero bugs.

### Contract tests: migrate when next touched

A first pass of the evaluation reported "13 of 21 pin a spelling". **That number was a sampling
artifact and is wrong** — reading all 21 gives three buckets, and the correction is itself an instance
of rule (b): a number that came from a sample rather than from the source. The mechanism
(`contains("someCall(")`) is identical across the good and the bad; what differs is the property the
file's assertions mostly establish.

**Keep as they are (8), invariant-grade:** `BlockOverlayLaunchContractTest`,
`EventDispatchOrderContractTest`, `SharedNamespaceUniquenessTest`, `BlockedCountSemanticsContractTest`,
`WidgetObservationContractTest`, `WidgetManifestContractTest`, `WidgetStrictModeContractTest`,
`BlockOverlayWalkAwayContractTest`.

**Mixed, majority-invariant — migrate the named assertion, keep the structural half (7):**
`MonitorServiceContractTest` (literal copy `"Nudge is active"`, importance constant names, a
hand-listed toggle-site enumeration) · `ServiceLifecycleContractTest` (`catch (_: IllegalStateException)`,
`var isRunning`, a hand-listed caller set) · `WatchdogDebugTriggerContractTest` (`ProtectionCheck.run(`,
the literal broadcast action) · `OverlayBackAndInsetsContractTest` (a `Surface(` assertion that pins
**whitespace**) · `LivePermissionStateContractTest` (presence spellings for `ContentObserver`,
`ON_RESUME`) · `InterventionsDelegationContractTest` (`topBlockedApps(events, sinceMs = rangeStart`,
an **argument name**) · `ScreenTimeSourceContractTest` (presence spellings `perAppOn(` / `totalOn(`).

**Migrate or delete when next touched (6), majority spelling-pinning** — the invariant is real, the
assertion is a transcript of today's code:

| File | What it rests on |
|---|---|
| `service/WebDomainEnforcementContractTest.kt` | pins source **formatting** inside a multi-line constructor call |
| `service/HomeScreenPassthroughContractTest.kt` | `lastPackage = null`, `lastDomain = null`, `is SittingEvent.Ended -> clear()`; its two ordering tests are worth saving |
| `data/preferences/ImportedSettingsWriteContractTest.kt` | `settings.$field?.let` — rewriting it as `if (x != null)` with identical behaviour breaks the test; its single-transaction count is worth saving |
| `ui/screens/home/HomeTileAffordanceContractTest.kt` | `assertEquals(6, statCardCallSites().size)` — a literal count that an unrelated new stat card breaks; the shared-label cross-check is worth saving |
| `ui/screens/stats/ChartSelectionContractTest.kt` | five assertions pinning exact wiring text; the `!contains("mutableStateOf")` absence check is the one to keep |
| `ui/screens/stats/StatsInsightEntryContractTest.kt` | literal card copy and icon names; "is it explained?" proxied by counting string literals over 20 characters |

Two of the mixed set — `MonitorServiceContractTest` and `ServiceLifecycleContractTest` — contain
**hand-listed enumerations of call sites**. That is the exact shape `tasks/lessons.md` condemns, where
the right answer was to delete the rule rather than re-express it. Treat those two as **design
questions**, not migration tasks: change the design so the enumeration is unnecessary.
